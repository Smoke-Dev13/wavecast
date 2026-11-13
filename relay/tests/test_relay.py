import asyncio
import json
import os
import shutil
import socket
import subprocess
import time

import av
import numpy as np
import pytest
from aiohttp import WSMsgType, web
from aiohttp.test_utils import TestClient, TestServer

from wavecast.plugins import dj as dj_mod
from wavecast.plugins import recognize as rec_mod
from wavecast.plugins.denoise import Denoise
from wavecast.plugins.loudnorm import LoudNorm

FMT = json.dumps({"type": "format", "codec": "pcm_s16le", "rate": 48000, "channels": 2})


def sine(k, amp=0.3, f=440.0, n=1920):
    t = (np.arange(n) + k * n) / 48000
    a = np.stack([np.sin(2 * np.pi * f * t)] * 2, 1) * amp
    return (a * 32767).astype("<i2").tobytes()


@pytest.fixture
def env(monkeypatch, tmp_path):
    monkeypatch.setenv("WAVECAST_KEY", "k")
    monkeypatch.setenv("WAVECAST_ADMIN_TOKEN", "adm")
    monkeypatch.setenv("WAVECAST_DB", str(tmp_path / "wc.db"))
    monkeypatch.delenv("REDIS_URL", raising=False)
    return monkeypatch


async def client(app=None):
    from wavecast.server import make_app
    c = TestClient(TestServer(app or make_app()))
    await c.start_server()
    return c


async def drain(ws, t=0.4):
    texts, bins = [], []
    try:
        while True:
            m = await ws.receive(timeout=t)
            if m.type == WSMsgType.TEXT:
                texts.append(json.loads(m.data))
            elif m.type == WSMsgType.BINARY:
                bins.append(m.data)
            else:
                break
    except asyncio.TimeoutError:
        pass
    return texts, bins


async def test_auth_and_stream(env):
    c = await client()
    with pytest.raises(Exception):
        await c.ws_connect("/ingest/party?key=bad")
    lo = await c.ws_connect("/ws/party?codec=opus")
    lp = await c.ws_connect("/ws/party?codec=pcm")
    src = await c.ws_connect("/ingest/party?key=k")
    await src.send_str(FMT)
    for k in range(25):
        await src.send_bytes(sine(k))
        await asyncio.sleep(0.005)
    to, bo = await drain(lo)
    tp, bp = await drain(lp)
    assert sum(len(b) for b in bp) == 25 * 1920 * 4
    assert 45 <= len(bo) <= 50                           # 1 с аудио ≈ 50 Opus-пакетов
    assert any(t["type"] == "status" and t["live"] for t in to)
    # Opus декодируется и это 440 Гц
    ctx = av.CodecContext.create("libopus", "r")
    ctx.sample_rate, ctx.layout = 48000, "stereo"
    pcm = np.concatenate([f.to_ndarray().reshape(-1) for p in bo for f in ctx.decode(av.Packet(p))])
    mono = pcm.reshape(-1, 2)[:, 0][4800:]
    peak = np.argmax(np.abs(np.fft.rfft(mono))) * 48000 / len(mono)
    assert abs(peak - 440) < 5
    st = await (await c.get("/status?room=party")).json()
    assert st["live"] and set(st["plugins"]) == {"denoise", "loudnorm"}
    await src.close()
    t, _ = await drain(lp)
    assert any(x["type"] == "status" and not x["live"] for x in t)
    await c.close()


