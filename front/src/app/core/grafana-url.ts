/** Configuração de runtime gerada pelo nginx do front a partir de GRAFANA_PORT (ver nginx/50-m2m-config.sh). */
export interface GrafanaConfig {
  port?: number | string;
}

/**
 * Endereço do Grafana: HTTPS, no mesmo host pelo qual o m2m foi aberto (coberto pelo mesmo certificado) e na porta configurada.
 * Sem porta válida devolve null e o botão não aparece.
 */
export function grafanaUrl(hostname: string, config: GrafanaConfig | null | undefined): string | null {
  const porta = Number(config?.port);
  if (!hostname || !Number.isInteger(porta) || porta < 1 || porta > 65535) return null;
  const host = hostname.includes(':') && !hostname.startsWith('[') ? `[${hostname}]` : hostname;
  return `https://${host}:${porta}/`;
}
