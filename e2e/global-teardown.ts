import type { FullConfig } from '@playwright/test';
import { limparResiduos } from './limpeza';
import { derrubarServidores } from './servidores-teste';

// Ao terminar (passando ou falhando), remove o que os testes criaram e derruba os servidores de teste
export default async function (config: FullConfig) {
  try {
    await limparResiduos(config.projects[0].use.baseURL ?? 'https://localhost');
  } finally {
    derrubarServidores();
  }
}
