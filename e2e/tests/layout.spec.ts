import { expect, test } from '@playwright/test';
import { credenciais } from '../playwright.config';

// Largura do container principal e listas sem corte (spec page-layout)
test.describe('layout das páginas', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!credenciais.senha, 'ADMIN_PASSWORD não definida');
    await page.setViewportSize({ width: 1800, height: 900 });
    await page.goto('/login');
    await page.getByLabel('Usuário').fill(credenciais.usuario);
    await page.getByLabel('Senha', { exact: true }).fill(credenciais.senha);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await expect(page).toHaveURL(/\/$/);
  });

  for (const rota of ['maquinas', 'perfis', 'backups', 'auditoria']) {
    test(`${rota} ocupa toda a largura e não corta a tabela`, async ({ page }) => {
      await page.goto(`/${rota}`);
      const secao = page.locator('section.m2m-page').first();
      await expect(secao).toBeVisible();
      const caixa = await secao.boundingBox();
      // Mais larga que o antigo limite de 1200 px
      expect(caixa!.width).toBeGreaterThan(1300);
      // A tabela cabe no container: sem rolagem horizontal escondendo colunas
      const tabela = page.locator('.tabela').first();
      if (await tabela.count()) {
        const cabe = await tabela.evaluate((e) => e.scrollWidth <= e.clientWidth + 1);
        expect(cabe).toBe(true);
      }
    });
  }

  test('perfis: o botão de excluir fica visível dentro da janela', async ({ page }) => {
    await page.goto('/perfis');
    const excluir = page.getByRole('button', { name: /excluir/i }).first();
    if (await excluir.count()) {
      const c = await excluir.boundingBox();
      expect(c!.x + c!.width).toBeLessThanOrEqual(1800);
    }
  });
});
