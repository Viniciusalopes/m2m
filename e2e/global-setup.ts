import type { FullConfig } from '@playwright/test';
import { limparResiduos } from './limpeza';
import { subirServidores } from './servidores-teste';

// Sobe os servidores de teste (E2E_SERVERS=1) e começa limpo: elimina resíduos de execuções interrompidas
export default async function (config: FullConfig) {
  subirServidores();
  await limparResiduos(config.projects[0].use.baseURL ?? 'https://localhost');
}
