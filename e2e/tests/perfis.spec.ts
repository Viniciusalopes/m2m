import { expect, test } from '@playwright/test';
import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { credenciais } from '../playwright.config';

// O m2m-back enxerga o host em /hostfs/home; os diretórios abaixo são do sandbox do NOTEBOOK (D19).
const SANDBOX = '/home/usuario/m2m-sandbox';

test.describe('perfis', () => {
  const id = Date.now();
  const raiz = `${SANDBOX}/e2e-${id}`;
  const nome = `Perfil-E2E-${id}`;

  test.beforeAll(() => {
    mkdirSync(`${raiz}/trabalho`, { recursive: true });
    writeFileSync(`${raiz}/trabalho/arquivo.txt`, 'conteúdo');
  });

  test.afterAll(() => {
    rmSync(raiz, { recursive: true, force: true });
  });

  test.beforeEach(async ({ page }) => {
    test.skip(!credenciais.senha, 'ADMIN_PASSWORD não definida');
    await page.goto('/login');
    await page.getByLabel('Usuário').fill(credenciais.usuario);
    await page.getByLabel('Senha', { exact: true }).fill(credenciais.senha);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await expect(page).toHaveURL(/\/$/);
  });

  async function escolherDiretorio(page: import('@playwright/test').Page, caminho: string[]) {
    const dialogo = page.getByRole('dialog');
    for (const parte of caminho) {
      await dialogo.getByText(parte, { exact: true }).first().click();
    }
    await dialogo.getByRole('button', { name: 'Escolher este diretório' }).click();
  }

  test('cria, edita e exclui um perfil pela interface', async ({ page }) => {
    await page.getByRole('link', { name: 'Perfis' }).click();
    await page.getByRole('link', { name: 'Novo perfil' }).click();

    await page.getByLabel('Nome', { exact: true }).fill(nome);
    // a origem vem preenchida com a primeira máquina (NOTEBOOK); armazenamento padrão LOCAL /home/backup
    await page.getByRole('button', { name: 'Adicionar diretório' }).click();
    await escolherDiretorio(page, ['usuario', 'm2m-sandbox', `e2e-${id}`, 'trabalho']);
    await expect(page.locator('.diretorios .caminho')).toContainText(`${raiz}/trabalho`);

    // armazenamento no sandbox (o /home/backup real não é tocado nos testes)
    await page.getByLabel('Diretório de armazenamento').fill(`${raiz}/backups`);
    await page.getByLabel('Backup diário ativo').click();
    await page.getByRole('button', { name: 'Salvar' }).click();

    const linha = page.getByRole('row', { name: new RegExp(nome) });
    await expect(linha).toBeVisible();
    await expect(linha).toContainText('Diário às 02:00');
    await expect(linha).toContainText(`${raiz}/trabalho`);

    // editar: complemento inválido é recusado no formulário
    await linha.getByRole('link', { name: 'Editar' }).click();
    await page.getByLabel('Complemento do nome (opcional)').fill('versão final/1');
    await page.getByLabel('Complemento do nome (opcional)').blur();
    await expect(page.getByText('O complemento só pode ter letras, números, hífen e sublinhado.').first()).toBeVisible();
    await page.getByLabel('Complemento do nome (opcional)').fill('versao_final-1');
    await page.getByRole('button', { name: 'Salvar' }).click();
    await expect(linha).toContainText('versao_final-1');

    // armazenamento dentro da origem é recusado pelo servidor
    await linha.getByRole('link', { name: 'Editar' }).click();
    await page.getByLabel('Diretório de armazenamento').fill(`${raiz}/trabalho/backups`);
    await page.getByRole('button', { name: 'Salvar' }).click();
    await expect(page.locator('p.erro')).toContainText('o backup incluiria a si mesmo');
    await page.getByRole('button', { name: 'Cancelar' }).click();

    // excluir mantendo os backups
    await linha.getByRole('button', { name: 'Excluir' }).click();
    await expect(page.getByRole('dialog')).toContainText('Manter os backups');
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
    await expect(linha).toHaveCount(0);
  });

  test('nome duplicado mostra erro', async ({ page }) => {
    await page.getByRole('link', { name: 'Perfis' }).click();
    for (let i = 0; i < 2; i++) {
      await page.getByRole('link', { name: 'Novo perfil' }).click();
      await page.getByLabel('Nome', { exact: true }).fill(`${nome}-dup`);
      await page.getByRole('button', { name: 'Adicionar diretório' }).click();
      await escolherDiretorio(page, ['usuario', 'm2m-sandbox', `e2e-${id}`, 'trabalho']);
      await page.getByLabel('Diretório de armazenamento').fill(`${raiz}/b-${i}`);
      await page.getByRole('button', { name: 'Salvar' }).click();
      if (i === 0) {
        await expect(page.getByRole('row', { name: new RegExp(`${nome}-dup`) })).toBeVisible();
      }
    }
    await expect(page.locator('p.erro')).toContainText('Já existe um perfil');
    await page.getByRole('button', { name: 'Cancelar' }).click();
    const linha = page.getByRole('row', { name: new RegExp(`${nome}-dup`) });
    await linha.getByRole('button', { name: 'Excluir' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
    await expect(linha).toHaveCount(0);
  });
});
