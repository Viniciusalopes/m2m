#!/bin/sh
# Instala o m2m numa máquina Ubuntu (ou derivada) amd64: confere os mínimos de memória e disco (abaixo deles, encerra
# sem alterar nada), prepara a máquina (pacotes, Docker, grupo docker), abre o quiz de instalação e, quando o instalador termina, conclui o que exige privilégio (a CA local do m2m no
# sistema). Rode como o seu usuário: o sudo pede a senha no terminal, só para o que precisar.
#
# Funciona de dois jeitos:
#   - dentro de um clone do repositório: compila o instalador a partir do clone e instala usando o próprio clone;
#   - baixado da release (com o SHA256SUMS ao lado): baixa o executável da mesma versão e confere o checksum.
#
# Numa máquina sem tela, acessada por SSH, o quiz abre no navegador do seu computador pelo túnel da própria sessão:
# entre com  ssh -L 8765:127.0.0.1:8765 usuario@maquina  e rode o script nessa sessão (o script confere isso antes).
#
# De outro computador (Linux ou macOS), o instalar-remoto.sh faz tudo isso com um comando só, inclusive abrir o quiz e
# importar a CA nos navegadores dele; ele roda este script com M2M_TUNEL=1, M2M_TOKEN_ARQUIVO e M2M_CA_AUTOMATICA=1.
#
# Uso: ./instalar.sh [--concluir] [opções do instalador, como --porta 8766 ou --sem-navegador]
#   --concluir  só conclui as ações de sistema (CA local), para quem abriu o instalador sem este script
set -eu

# a release grava aqui a versão da tag (ex.: v1.1.0); no repositório fica vazia
VERSAO=""
REPO="https://github.com/Viniciusalopes/m2m"
README="$REPO#instalação-limpa-ubuntu-e-derivados"
MIN_DISCO_GIB=16
# uma máquina de 4 GiB mostra cerca de 3,8 GiB em MemTotal: aceita a partir de 3,5 GiB (o mesmo do instalador)
MIN_MEMORIA_KIB=3670016
CA_SISTEMA="/usr/local/share/ca-certificates/m2m-local-ca.crt"
REGISTRO="$HOME/.config/m2m/instalacao.json"
CA_LOCAL="$HOME/.config/m2m/tls/ca.crt"

RAIZ="$(cd "$(dirname "$0")" && pwd)"
USUARIO="$(id -un)"
# porta do quiz: a do túnel da sessão SSH (8765, ou a passada em --porta)
PORTA=8765

diga() { printf 'm2m: %s\n' "$*"; }
falha() {
    printf 'm2m: %s\n' "$1" >&2
    printf 'm2m: o passo a passo está em %s\n' "$README" >&2
    exit 1
}

# pergunta sim/não no terminal (mesmo com a entrada redirecionada); o segundo argumento é o padrão (s ou n)
pergunta() {
    if [ "$2" = s ]; then opcoes="[S/n]"; else opcoes="[s/N]"; fi
    # o /dev/tty existe mesmo sem terminal (ssh sem -t, cron): só abrir de fato diz se há onde perguntar
    # (num subshell: no dash, um redirecionamento que falha num comando embutido encerra o script)
    (: </dev/tty) 2>/dev/null || falha "não há terminal para confirmar: rode o script num terminal interativo."
    while :; do
        printf 'm2m: %s %s ' "$1" "$opcoes" >/dev/tty
        read -r resposta </dev/tty || resposta=""
        # sem espaços nem \r, em minúsculas: "S", " s" e "Sim" valem como sim
        resposta="$(printf '%s' "$resposta" | tr -d ' \t\r' | tr '[:upper:]' '[:lower:]')"
        case "${resposta:-$2}" in
            s | sim | y | yes) return 0 ;;
            n | nao | não | no) return 1 ;;
            *) printf 'm2m: responda s ou n.\n' >/dev/tty ;;
        esac
    done
}

