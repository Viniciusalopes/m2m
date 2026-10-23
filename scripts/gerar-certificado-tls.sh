#!/bin/sh
# Cria a CA local do m2m e o certificado de servidor (SAN: localhost, loopback e host do PUBLIC_URL),
# instala a CA no sistema e no Chrome/Chromium e grava TLS_CERT_PATH/TLS_KEY_PATH no .env.
# Idempotente: sem nada a fazer, sai em silêncio e sem sudo. Falha de instalação é só aviso.
# Uso: gerar-certificado-tls.sh [--sem-sistema]   (--sem-sistema só gera os arquivos)
set -e
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${M2M_ENV_FILE:-$RAIZ/.env}"
DIR="${M2M_TLS_DIR:-$HOME/.config/m2m/tls}"
NOME_CA="m2m-local-ca"
SISTEMA=1
NOVO=0   # 1 quando a CA foi criada ou instalada: só então repete o aviso de importação manual
[ "$1" = "--sem-sistema" ] && SISTEMA=0

aviso() { echo "m2m: aviso: $*" >&2; }

# Host do PUBLIC_URL (sem esquema, porta e caminho); lê o .env sem executá-lo
PUBLIC_URL="${PUBLIC_URL:-}"
if [ -z "$PUBLIC_URL" ] && [ -f "$ENV_FILE" ]; then
    PUBLIC_URL="$(sed -n 's/^PUBLIC_URL=//p' "$ENV_FILE" | tail -n 1 | tr -d '"'"'"' \r')"
fi
HOST="${PUBLIC_URL#*://}"; HOST="${HOST%%/*}"
case "$HOST" in
    \[*) HOST="${HOST#\[}"; HOST="${HOST%%\]*}" ;;
    *) HOST="${HOST%%:*}" ;;
esac

mkdir -p "$DIR"; chmod 700 "$DIR"
CA_KEY="$DIR/ca.key"; CA_CRT="$DIR/ca.crt"
SRV_KEY="$DIR/server.key"; SRV_CRT="$DIR/server.crt"

# --- CA local (10 anos), restrita a nomes locais e redes privadas ---
if [ ! -s "$CA_KEY" ] || [ ! -s "$CA_CRT" ]; then
    NC="permitted;DNS:localhost,permitted;DNS:.local,permitted;DNS:.lan,permitted;DNS:.home.arpa,permitted;IP:127.0.0.0/255.0.0.0,permitted;IP:10.0.0.0/255.0.0.0,permitted;IP:172.16.0.0/255.240.0.0,permitted;IP:192.168.0.0/255.255.0.0,permitted;IP:::1/ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
    # host com nome DNS fora dos sufixos locais também precisa constar na restrição
    if [ -n "$HOST" ] && ! echo "$HOST" | grep -Eq '^[0-9.]+$|:'; then NC="$NC,permitted;DNS:$HOST"; fi
    cat > "$DIR/ca.cnf" <<EOF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = m2m local CA
O = Vovolinux
[ext]
basicConstraints = critical,CA:TRUE,pathlen:0
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
nameConstraints = critical,$NC
EOF
    umask 077
    openssl req -x509 -nodes -newkey rsa:3072 -days 3650 -config "$DIR/ca.cnf" \
        -keyout "$CA_KEY" -out "$CA_CRT" 2>/dev/null
    rm -f "$DIR/ca.cnf" "$SRV_KEY" "$SRV_CRT"   # certificado antigo não pertence à nova CA
    chmod 600 "$CA_KEY"; chmod 644 "$CA_CRT"
    NOVO=1
    echo "m2m: CA local criada em $CA_CRT"
fi

# --- Certificado de servidor: reemite se faltar, vencer em < 30 dias ou não cobrir o host ---
PRECISA=0
if [ ! -s "$SRV_KEY" ] || [ ! -s "$SRV_CRT" ]; then
    PRECISA=1
elif ! openssl x509 -in "$SRV_CRT" -noout -checkend 2592000 >/dev/null; then
    echo "m2m: certificado expira em $(openssl x509 -in "$SRV_CRT" -noout -enddate | cut -d= -f2); reemitindo"
    PRECISA=1
elif [ -n "$HOST" ] && ! echo "$HOST" | grep -q ':' && \
    ! { openssl x509 -in "$SRV_CRT" -noout -ext subjectAltName | tr -d ' \n'; echo ,; } | grep -Fq -e "DNS:$HOST," -e "IPAddress:$HOST,"; then
    PRECISA=1
fi

if [ "$PRECISA" = 1 ]; then
    SAN="DNS:localhost,IP:127.0.0.1,IP:::1"
    if [ -n "$HOST" ] && [ "$HOST" != "localhost" ] && [ "$HOST" != "127.0.0.1" ] && [ "$HOST" != "::1" ]; then
        if echo "$HOST" | grep -Eq '^[0-9.]+$|:'; then SAN="$SAN,IP:$HOST"; else SAN="$SAN,DNS:$HOST"; fi
    fi
    cat > "$DIR/server.cnf" <<EOF
