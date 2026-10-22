import { expect, test, Page } from '@playwright/test';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { credenciais } from '../playwright.config';

const SANDBOX = '/home/usuario/m2m-sandbox';

/** Só o passo ativo do assistente tem botão "Continuar" visível. */
async function continuar(page: Page) {
  // espera a animação do passo anterior terminar antes de clicar no botão do passo atual
  await page.waitForTimeout(600);
  await page.locator('button.mat-stepper-next:visible', { hasText: 'Continuar' }).first().click({ force: true });
  await page.waitForTimeout(600);
}

test.describe('restauração pela interface', () => {
  const id = Date.now();
  const raiz = `${SANDBOX}/e2e-rest-${id}`;
  const nome = `Rest-E2E-${id}`;

  test.beforeAll(() => {
    mkdirSync(`${raiz}/origem/sub`, { recursive: true });
    writeFileSync(`${raiz}/origem/arquivo.txt`, 'conteúdo original');
    writeFileSync(`${raiz}/origem/.oculto`, 'oculto');
    writeFileSync(`${raiz}/origem/sub/fundo.txt`, 'fundo');
  });

  test.afterAll(() => rmSync(raiz, { recursive: true, force: true }));

  async function entrar(page: Page) {
    await page.goto('/login');
    await page.getByLabel('Usuário').fill(credenciais.usuario);
    await page.getByLabel('Senha', { exact: true }).fill(credenciais.senha);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await expect(page).toHaveURL(/\/$/);
  }

  test('backup, restauração em espelho com prévia, seletiva com conflitos e desfazer', async ({ page }) => {
    test.skip(!credenciais.senha, 'ADMIN_PASSWORD não definida');
    test.setTimeout(120_000);
    await entrar(page);

    // perfil + backup
    await page.getByRole('link', { name: 'Perfis', exact: true }).click();
    await page.getByRole('link', { name: 'Novo perfil' }).click();
    await page.getByLabel('Nome', { exact: true }).fill(nome);
    await page.getByRole('button', { name: 'Adicionar diretório' }).click();
    const d = page.getByRole('dialog');
    for (const parte of ['usuario', 'm2m-sandbox', `e2e-rest-${id}`, 'origem']) {
      await d.getByText(parte, { exact: true }).first().click();
    }
    await d.getByRole('button', { name: 'Escolher este diretório' }).click();
    await page.getByLabel('Diretório de armazenamento').fill(`${raiz}/backups`);
    await page.getByRole('button', { name: 'Salvar' }).click();
    const linhaPerfil = page.getByRole('row', { name: new RegExp(nome) });
    await linhaPerfil.getByRole('button', { name: 'Backup agora' }).click();
    await page.getByRole('button', { name: 'Iniciar backup' }).click();
    await expect(page.getByText('Job concluído.')).toBeVisible({ timeout: 60_000 });

    // restauração em espelho para um caminho novo
    await page.getByRole('link', { name: 'Backups' }).click();
    const linhaBackup = page.getByRole('row', { name: new RegExp(nome) }).first();
    await linhaBackup.getByRole('button', { name: 'Restaurar' }).click();
    await expect(page).toHaveURL(/\/restaurar\/\d+$/);
    await page.getByRole('combobox', { name: 'Máquina de destino' }).click({ force: true }); // o rótulo flutuante intercepta o clique do Playwright
    await page.getByRole('option', { name: /NOTEBOOK/ }).click();
    await continuar(page);
    // destino = origem: o caminho original vem pré-preenchido; troca pelo caminho de restauração
    const campo = page.getByLabel('Caminho de destino');
    await expect(campo).toHaveValue(`${raiz}/origem`);
    await campo.fill(`${raiz}/restaurado`);
    await continuar(page);
    await expect(page.getByText('será criado')).toBeVisible();
    await expect(page.getByText('Antes de alterar o destino')).toBeVisible();
    await continuar(page);
    // sem confirmação o botão fica desabilitado
    await expect(page.getByRole('button', { name: 'Iniciar restauração' })).toBeDisabled();
    await page.getByRole('checkbox', { name: /Entendo que o destino será espelhado/ }).check();
    await page.getByRole('button', { name: 'Iniciar restauração' }).click();
    await expect(page).toHaveURL(/\/jobs\/\d+$/);
    await expect(page.getByText('Job concluído.')).toBeVisible({ timeout: 60_000 });
    expect(readFileSync(`${raiz}/restaurado/arquivo.txt`, 'utf8')).toBe('conteúdo original');
    expect(readFileSync(`${raiz}/restaurado/.oculto`, 'utf8')).toBe('oculto');
    expect(readFileSync(`${raiz}/restaurado/sub/fundo.txt`, 'utf8')).toBe('fundo');

    // restauração seletiva: um arquivo para outro diretório
    await page.getByRole('link', { name: 'Backups' }).click();
    const linhaRegular = page
      .getByRole('row', { name: new RegExp(nome) })
      .filter({ hasText: /Backup(?! —)/ })
      .filter({ has: page.getByRole('button', { name: 'Ver conteúdo' }) })
      .first();
    await linhaRegular.getByRole('button', { name: 'Ver conteúdo' }).click();
    const conteudo = page.getByRole('dialog');
    await conteudo.getByRole('cell', { name: /dir-1/ }).click();
    await conteudo.getByRole('row', { name: /arquivo\.txt/ }).getByRole('checkbox').check();
    await conteudo.getByRole('button', { name: /Restaurar selecionados \(1\)/ }).click();
    const sel = page.getByRole('dialog');
    await sel.getByRole('combobox', { name: 'Máquina de destino' }).click({ force: true });
    await page.getByRole('option', { name: /NOTEBOOK/ }).click();
    await sel.getByLabel('Diretório de destino').fill(`${raiz}/recuperados`);
    await sel.getByRole('button', { name: 'Verificar conflitos' }).click();
    await expect(sel.getByText('Nenhum item existe no destino')).toBeVisible();
    await sel.getByRole('button', { name: 'Iniciar restauração' }).click();
    await expect(page.getByText('Job concluído.')).toBeVisible({ timeout: 60_000 });
    expect(readFileSync(`${raiz}/recuperados/arquivo.txt`, 'utf8')).toBe('conteúdo original');
    expect(existsSync(`${raiz}/recuperados/sub`)).toBe(false);

    // desfazer a restauração em espelho pelo backup de segurança
    await page.getByRole('link', { name: 'Backups' }).click();
    const seguranca = page.getByRole('row').filter({ hasText: 'Segurança (antes de restaurar)' }).first();
    await seguranca.getByRole('button', { name: 'Desfazer' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Desfazer' }).click();
    await expect(page.getByText('Job concluído.')).toBeVisible({ timeout: 60_000 });
    // o caminho de restauração não existia antes: desfazer o remove
    expect(existsSync(`${raiz}/restaurado`)).toBe(false);

    // limpeza do perfil (os backups ficam órfãos e são apagados junto)
    await page.getByRole('link', { name: 'Perfis', exact: true }).click();
    const l = page.getByRole('row', { name: new RegExp(nome) });
    await l.getByRole('button', { name: 'Excluir' }).click();
    await page.getByRole('dialog').getByText('Excluir também os arquivos de backup').click();
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
    await expect(l).toHaveCount(0);
  });
});
