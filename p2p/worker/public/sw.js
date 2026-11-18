// WaveCast: минимальный service worker — нужен, чтобы страницу эфира можно было установить как приложение.
// Ничего не кэширует: эфир живой, офлайн он не имеет смысла.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', () => {});
