/*
 * FSP push service worker (F-12). Receives Web Push messages (RFC 8291 — the payload is
 * already decrypted by the browser before we see it), shows a system notification, and
 * focuses/navigates on click. The server payload is DATA-MINIMIZED by design (AGENTS §2c):
 * generic title/body + opaque link; never amounts, account data or personal details.
 */
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = { title: 'FSP', body: '' };
  }
  const title = data.title || 'FSP';
  event.waitUntil(
    self.registration.showNotification(title, {
      body: data.body || '',
      tag: data.type || 'fsp',
      // re-navigations land on the SPA; the router resolves the link from postMessage state
      data: { link: data.link || '/approvals' },
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const link = (event.notification.data && event.notification.data.link) || '/approvals';
  event.waitUntil(
    (async () => {
      const clientList = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      for (const client of clientList) {
        if ('focus' in client) {
          await client.focus();
          client.postMessage({ type: 'fsp-navigate', link });
          return;
        }
      }
      await self.clients.openWindow(link);
    })(),
  );
});
