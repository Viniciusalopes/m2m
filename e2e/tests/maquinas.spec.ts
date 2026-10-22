import { expect, test } from '@playwright/test';
import { credenciais } from '../playwright.config';

// Precisa do compose de teste (servidores SFTP/FTP em container): E2E_SERVERS=1
const comServidores = process.env.E2E_SERVERS === '1';
const sftpHost = process.env.E2E_SFTP_HOST ?? 'm2m-test-sftp';

test.describe('máquinas', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!credenciais.senha, 'ADMIN_PASSWORD não definida');
    await page.goto('/login');
    await page.getByLabel('Usuário').fill(credenciais.usuario);
    await page.getByLabel('Senha', { exact: true }).fill(credenciais.senha);
    await page.getByRole('button', { name: 'Entrar' }).click();
    await expect(page).toHaveURL(/\/$/);
    await page.getByRole('link', { name: 'Máquinas' }).click();
  });

  test('mostra as máquinas iniciais do NOTEBOOK', async ({ page }) => {
    await expect(page.getByRole('cell', { name: /NOTEBOOK/ })).toBeVisible();
    await expect(page.getByRole('cell', { name: /NOTEBOOK-REMOTO/ })).toBeVisible();
    await expect(page.getByRole('cell', { name: '192.168.0.10:22' })).toBeVisible();
  });

  test('FTP mostra o alerta de falta de criptografia no formulário', async ({ page }) => {
    await page.getByRole('button', { name: 'Nova máquina' }).click();
    await page.getByRole('combobox', { name: 'Protocolo' }).click();
    await page.getByRole('option', { name: 'FTP', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('FTP sem TLS');
    await page.getByRole('button', { name: 'Cancelar' }).click();
  });

  test('SFTP por chave mostra a chave pública com o comando para copiar', async ({ page }) => {
    await page.getByRole('button', { name: 'Nova máquina' }).click();
    await expect(page.getByText('Chave pública do m2m')).toBeVisible();
    await expect(page.locator('pre')).toContainText('authorized_keys');
    await expect(page.getByRole('button', { name: 'Copiar comando' })).toBeVisible();
    await page.getByRole('button', { name: 'Cancelar' }).click();
  });

  test('cadastra, confirma a identidade e testa um servidor SFTP em container', async ({ page }) => {
    test.skip(!comServidores, 'requer o compose de teste (E2E_SERVERS=1)');
    const nome = `E2E-SFTP-${Date.now()}`;
    await page.getByRole('button', { name: 'Nova máquina' }).click();
    await page.getByLabel('Nome', { exact: true }).fill(nome);
    await page.getByLabel('Host (IP ou nome DNS)').fill(sftpHost);
    await page.getByLabel('Usuário', { exact: true }).fill('m2m');
    await page.getByRole('combobox', { name: 'Autenticação' }).click();
    await page.getByRole('option', { name: 'Senha', exact: true }).click();
    await page.getByLabel('Senha', { exact: true }).fill('m2m-teste');
    await page.getByRole('button', { name: 'Salvar' }).click();
    const linha = page.getByRole('row', { name: new RegExp(nome) });
    await expect(linha).toBeVisible();
    await expect(linha).toContainText('A confirmar');

    await linha.getByRole('button', { name: 'Testar conexão' }).click();
    await expect(page.getByRole('heading', { name: 'Confirme a identidade do servidor' })).toBeVisible();
    await expect(page.locator('.fingerprint').first()).toContainText('SHA256:');
    await page.getByRole('button', { name: 'Confirmar esta identidade' }).click();

    // servidor com chroot: lê, mas não grava → só serve de origem
    await expect(page.getByText('só pode ser usada como origem')).toBeVisible();
    await expect(page.getByText('Preservado').first()).toBeVisible();
    await page.getByRole('button', { name: 'Fechar' }).click();
    await expect(linha).toContainText('Confirmada');

    // remover
    await linha.getByRole('button', { name: 'Excluir' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
    await expect(linha).toHaveCount(0);
  });
});
