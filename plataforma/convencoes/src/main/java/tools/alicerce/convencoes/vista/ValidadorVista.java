package tools.alicerce.convencoes.vista;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.vista.CatalogoVista.Propriedade;

/**
 * Recusa toda descrição que saia do catálogo, antes de ela chegar a um renderizador. A garantia de
 * origem dos números está na forma: número só entra por {@code {"caminho": ...}}, o caminho precisa
 * existir nos dados, e os dados só o servidor preenche, a partir das capacidades. Texto fixo não
 * aceita dígito nenhum. Número escrito por extenso não é detectável aqui: é o limite conhecido da regra.
 */
public final class ValidadorVista {

    static final int MAX_COMPONENTES = 200;
    static final int MAX_TEXTO = 120;

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,40}");
    private static final Pattern CAMINHO = Pattern.compile("(/[a-z0-9_]{1,60}){1,8}");
    private static final Pattern CAMPO = Pattern.compile("[a-z][a-z0-9_]{0,60}");
    private static final Pattern DIGITO = Pattern.compile("\\p{Nd}");
    private static final Pattern MARCACAO = Pattern.compile("[<>&{}\\\\`]");
    private static final Set<String> CHAVES_COMPONENTE = Set.of("id", "componente");

    private final Map<String, ?> dados;
    private final Map<String, Map<?, ?>> porId = new HashMap<>();

    private ValidadorVista(Map<String, ?> dados) {
        this.dados = dados;
    }

    /**
     * @param raiz o id do componente {@code pagina} em que a vista começa
     * @param componentes a lista plana, com ligação entre eles só por {@code filhos}
     * @param dados o modelo de dados, preenchido pelo servidor
     * @throws ErroTipado na primeira regra quebrada
     */
    public static void validar(String raiz, List<?> componentes, Map<String, ?> dados) {
        new ValidadorVista(dados).executar(raiz, componentes);
    }

    private void executar(String raiz, List<?> componentes) {
        if (componentes == null || componentes.isEmpty()) {
            throw recusa("componentes", "A vista não tem componentes.");
        }
        if (componentes.size() > MAX_COMPONENTES) {
            throw recusa("componentes", "A vista passa de " + MAX_COMPONENTES + " componentes.");
        }
        for (Object componente : componentes) {
            if (!(componente instanceof Map<?, ?> mapa)) {
                throw recusa("componentes", "Cada componente é um objeto.");
            }
            if (!(mapa.get("id") instanceof String id) || !ID.matcher(id).matches()) {
                throw recusa("componentes", "Componente sem id válido.");
            }
            if (porId.put(id, mapa) != null) {
                throw recusa("componentes", "Id repetido: " + id + ".");
            }
        }
        for (Map.Entry<String, Map<?, ?>> componente : porId.entrySet()) {
            componente(componente.getKey(), componente.getValue());
        }
        arvore(raiz);
    }

    private void componente(String id, Map<?, ?> mapa) {
        if (!(mapa.get("componente") instanceof String nome) || CatalogoVista.propriedades(nome) == null) {
            throw recusa(id, "Componente fora do catálogo. Use um destes: " + CatalogoVista.nomes() + ".");
        }
        propriedades(id, mapa, CatalogoVista.propriedades(nome), CHAVES_COMPONENTE);
    }

    private void propriedades(String id, Map<?, ?> valores, Map<String, Propriedade> permitidas, Set<String> envelope) {
        for (Object chave : valores.keySet()) {
            if (!permitidas.containsKey(chave) && !envelope.contains(chave)) {
                throw recusa(id, "Propriedade fora do catálogo: " + chave + ".");
            }
        }
        for (Map.Entry<String, Propriedade> permitida : permitidas.entrySet()) {
            String nome = permitida.getKey();
            Object valor = valores.get(nome);
            if (valor == null) {
                if (permitida.getValue().obrigatoria()) {
                    throw recusa(id, "Falta a propriedade " + nome + ".");
                }
                continue;
            }
            valor(id, nome, permitida.getValue(), valor);
        }
    }

    private void valor(String id, String nome, Propriedade propriedade, Object valor) {
        switch (propriedade.tipo()) {
            case TEXTO -> texto(id, nome, valor);
            case VALOR -> {
                if (resolver(id, nome, valor) instanceof Map<?, ?> || resolver(id, nome, valor) instanceof List<?>) {
                    throw recusa(id, nome + " precisa apontar para um valor simples.");
                }
            }
            case ROTULO -> {
                if (valor instanceof String) {
                    texto(id, nome, valor);
                } else {
                    valor(id, nome, new Propriedade(CatalogoVista.Tipo.VALOR, true, Set.of(), Map.of()), valor);
                }
            }
            case LISTA -> {
                if (!(resolver(id, nome, valor) instanceof List<?>)) {
                    throw recusa(id, nome + " precisa apontar para uma lista.");
                }
            }
            case CAMPO -> campo(id, nome, valor);
            case OPCAO -> {
                if (!propriedade.opcoes().contains(valor)) {
                    throw recusa(id, nome + " aceita só " + propriedade.opcoes().stream().sorted().toList() + ".");
                }
            }
            case FILHOS -> filhos(id, nome, valor);
            case ITENS -> {
                if (!(valor instanceof List<?> itens) || itens.isEmpty()) {
                    throw recusa(id, nome + " precisa de pelo menos um item.");
                }
                for (Object item : itens) {
                    if (!(item instanceof Map<?, ?> mapa)) {
                        throw recusa(id, "Cada item de " + nome + " é um objeto.");
                    }
                    propriedades(id, mapa, propriedade.itens(), Set.of());
                }
            }
        }
    }

    private void texto(String id, String nome, Object valor) {
        if (!(valor instanceof String texto) || texto.isBlank()) {
            throw recusa(id, nome + " precisa ser um texto. Número entra por {\"caminho\": ...}.");
        }
        if (texto.length() > MAX_TEXTO) {
            throw recusa(id, nome + " passa de " + MAX_TEXTO + " caracteres.");
        }
        if (DIGITO.matcher(texto).find()) {
            throw recusa(id, nome + " tem número escrito à mão. Todo número vem dos dados, por {\"caminho\": ...}.");
        }
        String minusculo = texto.toLowerCase(Locale.ROOT);
        if (MARCACAO.matcher(texto).find() || minusculo.contains("javascript:") || minusculo.contains("data:")) {
            throw recusa(id, nome + " tem marcação; a vista aceita só texto puro.");
        }
    }

    /** Um campo que falta em alguma linha só deixa a célula vazia, como o dado opcional. */
    private void campo(String id, String nome, Object valor) {
        if (!(valor instanceof String campo) || !CAMPO.matcher(campo).matches()) {
            throw recusa(id, nome + " precisa ser o nome de um campo.");
        }
    }

    private void filhos(String id, String nome, Object valor) {
        if (!(valor instanceof List<?> filhos) || filhos.isEmpty()) {
            throw recusa(id, nome + " precisa de pelo menos um componente.");
        }
        for (Object filho : filhos) {
            if (!porId.containsKey(filho)) {
                throw recusa(id, "O filho " + filho + " não existe.");
            }
        }
    }

    private Object resolver(String id, String nome, Object valor) {
        if (!(valor instanceof Map<?, ?> ligacao) || ligacao.size() != 1 || !(ligacao.get("caminho") instanceof String caminho)) {
            throw recusa(id, nome + " só aceita {\"caminho\": ...}: número nunca vem escrito na descrição.");
        }
        if (!CAMINHO.matcher(caminho).matches()) {
            throw recusa(id, "Caminho inválido em " + nome + ".");
        }
        Object atual = dados;
        for (String parte : caminho.substring(1).split("/")) {
            atual = passo(atual, parte);
            if (atual == null) {
                throw recusa(id, "O caminho " + caminho + " não existe nos dados: número sem origem no domínio.");
            }
        }
        return atual;
    }

    private static Object passo(Object atual, String parte) {
        if (atual instanceof Map<?, ?> mapa) {
            return mapa.get(parte);
        }
        if (atual instanceof List<?> lista && parte.chars().allMatch(Character::isDigit) && parte.length() < 5) {
            int indice = Integer.parseInt(parte);
            return indice < lista.size() ? lista.get(indice) : null;
        }
        return null;
    }

    /** Uma árvore a partir da raiz: sem ciclo, sem componente solto e sem filho de dois pais. */
    private void arvore(String raiz) {
        Map<?, ?> inicio = porId.get(raiz);
        if (inicio == null || !"pagina".equals(inicio.get("componente"))) {
            throw recusa("raiz", "A raiz precisa ser um componente pagina.");
        }
        Set<String> vistos = new HashSet<>(Set.of(raiz));
        Deque<String> pendentes = new ArrayDeque<>(List.of(raiz));
        while (!pendentes.isEmpty()) {
            if (porId.get(pendentes.pop()).get("filhos") instanceof List<?> filhos) {
                for (Object filho : filhos) {
                    String id = (String) filho;
                    if (!vistos.add(id)) {
                        throw recusa(id, "Componente com dois pais ou em ciclo.");
                    }
                    pendentes.push(id);
                }
            }
        }
        if (vistos.size() != porId.size()) {
            throw recusa("componentes", "Há componentes fora da árvore da raiz.");
        }
    }

    private static ErroTipado recusa(String onde, String mensagem) {
        return ErroTipado.validacao("vista", "Vista recusada (" + onde + "): " + mensagem);
    }
}
