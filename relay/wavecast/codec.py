"""Кодеки: приведение входа к 48 кГц стерео и кодирование Opus через PyAV (libopus)."""
import av
import numpy as np

RATE = 48_000
CHANNELS = 2


class InputNormalizer:
    """PCM s16le любого формата → float32 (frames, 2) @ 48 кГц."""

    def __init__(self, rate: int, channels: int):
        self.rate, self.channels = rate, channels
        self.passthrough = rate == RATE and channels == CHANNELS
        if not self.passthrough:
            self.rs = av.AudioResampler(format="flt", layout="stereo", rate=RATE)
            self.layout = "stereo" if channels == 2 else "mono"
            self.pts = 0

    def __call__(self, data: bytes) -> np.ndarray:
        pcm = np.frombuffer(data, dtype="<i2")
        if self.passthrough:
            return pcm.reshape(-1, CHANNELS).astype(np.float32) / 32768.0
        frame = av.AudioFrame.from_ndarray(pcm.reshape(1, -1), format="s16", layout=self.layout)
        frame.sample_rate = self.rate
        frame.pts = self.pts
        self.pts += len(pcm) // self.channels
        out = [f.to_ndarray().reshape(-1, CHANNELS) for f in self.rs.resample(frame)]
        return np.concatenate(out).astype(np.float32) if out else np.zeros((0, CHANNELS), np.float32)


def to_s16(audio: np.ndarray) -> np.ndarray:
    return (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2")


class OpusEncoder:
    """Стерео 48 кГц, пакеты по 20 мс. ~128 кбит/с вместо 1536 кбит/с у PCM."""

    def __init__(self, bitrate: int = 128_000):
        ctx = av.CodecContext.create("libopus", "w")
        ctx.sample_rate = RATE
        ctx.layout = "stereo"
        ctx.format = "s16"
        ctx.bit_rate = bitrate
        ctx.options = {"application": "audio", "frame_duration": "20"}
        self.ctx = ctx
        self.pts = 0

    def encode(self, s16: np.ndarray) -> list[bytes]:
        frame = av.AudioFrame.from_ndarray(s16.reshape(1, -1), format="s16", layout="stereo")
        frame.sample_rate = RATE
        frame.pts = self.pts
        self.pts += len(s16)
        return [bytes(p) for p in self.ctx.encode(frame)]
