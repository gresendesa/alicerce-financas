// A casca: login OIDC com PKCE pelo cliente app-web, rotas e leitura da API. O token fica só na memória
// desta aba e tem apenas fin:read; recarregar a página refaz o login sem pedir senha, pela sessão do Keycloak.
// As escritas são duas: desfazer e pedir o link de envio de comprovante ou foto. Cada uma
// pede no clique um token só com o seu escopo (fin:undo ou fin:attach), que fica só na memória e é descartado
// ao fim. As ações moram aqui, fora do catálogo, e só nas telas padrão: vista composta não as alcança.
(function () {
  "use strict";

  const conteudo = document.getElementById("conteudo");
  let config;
  let token = null;
  let desfazendo = null; // { token, codigo }: só entre o clique em desfazer e o resultado
  let anexando = null; // { token, codigo }: só entre o clique em enviar e a abertura da página de envio

  function base64Url(bytes) {
    return btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }

  const sortear = () => base64Url(crypto.getRandomValues(new Uint8Array(32)));

  // acao: { desfazer: codigo } ou { anexar: codigo }; sem ela, o login de leitura.
  async function iniciarLogin(acao) {
    acao = acao || {};
    const verificador = sortear();
    const estado = sortear();
    const desafio = base64Url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verificador)));
    sessionStorage.setItem("login", JSON.stringify({ verificador, estado, destino: location.pathname + location.search,
      desfazer: acao.desfazer, anexar: acao.anexar }));
    const parametros = new URLSearchParams({
      client_id: config.cliente,
      response_type: "code",
      scope: acao.desfazer ? config.escopo_desfazer : acao.anexar ? config.escopo_anexo : config.escopo,
      redirect_uri: location.origin + "/callback",
      state: estado,
      code_challenge: desafio,
      code_challenge_method: "S256",
    });
    location.assign(config.emissor + "/protocol/openid-connect/auth?" + parametros);
  }

  async function concluirLogin() {
    const recebido = new URLSearchParams(location.search);
    const login = JSON.parse(sessionStorage.getItem("login") || "null");
    sessionStorage.removeItem("login");
    if (recebido.get("error")) {
      throw new Error("O login foi recusado (" + recebido.get("error") + "). Volte e tente de novo.");
    }
    if (!login || recebido.get("state") !== login.estado || !recebido.get("code")) {
      throw new Error("O login não foi concluído. Abra o link de novo.");
    }
    const resposta = await fetch(config.emissor + "/protocol/openid-connect/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        client_id: config.cliente,
        code: recebido.get("code"),
        redirect_uri: location.origin + "/callback",
        code_verifier: login.verificador,
      }),
    });
    if (!resposta.ok) throw new Error("O login foi recusado.");
    const recebidoToken = (await resposta.json()).access_token;
    if (login.desfazer) desfazendo = { token: recebidoToken, codigo: login.desfazer };
    else if (login.anexar) anexando = { token: recebidoToken, codigo: login.anexar };
    else token = recebidoToken;
    history.replaceState(null, "", login.destino);
  }

  function aviso(texto) {
    const paragrafo = document.createElement("p");
    paragrafo.className = "aviso";
    paragrafo.setAttribute("role", "alert");
    paragrafo.textContent = texto;
    conteudo.replaceChildren(paragrafo);
  }

  function elemento(nome, classe, texto) {
    const criado = document.createElement(nome);
    if (classe) criado.className = classe;
    if (texto) criado.textContent = texto;
    return criado;
  }

  function linkInterno(texto, url) {
    const link = elemento("a", null, texto);
    link.href = url;
    link.addEventListener("click", (evento) => aoAbrir(evento, url));
    return link;
  }

  function telaDesfazer(montada) {
    const titulo = montada.querySelector("h1");
    document.title = titulo.textContent + " · Alicerce";
    titulo.focus();
  }

  // Bloqueios e recusas: a mensagem do domínio, as operações dependentes e a volta para a operação.
  function recusaDoDesfazer(erro, codigo) {
    aviso(erro.mensagem || "Não foi possível desfazer esta operação.");
    const candidatos = erro.candidatos || [];
    if (candidatos.length > 0) {
      const lista = elemento("ul", "lista");
      lista.setAttribute("aria-label", "Operações que dependem desta");
      for (const candidato of candidatos) {
        const item = elemento("li");
        item.append(candidato.url ? linkInterno(candidato.nome, candidato.url) : candidato.nome);
        lista.append(item);
      }
      conteudo.append(lista);
    }
    const volta = elemento("p");
    volta.append(linkInterno("Voltar para a operação", location.origin + "/fin/o/" + encodeURIComponent(codigo)));
    conteudo.append(volta);
  }

  function pedirDesfazer(rota, corpo) {
    return fetch("/api/fin/operacoes/" + encodeURIComponent(desfazendo.codigo) + "/desfazer" + rota, {
      method: "POST",
      headers: { Authorization: "Bearer " + desfazendo.token, "Content-Type": "application/json" },
      body: JSON.stringify(corpo),
    });
  }

  // O fim do fluxo: o token sai da memória antes de qualquer outra coisa.
  async function encerrarDesfazer(resposta) {
    const codigo = desfazendo.codigo;
    desfazendo = null;
    if (resposta.ok) return (await resposta.json()).vista;
    const erro = resposta.status === 401 ? { mensagem: "O tempo para confirmar acabou. Nada mudou: peça a prévia de novo." }
      : await resposta.json().catch(() => ({}));
    recusaDoDesfazer(erro, codigo);
    return null;
  }

  // Volta do login de desfazer. Só mostra a prévia, que não grava nada; aplicar exige o clique na confirmação.
  async function mostrarPrevia() {
    let resposta;
    try {
      resposta = await pedirDesfazer("/previa", {});
    } catch (falha) {
      desfazendo = null;
      return aviso("Sem conexão com o Alicerce. Nada mudou.");
    }
    if (!resposta.ok) return encerrarDesfazer(resposta);
    const previa = await resposta.json();
    const montada = AlicerceVista.renderizar(conteudo, previa.vista, { aoAbrir });
    const chave = crypto.randomUUID(); // a mesma em toda nova tentativa desta confirmação
    const acoes = elemento("section", "cartao");
    acoes.setAttribute("aria-label", "Confirmar o desfazer");
    const confirmar = elemento("button", "perigo", "Confirmar desfazer");
    confirmar.type = "button";
    const cancelar = elemento("button", null, "Cancelar");
    cancelar.type = "button";
    const botoes = elemento("div", "acoes");
    botoes.append(confirmar, cancelar);
    const situacao = elemento("p", "aviso");
    situacao.setAttribute("role", "alert");
    situacao.hidden = true;
    acoes.append(botoes, situacao);
    montada.append(acoes);
    telaDesfazer(montada);

    cancelar.addEventListener("click", () => navegar(location.pathname));
    confirmar.addEventListener("click", async () => {
      confirmar.disabled = true;
      cancelar.disabled = true;
      try {
        const aplicada = await pedirDesfazer("", { confirmacao: previa.confirmacao, chave_idempotencia: chave });
        const vista = await encerrarDesfazer(aplicada);
        if (vista) telaDesfazer(AlicerceVista.renderizar(conteudo, vista, { copiar, aoAbrir }));
      } catch (falha) {
        situacao.textContent = "Sem conexão com o Alicerce. Tente de novo: a confirmação não aplica duas vezes.";
        situacao.hidden = false;
        confirmar.disabled = false;
        cancelar.disabled = false;
      }
    });
  }

  function acaoDesfazer(montada, vista) {
    const rota = /^\/fin\/o\/([^/]+)$/.exec(location.pathname);
    if (!rota || !vista.dados.operacao || vista.dados.operacao.desfazivel !== true) return;
    const secao = elemento("section", "cartao");
    secao.setAttribute("aria-label", "Desfazer esta operação");
    secao.append(elemento("h2", null, "Desfazer"), elemento("p", null, "Primeiro vem a prévia, e nada muda sem a sua confirmação."));
    const botao = elemento("button", null, "Ver prévia do desfazer");
    botao.type = "button";
    botao.addEventListener("click", () => iniciarLogin({ desfazer: rota[1] }));
    secao.append(botao);
    montada.append(secao);
  }

  // Só na tela padrão de um lançamento vigente: nem anulado nem estorno.
  function acaoAnexar(montada, vista) {
    const rota = /^\/fin\/t\/([^/]+)$/.exec(location.pathname);
    if (!rota || !vista.dados.lancamento || vista.dados.lancamento.anexavel !== true) return;
    const secao = elemento("section", "cartao");
    secao.setAttribute("aria-label", "Enviar comprovante ou foto");
    secao.append(elemento("h2", null, "Comprovante ou foto"),
      elemento("p", null, "Envie a nota, o recibo ou a foto do que foi comprado. A próxima página pergunta o que é a imagem."));
    const botao = elemento("button", null, "Enviar comprovante ou foto");
    botao.type = "button";
    botao.addEventListener("click", () => iniciarLogin({ anexar: rota[1] }));
    secao.append(botao);
    montada.append(secao);
  }

  // Volta do login de envio: pede o link e abre a página de envio. O token sai da memória antes de tudo.
  async function abrirEnvio() {
    const { token: tokenAnexo, codigo } = anexando;
    anexando = null;
    const voltar = () => {
      const volta = elemento("p");
      volta.append(linkInterno("Voltar ao lançamento", location.origin + "/fin/t/" + encodeURIComponent(codigo)));
      conteudo.append(volta);
    };
    let resposta;
    try {
      resposta = await fetch("/api/fin/lancamentos/" + encodeURIComponent(codigo) + "/anexo", {
        method: "POST",
        headers: { Authorization: "Bearer " + tokenAnexo },
      });
    } catch (falha) {
      aviso("Sem conexão com o Alicerce. Nada mudou.");
      return voltar();
    }
    if (!resposta.ok) {
      const erro = await resposta.json().catch(() => ({}));
      aviso(erro.mensagem || "Não foi possível abrir o envio para este lançamento.");
      return voltar();
    }
    location.assign((await resposta.json()).url_anexo);
  }

  async function copiar(valor, botao, texto) {
    try {
      await navigator.clipboard.writeText(valor);
      botao.textContent = "Copiado";
    } catch (negado) {
      botao.textContent = "Não foi possível copiar";
    }
    setTimeout(() => { botao.textContent = texto; }, 2000);
  }

  // Arquivos, e não telas: o navegador os abre direto, como o sw.js os deixa passar.
  const ARQUIVOS = ["/fin/comprovante/", "/fin/anexo/"];

  function aoAbrir(evento, url) {
    const destino = new URL(url);
    if (ARQUIVOS.some((prefixo) => destino.pathname.startsWith(prefixo))) return;
    if (destino.origin === location.origin && !evento.metaKey && !evento.ctrlKey) {
      evento.preventDefault();
      navegar(destino.pathname + destino.search);
    }
  }

  function navegar(caminho) {
    history.pushState(null, "", caminho);
    carregar(true);
  }

  async function carregar(moverFoco) {
    desfazendo = null;
    anexando = null;
    if (!token) return iniciarLogin();
    const consulta = new URLSearchParams(location.search);
    consulta.set("caminho", location.pathname);
    try {
      const resposta = await fetch("/api/fin/vista?" + consulta, { headers: { Authorization: "Bearer " + token } });
      if (resposta.status === 401) {
        token = null;
        return iniciarLogin();
      }
      if (resposta.status === 404) return aviso("Não encontramos este endereço na sua conta.");
      if (!resposta.ok) return aviso("Esta tela não pôde ser aberta.");
      const vista = await resposta.json();
      const montada = AlicerceVista.renderizar(conteudo, vista, { copiar, aoAbrir });
      acaoDesfazer(montada, vista);
      acaoAnexar(montada, vista);
      const titulo = montada.querySelector("h1");
      document.title = titulo.textContent + " · Alicerce";
      if (moverFoco) titulo.focus();
    } catch (falha) {
      aviso("Sem conexão com o Alicerce, ou esta tela não pôde ser desenhada.");
    }
  }

  document.addEventListener("click", (evento) => {
    const link = evento.target.closest && evento.target.closest("nav a");
    if (link) aoAbrir(evento, link.href);
  });

  document.getElementById("sair").addEventListener("click", () => {
    token = null;
    desfazendo = null;
    anexando = null;
    const parametros = new URLSearchParams({ client_id: config.cliente, post_logout_redirect_uri: location.origin + "/" });
    location.assign(config.emissor + "/protocol/openid-connect/logout?" + parametros);
  });

  window.addEventListener("popstate", () => carregar(true));

  (async function iniciar() {
    if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => {});
    try {
      config = await (await fetch("/web/config.json")).json();
      if (location.pathname === "/callback") await concluirLogin();
      if (location.pathname === "/") history.replaceState(null, "", "/fin/contas");
      await (desfazendo ? mostrarPrevia() : anexando ? abrirEnvio() : carregar(false));
    } catch (falha) {
      aviso(falha.message || "A interface não pôde ser aberta.");
    }
  })();
})();
