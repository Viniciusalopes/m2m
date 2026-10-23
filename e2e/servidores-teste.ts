import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';

const raiz = resolve(__dirname, '..');
const servicos = ['m2m-test-sftp', 'm2m-test-ssh', 'm2m-test-ftp', 'm2m-test-ftps', 'm2m-test-smtp'];
// O volume do OpenSSH acumulava pastas de execuções anteriores e quebrava a listagem do painel de transferência
const volumes = ['m2m_m2m-test-ssh-data'];
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
  if (!comServidores) return;
  docker('rm', '-sfv', ...servicos);
  execFileSync('docker', ['volume', 'rm', '-f', ...volumes], { cwd: raiz, stdio: 'inherit' });
}
