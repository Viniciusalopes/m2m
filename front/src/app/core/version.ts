/** Configuração de runtime gerada pelo nginx do front a partir de M2M_VERSION (ver nginx/50-m2m-config.sh). */
export interface VersaoConfig {
  versao?: unknown;
}

/** Versão instalada (ex.: v1.0.0), ou vazio quando o arquivo falta ou não traz uma versão válida. */
export function versaoInstalada(config: VersaoConfig | null | undefined): string {
  const v = typeof config?.versao === 'string' ? config.versao.trim() : '';
  return /^[0-9A-Za-z._+-]{1,64}$/.test(v) ? v : '';
}
