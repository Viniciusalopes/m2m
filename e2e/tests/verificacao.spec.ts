import { expect, test } from '@playwright/test';
import { mkdirSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { credenciais } from '../playwright.config';

// Verificação entre duas pastas do próprio sandbox do NOTEBOOK: a máquina de destino é a NOTEBOOK-REMOTO? Não: usa um
// segundo perfil LOCAL só se houver outra máquina; aqui o fluxo de tela é validado até a escolha do destino.
const SANDBOX = '/home/usuario/m2m-sandbox';

test.describe('verificação', () => {
  const id = Date.now();
  const raiz = `${SANDBOX}/e2e-ver-${id}`;
  const nome = `Ver-E2E-${id}`;

  test.beforeAll(() => {
    mkdirSync(`${raiz}/origem`, { recursive: true });
    writeFileSync(`${raiz}/origem/a.txt`, 'a');
    utimesSync(`${raiz}/origem/a.txt`, 1_700_000_000, 1_700_000_000);
  });

  test.afterAll(() => rmSync(raiz, { recursive: true, force: true }));

  test('sem máquina mapeada o diálogo avisa que não há destino', async ({ page }) => {
    test.skip(!credenciais.senha, 'ADMIN_PASSWORD não definida');
    await page.goto('/login');
    await page.getByLabel('Usuário').fill(credenciais.usuario);
    await page.getByLabel('Senha', { exact: true }).fill(credenciais.senha);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await page.getByRole('link', { name: 'Perfis' }).click();
    await page.getByRole('link', { name: 'Novo perfil' }).click();
    await page.getByLabel('Nome', { exact: true }).fill(nome);
    await page.getByRole('button', { name: 'Adicionar diretório' }).click();
    const d = page.getByRole('dialog');
    for (const parte of ['usuario', 'm2m-sandbox', `e2e-ver-${id}`, 'origem']) {
      await d.getByText(parte, { exact: true }).first().click();
    }
    await d.getByRole('button', { name: 'Escolher este diretório' }).click();
    await page.getByLabel('Diretório de armazenamento').fill(`${raiz}/backups`);
    await page.getByRole('button', { name: 'Salvar' }).click();

    const linha = page.getByRole('row', { name: new RegExp(nome) });
    await linha.getByRole('button', { name: 'Verificar' }).click();
    await expect(page.getByRole('dialog')).toContainText('Nenhuma máquina tem mapeamento');
    await expect(page.getByRole('dialog').getByRole('button', { name: 'Iniciar verificação' })).toBeDisabled();
    await page.getByRole('button', { name: 'Cancelar' }).click();

    await linha.getByRole('button', { name: 'Excluir' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
    await expect(linha).toHaveCount(0);
  });
});
