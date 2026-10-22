import { expect, test } from '@playwright/test';
import { credenciais } from '../playwright.config';

// Precisa do compose de teste (servidor SMTP MailDev): E2E_SERVERS=1
const comServidores = process.env.E2E_SERVERS === '1';

test.describe('configurações e painel', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!credenciais.senha, 'ADMIN_PASSWORD não definida');
    await page.goto('/login');
    await page.getByLabel('Usuário').fill(credenciais.usuario);
    await page.getByLabel('Senha', { exact: true }).fill(credenciais.senha);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await expect(page).toHaveURL(/\/$/);
  });

  test('o painel mostra job atual, alertas, perfis e espaço dos armazenamentos', async ({ page }) => {
    await expect(page.getByRole('heading', { name: 'Painel' })).toBeVisible();
    await expect(page.getByText('Job em andamento')).toBeVisible();
    await expect(page.getByText('Alertas', { exact: true })).toBeVisible();
    await expect(page.getByText('Espaço dos armazenamentos')).toBeVisible();
  });

  test('a central de notificações abre pelo sino', async ({ page }) => {
    await page.getByRole('button', { name: 'Notificações' }).click();
    await expect(page).toHaveURL(/\/notificacoes$/);
    await expect(page.getByRole('heading', { name: 'Notificações' })).toBeVisible();
  });

  test('configura o SMTP, envia o e-mail de teste e nunca mostra a senha de volta', async ({ page }) => {
    test.skip(!comServidores, 'requer o compose de teste (E2E_SERVERS=1)');
    await page.getByRole('link', { name: 'Configurações' }).click();
    await expect(page.getByText('E-mail (SMTP)').first()).toBeVisible();
    await page.getByLabel('Servidor SMTP').fill('m2m-test-smtp');
    await page.getByLabel('Porta').fill('1025');
    await page.getByRole('combobox', { name: 'Segurança' }).click({ force: true });
    await page.getByRole('option', { name: 'Nenhuma' }).click();
    await page.getByLabel('Usuário (opcional)').fill('usuario');
    await page.getByLabel('Senha', { exact: true }).fill('segredo-smtp-e2e');
    await page.getByLabel('Remetente').fill('m2m@exemplo.test');
    await page.getByRole('button', { name: 'Enviar e-mail de teste' }).click();
    await expect(page.getByText('E-mail de teste enviado para o seu endereço.')).toBeVisible();

    // com porta errada o erro do servidor aparece
    await page.getByLabel('Porta').fill('1');
    await page.getByRole('button', { name: 'Enviar e-mail de teste' }).click();
    await expect(page.getByText('Falha no envio')).toBeVisible();
    await page.getByLabel('Porta').fill('1025');

    await page.getByRole('button', { name: 'Salvar' }).click();
    await expect(page.getByText('Configuração salva.')).toBeVisible();
    await page.reload();
    await expect(page.getByLabel('Servidor SMTP')).toHaveValue('m2m-test-smtp');
    await expect(page.getByLabel('Senha', { exact: true })).toHaveValue(''); // a senha não volta
    await expect(page.getByText('Senha salva.')).toBeVisible();
  });
});
