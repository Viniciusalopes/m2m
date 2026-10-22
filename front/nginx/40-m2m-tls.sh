#!/bin/sh
# Usa o certificado montado por volume (TLS_CERT_PATH/TLS_KEY_PATH → /etc/m2m/tls-ext/) ou gera um autoassinado no primeiro start.
set -e
mkdir -p /etc/m2m/tls
if [ -s /etc/m2m/tls-ext/tls.crt ] && [ -s /etc/m2m/tls-ext/tls.key ]; then
    cp /etc/m2m/tls-ext/tls.crt /etc/m2m/tls/tls.crt
    cp /etc/m2m/tls-ext/tls.key /etc/m2m/tls/tls.key
    echo "m2m: usando o certificado TLS fornecido"
else
    openssl req -x509 -nodes -newkey rsa:3072 -days 825 \
        -subj "/CN=m2m/O=Vovolinux" \
        -keyout /etc/m2m/tls/tls.key -out /etc/m2m/tls/tls.crt 2>/dev/null
    echo "m2m: certificado autoassinado gerado (sem certificado fornecido)"
fi
