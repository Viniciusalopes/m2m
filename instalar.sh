#!/bin/sh
# Instala o m2m a partir deste clone: compila o instalador em containers (sem exigir Go nem Node na máquina) e abre o
# quiz de instalação usando o próprio clone como diretório. Para Ubuntu e derivados, em amd64.
# Uso: ./instalar.sh [opções do instalador, como --porta 8766 ou --sem-navegador]
set -eu
RAIZ="$(cd "$(dirname "$0")" && pwd)"
README="https://github.com/viniciusalopes/m2m#instalação-limpa-ubuntu-e-derivados"
RELEASES="https://github.com/viniciusalopes/m2m/releases/latest"

falha() {
    echo "m2m: $1" >&2
    echo "m2m: veja o passo a passo em $README" >&2
    echo "m2m: ou use o executável pronto, que orienta a instalação do Docker: $RELEASES" >&2
    exit 1
}

case "$(uname -m)" in
    x86_64 | amd64) ;;
    *) falha "o instalador é só para amd64 e esta máquina é $(uname -m)." ;;
esac

[ -d "$RAIZ/.git" ] || falha "$RAIZ não é um clone do repositório (falta o .git). Clone com: git clone https://github.com/viniciusalopes/m2m.git"

if ! command -v docker >/dev/null 2>&1; then
    falha "o Docker não está instalado. Instale o Docker Engine e o Compose v2 antes de rodar este script."
fi
if ! erro="$(docker info 2>&1 >/dev/null)"; then
    case "$erro" in
        *"permission denied"*)
            falha "o usuário $(id -un) não tem permissão de usar o Docker. Rode: sudo usermod -aG docker $(id -un) e abra uma nova sessão (ou use: sg docker -c ./instalar.sh)." ;;
        *)
            falha "o Docker não está respondendo. Rode: sudo systemctl enable --now docker" ;;
    esac
fi

echo "m2m: compilando o instalador a partir deste clone (na primeira vez, baixa as imagens node e golang: alguns minutos)"
sh "$RAIZ/scripts/gerar-instalador.sh" --permitir-alteracoes

# o gerar-instalador.sh apaga os executáveis anteriores: sobra um só
INSTALADOR=""
for f in "$RAIZ"/dist/instalador/m2m-instalador-*-linux-amd64; do
    [ -f "$f" ] && INSTALADOR="$f"
done
[ -n "$INSTALADOR" ] || falha "o instalador não foi gerado; confira as mensagens acima."
exec "$INSTALADOR" --clone "$RAIZ" "$@"