# mostra e roda um comando como root
como_root() {
    printf '  $ sudo %s\n' "$*"
    sudo "$@"
}

# protege um valor para o shell, para montar o comando do sg docker -c
aspas() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

instalado() {
    dpkg-query -W -f='${Status}' "$1" 2>/dev/null | grep -q "ok installed"
}

# KiB livres no diretório (ou no ancestral mais próximo que exista)
livre_kib() {
    d="$1"
    while [ ! -e "$d" ]; do d="$(dirname "$d")"; done
    df -Pk "$d" | awk 'NR == 2 { print $4 }'
}

# ponto de montagem do diretório (ou do ancestral mais próximo que exista)
montagem_de() {
    d="$1"
    while [ ! -e "$d" ]; do d="$(dirname "$d")"; done
    df -P "$d" | awk 'NR == 2 { print $6 }'
}

# KiB em GiB, com uma casa decimal
em_gib() {
    awk -v k="$1" 'BEGIN { printf "%.1f GiB", k / 1048576 }'
}

docker_acessivel() {
    docker info >/dev/null 2>&1
}

# roda um comando com acesso ao Docker: direto, ou pelo sg docker quando o grupo ainda não vale nesta sessão
com_docker() {
    if docker_acessivel; then
        "$@"
    else
        linha=""
        for arg in "$@"; do linha="$linha $(aspas "$arg")"; done
        sg docker -c "$linha"
    fi
}

# ---- 1. conferir ----

conferir() {
    case "$(uname -m)" in
        x86_64 | amd64) ;;
        *) falha "o m2m é instalado só em amd64, e esta máquina é $(uname -m)." ;;
    esac
    [ -r /etc/os-release ] || falha "não foi possível identificar o sistema (/etc/os-release)."
    # shellcheck disable=SC1091
    . /etc/os-release
    case " ${ID:-} ${ID_LIKE:-} " in
        *" ubuntu "*) ;;
        *) falha "o m2m é instalado só no Ubuntu e em derivados, e este sistema é ${PRETTY_NAME:-desconhecido}." ;;
    esac
    CODINOME="${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}"
    [ -n "$CODINOME" ] || falha "não foi possível saber em qual versão do Ubuntu este sistema se baseia."
    if [ "$(id -u)" -eq 0 ]; then
        falha "rode este script como o seu usuário, não como root: ele pede a senha do sudo quando precisar."
    fi
    command -v sudo >/dev/null 2>&1 || falha "o sudo não está instalado; peça a quem administra a máquina."

    if [ -d "$RAIZ/.git" ] && [ -f "$RAIZ/docker-compose.yml" ] && [ -f "$RAIZ/scripts/gerar-instalador.sh" ]; then
        MODO=clone
        DIR_INSTALACAO="$RAIZ"
    elif [ -n "$VERSAO" ]; then
        MODO=release
        DIR_INSTALACAO="$HOME/m2m"
    else
        falha "este instalar.sh não é de uma release nem está num clone: baixe-o da release ($REPO/releases/latest) ou rode-o de dentro do clone."
    fi
    conferir_minimos
}

