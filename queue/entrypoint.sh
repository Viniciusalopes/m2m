#!/bin/sh
# O RabbitMQ não cria o usuário padrão quando há definitions: gera o arquivo final com o usuário do ambiente.
set -e
: "${RABBITMQ_DEFAULT_USER:?RABBITMQ_DEFAULT_USER não definido}"
: "${RABBITMQ_DEFAULT_PASS:?RABBITMQ_DEFAULT_PASS não definido}"
sed -e "s/__RABBITMQ_USER__/${RABBITMQ_DEFAULT_USER}/g" \
    -e "s/__RABBITMQ_PASSWORD__/${RABBITMQ_DEFAULT_PASS}/g" \
    /etc/rabbitmq/definitions.json > /etc/rabbitmq/definitions.generated.json
chown rabbitmq:rabbitmq /etc/rabbitmq/definitions.generated.json
chmod 600 /etc/rabbitmq/definitions.generated.json
exec docker-entrypoint.sh "$@"
