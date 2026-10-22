import { expect, request, test } from '@playwright/test';
import { credenciais } from '../playwright.config';

test.describe('acesso', () => {
  test('sem sessão redireciona para o login', async ({ page }) => {
    await page.goto('/auditoria');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { name: 'm2m - Machine To Machine' })).toBeVisible();
    await expect(page).toHaveTitle('m2m - Machine To Machine');
  });

  test('HTTP é redirecionado para HTTPS', async ({ baseURL }) => {
    const http = (baseURL ?? 'https://localhost').replace('https://', 'http://').replace(':443', '');
    const ctx = await request.newContext({ ignoreHTTPSErrors: true, maxRedirects: 0 });
    const r = await ctx.get(http + '/');
    expect(r.status()).toBe(301);
    expect(r.headers()['location']).toMatch(/^https:\/\//);
  });

  test('API sem sessão responde 401', async ({ request: api }) => {
    const r = await api.get('/api/me');
    expect(r.status()).toBe(401);
  });

  test('senha incorreta mostra erro e não entra', async ({ page }) => {
    await page.goto('/login');
    // usuário inexistente: não consome o limite de tentativas do administrador
    await page.getByLabel('Usuário').fill('usuario-inexistente-e2e');
    await page.getByLabel('Senha', { exact: true }).fill('qualquer-senha');
    await page.getByRole('button', { name: 'Entrar' }).click();
    await expect(page.getByRole('alert')).toContainText('Usuário ou senha incorretos.');
    await expect(page).toHaveURL(/\/login$/);
  });
});

test.describe('com sessão', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!credenciais.senha, 'ADMIN_PASSWORD não definida (variável de ambiente ou .env)');
    await page.goto('/login');
    await page.getByLabel('Usuário').fill(credenciais.usuario);
    await page.getByLabel('Senha', { exact: true }).fill(credenciais.senha);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await expect(page).toHaveURL(/\/$/);
  });

  test('mostra o layout base e navega pelo menu', async ({ page }) => {
    await expect(page.getByRole('link', { name: /m2m - Machine To Machine/ })).toBeVisible();
    await expect(page.getByText(`Olá, ${credenciais.usuario}`)).toBeVisible();

    await page.getByRole('link', { name: 'Auditoria' }).click();
    await expect(page).toHaveURL(/\/auditoria$/);
    await expect(page.getByRole('cell', { name: 'LOGIN' }).first()).toBeVisible();

    await page.getByRole('link', { name: 'Sobre' }).click();
    await expect(page.getByRole('link', { name: 'FileZilla Client' })).toBeVisible();
    await expect(page.getByText('não contém código do FileZilla').first()).toBeVisible();
  });

  test('alternância de tema é lembrada', async ({ page }) => {
    await page.getByRole('button', { name: 'Alternar tema claro/escuro' }).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  });

  test('sair volta para o login e bloqueia a API', async ({ page }) => {
    await page.getByRole('button', { name: new RegExp(credenciais.usuario) }).click();
    await page.getByRole('menuitem', { name: 'Sair' }).click();
    await expect(page).toHaveURL(/\/login$/);
    const r = await page.request.get('/api/me');
    expect(r.status()).toBe(401);
  });
});
