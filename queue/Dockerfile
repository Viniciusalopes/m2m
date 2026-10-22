FROM rabbitmq:4-management
COPY rabbitmq.conf /etc/rabbitmq/conf.d/20-m2m.conf
COPY definitions.json /etc/rabbitmq/definitions.json
COPY entrypoint.sh /usr/local/bin/m2m-entrypoint.sh
ENTRYPOINT ["m2m-entrypoint.sh"]
CMD ["rabbitmq-server"]
