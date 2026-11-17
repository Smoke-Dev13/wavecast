# WaveCast

Своё радио с телефона. Нажимаешь «В эфир», включаешь музыку в любом приложении или в браузере — друзья открывают ссылку и слышат в своих наушниках то же, что и ты. Слушателям ничего ставить не нужно.

```
 Телефон (Android 10+)                                   Друзья (любой браузер)
 звук всех приложений ──▶ WaveCast ══ WebRTC, Opus, напрямую ══▶ https://…/r/party
                              │                                      ▲
                              └── сигнальный Worker (только знакомит, звук через него не идёт)
                              └── http://192.168.x.x:8080 — та же Wi-Fi сеть, без интернета
```

## Быстрый старт

**1. Сигнальный сервис** — один раз, бесплатный Cloudflare, только мышкой:

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/Smoke-Dev13/wavecast/tree/main/p2p/worker)

Войди в Cloudflare, нажми **Create and deploy** — через минуту будет адрес вида `https://wavecast.<аккаунт>.workers.dev`.
Или из терминала: `cd p2p/worker && npm i && npx wrangler deploy`.

**2. Телефон:** APK из [Releases](../../releases/latest) → вставь адрес сервиса → «В эфир» → покажи QR или нажми «Поделиться ссылкой».

**3. Друзья** открывают ссылку и жмут «Слушать».

Без адреса сервиса приложение работает только в локальном режиме: ссылка `http://<IP телефона>:8080` для тех, кто в твоей Wi-Fi сети или на твоей точке доступа.

## Безопасность

- **Запрос на вход.** Слушатель вводит имя и просится в эфир, ведущему приходит уведомление «Аня хочет подключиться» с кнопками «Пустить» и «Отклонить». Пущенных и отклонённых приложение помнит до конца эфира. Одновременно ждут не больше 5 запросов. Можно включить «Пускать всех без вопросов». Работает и по ссылке, и в Wi‑Fi сети.
- **Имя слушателя ничем не подтверждено** — это подсказка, а не проверка личности.
- **Ключ подписи APK** в репозиторий не входит. Для сборки: `android/keystore.properties` (storeFile, storePassword, keyAlias, keyPassword) или переменные `WAVECAST_KEYSTORE`, `WAVECAST_STORE_PASSWORD`. В GitHub Actions — секреты `WAVECAST_KEYSTORE_B64` и `WAVECAST_STORE_PASSWORD`, без них APK собирается неподписанным.
- В эфир попадает «медийный» звук: музыка, видео, игры, голосовые и звук мессенджеров по умолчанию не попадают (переключатель «Не транслировать мессенджеры»). Звонки и уведомления — тоже нет.
- При прямом соединении ведущий и слушатели видят IP-адреса друг друга, как при любом P2P-звонке.

## Обновления

Приложение при открытии проверяет `https://<сервис>/app/latest.json` и, если вышла новая версия, показывает плашку «Обновить». APK скачивается с того же сайта, сверяется по SHA-256 и ставится через системное окно установки. Android примет обновление только с той же подписью.

Выпуск новой версии: подними `versionCode`/`versionName` в `android/app/build.gradle.kts`, затем
```bash
cd android && ./release.sh "Что нового" && cd .. && git add -A && git commit -m "release" && git push
```
Cloudflare сам выложит APK и `latest.json`. Ссылка на установку для друзей: `https://<сервис>/app/wavecast.apk`.

## Как это устроено

- Звук идёт от телефона к каждому слушателю напрямую (P2P), ~128 кбит/с стерео Opus на человека. По умолчанию до 30 слушателей; упирается в исходящий канал телефона.
- Название эфира закрепляется за ключом твоего приложения: чужой телефон не сможет вещать в твою комнату.
- Если сервис ненадолго отвалится, уже подключённые продолжают слушать.
- Часть мобильных сетей (CGNAT) не пропускает прямые соединения. Тогда нужен TURN: создай ключ в Cloudflare → Realtime → TURN и выполни `npx wrangler secret put TURN_KEY_ID`, `npx wrangler secret put TURN_KEY_API_TOKEN`. У слушателя в поле «Связь» будет видно «напрямую» или «через TURN».
- В эфир попадает музыка, видео и игры. Звонки, уведомления и приложения, запретившие захват, не попадают.

## Для больших эфиров: relay-сервер

Когда слушателей больше, чем тянет канал телефона, звук можно раздавать через свой сервер (`relay/`): комнаты, Opus, ИИ-плагины, кластер на Redis. В приложение этот режим пока не встроен; с relay работает источник с ПК (`source/`).

```bash
cd relay && cp .env.example .env && sed -i "s/^WAVECAST_KEY=.*/WAVECAST_KEY=$(openssl rand -hex 16)/; s/^WAVECAST_ADMIN_TOKEN=.*/WAVECAST_ADMIN_TOKEN=$(openssl rand -hex 24)/" .env && docker compose up -d --build && grep -E "KEY|TOKEN" .env
pip install -r source/requirements.txt && python source/wavecast_source.py https://host/r/party -k КЛЮЧ
```

### Комнаты и тарифы (админ-API)

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

### Масштабирование relay

```bash
echo "REDIS_URL=redis://redis:6379/0" >> .env && docker compose --profile cluster up -d --build --scale wavecast=3
```
Источник подключается к любому инстансу, слушатели к любым другим. Пакеты ходят через Redis pub/sub, а слушатели учитываются по всему кластеру. Метрики для Prometheus отдаются на `/metrics`.

### Плагин за 20 строк

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

### ИИ-диджей

LLM подключается через любой OpenAI-совместимый API: OpenAI, OpenRouter или локальные Ollama и LM Studio (`DJ_LLM_URL=http://host:11434/v1`). Если ключа нет, диджей зачитывает шаблон «Сейчас у нас в эфире…». Для TTS есть `edge` (бесплатно, голоса `ru-RU-DmitryNeural`, `ru-RU-SvetlanaNeural`, `kk-KZ-DauletNeural`…) и `openai` (любой совместимый `/audio/speech`, включая локальный Piper).

## Разработка

```bash
cd relay && pip install -e ".[ai,redis,dev]" && python -m pytest -q     # тесты, включая кластер на Redis
python -m wavecast                                                       # локальный запуск
```
Сигнальный сервис локально: `cd p2p/worker && npx wrangler dev`.

Android без Gradle и SDK (Ubuntu/Debian): `android/build-nogradle.sh` (нужен ключ подписи, см. «Безопасность»).

Страницы слушателя собираются из `design-src/`: `python3 design-src/build.py`.

Android: `gradle -p android assembleRelease` (JDK 17, Android SDK 34). APK собирается в GitHub Actions при каждом push, релиз создаётся по тегу `v*`.

## Планы

- [ ] iOS (ReplayKit) и Telegram-бот: управление комнатой, ссылка в чат
- [ ] «Сейчас играет» из системного плеера телефона
- [ ] Приложение-слушатель / PWA: звук не обрывается при блокировке экрана
- [ ] Синхронное прослушивание рядом (компенсация задержки)
- [ ] HLS + CDN для тысяч слушателей
- [ ] Аккаунты, личный кабинет, оплата (Kaspi / Stripe) поверх админ-API
- [ ] Чат и реакции слушателей, заявки на треки, ИИ-модерация

MIT License
