#!/bin/sh
# Configuração de runtime do front: expõe a porta do Grafana (GRAFANA_PORT) em /config/grafana.json
# para o botão da página de auditoria, sem recompilar o front.
set -e
mkdir -p /usr/share/nginx/html/config
PORTA="${GRAFANA_PORT:-3000}"
case "$PORTA" in
    ""|*[!0-9]*) PORTA=3000 ;;
esac
printf '{"port":%s}\n' "$PORTA" > /usr/share/nginx/html/config/grafana.json
