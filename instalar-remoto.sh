#!/bin/sh
# Instala (ou desinstala) o m2m numa máquina acessada por SSH com um comando só, rodado no SEU computador (Linux ou
# macOS), onde está o navegador:
#
#   sh instalar-remoto.sh usuario@maquina [opções do ssh, como -p 2222 ou -i ~/.ssh/chave]
#
# Numa única conexão SSH (a senha do SSH é pedida uma vez), o script:
#   1. baixa e confere, na máquina de destino, o instalar.sh desta mesma versão e o roda com o túnel do quiz já aberto
#      (a preparação de lá pede a senha do sudo no terminal, como sempre);
#   2. abre o quiz no seu navegador assim que ele responde;
#   3. quando o instalador é encerrado, importa a CA local do m2m nos navegadores deste computador (Chrome/Chromium e
#      Firefox no Linux, sem sudo; Acesso às Chaves no macOS) e abre o m2m; depois de uma desinstalação, tira a CA.
set -eu

# a release grava aqui a versão da tag (ex.: v1.1.0); no repositório fica vazia
VERSAO=""
REPO="https://github.com/Viniciusalopes/m2m"
README="$REPO#instalação-limpa-ubuntu-e-derivados"
# porta do quiz na máquina de destino; a deste computador é a primeira livre a partir dela
PORTA=8765

diga() { printf 'm2m: %s\n' "$*"; }
falha() {
    printf 'm2m: %s\n' "$1" >&2
    printf 'm2m: o passo a passo está em %s\n' "$README" >&2
    exit 1
}

[ $# -ge 1 ] || falha "uso: sh instalar-remoto.sh usuario@maquina [opções do ssh]"
DESTINO="$1"
shift
MAQUINA="${DESTINO#*@}"
APELIDO="m2m $MAQUINA"
[ -n "$VERSAO" ] || falha "este instalar-remoto.sh não é de uma release: baixe-o de $REPO/releases/latest."
command -v ssh >/dev/null 2>&1 || falha "o ssh não está instalado neste computador."
SISTEMA="$(uname -s)"

# token do quiz: gerado aqui para abrir o navegador deste computador no endereço certo
if command -v openssl >/dev/null 2>&1; then
    TOKEN="$(openssl rand -hex 32)"
else
    TOKEN="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
fi

CONTROLE="$(mktemp -d "${TMPDIR:-/tmp}/m2m-remoto.XXXXXX")"
MESTRE="$CONTROLE/ssh"
encerrar_conexao() {
    ssh -S "$MESTRE" -O exit "$DESTINO" >/dev/null 2>&1 || true
    rm -rf "$CONTROLE"
}
trap encerrar_conexao EXIT
trap 'exit 130' INT TERM

# roda um comando na máquina de destino pela conexão já aberta (sem pedir a senha de novo; as opções do ssh, como
# -p e -i, só contam na primeira conexão, que fica como mestre)
remoto() { ssh -S "$MESTRE" "$DESTINO" "$@" 2>/dev/null; }

# ---- 1. conexão única, com o túnel do quiz ----

porta_ocupada() {
    if command -v ss >/dev/null 2>&1; then
        ss -Hltn "sport = :$1" 2>/dev/null | grep -q .
    else
        lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
    fi
}
LOCAL=$PORTA
while porta_ocupada "$LOCAL"; do
    LOCAL=$((LOCAL + 1))
    [ "$LOCAL" -lt $((PORTA + 20)) ] || falha "não há porta livre entre $PORTA e $((PORTA + 19)) neste computador para o túnel do quiz."
done

diga "conectando em $DESTINO (a senha do SSH, se houver, é pedida só agora)"
# -q: sem as mensagens "channel N: open failed" de abas antigas do quiz batendo no túnel antes de ele existir
if ! ssh -q -f -N -M -S "$MESTRE" -o ControlPersist=yes -o ExitOnForwardFailure=yes \
    -L "$LOCAL:127.0.0.1:$PORTA" "$@" "$DESTINO"; then
    falha "não foi possível conectar em $DESTINO (endereço, usuário, senha ou chave) ou abrir o túnel na porta $LOCAL deste computador."
fi

# ---- 2. instalar.sh desta versão na máquina de destino, conferido; o token vai num arquivo 0600 ----

diga "preparando o instalar.sh $VERSAO em $MAQUINA:~/m2m-instalador"
# o script vai pela entrada padrão do sh remoto: nem o token nem os comandos aparecem na lista de processos de lá
if ! remoto sh -s <<EOF
set -eu
mkdir -p ~/m2m-instalador
cd ~/m2m-instalador
base="$REPO/releases/download/$VERSAO"
if ! grep -qx 'VERSAO="$VERSAO"' instalar.sh 2>/dev/null; then
    curl -fsSL -o instalar.sh "\$base/instalar.sh"
    curl -fsSL -o SHA256SUMS "\$base/SHA256SUMS"
fi
grep ' instalar.sh\$' SHA256SUMS | sha256sum -c --status
umask 077
printf '%s' '$TOKEN' > .token
EOF
then
    falha "não foi possível baixar e conferir o instalar.sh $VERSAO em $MAQUINA (internet ou checksum)."
fi

# ---- 3. abrir o quiz neste computador assim que ele responder lá ----

abrir() {
    case "$SISTEMA" in
        Darwin) open "$1" >/dev/null 2>&1 ;;
        *) command -v xdg-open >/dev/null 2>&1 && xdg-open "$1" >/dev/null 2>&1 ;;
    esac
}
URL_QUIZ="http://127.0.0.1:$LOCAL/?t=$TOKEN"
touch "$CONTROLE/ativo"
(
    # espera a porta abrir lá (sem bater no túnel antes da hora) e abre o navegador uma vez
    while [ -f "$CONTROLE/ativo" ]; do
        if remoto "ss -Hltn 'sport = :$PORTA' | grep -q ." ; then
            sleep 1
            abrir "$URL_QUIZ" || diga "abra no navegador: $URL_QUIZ"
            break
        fi
        sleep 2
    done
) &
ABRIDOR=$!

