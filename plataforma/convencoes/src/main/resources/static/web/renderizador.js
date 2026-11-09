// Renderizador do catálogo alicerce/v1, o mesmo na PWA e no MCP App. Só cria nós e usa
// textContent: nada da descrição ou dos dados vira HTML. O servidor já validou; aqui a recusa é a
// segunda camada, e nada é posto na página se a vista inteira não puder ser montada.
(function () {
  "use strict";

  const CATALOGO = "alicerce/v1";

  function no(nome, classe, texto) {
    const elemento = document.createElement(nome);
    if (classe) elemento.className = classe;
    if (texto !== undefined && texto !== null) elemento.textContent = String(texto);
    return elemento;
  }

  function dinheiro(valor, moeda) {
    const m = /^(-?)(\d+)\.(\d{2})$/.exec(String(valor));
    if (!m) return String(valor);
    const inteiro = m[2].replace(/\B(?=(\d{3})+$)/g, ".");
    return m[1] + (moeda === "BRL" ? "R$ " : moeda + " ") + inteiro + "," + m[3];
  }

  // Número decimal vindo do servidor como texto ("6.0952380952"): só troca a notação, nunca arredonda.
  function numero(valor) {
    const m = /^(-?)(\d+)(?:\.(\d+))?$/.exec(String(valor));
    if (!m) return String(valor);
    const inteiro = m[2].replace(/\B(?=(\d{3})+$)/g, ".");
    return m[1] + inteiro + (m[3] ? "," + m[3] : "");
  }

  function data(valor) {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(valor));
    return m ? m[3] + "/" + m[2] + "/" + m[1] : String(valor);
  }

  // Data e hora já no fuso do dono, vindas do servidor ("2026-11-05T14:26"): o navegador não converte nada.
  function dataHora(valor) {
    const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(String(valor));
    return m ? m[3] + "/" + m[2] + "/" + m[1] + " " + m[4] + ":" + m[5] : String(valor);
  }

  function urlSegura(valor) {
    try {
      const url = new URL(String(valor));
      return url.protocol === "https:" || url.hostname === "localhost" ? url.href : null;
    } catch (invalida) {
      return null;
    }
  }

  function renderizar(alvo, vista, opcoes) {
    if (!vista || vista.catalogo !== CATALOGO || !Array.isArray(vista.componentes)) {
      throw new Error("Vista fora do catálogo");
    }
    const porId = new Map(vista.componentes.map((c) => [c.id, c]));
    const dados = vista.dados || {};
    const moeda = dados.moeda;

    function resolver(ligacao) {
      let atual = dados;
      for (const parte of ligacao.caminho.split("/").slice(1)) {
        if (atual === null || typeof atual !== "object" || !Object.hasOwn(atual, parte)) {
          throw new Error("Caminho sem dados: " + ligacao.caminho);
        }
        atual = atual[parte];
      }
      return atual;
    }

    const rotulo = (valor) => (typeof valor === "string" ? valor : resolver(valor));

    // Tela com contas em moedas diferentes: a moeda vem de quem traz o valor (moeda_<campo>; em valor_<x>,
    // moeda_<x>, como valor_original e moeda_original; depois moeda), e a da tela só vale sem nenhuma delas.
    // Assim também uma vista composta nunca põe R$ em dólar.
    function moedaDe(origem, campo) {
      if (origem !== null && typeof origem === "object") {
        const par = campo.startsWith("valor_") ? origem["moeda_" + campo.slice("valor_".length)] : undefined;
        return origem["moeda_" + campo] ?? par ?? origem.moeda ?? moeda;
      }
      return moeda;
    }

    function pai(caminho) {
      const partes = caminho.split("/");
      const campo = partes.pop();
      return { origem: partes.length > 1 ? resolver({ caminho: partes.join("/") }) : dados, campo };
    }

    function formatar(valor, formato, origem, campo) {
      if (valor === undefined || valor === null) return "";
      if (formato === "dinheiro") return dinheiro(valor, moedaDe(origem, campo));
      if (formato === "data") return data(valor);
      if (formato === "data_hora") return dataHora(valor);
      if (formato === "numero") return numero(valor);
      return String(valor);
    }

    function classeDaColuna(coluna) {
      if (coluna.formato === "dinheiro" || coluna.formato === "numero") return "numero";
      return coluna.formato === "data" || coluna.formato === "data_hora" ? "data" : null;
    }

    // Texto de célula: palavra com hífen (2026-10, PARC-0042) não se parte no hífen.
    function textoInteiro(alvo, texto) {
      const partes = String(texto).split(/(\S*\w-\w\S*)/);
      partes.forEach((parte, i) => {
        if (!parte) return;
        if (i % 2 === 1) alvo.append(no("span", "inteira", parte));
        else alvo.append(document.createTextNode(parte));
      });
      return alvo;
    }

    function ancora(texto, endereco) {
      const url = urlSegura(endereco);
      if (!url) return no("span", null, texto);
      const a = no("a", null, texto);
      a.href = url;
      if (opcoes.aoAbrir) {
        a.addEventListener("click", (evento) => opcoes.aoAbrir(evento, url));
      }
      return a;
    }

    function filhos(pai, ids) {
      for (const id of ids) pai.append(montar(id));
    }

    const construtores = {
      pagina(c) {
        const pagina = no("article", "vista");
        const titulo = no("h1", null, rotulo(c.titulo));
        titulo.tabIndex = -1;
        pagina.append(titulo);
        filhos(pagina, c.filhos);
        return pagina;
      },
      cartao(c) {
        const cartao = no("section", "cartao");
        cartao.append(no("h2", null, rotulo(c.titulo)));
        filhos(cartao, c.filhos);
        return cartao;
      },
      texto(c) {
        return no("p", c.estilo === "aviso" ? "aviso" : null, rotulo(c.valor));
      },
      totais(c) {
        const lista = no("dl", "totais");
        lista.setAttribute("aria-label", c.rotulo);
        for (const item of c.itens) {
          const grupo = no("div");
          const { origem, campo } = pai(item.valor.caminho);
          grupo.append(no("dt", null, item.rotulo), no("dd", null, formatar(resolver(item.valor), item.formato, origem, campo)));
          lista.append(grupo);
        }
        return lista;
      },
      lista(c) {
        const linhas = resolver(c.origem);
        if (linhas.length === 0) return no("p", null, c.rotulo + ": nada por aqui.");
        // O título fica visível, como a legenda das tabelas: só com aria-label, o item solto não dizia o que era.
        const bloco = no("div", "bloco-lista");
        bloco.append(no("p", "legenda-lista", c.rotulo));
        const lista = no("ul", "lista");
        lista.setAttribute("aria-label", c.rotulo);
        for (const linha of linhas) {
          const item = no("li");
          const titulo = linha[c.campo_titulo];
          item.append(c.campo_url && linha[c.campo_url] ? ancora(titulo, linha[c.campo_url]) : no("span", null, titulo));
          if (c.campo_detalhe) item.append(no("small", null, linha[c.campo_detalhe]));
          lista.append(item);
        }
        bloco.append(lista);
        return bloco;
      },
      tabela(c) {
        const linhas = resolver(c.origem);
        if (linhas.length === 0) return no("p", null, c.legenda + ": nada por aqui.");
        const rolagem = no("div", "rolagem");
        rolagem.tabIndex = 0;
        rolagem.setAttribute("role", "region");
        rolagem.setAttribute("aria-label", c.legenda);
        // Em tela estreita o CSS mostra cada linha como cartão; os papéis explícitos mantêm a tabela para o leitor de tela.
        const tabela = no("table");
        tabela.setAttribute("role", "table");
        tabela.append(no("caption", null, c.legenda));
        const cabecalho = no("tr");
        cabecalho.setAttribute("role", "row");
        for (const coluna of c.colunas) {
          const th = no("th", classeDaColuna(coluna), coluna.titulo);
          th.scope = "col";
          th.setAttribute("role", "columnheader");
          cabecalho.append(th);
        }
        const cabeca = no("thead");
        cabeca.setAttribute("role", "rowgroup");
        cabeca.append(cabecalho);
        const corpo = no("tbody");
        corpo.setAttribute("role", "rowgroup");
        for (const linha of linhas) {
          const tr = no("tr");
          tr.setAttribute("role", "row");
          if (c.campo_destaque && linha[c.campo_destaque] === true) tr.classList.add("destaque");
          for (const coluna of c.colunas) {
            const td = no("td", classeDaColuna(coluna));
            td.setAttribute("role", "cell");
            // Título da coluna no cartão de tela estreita; o leitor de tela já o tem pelo cabeçalho.
            const titulo = no("span", "rotulo", coluna.titulo);
            titulo.setAttribute("aria-hidden", "true");
            td.append(titulo);
            const texto = formatar(linha[coluna.campo], coluna.formato, linha, coluna.campo);
            if (coluna.campo_url && linha[coluna.campo_url]) {
              const a = ancora("", linha[coluna.campo_url]);
              td.append(textoInteiro(a, texto));
            } else {
              const valor = no("span");
              td.append(textoInteiro(valor, texto));
            }
            if (!texto) td.classList.add("vazia");
            tr.append(td);
          }
          corpo.append(tr);
        }
        tabela.append(cabeca, corpo);
        rolagem.append(tabela);
        return rolagem;
      },
      grafico(c) {
        const linhas = resolver(c.origem);
        if (linhas.length === 0) return no("p", null, c.rotulo + ": nada por aqui.");
        // A barra é só desenho: rótulo e valor ficam em texto, e o leitor de tela lê a lista.
        const valores = linhas.map((l) => Math.abs(Number(l[c.campo_valor])) || 0);
        const maior = Math.max(...valores, 0);
        const figura = no("figure", "grafico");
        figura.append(no("figcaption", null, c.rotulo));
        const lista = no("ul");
        linhas.forEach((linha, i) => {
          const item = no("li");
          const barra = no("span", "barra");
          barra.setAttribute("aria-hidden", "true");
          const preenchida = no("span");
          preenchida.style.width = (maior === 0 ? 0 : (valores[i] / maior) * 100) + "%";
          barra.append(preenchida);
          item.append(no("span", "nome", linha[c.campo_rotulo]), barra, no("span", "valor", formatar(linha[c.campo_valor], c.formato, linha, c.campo_valor)));
          lista.append(item);
        });
        figura.append(lista);
        return figura;
      },
      link_curto(c) {
        const id = resolver(c.referencia);
        const url = resolver(c.url);
        const bloco = no("p", "link-curto");
        bloco.append(no("span", null, c.rotulo + ": "), ancora(id, url));
        if (opcoes.copiar) {
          bloco.append(botaoCopiar("Copiar id", id), botaoCopiar("Copiar link", url));
        }
        return bloco;
      },
      link(c) {
        const bloco = no("p");
        bloco.append(ancora(c.rotulo, resolver(c.url)));
        return bloco;
      },
    };

    function botaoCopiar(texto, valor) {
      const botao = no("button", null, texto);
      botao.type = "button";
      botao.addEventListener("click", () => opcoes.copiar(valor, botao, texto));
      return botao;
    }

    function montar(id) {
      const componente = porId.get(id);
      if (!componente || !Object.hasOwn(construtores, componente.componente)) {
        throw new Error("Componente fora do catálogo");
      }
      const elemento = construtores[componente.componente](componente);
      elemento.dataset.componente = componente.componente;
      return elemento;
    }

    const montada = montar(vista.raiz);
    alvo.replaceChildren(montada);
    return montada;
  }

  globalThis.AlicerceVista = { renderizar };
})();
