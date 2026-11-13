"""Комнаты и тарифы. SQLite — для одного сервера; для кластера укажи общий путь или вынеси в Postgres."""
import json
import os
import secrets
import sqlite3
import threading
import time
from dataclasses import asdict, dataclass, field

DEFAULT_PLANS = {
    "free": {"max_listeners": 10, "plugins": ["denoise", "loudnorm"], "opus_bitrate": 96_000},
    "pro": {"max_listeners": 200, "plugins": ["denoise", "loudnorm", "recognize", "dj"], "opus_bitrate": 160_000},
}


def load_plans() -> dict:
    path = os.environ.get("WAVECAST_PLANS")
    if path and os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    return DEFAULT_PLANS


@dataclass
class RoomRecord:
    id: str
    key: str
    plan: str = "free"
    owner: str = ""
    created: float = field(default_factory=time.time)
    expires: float | None = None        # для платных тарифов: после истечения — free
    plugins: list[str] | None = None    # переопределение списка плагинов
    plugin_config: dict = field(default_factory=dict)

    def effective_plan(self) -> str:
        return "free" if self.expires and self.expires < time.time() else self.plan

    def public(self) -> dict:
        d = asdict(self)
        d["effective_plan"] = self.effective_plan()
        return d


class Store:
    def __init__(self, path: str):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.lock = threading.Lock()
        self.db.execute("""CREATE TABLE IF NOT EXISTS rooms (
            id TEXT PRIMARY KEY, key TEXT NOT NULL, plan TEXT, owner TEXT, created REAL,
            expires REAL, plugins TEXT, plugin_config TEXT)""")
        self.db.commit()

    def _row(self, r) -> RoomRecord:
        return RoomRecord(id=r[0], key=r[1], plan=r[2], owner=r[3], created=r[4], expires=r[5],
                          plugins=json.loads(r[6]) if r[6] else None,
                          plugin_config=json.loads(r[7]) if r[7] else {})

    def get(self, room_id: str) -> RoomRecord | None:
        with self.lock:
            r = self.db.execute("SELECT * FROM rooms WHERE id=?", (room_id,)).fetchone()
        return self._row(r) if r else None

    def list(self) -> list[RoomRecord]:
        with self.lock:
            return [self._row(r) for r in self.db.execute("SELECT * FROM rooms ORDER BY created")]

    def save(self, rec: RoomRecord) -> RoomRecord:
        with self.lock:
            self.db.execute("INSERT OR REPLACE INTO rooms VALUES (?,?,?,?,?,?,?,?)", (
                rec.id, rec.key, rec.plan, rec.owner, rec.created, rec.expires,
                json.dumps(rec.plugins) if rec.plugins is not None else None,
                json.dumps(rec.plugin_config)))
            self.db.commit()
        return rec

    def create(self, room_id: str | None, plan: str, owner: str = "", days: float | None = None) -> RoomRecord:
        rid = room_id or secrets.token_urlsafe(6).lower().replace("_", "").replace("-", "")
        rec = RoomRecord(id=rid, key=secrets.token_urlsafe(18), plan=plan, owner=owner,
                         expires=time.time() + days * 86400 if days else None)
        return self.save(rec)

    def delete(self, room_id: str) -> bool:
        with self.lock:
            n = self.db.execute("DELETE FROM rooms WHERE id=?", (room_id,)).rowcount
            self.db.commit()
        return n > 0
