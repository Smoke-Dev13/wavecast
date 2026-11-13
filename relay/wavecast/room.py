"""Комната: источник → цепочка плагинов → PCM/Opus → шина → слушатели."""
import asyncio
import json
import logging
import time
from typing import Any

import numpy as np
from aiohttp import web

from . import plugins as plugin_mod
from .bus import OPUS, PCM, TEXT, LocalBus
from .codec import CHANNELS, RATE, InputNormalizer, OpusEncoder, to_s16

log = logging.getLogger("wavecast.room")
QUEUE_MAX = 100  # пакетов на слушателя (≈2 с Opus), дальше старые выбрасываются


class Listener:
    def __init__(self, ws: web.WebSocketResponse, codec: str):
        self.ws, self.codec = ws, codec
        self.q: asyncio.Queue = asyncio.Queue(maxsize=QUEUE_MAX)
        self.dropped = 0

    def push(self, item):
        if self.q.full():
            try:
                self.q.get_nowait(); self.dropped += 1
            except asyncio.QueueEmpty:
                pass
        self.q.put_nowait(item)

    async def pump(self):
        while True:
            item = await self.q.get()
            if item is None:
                return
            if isinstance(item, str):
                await self.ws.send_str(item)
            else:
                await self.ws.send_bytes(item)


class Room:
    def __init__(self, room_id: str, bus: LocalBus):
        self.id = room_id
        self.bus = bus
        self.listeners: set[Listener] = set()
        self.source: web.WebSocketResponse | None = None
        self.plugins: list[plugin_mod.Plugin] = []
        self.rate, self.channels = RATE, CHANNELS
        self.normalize: InputNormalizer | None = None
        self.opus: OpusEncoder | None = None
        self.plan: dict = {}
        self.started = 0.0
        self.bytes_in = 0
        self.total_listeners = 0

    # ---------- источник (только на инстансе, куда подключён телефон/ПК) ----------

    async def start_broadcast(self, ws, plan: dict, plugin_names: list[str], plugin_config: dict):
        if self.source is not None and not self.source.closed:
            await self.source.close(message=b"replaced")
            await self.end_broadcast()
        self.source, self.plan = ws, plan
        self.started, self.bytes_in = time.time(), 0
        self.opus = OpusEncoder(int(plan.get("opus_bitrate", 128_000)))
        self.normalize = InputNormalizer(RATE, CHANNELS)
        self.plugins = plugin_mod.build(self, plugin_names, plugin_config)
        for p in self.plugins:
            try:
                await p.start()
            except Exception:
                log.exception("[%s] плагин %s не стартовал", self.id, p.name)
        await self.bus.set_meta(self.id, "live", "1")
        await self.bus.set_meta(self.id, "max_listeners", str(plan.get("max_listeners", 10)))
        fmt = json.dumps({"type": "format", "rate": RATE, "channels": CHANNELS})
        await self.bus.set_meta(self.id, "format", fmt)
        await self.bus.publish(self.id, TEXT, fmt.encode())
        await self.bus.set_meta(self.id, "plugins", json.dumps([p.name for p in self.plugins]))
        log.info("[%s] эфир начат, плагины: %s", self.id, [p.name for p in self.plugins])
        await self.publish_status()

    def set_input_format(self, rate: int, channels: int):
        self.normalize = InputNormalizer(int(rate), int(channels))

    async def on_audio(self, data: bytes):
        self.bytes_in += len(data)
        audio = self.normalize(data)
        if not len(audio):
            return
        for p in list(self.plugins):
            try:
                audio = p.process(audio)
            except Exception:
                log.exception("[%s] плагин %s упал и отключён", self.id, p.name)
                self.plugins.remove(p)
        s16 = to_s16(audio)
        await self.bus.publish(self.id, PCM, s16.tobytes())
        for pkt in self.opus.encode(s16):
            await self.bus.publish(self.id, OPUS, pkt)

    async def emit(self, event: dict[str, Any]):
        """Событие от плагина → всем слушателям и остальным плагинам."""
        msg = json.dumps(event, ensure_ascii=False)
        if event.get("type") == "track":
            await self.bus.set_meta(self.id, "track", msg)
        await self.bus.publish(self.id, TEXT, msg.encode())
        for p in self.plugins:
            try:
                await p.on_event(event)
            except Exception:
                log.exception("[%s] %s.on_event", self.id, p.name)

    async def end_broadcast(self):
        for p in self.plugins:
            try:
                await p.stop()
            except Exception:
                log.exception("[%s] %s.stop", self.id, p.name)
        self.plugins = []
        self.source = None
        for k in ("live", "format", "track", "plugins"):
            await self.bus.set_meta(self.id, k, None)
        log.info("[%s] эфир окончен", self.id)
        await self.publish_status()

    async def publish_status(self):
        live = (await self.bus.get_meta(self.id)).get("live") == "1"
        self.total_listeners = await self.bus.total_listeners(self.id)
        await self.bus.publish(self.id, TEXT, json.dumps(
            {"type": "status", "live": live, "listeners": self.total_listeners}).encode())
        if self.source is not None and not self.source.closed:
            try:
                await self.source.send_str(json.dumps({"type": "listeners", "count": self.total_listeners}))
            except ConnectionError:
                pass

    # ---------- слушатели (на любом инстансе) ----------

    def deliver(self, kind: bytes, payload: bytes):
        if kind == TEXT:
            s = payload.decode()
            for l in self.listeners:
                l.push(s)
        else:
            want = "opus" if kind == OPUS else "pcm"
            for l in self.listeners:
                if l.codec == want:
                    l.push(payload)

    @property
    def idle(self) -> bool:
        return self.source is None and not self.listeners

    def stats(self) -> dict:
        return {
            "room": self.id, "live": self.source is not None, "local_listeners": len(self.listeners),
            "listeners": self.total_listeners, "plugins": [p.name for p in self.plugins],
            "bytes_in": self.bytes_in, "uptime": round(time.time() - self.started) if self.source else 0,
        }
