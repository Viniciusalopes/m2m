#!/bin/sh
# Constrói e sobe o m2m já com o certificado TLS criado, instalado e configurado (alias m2m-up).
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
# Falha ao preparar o certificado não impede a subida: o front cai no autoassinado
sh "$RAIZ/scripts/gerar-certificado-tls.sh" || echo "m2m: aviso: certificado TLS não preparado; subindo com o certificado de fallback" >&2
exec docker compose -f "$RAIZ/docker-compose.yml" --env-file "$RAIZ/.env" up -d --build "$@"
