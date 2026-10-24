#!/bin/sh
# Configuração de runtime do front, sem recompilar: a porta do Grafana (GRAFANA_PORT) em /config/grafana.json,
# para o botão da página de auditoria, e a versão instalada (M2M_VERSION) em /config/versao.json, para a tela Sobre.
set -e
mkdir -p /usr/share/nginx/html/config
PORTA="${GRAFANA_PORT:-3000}"
case "$PORTA" in
    ""|*[!0-9]*) PORTA=3000 ;;
esac
printf '{"port":%s}\n' "$PORTA" > /usr/share/nginx/html/config/grafana.json
# só o formato do git describe (ex.: v1.0.0, v1.0.0-1-g8f02829); qualquer outra coisa vira vazio
VERSAO="${M2M_VERSION:-}"
case "$VERSAO" in
    *[!0-9A-Za-z._+-]*) VERSAO="" ;;
esac
printf '{"versao":"%s"}\n' "$VERSAO" > /usr/share/nginx/html/config/versao.json
