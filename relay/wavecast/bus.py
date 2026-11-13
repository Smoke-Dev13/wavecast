"""Шина между инстансами relay.

LocalBus  — один процесс, без внешних зависимостей.
RedisBus  — горизонтальное масштабирование: источник подключён к одному инстансу,
            слушатели — к любым за балансировщиком. Пакеты идут через Redis pub/sub.
"""
import asyncio
import logging
import os
import time
import uuid
from typing import Callable

log = logging.getLogger("wavecast.bus")

PCM, OPUS, TEXT = b"P", b"O", b"T"
Handler = Callable[[str, bytes, bytes], None]


class LocalBus:
    def __init__(self):
        self.handler: Handler | None = None
        self.meta: dict[str, dict[str, str]] = {}
        self.counts: dict[str, int] = {}

    async def start(self, handler: Handler):
        self.handler = handler

    async def stop(self):
        pass

    async def publish(self, room: str, kind: bytes, payload: bytes):
        if self.handler:
            self.handler(room, kind, payload)

    async def set_meta(self, room: str, key: str, value: str | None):
        m = self.meta.setdefault(room, {})
        if value is None:
            m.pop(key, None)
        else:
            m[key] = value

    async def get_meta(self, room: str) -> dict[str, str]:
        return dict(self.meta.get(room, {}))

    async def report_listeners(self, room: str, n: int):
        self.counts[room] = n

    async def total_listeners(self, room: str) -> int:
        return self.counts.get(room, 0)


class RedisBus(LocalBus):
    TTL = 10  # с — через сколько считать инстанс мёртвым в подсчёте слушателей

    def __init__(self, url: str):
        super().__init__()
        import redis.asyncio as redis
        self.r = redis.from_url(url)
        self.instance = os.environ.get("HOSTNAME") or uuid.uuid4().hex[:8]
        self._task: asyncio.Task | None = None

    async def start(self, handler: Handler):
        self.handler = handler
        self.pubsub = self.r.pubsub()
        await self.pubsub.psubscribe("wc:room:*")
        self._task = asyncio.create_task(self._loop())
        log.info("Redis-шина подключена, инстанс %s", self.instance)

    async def _loop(self):
        while True:
            try:
                msg = await self.pubsub.get_message(ignore_subscribe_messages=True, timeout=1.0)
                if msg and msg["type"] == "pmessage":
                    room = msg["channel"].decode()[len("wc:room:"):]
                    data: bytes = msg["data"]
                    self.handler(room, data[:1], data[1:])
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("ошибка шины")
                await asyncio.sleep(1)

    async def stop(self):
        if self._task:
            self._task.cancel()
        await self.pubsub.aclose()
        await self.r.aclose()

    async def publish(self, room: str, kind: bytes, payload: bytes):
        await self.r.publish(f"wc:room:{room}", kind + payload)

    async def set_meta(self, room: str, key: str, value: str | None):
        k = f"wc:meta:{room}"
        if value is None:
            await self.r.hdel(k, key)
        else:
            await self.r.hset(k, key, value)
            await self.r.expire(k, 86400)

    async def get_meta(self, room: str) -> dict[str, str]:
        return {a.decode(): b.decode() for a, b in (await self.r.hgetall(f"wc:meta:{room}")).items()}

    async def report_listeners(self, room: str, n: int):
        await self.r.hset(f"wc:cnt:{room}", self.instance, f"{n}:{time.time()}")
        await self.r.expire(f"wc:cnt:{room}", 3600)

    async def total_listeners(self, room: str) -> int:
        total, now = 0, time.time()
        for v in (await self.r.hgetall(f"wc:cnt:{room}")).values():
            n, ts = v.decode().split(":", 1)
            if now - float(ts) < self.TTL:
                total += int(n)
        return total


def make_bus() -> LocalBus:
    url = os.environ.get("REDIS_URL")
    return RedisBus(url) if url else LocalBus()
