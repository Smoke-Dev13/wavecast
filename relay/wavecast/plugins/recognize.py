"""Распознавание играющего трека и показ «Сейчас играет» слушателям.

Бэкенды:
  shazam   — shazamio, без ключей (неофициальный API Shazam)
  acoustid — Chromaprint (fpcalc) + AcoustID, нужен бесплатный ключ: https://acoustid.org/new-application
"""
import asyncio
import io
import json
import os
import shutil
import tempfile
import time
import wave

import numpy as np

from . import Plugin, register

SR = 16_000


def to_wav(mono16k: np.ndarray) -> bytes:
    pcm = (np.clip(mono16k, -1, 1) * 32767).astype("<i2").tobytes()
    bio = io.BytesIO()
    with wave.open(bio, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm)
    return bio.getvalue()


async def shazam_backend(wav: bytes, cfg: dict) -> dict | None:
    from shazamio import Shazam
    out = await Shazam().recognize(wav)
    t = out.get("track") if isinstance(out, dict) else None
    if not t:
        return None
    return {
        "title": t.get("title"),
        "artist": t.get("subtitle"),
        "cover": (t.get("images") or {}).get("coverart"),
        "url": t.get("url"),
    }


async def acoustid_backend(wav: bytes, cfg: dict) -> dict | None:
    import aiohttp
    key = cfg.get("acoustid_key") or os.environ.get("ACOUSTID_KEY")
    fpcalc = shutil.which("fpcalc")
    if not key or not fpcalc:
        raise RuntimeError("для acoustid нужны ACOUSTID_KEY и fpcalc (пакет chromaprint)")
    with tempfile.NamedTemporaryFile(suffix=".wav") as f:
        f.write(wav); f.flush()
        p = await asyncio.create_subprocess_exec(fpcalc, "-json", f.name, stdout=asyncio.subprocess.PIPE)
        fp = json.loads((await p.communicate())[0])
    params = {"client": key, "meta": "recordings releasegroups", "duration": int(fp["duration"]),
              "fingerprint": fp["fingerprint"]}
    async with aiohttp.ClientSession() as s:
        async with s.post("https://api.acoustid.org/v2/lookup", data=params) as r:
            data = await r.json()
    for res in data.get("results", []):
        for rec in res.get("recordings", []) or []:
            if rec.get("title"):
                return {"title": rec["title"],
                        "artist": ", ".join(a["name"] for a in rec.get("artists", [])) or None,
                        "cover": None, "url": f"https://musicbrainz.org/recording/{rec['id']}"}
    return None


BACKENDS = {"shazam": shazam_backend, "acoustid": acoustid_backend}


@register
class Recognize(Plugin):
    name = "recognize"
    order = 50  # после обработки звука, до подмешивания голоса

    def __init__(self, room, config):
        super().__init__(room, config)
        self.window = float(config.get("window_s", 10))
        self.interval = float(config.get("interval_s", 20))
        self.backend = BACKENDS.get(config.get("backend", "shazam"), shazam_backend)
        self.buf = np.zeros(int(self.window * SR), dtype=np.float32)
        self.filled = 0
        self.last_try = 0.0
        self.busy = False
        self.current: tuple | None = None
        self._task: asyncio.Task | None = None

    def process(self, audio: np.ndarray) -> np.ndarray:
        rate = self.room.rate
        mono = audio.mean(axis=1)
        step = max(1, rate // SR)
        n = len(mono) // step * step
        ds = mono[:n].reshape(-1, step).mean(axis=1) if step > 1 else mono
        k = len(ds)
        self.buf = np.roll(self.buf, -k)
        self.buf[-k:] = ds
        self.filled = min(len(self.buf), self.filled + k)

        now = time.monotonic()
        if (not self.busy and self.filled == len(self.buf) and now - self.last_try >= self.interval
                and float(np.sqrt(np.mean(self.buf ** 2))) > 0.003):
            self.last_try = now
            self.busy = True
            self._task = asyncio.get_running_loop().create_task(self._run(self.buf.copy()))
        return audio

    async def _run(self, snippet: np.ndarray):
        try:
            info = await self.backend(to_wav(snippet), self.config)
            if info and info.get("title"):
                key = (info.get("title"), info.get("artist"))
                if key != self.current:
                    self.current = key
                    self.log.info("сейчас играет: %s — %s", info.get("artist"), info.get("title"))
                    await self.room.emit({"type": "track", **info})
        except Exception as e:
            self.log.warning("распознавание не удалось: %s", e)
        finally:
            self.busy = False

    async def stop(self):
        if self._task:
            self._task.cancel()
