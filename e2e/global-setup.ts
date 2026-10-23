import type { FullConfig } from '@playwright/test';
import { limparResiduos } from './limpeza';

// Começa limpo: elimina resíduos de execuções interrompidas
export default async function (config: FullConfig) {
  await limparResiduos(config.projects[0].use.baseURL ?? 'https://localhost');
}