# ---- 4. o instalar.sh, interativo, nesta mesma conexão ----

registro_antes="$(remoto 'test -f ~/.config/m2m/instalacao.json && echo sim || true')"
# -q: sem o "Shared connection to ... closed" do fim; o código de saída do instalar.sh volta pelo ssh
saida=0
ssh -q -S "$MESTRE" -t "$DESTINO" \
    "cd ~/m2m-instalador && M2M_TUNEL=1 M2M_CA_AUTOMATICA=1 M2M_TOKEN_ARQUIVO=.token sh instalar.sh --porta $PORTA" || saida=$?
rm -f "$CONTROLE/ativo"
kill "$ABRIDOR" 2>/dev/null || true
remoto 'rm -f ~/m2m-instalador/.token' || true

# ---- 5. a CA local do m2m nos navegadores deste computador ----

certutil_disponivel() {
    command -v certutil >/dev/null 2>&1 && return 0
    if command -v apt-get >/dev/null 2>&1; then
        diga "para os navegadores deste computador confiarem no m2m, falta o certutil (pacote libnss3-tools)."
        diga "instalando com sudo (a senha é a deste computador):"
        printf '  $ sudo apt-get install -y libnss3-tools\n'
        sudo apt-get install -y libnss3-tools >/dev/null && return 0
    fi
    return 1
}

# bancos NSS do usuário: Chrome/Chromium (inclusive o Snap) e cada perfil do Firefox (inclusive o Snap)
bancos_nss() {
    printf '%s\n' "$HOME/.pki/nssdb"
    [ -d "$HOME/snap/chromium/current/.pki/nssdb" ] && printf '%s\n' "$HOME/snap/chromium/current/.pki/nssdb"
    for perfil in "$HOME"/.mozilla/firefox/*/ "$HOME"/snap/firefox/common/.mozilla/firefox/*/; do
        [ -f "$perfil/cert9.db" ] && printf '%s\n' "${perfil%/}"
    done
    return 0
}