# memória e disco abaixo dos mínimos: mostra a tabela "Requisitos mínimos" e encerra, sem alterar nada (o script não
# mexe em discos nem volumes). Com o m2m já instalado, não cobra: ele mesmo ocupa o disco, e a desinstalação precisa
# continuar possível.
conferir_minimos() {
    [ -f "$REGISTRO" ] && return 0
    # separador das colunas: um caractere de controle, não a tabulação, que o read trata como espaço e junta as
    # colunas vazias
    sep="$(printf '\037')"
    # uma linha por requisito: ícone, requisito, valor da máquina, mínimo, solução
    linhas=""
    falta=0
    memoria="$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo)"
    if [ "$memoria" -lt "$MIN_MEMORIA_KIB" ]; then
        falta=1
        linhas="$linhas✘${sep}Memória${sep}$(em_gib "$memoria")${sep}4 GiB${sep}aumente a memória da máquina para 4 GiB
"
    else
        linhas="$linhas✔${sep}Memória${sep}$(em_gib "$memoria")${sep}4 GiB${sep}-
"
    fi
    minimo=$((MIN_DISCO_GIB * 1024 * 1024))
    medidos=" "
    for d in "$DIR_INSTALACAO" /var/lib/docker; do
        # os dois costumam estar no mesmo sistema de arquivos: uma linha por ponto de montagem
        m="$(montagem_de "$d")"
        case "$medidos" in *" $m "*) continue ;; esac
        medidos="$medidos$m "
        livre="$(livre_kib "$d")"
        if [ "$livre" -lt "$minimo" ]; then
            falta=1
            linhas="$linhas✘${sep}Disco ($m)${sep}$(em_gib "$livre") livres${sep}$MIN_DISCO_GIB GiB${sep}libere $(em_gib $((minimo - livre))) em $m ou aumente o disco
"
        else
            linhas="$linhas✔${sep}Disco ($m)${sep}$(em_gib "$livre") livres${sep}$MIN_DISCO_GIB GiB${sep}-
"
        fi
    done
    [ "$falta" = 1 ] || return 0

    nome="$(cat /etc/hostname 2>/dev/null || uname -n)"
    diga "esta máquina não tem o mínimo para instalar o m2m."
    printf '\nRequisitos mínimos\n'
    tabela " ${sep}${sep}Máquina ($nome)${sep}Mínimo${sep}Solução
$linhas"
    printf '\n'
    diga "nada foi alterado. Depois de atender aos requisitos, rode o script de novo."
    printf 'm2m: o passo a passo está em %s\n' "$README" >&2
    exit 1
}

# quantidade de caracteres (não de bytes): descarta os bytes de continuação do UTF-8, em qualquer locale
caracteres() {
    printf '%s' "$1" | LC_ALL=C tr -d '\200-\277' | wc -c | tr -d ' '
}

# imprime as linhas (colunas separadas por $sep) com as colunas alinhadas (a última sem preenchimento)
tabela() {
    l2=0
    l3=0
    l4=0
    while IFS="$sep" read -r c1 c2 c3 c4 c5; do
        n="$(caracteres "$c2")"; [ "$n" -gt "$l2" ] && l2=$n
        n="$(caracteres "$c3")"; [ "$n" -gt "$l3" ] && l3=$n
        n="$(caracteres "$c4")"; [ "$n" -gt "$l4" ] && l4=$n
    done <<EOF
$1
EOF
    while IFS="$sep" read -r c1 c2 c3 c4 c5; do
        [ -n "$c1$c2" ] || continue
        printf '%s %s   %s   %s   %s\n' "$c1" "$(preencher "$c2" "$l2")" "$(preencher "$c3" "$l3")" "$(preencher "$c4" "$l4")" "$c5"
    done <<EOF
$1
EOF
}

preencher() {
    printf '%s%*s' "$1" $(($2 - $(caracteres "$1"))) ''
}

# ---- 1b. sessão SSH sem tela: o quiz passa pelo túnel da própria sessão ----

sem_tela_por_ssh() {
    [ -n "${SSH_CONNECTION:-}" ] && [ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ]
}

