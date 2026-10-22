#!/bin/sh
# Exporta o OpenAPI do back (via teste) e gera os tipos TypeScript do front em front/src/app/api/schema.d.ts.
set -e
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$RAIZ/back" && ./mvnw -B -q test -Dtest=OpenApiIT
cp target/openapi.json "$RAIZ/front/src/app/api/openapi.json"
cd "$RAIZ/front" && npx openapi-typescript src/app/api/openapi.json -o src/app/api/schema.d.ts
echo "Cliente da API gerado em front/src/app/api/schema.d.ts"
