#!/bin/sh
# Usa o certificado montado por volume (TLS_CERT_PATH/TLS_KEY_PATH → /etc/m2m/tls-ext/) ou gera um autoassinado no start.
# Roda como root só para ler a chave (modo 600 do dono do host); o Grafana sobe como o usuário grafana (472).
set -e
DIR=/etc/m2m/tls
mkdir -p "$DIR"
if [ -s /etc/m2m/tls-ext/tls.crt ] && [ -s /etc/m2m/tls-ext/tls.key ]; then
    cp /etc/m2m/tls-ext/tls.crt "$DIR/tls.crt"
    cp /etc/m2m/tls-ext/tls.key "$DIR/tls.key"
    echo "m2m: usando o certificado TLS fornecido"
else
    # SAN: localhost, loopback e host do PUBLIC_URL (navegadores ignoram o CN)
    SAN="DNS:localhost,IP:127.0.0.1,IP:::1"
    HOST="${PUBLIC_URL#*://}"; HOST="${HOST%%/*}"
    case "$HOST" in
        \[*) HOST="${HOST#\[}"; HOST="${HOST%%\]*}" ;;
        *) HOST="${HOST%%:*}" ;;
    esac
    case "$HOST" in
        ""|localhost|127.0.0.1|::1) ;;
        *:*|[0-9]*.[0-9]*.[0-9]*.[0-9]*) SAN="$SAN,IP:$HOST" ;;
        *) SAN="$SAN,DNS:$HOST" ;;
    esac
    openssl req -x509 -nodes -newkey rsa:3072 -days 397 \
        -subj "/CN=m2m/O=Vovolinux" -addext "subjectAltName=$SAN" \
        -keyout "$DIR/tls.key" -out "$DIR/tls.crt" 2>/dev/null
    echo "m2m: certificado autoassinado gerado (sem certificado fornecido)"
fi
chown -R 472:0 "$DIR"
chmod 600 "$DIR/tls.key"
exec su-exec 472:0 /run.sh "$@"
