#!/usr/bin/env bash
# Publica fin. e app.alicerce.tools: Cloudflare (DNS em proxy, certificado de origem, exceção no WAF)
# → Nginx Proxy Manager (TLS de origem) → Caddy interno (alicerce-caddy:80). Idempotente: o que já
# existe é mantido.
# Uso: ./publicar.sh [arquivo-de-segredos]   (padrão: ~/.config/alicerce/secrets.env)
#   Variáveis lidas: CLOUDFLARE_API_TOKEN, NPM_URL, NPM_TOKEN, IP_ORIGEM
#   Token da Cloudflare: Zone DNS Edit, Zone WAF Edit, SSL and Certificates Edit.
#   Sem SSL and Certificates Edit: gere o certificado de origem no painel e informe
#   CERTIFICADO_ORIGEM e CHAVE_ORIGEM (caminhos dos PEM), apagando-os depois.
set -euo pipefail
SEGREDOS="${1:-$HOME/.config/alicerce/secrets.env}"
set -a; . "$SEGREDOS"; set +a

ZONA=alicerce.tools
IP_ORIGEM="${IP_ORIGEM:?defina IP_ORIGEM (IP público do servidor) no arquivo de segredos}"
HOSTS=(fin.alicerce.tools app.alicerce.tools)
DESTINO=alicerce-caddy
NOME_CERTIFICADO="alicerce.tools (origem Cloudflare)"
DESCRICAO_WAF="alicerce: MCP e metadados OAuth sem desafios"
# O NPM confia nas faixas da Cloudflare, mas lê o IP real de X-Real-IP, que a Cloudflare repassa do
# cliente sem tocar. CF-Connecting-IP é sempre reescrito por ela.
CONFIG_AVANCADA=$'real_ip_header CF-Connecting-IP;\nproxy_buffering off;'

