import { createHmac } from 'node:crypto';
import { expect, test, Page } from '@playwright/test';
import { credenciais } from '../playwright.config';

const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

/** TOTP do RFC 6238 (SHA-1, 6 dígitos, 30 s), igual ao do backend. */
function totp(secretBase32: string, deslocamento = 0): string {
  let bits = '';
  for (const c of secretBase32.replace(/=+$/, '')) {
    bits += BASE32.indexOf(c).toString(2).padStart(5, '0');
  }
  const bytes = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)));
  const passo = BigInt(Math.floor(Date.now() / 30000) + deslocamento);
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(passo);
  const h = createHmac('sha1', bytes).update(msg).digest();
  const o = h[h.length - 1] & 0x0f;
  const n = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(n % 1_000_000).padStart(6, '0');
}

async function entrarComSenha(page: Page) {
  await page.goto('/login');
  await page.getByLabel('Usuário').fill(credenciais.usuario);
  await page.getByLabel('Senha', { exact: true }).fill(credenciais.senha);
  await page.getByRole('button', { name: 'Entrar' }).click();
}

test.describe('segundo fator (TOTP)', () => {
  test('ativa com QR code, exige o código no login, aceita recuperação e desativa', async ({ page }) => {
    test.skip(!credenciais.senha, 'ADMIN_PASSWORD não definida');
    test.setTimeout(120_000);
    let recuperacao: string[] = [];
    try {
      await entrarComSenha(page);
      await expect(page).toHaveURL(/\/$/);

      // ativação: QR code, segredo, confirmação do primeiro código e códigos de recuperação
      await page.getByRole('link', { name: 'Configurações' }).click();
      const secao = page.locator('m2m-totp-section');
      await secao.getByRole('button', { name: 'Ativar o segundo fator' }).click();
      await expect(secao.getByRole('img', { name: 'QR code do segundo fator' })).toBeVisible();
      const segredo = (await secao.locator('code').innerText()).trim();
      expect(segredo).toMatch(/^[A-Z2-7]{32}$/);
      await secao.getByLabel('Código de 6 dígitos').fill('000000');
      await secao.getByRole('button', { name: 'Confirmar e ativar' }).click();
      await expect(secao.getByRole('alert')).toContainText('Código inválido');
      await secao.getByLabel('Código de 6 dígitos').fill(totp(segredo));
      await secao.getByRole('button', { name: 'Confirmar e ativar' }).click();
      await expect(secao.getByText('Guarde estes 10 códigos de recuperação')).toBeVisible();
      recuperacao = await secao.locator('.codigos li').allInnerTexts();
      expect(recuperacao).toHaveLength(10);
      await secao.getByRole('button', { name: 'Guardei os códigos' }).click();
      await expect(secao.getByText('Segundo fator ativo. Códigos de recuperação restantes: 10.')).toBeVisible();

      // login em duas etapas: a senha sozinha não libera; código errado recusado; TOTP do passo seguinte libera
      await page.getByRole('button', { name: new RegExp(credenciais.usuario) }).click();
      await page.getByRole('menuitem', { name: 'Sair' }).click();
      await entrarComSenha(page);
      await expect(page.getByText('Informe o código de 6 dígitos')).toBeVisible();
      expect((await page.request.get('/api/me')).status()).toBe(401);
      await page.getByLabel('Código', { exact: true }).fill('000000');
      await page.getByRole('button', { name: 'Verificar' }).click();
      await expect(page.getByRole('alert')).toContainText('Código inválido');
      await page.getByLabel('Código', { exact: true }).fill(totp(segredo, 1));
      await page.getByRole('button', { name: 'Verificar' }).click();
      await expect(page).toHaveURL(/\/$/);
      expect((await page.request.get('/api/me')).status()).toBe(200);

      // código de recuperação: vale uma vez
      await page.getByRole('button', { name: new RegExp(credenciais.usuario) }).click();
      await page.getByRole('menuitem', { name: 'Sair' }).click();
      await entrarComSenha(page);
      await page.getByLabel('Código', { exact: true }).fill(recuperacao[0]);
      await page.getByRole('button', { name: 'Verificar' }).click();
      await expect(page).toHaveURL(/\/$/);
      await page.getByRole('button', { name: new RegExp(credenciais.usuario) }).click();
      await page.getByRole('menuitem', { name: 'Sair' }).click();
      await entrarComSenha(page);
      await page.getByLabel('Código', { exact: true }).fill(recuperacao[0]);
      await page.getByRole('button', { name: 'Verificar' }).click();
      await expect(page.getByRole('alert')).toContainText('Código inválido');
    } finally {
      // sempre deixa a conta como estava: sem segundo fator (usa um código de recuperação que ainda vale)
      if (recuperacao.length) {
        await entrarComSenha(page);
        await page.getByLabel('Código', { exact: true }).waitFor({ timeout: 5000 }).catch(() => undefined);
        if (await page.getByLabel('Código', { exact: true }).isVisible()) {
          await page.getByLabel('Código', { exact: true }).fill(recuperacao[1]);
          await page.getByRole('button', { name: 'Verificar' }).click();
          await expect(page).toHaveURL(/\/$/);
        }
        await page.goto('/configuracoes');
        const secao = page.locator('m2m-totp-section');
        if (await secao.getByRole('button', { name: 'Desativar o segundo fator' }).isVisible()) {
          await secao.getByLabel('Senha atual').fill(credenciais.senha);
          await secao.getByLabel('Código do aplicativo ou de recuperação').fill(recuperacao[2]);
          await secao.getByRole('button', { name: 'Desativar o segundo fator' }).click();
          await expect(secao.getByRole('button', { name: 'Ativar o segundo fator' })).toBeVisible();
        }
      }
    }
    // com a conta de volta ao normal, a senha basta
    await page.getByRole('button', { name: new RegExp(credenciais.usuario) }).click();
    await page.getByRole('menuitem', { name: 'Sair' }).click();
    await entrarComSenha(page);
    await expect(page).toHaveURL(/\/$/);
  });
});
