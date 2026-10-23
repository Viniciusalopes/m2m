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

  // Escala compacta: com o navegador em 100% as medidas são 90% das de antes (barra 64 → 57,6 px; item do menu 48 → 43,2 px)
  test('barra superior e itens do menu têm 90% do tamanho anterior', async ({ page }) => {
    await page.goto('/maquinas');
    const barra = await page.locator('mat-toolbar').first().boundingBox();
    expect(barra!.height).toBeGreaterThan(56.5);
    expect(barra!.height).toBeLessThan(58.7);
    const itens = page.locator('.menu a');
    const [a, b] = [await itens.nth(0).boundingBox(), await itens.nth(1).boundingBox()];
    expect(a!.height).toBeGreaterThan(42);
    expect(a!.height).toBeLessThan(44.4);
    expect(b!.y - a!.y).toBeGreaterThan(42);
    expect(b!.y - a!.y).toBeLessThan(44.4);
  });

  test('o tamanho base da fonte é 90% e os rótulos do menu não são cortados', async ({ page }) => {
    await page.goto('/sobre');
    const raiz = await page.evaluate(() => getComputedStyle(document.documentElement).fontSize);
    expect(parseFloat(raiz)).toBeCloseTo(14.4, 1);
    const cortado = await page
      .locator('.menu a')
      .evaluateAll((els) => els.some((e) => e.scrollWidth > e.clientWidth + 1));
    expect(cortado).toBe(false);
  });

  test('sobre: o container e os cartões ocupam a mesma largura das demais telas', async ({
    page,
  }) => {
    await page.goto('/maquinas');
    const lista = await page.locator('section.m2m-page').first().boundingBox();
    await page.goto('/sobre');
    const sobre = await page.locator('section.m2m-page').first().boundingBox();
    expect(Math.abs(sobre!.width - lista!.width)).toBeLessThan(2);
    const cartoes = await page
      .locator('section.m2m-page mat-card')
      .evaluateAll((els) => els.map((e) => e.getBoundingClientRect().width));
    expect(cartoes.length).toBeGreaterThanOrEqual(2);
    for (const largura of cartoes) {
      // o cartão ocupa toda a largura útil do container (menos a margem interna de 1rem de cada lado)
      expect(largura).toBeGreaterThan(sobre!.width - 40);
    }
  });

  test('perfis: o botão de excluir fica visível dentro da janela', async ({ page }) => {
    await page.goto('/perfis');
    const excluir = page.getByRole('button', { name: /excluir/i }).first();
    if (await excluir.count()) {
      const c = await excluir.boundingBox();
      expect(c!.x + c!.width).toBeLessThanOrEqual(1800);
    }
  });
});
