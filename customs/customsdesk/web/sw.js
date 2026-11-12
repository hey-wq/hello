"use strict";
// Makes the app installable and lets its screen open when the connection is poor.
// Shipment data is never cached: anything under /api always goes to the server,
// so the device holds no copy of declarations or records.

const CACHE = "customsdesk-shell-v1";
const SHELL = ["/", "/static/app.css", "/static/app.js", "/static/icons/icon-192.png"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== location.origin || url.pathname.startsWith("/api/")) return;
  // Network first, so a new version of the screen is picked up as soon as it is deployed.
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        if (response.ok) caches.open(CACHE).then((cache) => cache.put(event.request, response.clone()));
        return response.clone();
      })
      .catch(() => caches.match(event.request)));
});
