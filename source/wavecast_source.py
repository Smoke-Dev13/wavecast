#!/usr/bin/env python3
"""WaveCast source для ПК: транслирует звук системы (loopback) или файл на relay.

  Windows / Linux (PulseAudio/PipeWire) / macOS*:
    python wavecast_source.py https://cast.example.com/r/party -k КЛЮЧ
    python wavecast_source.py https://cast.example.com/r/party -k КЛЮЧ --file track.mp3
    python wavecast_source.py --list

  * macOS не умеет loopback без виртуального устройства (BlackHole) — выбери его через --device.
"""
import argparse
import asyncio
import json
import re
import sys
import threading
import time
from urllib.parse import quote

import aiohttp
import numpy as np

RATE, CH, CHUNK = 48_000, 2, 1920  # 40 мс


def ingest_url(url: str, key: str) -> str:
    u = url.strip().rstrip("/")
    if "://" not in u:
        u = "https://" + u
    u = re.sub(r"^http://", "ws://", re.sub(r"^https://", "wss://", u))
    if "/ingest" in u and "key=" in u:
        return u
    m = re.match(r"^(wss?://[^/?#]+)(?:/r/([^/?#]+))?", u)
    room = f"/{m.group(2)}" if m and m.group(2) else ""
    return f"{m.group(1)}/ingest{room}?key={quote(key)}"


def loopback_frames(device: str | None, stop: threading.Event, q: "asyncio.Queue", loop):
    import soundcard as sc
    if device:
        mic = next((m for m in sc.all_microphones(include_loopback=True) if device.lower() in m.name.lower()), None)
        if mic is None:
            sys.exit(f"устройство '{device}' не найдено, смотри --list")
    else:
        mic = sc.get_microphone(id=str(sc.default_speaker().name), include_loopback=True)
    print(f"захват: {mic.name}")
    with mic.recorder(samplerate=RATE, channels=CH, blocksize=CHUNK) as rec:
        while not stop.is_set():
            data = rec.record(numframes=CHUNK)
            pcm = (np.clip(data, -1, 1) * 32767).astype("<i2").tobytes()
            loop.call_soon_threadsafe(q.put_nowait, pcm)


def file_frames(path: str, stop: threading.Event, q: "asyncio.Queue", loop):
    import av
    rs = av.AudioResampler(format="s16", layout="stereo", rate=RATE)
    buf, t0, sent = b"", time.monotonic(), 0
    with av.open(path) as c:
        for frame in c.decode(audio=0):
            for f in rs.resample(frame):
                buf += f.to_ndarray().tobytes()
                while len(buf) >= CHUNK * 4 and not stop.is_set():
                    loop.call_soon_threadsafe(q.put_nowait, buf[:CHUNK * 4])
                    buf, sent = buf[CHUNK * 4:], sent + CHUNK
                    delay = t0 + sent / RATE - time.monotonic()   # отдаём в реальном времени
                    if delay > 0:
                        time.sleep(delay)
            if stop.is_set():
                return
    loop.call_soon_threadsafe(q.put_nowait, None)


async def run(args):
    url = ingest_url(args.url, args.key or "")
    loop = asyncio.get_running_loop()
    q: asyncio.Queue = asyncio.Queue(maxsize=200)
    stop = threading.Event()
    producer = file_frames if args.file else loopback_frames
    threading.Thread(target=producer, args=(args.file or args.device, stop, q, loop), daemon=True).start()

    backoff = 1
    try:
        while True:
            try:
                async with aiohttp.ClientSession() as s, s.ws_connect(url, heartbeat=15) as ws:
                    print("в эфире →", re.sub(r"key=[^&]+", "key=***", url))
                    backoff = 1
                    await ws.send_str(json.dumps({"type": "format", "codec": "pcm_s16le", "rate": RATE, "channels": CH}))

                    async def reader():
                        async for m in ws:
                            if m.type == aiohttp.WSMsgType.TEXT:
                                d = json.loads(m.data)
                                if d.get("type") == "listeners":
                                    print(f"\rслушателей: {d['count']}   ", end="", flush=True)

                    rt = asyncio.create_task(reader())
                    while True:
                        chunk = await q.get()
                        if chunk is None:
                            print("\nфайл закончился"); return
                        while q.qsize() > 50:   # сеть не успевает — не копим задержку
                            q.get_nowait()
                        await ws.send_bytes(chunk)
                        if rt.done():
                            break
            except (aiohttp.ClientError, ConnectionError) as e:
                print(f"\nнет связи ({e}), повтор через {backoff} с")
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 15)
    finally:
        stop.set()


def main():
    ap = argparse.ArgumentParser(description="WaveCast: трансляция звука с ПК")
    ap.add_argument("url", nargs="?", help="https://host/r/<комната> или ingest-ссылка")
    ap.add_argument("-k", "--key", help="ключ комнаты / WAVECAST_KEY")
    ap.add_argument("--file", help="транслировать файл вместо системного звука")
    ap.add_argument("--device", help="часть имени устройства захвата")
    ap.add_argument("--list", action="store_true", help="показать устройства")
    a = ap.parse_args()
    if a.list:
        import soundcard as sc
        for m in sc.all_microphones(include_loopback=True):
            print(("[loopback] " if m.isloopback else "[mic]      ") + m.name)
        return
    if not a.url:
        ap.error("укажи url")
    try:
        asyncio.run(run(a))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
