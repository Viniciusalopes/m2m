import { request } from '@playwright/test';
import { existsSync, readdirSync, rmSync } from 'node:fs';
import { SANDBOX } from './ambiente';
import { credenciais } from './playwright.config';


const ehE2E = (nome: string | null | undefined) => (nome ?? '').includes('E2E');

/**
 * Remove pela API tudo o que os testes criaram (perfis, backups e máquinas "E2E"), mais as pastas
 * "e2e-*" do sandbox. Nunca falha a execução: só registra o que não conseguiu limpar.
 */
export async function limparResiduos(baseURL: string): Promise<void> {
  if (!credenciais.senha) return;
  const api = await request.newContext({ baseURL, ignoreHTTPSErrors: true });
  try {
    await api.get('/api/me');
    const xsrf = async () =>
      (await api.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '';
    const login = await api.post('/api/auth/login', {
      form: { username: credenciais.usuario, password: credenciais.senha },
      headers: { 'X-XSRF-TOKEN': await xsrf() },
    });
    if (login.status() !== 204) {
      console.warn(`[limpeza e2e] login não concluído (HTTP ${login.status()}); resíduos mantidos`);
      return;
    }
    const apagar = async (caminho: string) => {
      const r = await api.delete(caminho, { headers: { 'X-XSRF-TOKEN': await xsrf() } });
      if (!r.ok()) console.warn(`[limpeza e2e] DELETE ${caminho} -> HTTP ${r.status()}`);
    };
    const listar = async (caminho: string): Promise<any[]> => {
      const r = await api.get(caminho);
      return r.ok() ? await r.json() : [];
    };

    // Ordem: perfis (com os backups) → backups órfãos → máquinas, por causa das chaves RESTRICT
    for (const p of await listar('/api/profiles')) {
      if (ehE2E(p.name)) await apagar(`/api/profiles/${p.id}?keepBackups=false`);
    }
    for (let pagina = 0; pagina < 20; pagina++) {
      const lote = (await listar(`/api/backups?size=200&page=${pagina}`)).filter((b) => ehE2E(b.profileName));
      if (!lote.length) break;
      for (const b of lote) await apagar(`/api/backups/${b.id}`);
    }
    for (const m of await listar('/api/machines')) {
      if ((m.name as string).startsWith('E2E-')) await apagar(`/api/machines/${m.id}`);
    }
  } catch (e) {
    console.warn('[limpeza e2e] falhou:', (e as Error).message);
  } finally {
    await api.dispose();
  }
  if (existsSync(SANDBOX)) {
    for (const nome of readdirSync(SANDBOX)) {
      if (nome.startsWith('e2e-')) rmSync(`${SANDBOX}/${nome}`, { recursive: true, force: true });
    }
  }
}
