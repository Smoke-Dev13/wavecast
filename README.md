# WaveCast

Открытый сервис для трансляции звука с телефона или ПК. Источник может быть любым: Spotify, YouTube, Яндекс Музыка, игра. Друзья слушают по ссылке в браузере, приложение им ставить не нужно.

```
 Android / ПК ──PCM──▶ relay ──[denoise → loudnorm → recognize → dj]──▶ Opus/PCM ──▶ браузеры
      │                  │
      └──Wi-Fi напрямую──┘── (Redis pub/sub между инстансами для масштабирования)
```

## Возможности

| | |
|---|---|
| **Захват любого звука** | Android 10+ (AudioPlaybackCapture), Windows/Linux (loopback) |
| **Два режима** | Локально по Wi-Fi или с точки доступа, сервер не нужен. Через интернет — с relay-сервером |
| **Комнаты** | `https://host/r/<комната>`, у каждой свой ключ и тариф |
| **Opus** | ~128 кбит/с вместо 1,5 Мбит/с; если браузер не умеет WebCodecs, автоматически включается PCM |
| **Нормализация громкости** | AGC к целевому уровню + пиковый лимитер |
| **Шумодав** | Удаление DC + noise gate в паузах |
| **Распознавание трека** | «Сейчас играет» с обложкой (Shazam без ключа или AcoustID) |
| **ИИ-диджей** | LLM пишет подводку, TTS озвучивает, музыка приглушается под голос |
| **Плагины** | Свои модули через entry points `wavecast.plugins` |
| **Масштабирование** | Несколько инстансов за балансировщиком, общий Redis |
| **Монетизация** | Тарифы (лимит слушателей, набор плагинов, битрейт), срок действия, админ-API |

## Быстрый старт

**Сервер** (Docker):
```bash
git clone https://github.com/Smoke-Dev13/wavecast && cd wavecast/relay && cp .env.example .env && sed -i "s/^WAVECAST_KEY=.*/WAVECAST_KEY=$(openssl rand -hex 16)/; s/^WAVECAST_ADMIN_TOKEN=.*/WAVECAST_ADMIN_TOKEN=$(openssl rand -hex 24)/" .env && docker compose up -d --build && grep -E "KEY|TOKEN" .env
```
Сервер нужно поставить за HTTPS-прокси (nginx, Caddy, Traefik) с поддержкой WebSocket. Opus в браузере работает только по HTTPS.

**Телефон:** скачай APK из [Releases](../../releases/latest) → включи «Через интернет» → адрес `https://host/r/party` и ключ → «Начать трансляцию».

**ПК:**
```bash
pip install -r source/requirements.txt && python source/wavecast_source.py https://host/r/party -k КЛЮЧ
```

**Друзьям** отправь ссылку `https://host/r/party`.

## Комнаты и тарифы (админ-API)

```bash
T=ТОКЕН; H=https://host
# создать pro-комнату на 30 дней — в ответе ключ, listen_url и ingest_url
curl -s -X POST $H/api/rooms -H "Authorization: Bearer $T" -d '{"id":"club","plan":"pro","days":30}'
# продлить, сменить тариф, перевыпустить ключ, настроить плагины
curl -s -X PATCH $H/api/rooms/club -H "Authorization: Bearer $T" -d '{"days":60,"rotate_key":true,"plugin_config":{"dj":{"chance":0.5}}}'
curl -s $H/api/rooms -H "Authorization: Bearer $T"
curl -s $H/api/plugins -H "Authorization: Bearer $T"
```
Тарифы задаются в `plans.example.json` → `WAVECAST_PLANS`. Когда срок оплаты истекает, комната переходит на `free`. Платёжный шлюз подключается вебхуком к `PATCH /api/rooms/{id}`.

С общим `WAVECAST_KEY` можно вещать в любую незарегистрированную комнату на тарифе по умолчанию.

## Масштабирование

```bash
echo "REDIS_URL=redis://redis:6379/0" >> .env && docker compose --profile cluster up -d --build --scale wavecast=3
```
Источник подключается к любому инстансу, слушатели к любым другим. Пакеты ходят через Redis pub/sub, а слушатели учитываются по всему кластеру. Метрики для Prometheus отдаются на `/metrics`.

## Плагин за 20 строк

```python
# my_pkg/bass.py
import numpy as np
from wavecast.plugins import Plugin

class BassBoost(Plugin):
    name = "bassboost"
    order = 30

    def __init__(self, room, config):
        super().__init__(room, config)
        self.k, self.lp = float(config.get("gain", 0.5)), np.zeros(2, np.float32)

    def process(self, audio):
        out = np.empty_like(audio)
        for i, x in enumerate(audio):            # простой ФНЧ + подмес
            self.lp += 0.02 * (x - self.lp)
            out[i] = x + self.k * self.lp
        return np.clip(out, -1, 1)

    async def on_event(self, event):
        if event["type"] == "track":
            await self.room.emit({"type": "info", "text": f"bass boost для {event['title']}"})
```
```toml
[project.entry-points."wavecast.plugins"]
bassboost = "my_pkg.bass:BassBoost"
```
Потом добавь `"bassboost"` в `plugins` нужного тарифа или комнаты.

## ИИ-диджей

LLM подключается через любой OpenAI-совместимый API: OpenAI, OpenRouter или локальные Ollama и LM Studio (`DJ_LLM_URL=http://host:11434/v1`). Если ключа нет, диджей зачитывает шаблон «Сейчас у нас в эфире…». Для TTS есть `edge` (бесплатно, голоса `ru-RU-DmitryNeural`, `ru-RU-SvetlanaNeural`, `kk-KZ-DauletNeural`…) и `openai` (любой совместимый `/audio/speech`, включая локальный Piper).

## Ограничения

- Android не даст захватить звук приложений, которые явно запретили захват (`allowAudioPlaybackCapture=false`), и звонков.
- На iPhone захватить звук других приложений можно только через ReplayKit Broadcast Extension. Это в планах.
- Shazam-бэкенд использует неофициальный API (shazamio). Для продакшена надёжнее AcoustID.

## Разработка

```bash
cd relay && pip install -e ".[ai,redis,dev]" && python -m pytest -q     # тесты, включая кластер на Redis
python -m wavecast                                                       # локальный запуск
```
Android: `gradle -p android assembleRelease` (JDK 17, Android SDK 34). APK собирается в GitHub Actions при каждом push, релиз создаётся по тегу `v*`.

## Планы

- [ ] iOS (ReplayKit) и Telegram-бот: управление комнатой, ссылка в чат
- [ ] Opus-кодирование прямо на телефоне (MediaCodec), чтобы в 10 раз снизить исходящий трафик
- [ ] HLS + CDN для тысяч слушателей
- [ ] Аккаунты, личный кабинет, оплата (Kaspi / Stripe) поверх админ-API
- [ ] Чат и реакции слушателей, заявки на треки, ИИ-модерация

MIT License
