import type { FullConfig } from '@playwright/test';
import { limparResiduos } from './limpeza';

// Ao terminar (passando ou falhando), remove o que os testes criaram
export default async function (config: FullConfig) {
  await limparResiduos(config.projects[0].use.baseURL ?? 'https://localhost');
}