CF=https://api.cloudflare.com/client/v4
cf() { curl -sS -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" -H 'Content-Type: application/json' "$@"; }
npm() { curl -sS -H "Authorization: Bearer $NPM_TOKEN" "$@"; }
exigir_sucesso() { jq -e '.success' >/dev/null <<<"$1" || { echo "Falhou: $(jq -c '.errors' <<<"$1")" >&2; exit 1; }; }

ID_ZONA=$(cf "$CF/zones?name=$ZONA" | jq -r '.result[0].id')

echo "== DNS"
for host in "${HOSTS[@]}"; do
  if [[ $(cf "$CF/zones/$ID_ZONA/dns_records?name=$host" | jq '.result | length') -gt 0 ]]; then
    echo "$host: já existe"; continue
  fi
  resposta=$(cf -X POST "$CF/zones/$ID_ZONA/dns_records" -d "$(jq -n --arg n "$host" --arg ip "$IP_ORIGEM" \
    '{type: "A", name: $n, content: $ip, proxied: true, ttl: 1, comment: "alicerce"}')")
  exigir_sucesso "$resposta"; echo "$host: criado (proxy ligado)"
done

echo "== Certificado de origem"
ID_CERTIFICADO=$(npm "$NPM_URL/api/nginx/certificates" | jq -r --arg n "$NOME_CERTIFICADO" '[.[] | select(.nice_name == $n)][0].id // empty')
if [[ -n $ID_CERTIFICADO ]]; then
  echo "já está no NPM (id $ID_CERTIFICADO)"
else
  TEMP=$(mktemp -d); trap 'rm -rf "$TEMP"' EXIT; chmod 700 "$TEMP"
  if [[ -n ${CERTIFICADO_ORIGEM:-} ]]; then
    # Certificado gerado no painel (SSL/TLS → Servidor de origem), em PEM.
    cp "$CERTIFICADO_ORIGEM" "$TEMP/certificado.pem"; cp "${CHAVE_ORIGEM:?informe CHAVE_ORIGEM}" "$TEMP/chave.pem"
  else
    openssl req -new -newkey rsa:2048 -nodes -keyout "$TEMP/chave.pem" -subj "/CN=$ZONA" -out "$TEMP/pedido.csr" 2>/dev/null
    resposta=$(cf -X POST "$CF/certificates" -d "$(jq -n --rawfile csr "$TEMP/pedido.csr" --args '{
      hostnames: $ARGS.positional, requested_validity: 5475, request_type: "origin-rsa", csr: $csr}' "${HOSTS[@]}")")
    exigir_sucesso "$resposta"
    jq -r '.result.certificate' <<<"$resposta" >"$TEMP/certificado.pem"
  fi
  ID_CERTIFICADO=$(npm -X POST -H 'Content-Type: application/json' "$NPM_URL/api/nginx/certificates" \
    -d "$(jq -n --arg n "$NOME_CERTIFICADO" '{provider: "other", nice_name: $n}')" | jq -r '.id')
  npm -X POST "$NPM_URL/api/nginx/certificates/$ID_CERTIFICADO/upload" \
    -F "certificate=@$TEMP/certificado.pem" -F "certificate_key=@$TEMP/chave.pem" >/dev/null
  echo "emitido na Cloudflare e enviado ao NPM (id $ID_CERTIFICADO); a chave só existe no NPM"
fi

echo "== Proxy hosts no NPM"
EXISTENTES=$(npm "$NPM_URL/api/nginx/proxy-hosts")
for host in "${HOSTS[@]}"; do
  if jq -e --arg h "$host" 'any(.[]; .domain_names | index($h))' >/dev/null <<<"$EXISTENTES"; then
    echo "$host: já existe"; continue
  fi
  resposta=$(npm -X POST -H 'Content-Type: application/json' "$NPM_URL/api/nginx/proxy-hosts" -d "$(jq -n \
    --arg h "$host" --arg d "$DESTINO" --argjson c "$ID_CERTIFICADO" --arg a "$CONFIG_AVANCADA" '{
      domain_names: [$h], forward_scheme: "http", forward_host: $d, forward_port: 80,
      certificate_id: $c, ssl_forced: true, http2_support: true, hsts_enabled: false, hsts_subdomains: false,
      block_exploits: true, allow_websocket_upgrade: false, caching_enabled: false, access_list_id: 0,
      advanced_config: $a, meta: {}, locations: []}')")
  jq -e '.id' >/dev/null <<<"$resposta" || { echo "Falhou: $resposta" >&2; exit 1; }
  echo "$host: criado → http://$DESTINO:80"
done

echo "== Exceção no WAF"
EXPRESSAO='(http.host eq "fin.alicerce.tools" and (http.request.uri.path eq "/mcp" or starts_with(http.request.uri.path, "/.well-known/")))'
REGRA=$(jq -n --arg e "$EXPRESSAO" --arg d "$DESCRICAO_WAF" '{
  action: "skip", expression: $e, description: $d, enabled: true, logging: {enabled: true},
  action_parameters: {ruleset: "current", phases: ["http_ratelimit", "http_request_firewall_managed"],
    products: ["bic", "hot", "securityLevel", "uaBlock", "zoneLockdown", "waf", "rateLimit"]}}')
ENTRADA=$(cf "$CF/zones/$ID_ZONA/rulesets/phases/http_request_firewall_custom/entrypoint")
if ! jq -e '.success' >/dev/null <<<"$ENTRADA"; then
  resposta=$(cf -X PUT "$CF/zones/$ID_ZONA/rulesets/phases/http_request_firewall_custom/entrypoint" \
    -d "$(jq -n --argjson r "$REGRA" '{rules: [$r]}')")
  exigir_sucesso "$resposta"; echo "regra criada"
elif jq -e --arg d "$DESCRICAO_WAF" 'any(.result.rules[]?; .description == $d)' >/dev/null <<<"$ENTRADA"; then
  echo "regra já existe"
else
  resposta=$(cf -X POST "$CF/zones/$ID_ZONA/rulesets/$(jq -r '.result.id' <<<"$ENTRADA")/rules" -d "$REGRA")
  exigir_sucesso "$resposta"; echo "regra acrescentada ao conjunto existente"
fi