conferir_tunel() {
    sem_tela_por_ssh || return 0
    # SSH_CONNECTION: ip-do-cliente porta-do-cliente ip-desta-máquina porta-desta-máquina
    maquina="$(printf '%s' "$SSH_CONNECTION" | cut -d' ' -f3)"
    entrar="ssh -L $PORTA:127.0.0.1:$PORTA $USUARIO@$maquina"
    # o instalar-remoto.sh abriu esta sessão com o túnel: não há o que perguntar
    if [ "${M2M_TUNEL:-}" = 1 ]; then
        conferir_porta
        return 0
    fi
    diga "esta sessão é SSH, sem tela: o quiz vai abrir no navegador do seu computador pelo túnel desta sessão."
    diga "(se aparecerem mensagens \"channel N: open failed\", são abas antigas do quiz no navegador: pode fechá-las)"
    diga "para isso, a sessão precisa ter sido aberta assim:  $entrar"
    if ! pergunta "Você entrou nesta sessão com esse -L?" s; then
        diga "saia (exit) e entre de novo com:"
        printf '  %s\n' "$entrar"
        diga "e rode o script nessa nova sessão. Ou, de uma vez só, do seu computador:"
        printf "  ssh -t -L %s:127.0.0.1:%s %s@%s 'sh %s/instalar.sh'\n" "$PORTA" "$PORTA" "$USUARIO" "$maquina" "$RAIZ"
        diga "nada foi alterado."
        exit 1
    fi
    conferir_porta
}

conferir_porta() {
    if ss -Hltn "sport = :$PORTA" 2>/dev/null | grep -q .; then
        falha "a porta $PORTA, a do túnel, já está em uso nesta máquina (talvez outro instalador do m2m ainda aberto: encerre-o) e o quiz não passaria pelo túnel em outra porta."
    fi
}

# ---- 2. planejar ----

PLANO=""
PACOTES=""
DOCKER_OFICIAL=0
LIGAR_DOCKER=0
GRUPO=0

planejar() {
    for p in ca-certificates curl git openssl libnss3-tools; do
        instalado "$p" || PACOTES="$PACOTES $p"
    done
    if ! command -v docker >/dev/null 2>&1; then
        DOCKER_OFICIAL=1
        PLANO="$PLANO
  - instalar o Docker Engine, o Compose v2 e o Buildx pelo repositório oficial do Docker (Ubuntu $CODINOME)"
    else
        # não troca um Docker existente: completa com o Compose e o Buildx do mesmo fornecedor
        compose="$(docker compose version --short 2>/dev/null || true)"
        if instalado docker.io; then
            case "$compose" in v2* | 2* | v[3-9]* | [3-9]*) ;; *) PACOTES="$PACOTES docker-compose-v2" ;; esac
            docker buildx version >/dev/null 2>&1 || PACOTES="$PACOTES docker-buildx"
        else
            case "$compose" in v2* | 2* | v[3-9]* | [3-9]*) ;; *) PACOTES="$PACOTES docker-compose-plugin" ;; esac
            docker buildx version >/dev/null 2>&1 || PACOTES="$PACOTES docker-buildx-plugin"
        fi
        if ! systemctl is-active --quiet docker 2>/dev/null && ! systemctl is-active --quiet docker.socket 2>/dev/null; then
            LIGAR_DOCKER=1
            PLANO="$PLANO
  - ligar o serviço do Docker"
        fi
    fi
    if [ -n "$PACOTES" ]; then
        PLANO="$PLANO
  - instalar os pacotes:$PACOTES"
    fi
    if ! id -nG "$USUARIO" | tr ' ' '\n' | grep -qx docker; then
        GRUPO=1
        PLANO="$PLANO
  - incluir o usuário $USUARIO no grupo docker (o quiz já abre com o grupo valendo)"
    fi
}

# ---- 3. preparar ----

