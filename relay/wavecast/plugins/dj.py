"""ИИ-диджей: на смене трека пишет короткую реплику через LLM, озвучивает и подмешивает
её в эфир, приглушая музыку (ducking).

LLM: любой OpenAI-совместимый API (OpenAI, OpenRouter, Ollama, LM Studio, vLLM…).
TTS: edge-tts (бесплатно, без ключа) или OpenAI-совместимый /audio/speech (OpenAI, Piper/openedai-speech…).
"""
import asyncio
import io
import os
import random
import time

import aiohttp
import numpy as np

from . import Plugin, register

SYSTEM_PROMPT = (
    "Ты — харизматичный радиодиджей. Говоришь по-русски, коротко и живо, без эмодзи и хэштегов. "
    "Одна-две фразы, не больше 30 слов. Можно факт об артисте или настроение трека. "
    "Не выдумывай факты, если не уверен — просто подводка."
)


def decode_audio(data: bytes, rate: int, channels: int) -> np.ndarray:
    """mp3/wav/ogg → float32 (frames, channels) с нужной частотой."""
    import av
    layout = "stereo" if channels == 2 else "mono"
    out = []
    with av.open(io.BytesIO(data)) as c:
        rs = av.AudioResampler(format="flt", layout=layout, rate=rate)
        for frame in c.decode(audio=0):
            for f in rs.resample(frame):
                out.append(f.to_ndarray().reshape(-1, channels))
        for f in rs.resample(None):
            out.append(f.to_ndarray().reshape(-1, channels))
    return np.concatenate(out).astype(np.float32) if out else np.zeros((0, channels), np.float32)


@register
class DJ(Plugin):
    name = "dj"
    order = 90  # последним: подмешивает голос в готовый звук

    def __init__(self, room, config):
        super().__init__(room, config)
        env = os.environ.get
        self.llm_url = config.get("llm_url") or env("DJ_LLM_URL", "https://api.openai.com/v1")
        self.llm_key = config.get("llm_key") or env("DJ_LLM_KEY", "")
        self.llm_model = config.get("llm_model") or env("DJ_LLM_MODEL", "gpt-4o-mini")
        self.tts = config.get("tts") or env("DJ_TTS", "edge")
        self.voice = config.get("voice") or env("DJ_VOICE", "ru-RU-DmitryNeural")
        self.tts_url = config.get("tts_url") or env("DJ_TTS_URL", self.llm_url)
        self.tts_key = config.get("tts_key") or env("DJ_TTS_KEY", self.llm_key)
        self.tts_model = config.get("tts_model") or env("DJ_TTS_MODEL", "tts-1")
        self.min_gap = float(config.get("min_gap_s", 90))
        self.chance = float(config.get("chance", 1.0))      # вероятность выйти в эфир на смене трека
        self.duck = float(config.get("duck", 0.25))          # громкость музыки под голосом
        self.voice_gain = float(config.get("voice_gain", 1.0))
        self.last_talk = 0.0
        self.voice: np.ndarray | None = None
        self.pos = 0
        self.gain = 1.0
        self.busy = False

    async def on_event(self, event):
        if event.get("type") != "track" or self.busy:
            return
        if time.monotonic() - self.last_talk < self.min_gap or random.random() > self.chance:
            return
        self.busy = True
        asyncio.get_running_loop().create_task(self._talk(event))

    async def _talk(self, track: dict):
        try:
            text = await self.write_line(track)
            audio = await self.speak(text)
            self.voice, self.pos = audio * self.voice_gain, 0
            self.last_talk = time.monotonic()
            await self.room.emit({"type": "dj", "text": text})
            self.log.info("диджей: %s", text)
        except Exception as e:
            self.log.warning("диджей промолчал: %s", e)
        finally:
            self.busy = False

    async def write_line(self, t: dict) -> str:
        fallback = f"Сейчас у нас в эфире {t.get('artist') or 'неизвестный артист'} — «{t.get('title')}»."
        if not self.llm_key and "api.openai.com" in self.llm_url:
            return fallback
        body = {
            "model": self.llm_model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": f"Начинается трек: {t.get('artist')} — {t.get('title')}. Подводка?"},
            ],
            "max_tokens": 120, "temperature": 0.9,
        }
        headers = {"Authorization": f"Bearer {self.llm_key}"} if self.llm_key else {}
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=20)) as s:
            async with s.post(self.llm_url.rstrip("/") + "/chat/completions", json=body, headers=headers) as r:
                r.raise_for_status()
                data = await r.json()
        return (data["choices"][0]["message"]["content"] or fallback).strip().strip('"«»')

    async def speak(self, text: str) -> np.ndarray:
        if self.tts == "edge":
            import edge_tts
            buf = bytearray()
            async for chunk in edge_tts.Communicate(text, self.voice).stream():
                if chunk["type"] == "audio":
                    buf += chunk["data"]
            data = bytes(buf)
        else:
            headers = {"Authorization": f"Bearer {self.tts_key}"} if self.tts_key else {}
            body = {"model": self.tts_model, "voice": self.voice, "input": text, "response_format": "mp3"}
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=30)) as s:
                async with s.post(self.tts_url.rstrip("/") + "/audio/speech", json=body, headers=headers) as r:
                    r.raise_for_status()
                    data = await r.read()
        return decode_audio(data, self.room.rate, self.room.channels)

    def process(self, audio: np.ndarray) -> np.ndarray:
        n = len(audio)
        talking = self.voice is not None
        want = self.duck if talking else 1.0
        # плавно приглушаем/возвращаем музыку: шаг не больше 0.15 за кусок (~250 мс на полный ход)
        nxt = self.gain + float(np.clip(want - self.gain, -0.15, 0.15))
        ramp = np.linspace(self.gain, nxt, n, dtype=np.float32)[:, None]
        self.gain = nxt
        out = audio * ramp
        if talking and self.gain <= self.duck + 0.2:   # голос вступает, когда музыка уже притихла
            seg = self.voice[self.pos:self.pos + n]
            out[:len(seg)] += seg
            self.pos += len(seg)
            if self.pos >= len(self.voice):
                self.voice = None
        return np.clip(out, -1.0, 1.0)