importar_linux() {
    certutil_disponivel || { diga "sem o certutil, a CA não foi importada: use o botão \"Baixar a CA\" na tela final do quiz."; return 1; }
    bancos_nss | while read -r banco; do
        if [ ! -f "$banco/cert9.db" ]; then
            mkdir -p "$banco"
            certutil -d "sql:$banco" -N --empty-password
        fi
        certutil -d "sql:$banco" -D -n "$APELIDO" >/dev/null 2>&1 || true
        certutil -d "sql:$banco" -A -t "C,," -n "$APELIDO" -i "$CONTROLE/ca.crt"
        diga "CA importada em $banco"
    done
}

remover_linux() {
    command -v certutil >/dev/null 2>&1 || return 0
    bancos_nss | while read -r banco; do
        [ -f "$banco/cert9.db" ] || continue
        if certutil -d "sql:$banco" -D -n "$APELIDO" >/dev/null 2>&1; then
            diga "CA do m2m em $MAQUINA retirada de $banco"
        fi
    done
}

# no macOS o certificado é achado pela impressão SHA-1, guardada ao importar (todas as CAs do m2m têm o mesmo nome)
ESTADO="$HOME/.config/m2m-remoto"
importar_macos() {
    mkdir -p "$ESTADO"
    diga "o macOS vai pedir a sua senha para confiar na CA do m2m:"
    security add-trusted-cert -r trustRoot -k "$HOME/Library/Keychains/login.keychain-db" "$CONTROLE/ca.crt"
    openssl x509 -in "$CONTROLE/ca.crt" -noout -fingerprint -sha1 | cut -d= -f2 | tr -d : >"$ESTADO/$MAQUINA.sha1"
    diga "CA importada no Acesso às Chaves"
}

remover_macos() {
    [ -f "$ESTADO/$MAQUINA.sha1" ] || return 0
    security delete-certificate -Z "$(cat "$ESTADO/$MAQUINA.sha1")" "$HOME/Library/Keychains/login.keychain-db" >/dev/null 2>&1 || true
    rm -f "$ESTADO/$MAQUINA.sha1"
    diga "CA do m2m em $MAQUINA retirada do Acesso às Chaves"
}

registro_depois="$(remoto 'test -f ~/.config/m2m/instalacao.json && echo sim || true')"
# o instalar.sh parou antes de instalar (requisitos mínimos, sistema, sudo recusado): nada mudou lá nem aqui
if [ "$saida" != 0 ] && [ "$registro_antes" != sim ] && [ "$registro_depois" != sim ]; then
    printf 'm2m: a instalação não foi feita em %s; confira as mensagens acima.\n' "$MAQUINA" >&2
    exit 1
fi
if [ "$registro_depois" = sim ]; then
    remoto 'cat ~/.config/m2m/tls/ca.crt 2>/dev/null' >"$CONTROLE/ca.crt" || true
    if [ -s "$CONTROLE/ca.crt" ]; then
        case "$SISTEMA" in
            Linux) importar_linux || true ;;
            Darwin) importar_macos || true ;;
            *) diga "neste sistema a CA não é importada automaticamente: use o botão \"Baixar a CA\" na tela final do quiz." ;;
        esac
    fi
    # shellcheck disable=SC2016 # o $ expande lá, na máquina de destino
    url="$(remoto 'd="$(sed -n "s/.*\"diretorio\": *\"\([^\"]*\)\".*/\1/p" ~/.config/m2m/instalacao.json | head -n 1)"; sed -n "s/^PUBLIC_URL=//p" "$d/.env" | head -n 1')"
    if [ -n "$url" ]; then
        diga "o m2m está em $url (se o navegador já estava aberto e ainda avisar do certificado, feche-o e abra de novo)"
        abrir "$url" || true
    fi
else
    # sem m2m instalado lá (desinstalado agora ou nunca concluído): a CA daquela máquina não serve mais aqui
    case "$SISTEMA" in
        Linux) remover_linux ;;
        Darwin) remover_macos ;;
    esac
fi
if [ "$saida" != 0 ]; then
    printf 'm2m: o instalar.sh terminou com erro em %s; confira as mensagens acima.\n' "$MAQUINA" >&2
    exit 1
fi
diga "pronto."