preparar() {
    if [ -z "$PLANO" ]; then
        diga "a máquina já está pronta para o m2m."
        return
    fi
    diga "para instalar o m2m nesta máquina, este script vai:$PLANO"
    pergunta "Continuar? O sudo vai pedir a sua senha." n || falha "nada foi alterado."
    sudo -v || falha "o usuário $USUARIO não conseguiu usar o sudo; peça a quem administra a máquina."

    if [ -n "$PACOTES" ] || [ "$DOCKER_OFICIAL" = 1 ]; then
        como_root apt-get update
    fi
    # as ferramentas antes do Docker: o repositório oficial precisa do curl e dos certificados
    base=""
    docker_pacotes=""
    for p in $PACOTES; do
        case "$p" in
            docker-*) docker_pacotes="$docker_pacotes $p" ;;
            *) base="$base $p" ;;
        esac
    done
    if [ -n "$base" ]; then
        # shellcheck disable=SC2086
        como_root apt-get install -y $base
    fi
    if [ "$DOCKER_OFICIAL" = 1 ]; then
        como_root install -m 0755 -d /etc/apt/keyrings
        como_root curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
        como_root chmod a+r /etc/apt/keyrings/docker.asc
        fonte="deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $CODINOME stable"
        printf '  $ sudo tee /etc/apt/sources.list.d/docker.list  # %s\n' "$fonte"
        printf '%s\n' "$fonte" | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
        como_root apt-get update
        docker_pacotes="docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin"
        LIGAR_DOCKER=1
    fi
    if [ -n "$docker_pacotes" ]; then
        # shellcheck disable=SC2086
        como_root apt-get install -y $docker_pacotes
    fi
    if [ "$LIGAR_DOCKER" = 1 ]; then
        # um socket que ficou em falha (por exemplo, na troca de pacotes do Docker) impede o serviço de subir
        sudo systemctl reset-failed docker.socket docker.service 2>/dev/null || true
        como_root systemctl enable --now docker.socket docker.service
    fi
    if [ "$GRUPO" = 1 ]; then
        como_root usermod -aG docker "$USUARIO"
    fi
    diga "máquina preparada."
}

# ---- 4. obter o instalador ----

obter_instalador() {
    if [ "$MODO" = clone ]; then
        diga "compilando o instalador a partir deste clone (na primeira vez, baixa as imagens node e golang: alguns minutos)"
        com_docker sh "$RAIZ/scripts/gerar-instalador.sh" --permitir-alteracoes
        INSTALADOR=""
        for f in "$RAIZ"/dist/instalador/m2m-instalador-*-linux-amd64; do
            [ -f "$f" ] && INSTALADOR="$f"
        done
        [ -n "$INSTALADOR" ] || falha "o instalador não foi gerado; confira as mensagens acima."
        return
    fi
    nome="m2m-instalador-$VERSAO-linux-amd64"
    INSTALADOR="$RAIZ/$nome"
    base="$REPO/releases/download/$VERSAO"
    if [ ! -f "$RAIZ/SHA256SUMS" ]; then
        diga "baixando o SHA256SUMS da $VERSAO"
        curl -fsSL -o "$RAIZ/SHA256SUMS" "$base/SHA256SUMS"
    fi
    if [ ! -f "$INSTALADOR" ]; then
        diga "baixando o instalador $VERSAO"
        curl -fsSL -o "$INSTALADOR" "$base/$nome"
    fi
    grep -q " $nome\$" "$RAIZ/SHA256SUMS" || falha "o SHA256SUMS não tem o checksum de $nome."
    if ! (cd "$RAIZ" && grep " $nome\$" SHA256SUMS | sha256sum -c --status); then
        rm -f "$INSTALADOR"
        falha "o checksum de $nome não confere: o arquivo foi apagado. Rode o script de novo para baixá-lo."
    fi
    chmod +x "$INSTALADOR"
}

# ---- 5. abrir o quiz ----

abrir_quiz() {
    diga "abrindo o quiz. Quando terminar, clique em \"Encerrar o instalador\" (ou Ctrl+C aqui) para o script concluir."
    # na sessão SSH, a porta do túnel; o instalador recebe --porta só se ela não veio nas opções
    if sem_tela_por_ssh && [ "$PORTA_NAS_OPCOES" = 0 ]; then
        set -- --porta "$PORTA" "$@"
    fi
    if [ "$MODO" = clone ]; then
        set -- "$INSTALADOR" --clone "$RAIZ" "$@"
    else
        set -- "$INSTALADOR" "$@"
    fi
    # o Ctrl+C vai para o instalador, que encerra sozinho; o script segue para concluir
    trap ':' INT
    com_docker env M2M_PELO_SCRIPT=1 "$@" || true
    trap - INT
}

