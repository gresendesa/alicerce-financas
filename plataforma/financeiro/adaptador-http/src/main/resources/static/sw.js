// Guarda só a casca, para abrir sem rede. Nunca guarda /api: dado financeiro não fica no aparelho. A página pública
// de prestação de contas (/fin/p/) passa direto: ela não é a casca e nunca abre sem rede.
const CACHE = "alicerce-casca-v14";
const CASCA = ["/fin/contas", "/web/estilo.css", "/web/casca.css", "/web/renderizador.js", "/web/app.js", "/web/config.json", "/web/icone.svg"];

self.addEventListener("install", (evento) => {
  evento.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(CASCA)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (evento) => {
  evento.waitUntil(caches.keys()
    .then((chaves) => Promise.all(chaves.filter((c) => c !== CACHE).map((c) => caches.delete(c))))
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (evento) => {
  const url = new URL(evento.request.url);
  const casca = url.origin === self.location.origin && (url.pathname.startsWith("/web/") || evento.request.mode === "navigate");
  if (evento.request.method !== "GET" || !casca || url.pathname.startsWith("/fin/comprovante/") || url.pathname.startsWith("/fin/anexo/")
    || url.pathname.startsWith("/fin/p/")) return;
  evento.respondWith(fetch(evento.request).catch(async () => {
    const guardada = await caches.match(evento.request.mode === "navigate" ? "/fin/contas" : evento.request);
    return guardada || Response.error();
  }));
});
