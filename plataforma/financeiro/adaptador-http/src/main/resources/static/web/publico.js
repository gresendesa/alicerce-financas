// A página pública de prestação de contas. Sem login, sem token OAuth e sem nada do app: só lê a vista
// guardada no retrato do link e a desenha com o mesmo renderizador do catálogo. O título fica genérico, para a
// prévia de um mensageiro não mostrar valor nem nome.
(function () {
  "use strict";

  const alvo = document.getElementById("conteudo");
  const partes = location.pathname.split("/");
  const token = partes.length === 4 && partes[1] === "fin" && partes[2] === "p" ? partes[3] : "";

  function aviso(texto) {
    const titulo = document.createElement("h1");
    titulo.textContent = "Link indisponível";
    const p = document.createElement("p");
    p.textContent = texto;
    alvo.replaceChildren(titulo, p);
  }

  fetch("/fin/p/" + encodeURIComponent(token) + "/vista", { credentials: "omit", cache: "no-store", referrerPolicy: "no-referrer" })
    .then((resposta) => {
      if (!resposta.ok) throw new Error(String(resposta.status));
      return resposta.json();
    })
    .then((vista) => {
      globalThis.AlicerceVista.renderizar(alvo, vista, {});
    })
    .catch(() => aviso("Este link não abre mais: venceu, foi revogado ou não existe. Peça um novo a quem o enviou."));
})();