# ---- 6. concluir ----

concluir() {
    if [ -f "$REGISTRO" ]; then
        if grep -q '"certificado": *"ca-local"' "$REGISTRO" && [ -f "$CA_LOCAL" ] && ! cmp -s "$CA_LOCAL" "$CA_SISTEMA"; then
            diga "falta confiar na CA local do m2m no sistema (para curl, wget e navegadores fora do Chrome)."
            if pergunta "Instalar a CA do m2m na confiança do sistema?" s; then
                como_root install -m 644 "$CA_LOCAL" "$CA_SISTEMA"
                como_root update-ca-certificates
            fi
        fi
        dir="$(sed -n 's/.*"diretorio": *"\([^"]*\)".*/\1/p' "$REGISTRO" | head -n 1)"
        url="$(grep -s '^PUBLIC_URL=' "$dir/.env" | head -n 1 | cut -d= -f2-)"
        [ -z "$url" ] || diga "o m2m está em $url"
        return
    fi
    desinstalou=0
    [ "${1:-0}" = 1 ] && desinstalou=1
    if [ -f "$CA_SISTEMA" ]; then
        diga "o m2m não está mais instalado, mas a CA local dele continua na confiança do sistema."
        if pergunta "Tirar a CA do m2m da confiança do sistema?" s; then
            como_root rm -f "$CA_SISTEMA"
            como_root update-ca-certificates --fresh
        fi
        desinstalou=1
    fi
    if [ "$desinstalou" = 1 ] && command -v docker >/dev/null 2>&1; then
        diga "o Docker continua instalado. Remova-o só se ele foi instalado apenas para o m2m: outros projetos perdem containers, imagens e volumes."
        if pergunta "Remover também o Docker?" n; then
            if instalado docker.io; then
                como_root apt-get purge -y docker.io docker-compose-v2 docker-buildx
            else
                como_root apt-get purge -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin docker-ce-rootless-extras
                como_root rm -f /etc/apt/sources.list.d/docker.list /etc/apt/keyrings/docker.asc
            fi
            como_root rm -rf /var/lib/docker /var/lib/containerd
        fi
    fi
}

# ---- principal ----

if [ "${1:-}" = "--concluir" ]; then
    shift
    concluir
    exit 0
fi
# o instalar-remoto.sh entrega o token num arquivo (0600), não na linha de comando, que outros usuários veriam no ps
if [ -n "${M2M_TOKEN_ARQUIVO:-}" ] && [ -f "$M2M_TOKEN_ARQUIVO" ]; then
    M2M_TOKEN="$(cat "$M2M_TOKEN_ARQUIVO")"
    rm -f "$M2M_TOKEN_ARQUIVO"
    export M2M_TOKEN
fi
# --porta N (ou --porta=N) nas opções muda a porta do quiz e, numa sessão SSH, a do túnel
PORTA_NAS_OPCOES=0
anterior=""
for arg in "$@"; do
    case "$anterior" in --porta | -porta) PORTA="$arg"; PORTA_NAS_OPCOES=1 ;; esac
    case "$arg" in --porta=* | -porta=*) PORTA="${arg#*=}"; PORTA_NAS_OPCOES=1 ;; esac
    anterior="$arg"
done
conferir
conferir_tunel
planejar
preparar
obter_instalador
registro_antes=0
[ -f "$REGISTRO" ] && registro_antes=1
abrir_quiz "$@"
if [ "$registro_antes" = 1 ] && [ ! -f "$REGISTRO" ]; then
    concluir 1
else
    concluir
fi
diga "pronto."
