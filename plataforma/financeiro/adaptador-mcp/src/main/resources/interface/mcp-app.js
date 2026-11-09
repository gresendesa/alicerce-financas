// O mínimo do MCP Apps, por postMessage: recebe a vista do resultado da tool e a desenha. Não fala com
// a rede e não vê token nenhum.
(function () {
  "use strict";

  const alvo = document.getElementById("vista");
  let proximoId = 1;

  function enviar(mensagem) {
    window.parent.postMessage(Object.assign({ jsonrpc: "2.0" }, mensagem), "*");
  }

  function avisarTamanho() {
    enviar({ method: "ui/notifications/size-changed", params: { height: document.documentElement.scrollHeight } });
  }

  function mostrarErro(texto) {
    const paragrafo = document.createElement("p");
    paragrafo.className = "aviso";
    paragrafo.textContent = texto;
    alvo.replaceChildren(paragrafo);
    avisarTamanho();
  }

  function desenhar(resultado) {
    if (resultado.isError || !resultado.structuredContent) {
      mostrarErro("O servidor recusou esta vista.");
      return;
    }
    try {
      AlicerceVista.renderizar(alvo, resultado.structuredContent, {
        aoAbrir(evento, url) {
          evento.preventDefault();
          enviar({ id: proximoId++, method: "ui/open-link", params: { url } });
        },
      });
      avisarTamanho();
    } catch (recusada) {
      mostrarErro("Esta vista não pôde ser desenhada.");
    }
  }

  const inicializacao = proximoId++;
  window.addEventListener("message", (evento) => {
    if (evento.source !== window.parent) return;
    const mensagem = evento.data;
    if (!mensagem || mensagem.jsonrpc !== "2.0") return;
    if (mensagem.id === inicializacao) {
      enviar({ method: "ui/notifications/initialized", params: {} });
    } else if (mensagem.method === "ui/notifications/tool-result") {
      desenhar(mensagem.params || {});
    }
  });

  enviar({
    id: inicializacao,
    method: "ui/initialize",
    params: { protocolVersion: "2026-01-26", appInfo: { name: "alicerce-vista", version: "1.0.0" }, appCapabilities: {} },
  });
})();
