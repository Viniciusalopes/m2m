import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';

const raiz = resolve(__dirname, '..');
const servicos = ['m2m-test-sftp', 'm2m-test-ssh', 'm2m-test-ftp', 'm2m-test-ftps', 'm2m-test-smtp'];
const compose = ['compose', '-f', 'docker-compose.yml', '-f', 'docker-compose.test.yml', '--env-file', '.env'];

/** Só com E2E_SERVERS=1: os servidores de teste ficam fora do stack e vivem apenas durante o e2e. */
export const comServidores = process.env.E2E_SERVERS === '1';

function docker(...args: string[]) {
  execFileSync('docker', [...compose, ...args], { cwd: raiz, stdio: 'inherit' });
}

export function subirServidores() {
  if (comServidores) docker('up', '-d', '--wait', '--no-deps', ...servicos);
}

export function derrubarServidores() {
  if (comServidores) docker('rm', '-sfv', ...servicos);
}
