#!/bin/sh
# Gera o executável do instalador (Linux amd64) com o código versionado do m2m e o quiz embutidos,
# mais o SHA256SUMS, em dist/instalador/. Não exige Go nem Node no host: constrói em containers node:24 e golang.
# Uso: gerar-instalador.sh [--permitir-alteracoes] [--sem-docker]
#   --permitir-alteracoes  gera mesmo com alterações não commitadas (entram as de arquivos já versionados)
#   --sem-docker           usa node, npm e go do PATH (CI, onde o Docker não monta o diretório do projeto)
set -eu
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
GO_IMAGEM="golang:1.27.3-bookworm"
NODE_IMAGEM="node:24"
PERMITIR=0
DOCKER=1
for arg in "$@"; do
    case "$arg" in
        --permitir-alteracoes) PERMITIR=1 ;;
        --sem-docker) DOCKER=0 ;;
        *) echo "uso: $0 [--permitir-alteracoes] [--sem-docker]" >&2; exit 2 ;;
    esac
done

cd "$RAIZ"
REF=HEAD
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    if [ "$PERMITIR" = 0 ]; then
        echo "m2m: há alterações não commitadas e o executável leva só o que está no commit." >&2
        echo "m2m: commite antes ou rode com --permitir-alteracoes." >&2
        exit 1
    fi
    # stash create gera um commit com a árvore de trabalho sem mexer nela
    REF="$(git stash create)"
    echo "m2m: aviso: gerando com alterações não commitadas"
fi
VERSAO="$(git describe --tags --always --dirty)"
CONTEUDO="installer/embed/conteudo"
SAIDA="dist/instalador"

echo "m2m: versão $VERSAO"
find "$CONTEUDO" -mindepth 1 ! -name .gitkeep ! -path "$CONTEUDO/ui" -exec rm -rf {} +
mkdir -p "$CONTEUDO/ui" "$SAIDA"
rm -f "$SAIDA"/m2m-instalador-* "$SAIDA/SHA256SUMS"
git archive --worktree-attributes --format=tar.gz -o "$CONTEUDO/m2m.tar.gz" "$REF"
printf '%s\n' "$VERSAO" > "$CONTEUDO/versao"

echo "m2m: construindo o quiz"
if [ "$DOCKER" = 1 ]; then
    # node_modules num volume: não mexe no node_modules do host. O container roda como root: o build e o cache do
    # Angular voltam para o usuário, senão o clone fica com arquivos que ele não consegue apagar
    docker run --rm -v "$RAIZ/front:/front" -v m2m-instalador-node-modules:/front/node_modules -w /front "$NODE_IMAGEM" \
        sh -c "npm ci --no-audit --no-fund >/dev/null && npx ng build instalador && chown -R $(id -u):$(id -g) dist .angular"
else
    (cd front && npm ci --no-audit --no-fund >/dev/null && npx ng build instalador)
fi
cp -R front/dist/instalador/browser/. "$CONTEUDO/ui/"

echo "m2m: testando e compilando o instalador"
COMPILAR='set -e
go test ./...
GOOS=linux GOARCH=amd64 go build -trimpath -buildvcs=false -ldflags="-s -w" -o "../$SAIDA/m2m-instalador-$VERSAO-linux-amd64" ./cmd/m2m-instalador'
if [ "$DOCKER" = 1 ]; then
    docker run --rm -v "$RAIZ:/src" -w /src/installer -e CGO_ENABLED=0 -e SAIDA="$SAIDA" -e VERSAO="$VERSAO" \
        -v m2m-instalador-go-mod:/go/pkg/mod -v m2m-instalador-go-cache:/root/.cache/go-build "$GO_IMAGEM" \
        sh -c "$COMPILAR; chown -R $(id -u):$(id -g) /src/$SAIDA"
else
    (cd installer && CGO_ENABLED=0 SAIDA="$SAIDA" VERSAO="$VERSAO" sh -c "$COMPILAR")
fi

(cd "$SAIDA" && sha256sum m2m-instalador-* > SHA256SUMS)
echo "m2m: executável em $SAIDA:"
ls -lh "$SAIDA"
