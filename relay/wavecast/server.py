"""HTTP/WebSocket-сервер WaveCast relay.

  GET  /, /r/{room}            веб-плеер
  WS   /ws[/{room}]?codec=     слушатели (opus | pcm)
  WS   /ingest[/{room}]?key=   источник (Android / ПК)
  GET  /status, /healthz, /metrics
  API  /api/rooms, /api/plugins   (Bearer WAVECAST_ADMIN_TOKEN)
"""
import asyncio
import json
import logging
import os
import re
import secrets
import time
from pathlib import Path

from aiohttp import WSMsgType, web

from . import plugins as plugin_mod
from .bus import make_bus
from .room import Listener, Room
from .store import Store, load_plans

log = logging.getLogger("wavecast")
STATIC = Path(__file__).resolve().parent / "static"
ROOM_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,47}$")


class App:
    def __init__(self):
        self.key = os.environ.get("WAVECAST_KEY", "")                 # общий ключ для «быстрых» комнат
        self.admin = os.environ.get("WAVECAST_ADMIN_TOKEN", "")
        self.default_plan = os.environ.get("WAVECAST_DEFAULT_PLAN", "free")
        self.public_url = os.environ.get("WAVECAST_PUBLIC_URL", "").rstrip("/")
        self.plans = load_plans()
        self.store = Store(os.environ.get("WAVECAST_DB", "wavecast.db"))
        self.bus = make_bus()
        self.rooms: dict[str, Room] = {}

    # ---------- жизненный цикл ----------

    async def on_startup(self, _):
        await self.bus.start(self._deliver)
        self._ticker = asyncio.create_task(self._tick())

    async def on_cleanup(self, _):
        self._ticker.cancel()
        for r in list(self.rooms.values()):
            if r.source is not None:
                await r.end_broadcast()
        await self.bus.stop()

    def _deliver(self, room_id: str, kind: bytes, payload: bytes):
        r = self.rooms.get(room_id)
        if r is not None:
            r.deliver(kind, payload)

    async def _tick(self):
        """Раз в 2 с: отчёт о числе слушателей в шину, статус в комнаты с источником, уборка."""
        while True:
            await asyncio.sleep(2)
            for rid, r in list(self.rooms.items()):
                try:
                    await self.bus.report_listeners(rid, len(r.listeners))
                    if r.source is not None:
                        await r.publish_status()
                    elif r.idle:
                        self.rooms.pop(rid, None)
                except Exception:
                    log.exception("tick %s", rid)

    def room(self, rid: str) -> Room:
        r = self.rooms.get(rid)
        if r is None:
            r = self.rooms[rid] = Room(rid, self.bus)
        return r

    def plan_for(self, rid: str):
        rec = self.store.get(rid)
        name = rec.effective_plan() if rec else self.default_plan
        plan = self.plans.get(name) or self.plans[self.default_plan]
        names = rec.plugins if rec and rec.plugins is not None else plan.get("plugins", [])
        cfg = dict(plan.get("plugin_config", {}))
        if rec:
            cfg.update(rec.plugin_config)
        return rec, plan, names, cfg

    @staticmethod
    def room_id(request: web.Request) -> str:
        rid = request.match_info.get("room", "main").lower()
        if not ROOM_RE.match(rid):
            raise web.HTTPBadRequest(text="bad room id")
        return rid

    # ---------- страницы ----------

    async def page(self, request):
        self.room_id(request)
        return web.FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-cache"})

    async def healthz(self, _):
        return web.Response(text="ok")

    async def status(self, request):
        rid = request.query.get("room", "main")
        r = self.rooms.get(rid)
        meta = await self.bus.get_meta(rid)
        return web.json_response({
            "room": rid, "live": meta.get("live") == "1",
            "listeners": await self.bus.total_listeners(rid),
            "track": json.loads(meta["track"]) if "track" in meta else None,
            "plugins": json.loads(meta["plugins"]) if "plugins" in meta else [],
            "local": r.stats() if r else None,
        })

    async def metrics(self, _):
        lines = ["# TYPE wavecast_listeners gauge", "# TYPE wavecast_live gauge", "# TYPE wavecast_bytes_in counter"]
        for rid, r in self.rooms.items():
            lines += [f'wavecast_listeners{{room="{rid}"}} {len(r.listeners)}',
                      f'wavecast_live{{room="{rid}"}} {int(r.source is not None)}',
                      f'wavecast_bytes_in{{room="{rid}"}} {r.bytes_in}']
        return web.Response(text="\n".join(lines) + "\n", content_type="text/plain")

    # ---------- источник ----------

    async def ingest(self, request: web.Request):
        rid = self.room_id(request)
        key = request.query.get("key", "")
        rec, plan, names, cfg = self.plan_for(rid)
        ok = (rec is not None and secrets.compare_digest(key, rec.key)) \
            or (self.admin and secrets.compare_digest(key, self.admin)) \
            or (rec is None and self.key and secrets.compare_digest(key, self.key))
        if not ok:
            raise web.HTTPUnauthorized(text="bad key")

        ws = web.WebSocketResponse(heartbeat=20, max_msg_size=1 << 20)
        await ws.prepare(request)
        room = self.room(rid)
        await room.start_broadcast(ws, plan, names, cfg)
        log.info("[%s] источник %s", rid, request.remote)
        try:
            async for msg in ws:
                if msg.type == WSMsgType.BINARY:
                    await room.on_audio(msg.data)
                elif msg.type == WSMsgType.TEXT:
                    try:
                        data = json.loads(msg.data)
                    except ValueError:
                        continue
                    if data.get("type") == "format":
                        room.set_input_format(data.get("rate", 48000), data.get("channels", 2))
                    elif data.get("type") == "event":   # источник может слать свои события (метаданные трека и т.п.)
                        await room.emit({k: v for k, v in data.items() if k != "type"} | {"type": data.get("event", "info")})
        finally:
            if room.source is ws:
                await room.end_broadcast()
        return ws

    # ---------- слушатели ----------

    async def listen(self, request: web.Request):
        rid = self.room_id(request)
        codec = "pcm" if request.query.get("codec") == "pcm" else "opus"
        ws = web.WebSocketResponse(heartbeat=20)
        await ws.prepare(request)
        meta = await self.bus.get_meta(rid)
        limit = int(meta.get("max_listeners") or self.plan_for(rid)[1].get("max_listeners", 10))
        local = len(self.rooms[rid].listeners) if rid in self.rooms else 0
        if max(await self.bus.total_listeners(rid), local) >= limit:
            await ws.send_str(json.dumps({"type": "error", "message": f"Комната заполнена (лимит {limit})"}))
            await ws.close()
            return ws

        room = self.room(rid)
        l = Listener(ws, codec)
        room.listeners.add(l)
        live = meta.get("live") == "1"
        l.push(json.dumps({"type": "status", "live": live, "listeners": room.total_listeners + 1}))
        for k in ("format", "track"):
            if k in meta:
                l.push(meta[k])
        await self.bus.report_listeners(rid, len(room.listeners))
        if room.source is not None:
            await room.publish_status()
        pump = asyncio.create_task(l.pump())
        try:
            async for _ in ws:
                pass
        finally:
            room.listeners.discard(l)
            l.push(None)
            pump.cancel()
            await self.bus.report_listeners(rid, len(room.listeners))
            if room.source is not None:
                await room.publish_status()
            log.debug("[%s] слушатель ушёл, сброшено пакетов: %d", rid, l.dropped)
        return ws

    # ---------- админ-API ----------

    def _auth(self, request):
        if not self.admin or request.headers.get("Authorization") != f"Bearer {self.admin}":
            raise web.HTTPUnauthorized(text="admin token required")

    def _links(self, request, rec) -> dict:
        base = self.public_url or f"{request.scheme}://{request.host}"
        ws = base.replace("https://", "wss://").replace("http://", "ws://")
        return {"listen_url": f"{base}/r/{rec.id}", "app_url": f"{base}/r/{rec.id}",
                "ingest_url": f"{ws}/ingest/{rec.id}?key={rec.key}"}

    async def api_rooms(self, request):
        self._auth(request)
        return web.json_response([r.public() for r in self.store.list()])

    async def api_create(self, request):
        self._auth(request)
        body = await request.json() if request.can_read_body else {}
        rid = body.get("id")
        if rid and (not ROOM_RE.match(rid) or self.store.get(rid)):
            raise web.HTTPConflict(text="room id invalid or taken")
        plan = body.get("plan", self.default_plan)
        if plan not in self.plans:
            raise web.HTTPBadRequest(text=f"unknown plan, available: {list(self.plans)}")
        rec = self.store.create(rid, plan, body.get("owner", ""), body.get("days"))
        return web.json_response(rec.public() | self._links(request, rec), status=201)

    async def api_update(self, request):
        self._auth(request)
        rec = self.store.get(request.match_info["room"])
        if not rec:
            raise web.HTTPNotFound()
        body = await request.json()
        if "plan" in body:
            if body["plan"] not in self.plans:
                raise web.HTTPBadRequest(text="unknown plan")
            rec.plan = body["plan"]
        if "days" in body:
            rec.expires = time.time() + float(body["days"]) * 86400 if body["days"] else None
        if "plugins" in body:
            rec.plugins = body["plugins"]
        if "plugin_config" in body:
            rec.plugin_config = body["plugin_config"]
        if body.get("rotate_key"):
            rec.key = secrets.token_urlsafe(18)
        self.store.save(rec)
        return web.json_response(rec.public() | self._links(request, rec))

    async def api_delete(self, request):
        self._auth(request)
        return web.json_response({"deleted": self.store.delete(request.match_info["room"])})

    async def api_plugins(self, request):
        self._auth(request)
        return web.json_response({"plugins": sorted(plugin_mod.available()), "plans": self.plans})


