#!/bin/sh
# Gera THIRD-PARTY-NOTICES.md (back + front) e falha se houver licença fora da lista de permitidos (D37).
set -e
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$RAIZ/back"
./mvnw -B -q process-resources
cd "$RAIZ/front"
npm run -s license:check >/dev/null
npm run -s license:notices
{
  echo "# Avisos de terceiros"
  echo
  echo "Gerado automaticamente por \`scripts/gerar-third-party-notices.sh\`. O m2m não contém código do FileZilla Client."
  echo
  echo "## Backend (Java)"
  echo
  echo '```'
  cat "$RAIZ/back/target/generated-sources/license/THIRD-PARTY.txt"
  echo '```'
  echo
  echo "## Frontend (Angular)"
  echo
  cat "$RAIZ/front/tmp/THIRD-PARTY-FRONT.md"
} > "$RAIZ/THIRD-PARTY-NOTICES.md"
mkdir -p "$RAIZ/front/src/assets"
cp "$RAIZ/THIRD-PARTY-NOTICES.md" "$RAIZ/front/src/assets/THIRD-PARTY-NOTICES.md"
echo "THIRD-PARTY-NOTICES.md gerado"
