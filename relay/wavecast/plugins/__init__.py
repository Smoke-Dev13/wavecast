"""Плагинная система WaveCast.

Плагин работает на уровне комнаты и получает аудио по кускам (float32, shape=(frames, channels),
диапазон -1..1). Он может:
  * менять звук в process() (нормализация, шумодав, подмешивание голоса диджея);
  * слать события слушателям через self.room.emit({...});
  * реагировать на события других плагинов в on_event().

Сторонние плагины подключаются через entry point группы ``wavecast.plugins``:

    [project.entry-points."wavecast.plugins"]
    myplugin = "my_package.module:MyPlugin"
"""
from __future__ import annotations

import logging
from importlib.metadata import entry_points
from typing import TYPE_CHECKING, Any

import numpy as np

if TYPE_CHECKING:
    from ..room import Room

log = logging.getLogger("wavecast.plugins")


class Plugin:
    #: уникальное имя, по нему плагин включают в тарифе/комнате
    name: str = ""
    #: порядок в цепочке: меньше — раньше (обработка звука → анализ → подмешивание)
    order: int = 100

    def __init__(self, room: "Room", config: dict[str, Any]):
        self.room = room
        self.config = config
        self.log = logging.getLogger(f"wavecast.{self.name}.{room.id}")

    async def start(self) -> None:
        """Вызывается при запуске эфира в комнате."""

    async def stop(self) -> None:
        """Вызывается при остановке эфира."""

    def process(self, audio: np.ndarray) -> np.ndarray:
        """Синхронная обработка куска звука. Должна быть быстрой (< 1 мс на 40 мс аудио)."""
        return audio

    async def on_event(self, event: dict[str, Any]) -> None:
        """События комнаты: {"type": "track", ...} и т.п."""


_REGISTRY: dict[str, type[Plugin]] = {}


def register(cls: type[Plugin]) -> type[Plugin]:
    if not cls.name:
        raise ValueError(f"{cls.__name__}: пустое имя плагина")
    _REGISTRY[cls.name] = cls
    return cls


def _load_builtin() -> None:
    from . import denoise, dj, loudnorm, recognize  # noqa: F401  (регистрация через декоратор)


def _load_external() -> None:
    try:
        eps = entry_points(group="wavecast.plugins")
    except TypeError:  # python < 3.10
        eps = entry_points().get("wavecast.plugins", [])
    for ep in eps:
        try:
            register(ep.load())
            log.info("внешний плагин %s загружен", ep.name)
        except Exception:
            log.exception("не удалось загрузить плагин %s", ep.name)


def available() -> dict[str, type[Plugin]]:
    if not _REGISTRY:
        _load_builtin()
        _load_external()
    return dict(_REGISTRY)


def build(room: "Room", names: list[str], configs: dict[str, dict]) -> list[Plugin]:
    reg = available()
    out: list[Plugin] = []
    for n in names:
        cls = reg.get(n)
        if cls is None:
            log.warning("плагин %s не найден, пропускаю", n)
            continue
        out.append(cls(room, configs.get(n, {})))
    return sorted(out, key=lambda p: p.order)
