#!/bin/sh
# Usa o certificado montado por volume (TLS_CERT_PATH/TLS_KEY_PATH → /etc/m2m/tls-ext/) ou gera um autoassinado no primeiro start.
set -e
mkdir -p /etc/m2m/tls
if [ -s /etc/m2m/tls-ext/tls.crt ] && [ -s /etc/m2m/tls-ext/tls.key ]; then
    cp /etc/m2m/tls-ext/tls.crt /etc/m2m/tls/tls.crt
    cp /etc/m2m/tls-ext/tls.key /etc/m2m/tls/tls.key
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
        -keyout /etc/m2m/tls/tls.key -out /etc/m2m/tls/tls.crt 2>/dev/null
    echo "m2m: certificado autoassinado gerado (sem certificado fornecido)"
fi