async def test_admin_rooms_plan_limit_and_ai(env, monkeypatch):
    async def fake_backend(wav, cfg):
        return {"title": "Song", "artist": "Band", "cover": None, "url": None}

    async def fake_line(self, t):
        return f"В эфире {t['artist']}!"

    async def fake_speak(self, text):
        return np.full((48000, 2), 0.5, np.float32)   # 1 с «голоса»

    monkeypatch.setitem(rec_mod.BACKENDS, "fake", fake_backend)
    monkeypatch.setattr(dj_mod.DJ, "write_line", fake_line)
    monkeypatch.setattr(dj_mod.DJ, "speak", fake_speak)

    c = await client()
    h = {"Authorization": "Bearer adm"}
    assert (await c.get("/api/rooms")).status == 401
    r = await c.post("/api/rooms", json={"id": "club", "plan": "pro"}, headers=h)
    room = await r.json()
    assert r.status == 201 and room["ingest_url"].endswith(room["key"])
    await c.patch("/api/rooms/club", json={"plugin_config": {"recognize": {"backend": "fake", "window_s": 1, "interval_s": 0},
                                                             "dj": {"min_gap_s": 0}}}, headers=h)
    # общий ключ не подходит к зарегистрированной комнате
    with pytest.raises(Exception):
        await c.ws_connect("/ingest/club?key=k")
    l = await c.ws_connect("/ws/club?codec=pcm")
    src = await c.ws_connect(f"/ingest/club?key={room['key']}")
    await src.send_str(FMT)
    for k in range(75):  # 3 с
        await src.send_bytes(sine(k, amp=0.05))
        await asyncio.sleep(0.01)
    texts, bins = await drain(l, 0.6)
    types = [t["type"] for t in texts]
    assert "track" in types and "dj" in types, types
    track = next(t for t in texts if t["type"] == "track")
    assert track["title"] == "Song"
    # голос диджея подмешан: где-то сигнал сильно громче исходной синусоиды 0.05
    peak = max(np.abs(np.frombuffer(b, "<i2")).max() for b in bins) / 32767
    assert peak > 0.3
    # новый слушатель сразу получает «сейчас играет»
    l2 = await c.ws_connect("/ws/club?codec=pcm")
    t2, _ = await drain(l2, 0.3)
    assert any(t["type"] == "track" for t in t2)
    await c.close()


async def test_listener_limit(env, tmp_path):
    plans = tmp_path / "plans.json"
    plans.write_text(json.dumps({"free": {"max_listeners": 1, "plugins": []}}))
    env.setenv("WAVECAST_PLANS", str(plans))
    c = await client()
    src = await c.ws_connect("/ingest/x?key=k")
    await src.send_str(FMT)
    l1 = await c.ws_connect("/ws/x")
    await drain(l1, 0.2)
    l2 = await c.ws_connect("/ws/x")
    t, _ = await drain(l2, 0.3)
    assert t and t[0]["type"] == "error"
    await c.close()


def test_loudnorm_and_denoise():
    class R: id, rate, channels = "t", 48000, 2
    ln = LoudNorm(R(), {})
    quiet = np.stack([np.sin(np.linspace(0, 400, 1920))] * 2, 1).astype(np.float32) * 0.02
    out = None
    for _ in range(200):
        out = ln.process(quiet)
    assert 20 * np.log10(np.sqrt(np.mean(out ** 2))) > -32           # тихий трек подтянут (до +12 дБ)
    loud = quiet / 0.02 * 1.0
    for _ in range(5):
        o = ln.process(loud)
    assert np.abs(o).max() <= 0.951                                    # лимитер держит потолок
    dn = Denoise(R(), {})
    hiss = (np.random.randn(1920, 2) * 0.0003).astype(np.float32)
    for _ in range(20):
        o = dn.process(hiss)
    assert np.sqrt(np.mean(o ** 2)) < 0.0003 * 0.05                    # шум в паузе задавлен


def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


@pytest.mark.skipif(not shutil.which("redis-server"), reason="нет redis-server")
async def test_redis_cluster(env, monkeypatch):
    port = free_port()
    proc = subprocess.Popen(["redis-server", "--port", str(port), "--save", "", "--appendonly", "no"],
                            stdout=subprocess.DEVNULL)
    time.sleep(0.5)
    try:
        monkeypatch.setenv("REDIS_URL", f"redis://127.0.0.1:{port}")
        from wavecast.server import make_app
        a = await client(make_app())
        b = await client(make_app())
        lb = await b.ws_connect("/ws/multi?codec=pcm")          # слушатель на инстансе B
        src = await a.ws_connect("/ingest/multi?key=k")        # источник на инстансе A
        await src.send_str(FMT)
        await asyncio.sleep(0.2)
        for k in range(10):
            await src.send_bytes(sine(k))
        await asyncio.sleep(2.5)                               # дать тикеру посчитать слушателей
        texts, bins = await drain(lb, 0.5)
        assert sum(len(x) for x in bins) == 10 * 1920 * 4
        m = await src.receive(timeout=1)
        assert json.loads(m.data)["count"] == 1                # источник видит слушателя с другого инстанса
        await a.close(); await b.close()
    finally:
        proc.terminate()
