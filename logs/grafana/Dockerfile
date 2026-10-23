# syntax=docker/dockerfile:1
# Grafana com HTTPS: o entrypoint copia o certificado do m2m (ou gera um autoassinado) e depois abaixa os privilégios.
FROM grafana/grafana:12.3.0
USER root
RUN apk add --no-cache openssl su-exec
COPY entrypoint-tls.sh /usr/local/bin/m2m-grafana-entrypoint.sh
COPY provisioning /etc/grafana/provisioning
COPY dashboards /etc/grafana/dashboards
EXPOSE 3000
ENTRYPOINT ["/usr/local/bin/m2m-grafana-entrypoint.sh"]
