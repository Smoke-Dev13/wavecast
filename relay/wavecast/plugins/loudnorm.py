"""Выравнивание громкости: медленный AGC к целевому уровню + пиковый лимитер."""
import numpy as np

from . import Plugin, register


def _db(x: float) -> float:
    return 20.0 * np.log10(max(x, 1e-9))


@register
class LoudNorm(Plugin):
    name = "loudnorm"
    order = 20

    def __init__(self, room, config):
        super().__init__(room, config)
        self.target = float(config.get("target_db", -18.0))     # целевой RMS, dBFS (≈ -14…-16 LUFS для музыки)
        self.max_gain = float(config.get("max_gain_db", 12.0))
        self.min_gain = float(config.get("min_gain_db", -12.0))
        self.ceiling = float(config.get("ceiling", 0.95))       # потолок лимитера
        self.silence = float(config.get("silence_db", -50.0))   # тише этого — не подтягиваем
        self.attack = float(config.get("attack", 0.30))         # доля шага на кусок при росте уровня
        self.release = float(config.get("release", 0.04))       # при падении уровня (медленно, чтобы не «качало»)
        self.env_db = None
        self.gain = 1.0

    def process(self, audio: np.ndarray) -> np.ndarray:
        rms = float(np.sqrt(np.mean(audio * audio))) if audio.size else 0.0
        lvl = _db(rms)
        if lvl > self.silence:
            if self.env_db is None:
                self.env_db = lvl
            k = self.attack if lvl > self.env_db else self.release
            self.env_db += k * (lvl - self.env_db)
            want_db = np.clip(self.target - self.env_db, self.min_gain, self.max_gain)
            want = 10 ** (want_db / 20)
        else:
            want = self.gain  # в тишине держим текущее усиление

        peak = float(np.max(np.abs(audio))) if audio.size else 0.0
        if peak * want > self.ceiling:
            want = self.ceiling / max(peak, 1e-9)

        ramp = np.linspace(self.gain, want, len(audio), dtype=np.float32)[:, None]
        self.gain = want
        return audio * ramp
