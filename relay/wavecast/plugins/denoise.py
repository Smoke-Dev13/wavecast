"""Шумодав для эфира: удаление постоянной составляющей + noise gate с плавными фронтами.

Глушит шипение и фон в паузах между треками, музыку не трогает.
"""
import numpy as np

from . import Plugin, register


@register
class Denoise(Plugin):
    name = "denoise"
    order = 10

    def __init__(self, room, config):
        super().__init__(room, config)
        self.open_db = float(config.get("open_db", -55.0))    # выше — гейт открывается
        self.close_db = float(config.get("close_db", -62.0))  # ниже — начинает закрываться (гистерезис)
        self.floor = 10 ** (float(config.get("floor_db", -40.0)) / 20)  # насколько глушить
        self.hold_chunks = int(config.get("hold_chunks", 8))   # ~320 мс удержания при кусках по 40 мс
        self.dc = None
        self.gain = 1.0
        self.below = 0

    def process(self, audio: np.ndarray) -> np.ndarray:
        if not audio.size:
            return audio
        mean = audio.mean(axis=0)
        self.dc = mean if self.dc is None else self.dc * 0.95 + mean * 0.05
        audio = audio - self.dc

        rms = float(np.sqrt(np.mean(audio * audio)))
        lvl = 20 * np.log10(max(rms, 1e-9))
        if lvl >= self.open_db:
            self.below, want = 0, 1.0
        elif lvl < self.close_db:
            self.below += 1
            want = self.floor if self.below > self.hold_chunks else self.gain
        else:
            want = self.gain

        ramp = np.linspace(self.gain, want, len(audio), dtype=np.float32)[:, None]
        self.gain = want
        return audio * ramp
