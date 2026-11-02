const CACHE = 'estudio-master-hires-prisma-v3';
const STATIC_ASSETS = [
  '/manifest.json',
  '/icons/icon-192.png?v=master1',
  '/icons/icon-512.png?v=master1',
  '/fonts/PlusJakartaSans-Variable.ttf',
  '/fonts/JetBrainsMono-Variable.ttf',
  '/fonts/BarlowSemiCondensed-Medium.ttf',
  '/fonts/BarlowSemiCondensed-SemiBold.ttf',
  '/fonts/BarlowSemiCondensed-Bold.ttf',
];

self.addEventListener('install', (e) => {
  self.skipWaiting();
  e.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(STATIC_ASSETS)).catch(() => {})
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    (async () => {
      const keys = await caches.keys();
      const hadOldCache = keys.some((k) => k !== CACHE);
      await Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)));
      await self.clients.claim();
      if (hadOldCache) {
        const windowClients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
        for (const client of windowClients) {
          try {
            if ('navigate' in client) {
              await client.navigate(client.url);
            }
          } catch (_) {}
          try {
            client.postMessage({ type: 'ESTUDIO_EQ_SW_PURGED' });
          } catch (_) {}
        }
      }
    })()
  );
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  if (url.origin !== self.location.origin) return;
  if (
    url.pathname.startsWith('/api/') ||
    url.pathname.startsWith('/media/') ||
    url.pathname.startsWith('/@') ||
    url.pathname.startsWith('/node_modules/') ||
    url.pathname === '/sw.js'
  ) {
    return;
  }

  // Network-first: siempre sirve el diseño y código más reciente si hay conexión,
  // y usa caché únicamente como respaldo cuando no hay internet (modo PWA offline).
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        if (res && res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {});
        }
        return res;
      })
      .catch(() =>
        caches.match(e.request).then((hit) => hit || caches.match('/index.html') || caches.match('/'))
      )
  );
});
