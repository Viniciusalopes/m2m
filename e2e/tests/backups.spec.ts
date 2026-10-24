import { expect, test, Page } from '@playwright/test';
import { existsSync, mkdirSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { credenciais } from '../playwright.config';
import { literal, MAQUINA_LOCAL, PARTES_SANDBOX, SANDBOX } from '../ambiente';

// O m2m-back enxerga o host em /hostfs/home; tudo abaixo vive no sandbox da máquina LOCAL (D19).

test.describe('backup pela interface', () => {
  const id = Date.now();
  const raiz = `${SANDBOX}/e2e-bk-${id}`;
  const nome = `Backup-E2E-${id}`;

  test.beforeAll(() => {
    mkdirSync(`${raiz}/trabalho/sub`, { recursive: true });
    writeFileSync(`${raiz}/trabalho/arquivo.txt`, 'conteúdo do arquivo');
    writeFileSync(`${raiz}/trabalho/.oculto`, 'oculto');
    writeFileSync(`${raiz}/trabalho/sub/fundo.txt`, 'fundo');
  });

  test.afterAll(() => {
    rmSync(raiz, { recursive: true, force: true });
  });

  async function entrar(page: Page) {
    await page.goto('/login');
    await page.getByLabel('Usuário').fill(credenciais.usuario);
    await page.getByLabel('Senha', { exact: true }).fill(credenciais.senha);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await expect(page).toHaveURL(/\/$/);
  }

  test('cria perfil, executa o backup, navega no conteúdo, baixa, fixa e exclui', async ({ page }) => {
    test.skip(!credenciais.senha, 'ADMIN_PASSWORD não definida');
    await entrar(page);

    // 1. perfil com origem e armazenamento no sandbox
    await page.getByRole('link', { name: 'Perfis' }).click();
    await page.getByRole('link', { name: 'Novo perfil' }).click();
    await page.getByLabel('Nome', { exact: true }).fill(nome);
    await page.getByRole('button', { name: 'Adicionar diretório' }).click();
    const dialogo = page.getByRole('dialog');
    for (const parte of [...PARTES_SANDBOX, `e2e-bk-${id}`, 'trabalho']) {
      await dialogo.getByText(parte, { exact: true }).first().click();
    }
    await dialogo.getByRole('button', { name: 'Escolher este diretório' }).click();
    await page.getByLabel('Diretório de armazenamento').fill(`${raiz}/backups`);
    await page.getByRole('button', { name: 'Salvar' }).click();
    const linhaPerfil = page.getByRole('row', { name: new RegExp(nome) });
    await expect(linhaPerfil).toBeVisible();

    // 2. backup agora → acompanhamento
    await linhaPerfil.getByRole('button', { name: 'Backup agora' }).click();
    await page.getByLabel('Complemento do nome (só neste backup)').fill('e2e');
    await page.getByRole('button', { name: 'Iniciar backup' }).click();
    await expect(page).toHaveURL(/\/jobs\/\d+$/);
    await expect(page.getByRole('heading', { name: new RegExp(`Backup — ${nome}`) })).toBeVisible();
    await expect(page.getByText('Concluído', { exact: true }).first()).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText('Job concluído.')).toBeVisible();
    // concluído: o botão de emergência some
    await expect(page.getByRole('button', { name: 'INTERROMPER AGORA' })).toHaveCount(0);

    // arquivo gravado no sandbox, com o complemento no nome, sem .partial
    const arquivos = readdirSync(`${raiz}/backups`);
    const tar = arquivos.find((n) => n.endsWith('.tar.zst'));
    expect(tar).toMatch(new RegExp(`^${nome}_e2e_${literal(MAQUINA_LOCAL)}_\\d{8}-\\d{6}\\.tar\\.zst$`));
    expect(arquivos.some((n) => n.endsWith('.partial'))).toBe(false);
    expect(arquivos).toContain(`${tar}.manifest`);

    // 3. lista de backups
    await page.getByRole('link', { name: 'Backups' }).click();
    const linha = page.getByRole('row', { name: new RegExp(tar!.replace(/\./g, '\\.')) });
    await expect(linha).toBeVisible();
    await expect(linha).toContainText('Concluído');

    // 4. ver conteúdo
    await linha.getByRole('button', { name: 'Ver conteúdo' }).click();
    const conteudo = page.getByRole('dialog');
    await expect(conteudo.getByRole('cell', { name: /dir-1/ })).toBeVisible();
    await conteudo.getByRole('cell', { name: /dir-1/ }).click();
    await expect(conteudo.getByRole('cell', { name: /\.oculto/ })).toBeVisible();
    await expect(conteudo.getByRole('cell', { name: /arquivo\.txt/ })).toBeVisible();
    await conteudo.getByRole('cell', { name: /sub/ }).click();
    await expect(conteudo.getByRole('cell', { name: /fundo\.txt/ })).toBeVisible();
    await conteudo.getByRole('button', { name: 'Fechar' }).click();

    // 5. baixar
    const [download] = await Promise.all([page.waitForEvent('download'), linha.getByRole('link', { name: 'Baixar' }).click()]);
    expect(download.suggestedFilename()).toBe(tar);
    const caminho = await download.path();
    expect(statSync(caminho!).size).toBe(statSync(`${raiz}/backups/${tar}`).size);

    // 6. fixar e excluir
    await linha.getByRole('button', { name: 'Fixar' }).click();
    await expect(linha.getByTitle('Fixado (fora da retenção)').first()).toBeVisible();
    await linha.getByRole('button', { name: 'Excluir' }).click();
    await expect(page.getByRole('dialog')).toContainText('fixado');
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
    await expect(linha).toHaveCount(0);
    await expect.poll(() => existsSync(`${raiz}/backups/${tar}`), { timeout: 10_000 }).toBe(false);

    // 7. limpeza do perfil
    await page.getByRole('link', { name: 'Perfis' }).click();
    const l = page.getByRole('row', { name: new RegExp(nome) });
    await l.getByRole('button', { name: 'Excluir' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
    await expect(l).toHaveCount(0);
  });
});