def make_app() -> web.Application:
    a = App()
    app = web.Application()
    app["wc"] = a
    app.on_startup.append(a.on_startup)
    app.on_cleanup.append(a.on_cleanup)
    r = app.router
    r.add_get("/", a.page)
    r.add_get("/r/{room}", a.page)
    r.add_get("/ws", a.listen)
    r.add_get("/ws/{room}", a.listen)
    r.add_get("/ingest", a.ingest)
    r.add_get("/ingest/{room}", a.ingest)
    r.add_get("/status", a.status)
    r.add_get("/healthz", a.healthz)
    r.add_get("/metrics", a.metrics)
    r.add_get("/api/rooms", a.api_rooms)
    r.add_post("/api/rooms", a.api_create)
    r.add_patch("/api/rooms/{room}", a.api_update)
    r.add_delete("/api/rooms/{room}", a.api_delete)
    r.add_get("/api/plugins", a.api_plugins)
    r.add_static("/static", STATIC)
    return app


def main():
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    a_key, a_admin = os.environ.get("WAVECAST_KEY"), os.environ.get("WAVECAST_ADMIN_TOKEN")
    if not a_key and not a_admin:
        log.warning("Не заданы WAVECAST_KEY и WAVECAST_ADMIN_TOKEN — вещать никто не сможет")
    web.run_app(make_app(), host=os.environ.get("WAVECAST_HOST", "0.0.0.0"),
                port=int(os.environ.get("WAVECAST_PORT", "8080")), access_log=None)


if __name__ == "__main__":
    main()
