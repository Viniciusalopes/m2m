import { expect, test, Page } from '@playwright/test';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { credenciais } from '../playwright.config';

// Precisa do compose de teste (servidor SFTP em container): E2E_SERVERS=1
const comServidores = process.env.E2E_SERVERS === '1';
const sshHost = process.env.E2E_SSH_HOST ?? 'm2m-test-ssh';
const SANDBOX = '/home/usuario/m2m-sandbox';

async function entrar(page: Page) {
  await page.goto('/login');
  await page.getByLabel('Usuário').fill(credenciais.usuario);
  await page.getByLabel('Senha', { exact: true }).fill(credenciais.senha);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page).toHaveURL(/\/$/);
}

test.describe('transferência pela interface', () => {
  const id = Date.now();
  const nome = `E2E-TRANSF-${id}`;
  const raiz = `${SANDBOX}/e2e-transf-${id}`;

  test.beforeAll(() => {
    mkdirSync(`${raiz}/origem/sub`, { recursive: true });
    writeFileSync(`${raiz}/origem/a.txt`, 'conteúdo A');
    writeFileSync(`${raiz}/origem/.oculto`, 'oculto');
    writeFileSync(`${raiz}/origem/sub/b.txt`, 'conteúdo B');
  });

  test.afterAll(() => rmSync(raiz, { recursive: true, force: true }));

  test('painéis, envio, conflito, operações de arquivo, comparação e filtro', async ({ page }) => {
    test.skip(!credenciais.senha, 'ADMIN_PASSWORD não definida');
    test.skip(!comServidores, 'requer o compose de teste (E2E_SERVERS=1)');
    test.setTimeout(150_000);
    await entrar(page);

    // máquina SFTP de teste: OpenSSH completo, com a home /config gravável
    await page.getByRole('link', { name: 'Máquinas' }).click();
    await page.getByRole('button', { name: 'Nova máquina' }).click();
    // o formulário termina de carregar com a chave pública; preencher antes disso perdia valores
    await expect(page.getByText('Chave pública do m2m')).toBeVisible();
    await page.getByLabel('Nome', { exact: true }).fill(nome);
    await page.getByLabel('Host (IP ou nome DNS)').fill(sshHost);
    await expect(page.getByLabel('Host (IP ou nome DNS)')).toHaveValue(sshHost);
    await page.getByLabel('Porta').fill('2222');
    await page.getByLabel('Usuário', { exact: true }).fill('m2m');
    await page.getByRole('combobox', { name: 'Autenticação' }).click();
    await page.getByRole('option', { name: 'Senha', exact: true }).click();
    await page.getByLabel('Senha', { exact: true }).fill('m2m-teste');
    await page.getByRole('button', { name: 'Salvar' }).click();
    const linha = page.getByRole('row', { name: new RegExp(nome) });
    await linha.getByRole('button', { name: 'Testar conexão' }).click();
    await page.getByRole('button', { name: 'Confirmar esta identidade' }).click();
    await page.getByRole('button', { name: 'Fechar' }).click();

    // tela de transferência: esquerdo = NOTEBOOK (sandbox), direito = servidor de teste (/config)
    await page.getByRole('link', { name: 'Transferência' }).click();
    await expect(page.getByRole('heading', { name: 'Transferência', level: 1 })).toBeVisible();
    const esquerdo = page.locator('m2m-transfer-pane').nth(0);
    const direito = page.locator('m2m-transfer-pane').nth(1);
    await direito.getByRole('combobox').click({ force: true });
    await page.getByRole('option', { name: nome }).click();
    const caminhoEsq = esquerdo.getByLabel('Caminho');
    await caminhoEsq.fill(`${raiz}/origem`);
    await caminhoEsq.press('Enter');
    const caminhoDir = direito.getByLabel('Caminho');
    await caminhoDir.fill('/config');
    await caminhoDir.press('Enter');
    await expect(esquerdo.getByRole('row', { name: /a\.txt/ })).toBeVisible();

    // pasta exclusiva da execução no servidor (o volume do servidor persiste entre execuções)
    const pasta = `e2e-${id}`;
    const dialogo = page.getByRole('dialog');
    await direito.locator('.rolagem').click({ button: 'right', position: { x: 40, y: 150 } });
    await page.getByRole('menuitem', { name: 'Criar pasta' }).click();
    await dialogo.getByLabel('Nome da pasta').fill(pasta);
    await dialogo.getByRole('button', { name: 'Confirmar' }).click();
    await direito.getByRole('row', { name: new RegExp(pasta) }).dblclick();
    await expect(caminhoDir).toHaveValue(`/config/${pasta}`);

    // ocultos aparecem por padrão e podem ser escondidos
    await expect(esquerdo.getByRole('row', { name: /\.oculto/ })).toBeVisible();
    await page.getByRole('switch', { name: 'Mostrar arquivos ocultos' }).click({ force: true });
    await expect(esquerdo.getByRole('row', { name: /\.oculto/ })).toHaveCount(0);
    await page.getByRole('switch', { name: 'Mostrar arquivos ocultos' }).click({ force: true });

    // enviar pasta e arquivo para a direita
    await esquerdo.getByRole('row', { name: /a\.txt/ }).click();
    await page.getByRole('button', { name: 'Enviar a seleção do painel esquerdo para o direito' }).click();
    const fila = page.locator('m2m-transfer-queue');
    await expect(fila.getByText('Concluída').first()).toBeVisible({ timeout: 30_000 });
    await expect(direito.getByRole('row', { name: /a\.txt/ })).toBeVisible({ timeout: 15_000 });

    // conflito: o mesmo arquivo de novo → diálogo, "pular" aplicado a todos
    await esquerdo.getByRole('row', { name: /a\.txt/ }).click();
    await page.getByRole('button', { name: 'Enviar a seleção do painel esquerdo para o direito' }).click();
    await expect(dialogo.getByRole('heading', { name: 'O item já existe no destino' })).toBeVisible();
    await dialogo.getByRole('radio', { name: 'Pular' }).click({ force: true });
    await dialogo.getByRole('checkbox', { name: /Aplicar a todos/ }).click({ force: true });
    await dialogo.getByRole('button', { name: 'Confirmar' }).click();
    await expect(fila.getByText('Pulada').first()).toBeVisible({ timeout: 30_000 });

    // criar pasta pelo menu de contexto, comparar e excluir (a confirmação informa a contagem)
    await expect(caminhoDir).toHaveValue(`/config/${pasta}`);
    await direito.locator('.rolagem').click({ button: 'right', position: { x: 40, y: 150 } });
    await page.getByRole('menuitem', { name: 'Criar pasta' }).click();
    await dialogo.getByLabel('Nome da pasta').fill('pasta-nova');
    await dialogo.getByRole('button', { name: 'Confirmar' }).click();
    await expect(direito.getByRole('row', { name: /pasta-nova/ })).toBeVisible();

    // confirma o estado do switch e repete o clique se ele se perder (flaky quando a legenda desloca o layout)
    const comparar = page.getByRole('switch', { name: 'Comparar pastas' });
    await comparar.click({ force: true });
    await expect(comparar).toHaveAttribute('aria-checked', 'true');
    await expect(page.getByLabel('Legenda da comparação')).toBeVisible();
    await expect(direito.getByRole('row', { name: /pasta-nova/ })).toContainText('Só neste lado');
    await expect(async () => {
      if ((await comparar.getAttribute('aria-checked')) === 'true') await comparar.click({ force: true });
      await expect(comparar).toHaveAttribute('aria-checked', 'false', { timeout: 1000 });
    }).toPass({ timeout: 15_000 });
    await expect(page.getByLabel('Legenda da comparação')).toHaveCount(0);

    await direito.getByRole('row', { name: /pasta-nova/ }).click();
    await direito.getByRole('row', { name: /pasta-nova/ }).click({ button: 'right' });
    await page.getByRole('menuitem', { name: 'Excluir' }).click();
    await expect(dialogo.getByText('1 item(ns) serão excluídos')).toBeVisible();
    await dialogo.getByRole('button', { name: 'Excluir' }).click();
    await expect(direito.getByRole('row', { name: /pasta-nova/ })).toHaveCount(0);

    // filtro: oculta "sub" e informa quantos itens ficaram escondidos
    await esquerdo.getByRole('button', { name: 'Filtros' }).click();
    await dialogo.getByRole('button', { name: 'Adicionar condição' }).click();
    await dialogo.getByLabel('Valor').fill('sub');
    await dialogo.getByRole('button', { name: 'Aplicar' }).click();
    await expect(esquerdo.getByRole('status')).toContainText('Filtro ativo · 1 item(ns) oculto(s)');
    await expect(esquerdo.getByRole('row', { name: /sub/ })).toHaveCount(0);

    // limpeza: apaga a pasta da execução e remove a máquina de teste
    await direito.getByRole('button', { name: 'Subir um nível' }).click();
    await direito.getByRole('row', { name: new RegExp(pasta) }).click();
    await direito.getByRole('row', { name: new RegExp(pasta) }).click({ button: 'right' });
    await page.getByRole('menuitem', { name: 'Excluir' }).click();
    await dialogo.getByRole('button', { name: 'Excluir' }).click();
    await expect(direito.getByRole('row', { name: new RegExp(pasta) })).toHaveCount(0);

    await page.getByRole('link', { name: 'Máquinas' }).click();
    await page.getByRole('row', { name: new RegExp(nome) }).getByRole('button', { name: 'Excluir' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Excluir' }).click();
    await expect(page.getByRole('row', { name: new RegExp(nome) })).toHaveCount(0);
  });
});