[req]
distinguished_name = dn
req_extensions = ext
prompt = no
[dn]
CN = m2m
O = Vovolinux
[ext]
subjectAltName = $SAN
basicConstraints = CA:FALSE
keyUsage = critical,digitalSignature
extendedKeyUsage = serverAuth
EOF
    umask 077
    openssl req -new -nodes -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \
        -config "$DIR/server.cnf" -keyout "$SRV_KEY" -out "$DIR/server.csr" 2>/dev/null
    openssl x509 -req -in "$DIR/server.csr" -CA "$CA_CRT" -CAkey "$CA_KEY" -CAcreateserial \
        -days 397 -extfile "$DIR/server.cnf" -extensions ext -out "$SRV_CRT" 2>/dev/null
    rm -f "$DIR/server.cnf" "$DIR/server.csr" "$DIR/ca.srl"
    chmod 600 "$SRV_KEY"; chmod 644 "$SRV_CRT"
    if ! openssl verify -CAfile "$CA_CRT" "$SRV_CRT" >/dev/null 2>&1; then
        aviso "o host '$HOST' está fora das restrições da CA; apague $CA_KEY e $CA_CRT e rode de novo para recriá-la"
        rm -f "$SRV_KEY" "$SRV_CRT"
        exit 1
    fi
    echo "m2m: certificado emitido ($SAN), válido até $(openssl x509 -in "$SRV_CRT" -noout -enddate | cut -d= -f2)"
fi

# --- .env: aponta TLS_CERT_PATH/TLS_KEY_PATH para os arquivos gerados (sem imprimir o conteúdo) ---
if [ -f "$ENV_FILE" ]; then
    for par in "TLS_CERT_PATH=$SRV_CRT" "TLS_KEY_PATH=$SRV_KEY"; do
        chave="${par%%=*}"
        if grep -qxF "$par" "$ENV_FILE"; then continue; fi
        tmp="$(mktemp)"
        if grep -q "^$chave=" "$ENV_FILE"; then
            awk -v k="$chave" -v v="${par#*=}" 'BEGIN{FS=OFS="="} $1==k{print k"="v; next} {print}' "$ENV_FILE" > "$tmp"
        else
            { cat "$ENV_FILE"; echo "$par"; } > "$tmp"
        fi
        cat "$tmp" > "$ENV_FILE"; rm -f "$tmp"
        echo "m2m: $chave configurado no .env"
    done
else
    aviso ".env não encontrado; defina TLS_CERT_PATH=$SRV_CRT e TLS_KEY_PATH=$SRV_KEY"
fi

[ "$SISTEMA" = 1 ] || { echo "m2m: CA para importar manualmente: $CA_CRT"; exit 0; }

# --- Instalação da CA no sistema (update-ca-certificates) ---
ALVO="/usr/local/share/ca-certificates/$NOME_CA.crt"
if ! cmp -s "$CA_CRT" "$ALVO"; then
    if [ -t 0 ]; then SUDO="sudo"; else SUDO="sudo -n"; fi
    if $SUDO install -m 644 "$CA_CRT" "$ALVO" && $SUDO update-ca-certificates >/dev/null; then
        NOVO=1
        echo "m2m: CA instalada no sistema"
    else
        aviso "não foi possível instalar a CA no sistema; rode: sudo install -m 644 $CA_CRT $ALVO && sudo update-ca-certificates"
    fi
fi

# --- Instalação da CA no Chrome/Chromium (NSS do usuário) ---
NSS="sql:$HOME/.pki/nssdb"
if command -v certutil >/dev/null 2>&1; then
    mkdir -p "$HOME/.pki/nssdb"
    [ -f "$HOME/.pki/nssdb/cert9.db" ] || certutil -d "$NSS" -N --empty-password
    imp_ca="$(openssl x509 -in "$CA_CRT" -noout -fingerprint -sha256)"
    imp_nss="$(certutil -d "$NSS" -L -n "$NOME_CA" -a 2>/dev/null | openssl x509 -noout -fingerprint -sha256 2>/dev/null || true)"
    if [ "$imp_ca" != "$imp_nss" ]; then
        certutil -d "$NSS" -D -n "$NOME_CA" 2>/dev/null || true
        certutil -d "$NSS" -A -t "C,," -n "$NOME_CA" -i "$CA_CRT"
        NOVO=1
        echo "m2m: CA instalada no Chrome/Chromium (feche e reabra o navegador)"
    fi
else
    aviso "certutil ausente: instale com 'sudo apt install libnss3-tools' e rode de novo para o Chrome confiar na CA"
fi
[ "$NOVO" = 1 ] && echo "m2m: para Firefox ou outros dispositivos, importe manualmente: $CA_CRT"
exit 0
