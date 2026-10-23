import { defineConfig } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/** Lê uma variável do ambiente ou do .env da raiz (nunca versionado). */
function env(nome: string, padrao = ''): string {
  if (process.env[nome]) return process.env[nome]!;
  try {
    const linha = readFileSync(resolve(__dirname, '../.env'), 'utf8')
      .split('\n')
      .find((l) => l.startsWith(nome + '='));
    return linha ? linha.slice(nome.length + 1).trim() : padrao;
  } catch {
    return padrao;
  }
}

export const credenciais = {
  usuario: env('ADMIN_USERNAME', 'admin'),
  senha: env('ADMIN_PASSWORD'),
};

export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  globalSetup: './global-setup.ts',
  globalTeardown: './global-teardown.ts',
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'https://localhost',
    ignoreHTTPSErrors: true, // certificado autoassinado do compose
    locale: 'pt-BR',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
