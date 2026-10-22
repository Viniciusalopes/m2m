#!/bin/sh
# Exporta o OpenAPI do back (via teste) e gera os tipos TypeScript do front em front/src/app/api/schema.d.ts.
set -e
# openapi-typescript (MIT) fica fora do package.json: ele ainda declara typescript ^5 como peer e o front usa o 6
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$RAIZ/back" && ./mvnw -B -q test -Dtest=OpenApiIT
cp target/openapi.json "$RAIZ/front/src/app/api/openapi.json"
cd "$RAIZ/front" && npx --yes openapi-typescript@7.13.0 src/app/api/openapi.json -o src/app/api/schema.d.ts
echo "Cliente da API gerado em front/src/app/api/schema.d.ts"
