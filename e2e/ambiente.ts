import { homedir } from 'node:os';
import { posix } from 'node:path';

// Valores do ambiente testado, por variáveis de ambiente, para o e2e não depender de uma máquina específica.

/** Sandbox no host onde os testes gravam; só as pastas filhas "e2e-*" são apagadas. Precisa ficar abaixo de /home. */
export const SANDBOX = process.env.E2E_SANDBOX ?? posix.join(homedir(), 'm2m-sandbox');

/** Partes do caminho do sandbox abaixo de /home, como aparecem no seletor de diretórios do m2m. */
export const PARTES_SANDBOX = posix.relative('/home', SANDBOX).split('/');

/** Nome da máquina LOCAL da instalação testada (M2M_LOCAL_NAME). */
export const MAQUINA_LOCAL = process.env.E2E_MAQUINA_LOCAL ?? 'LOCAL';

/** Máquina real opcional, só lida: nome e "host:porta" como aparecem na tela Máquinas. Sem as variáveis, o teste é pulado. */
export const MAQUINA_REAL = process.env.E2E_MAQUINA_REAL_NOME
  ? { nome: process.env.E2E_MAQUINA_REAL_NOME, endereco: process.env.E2E_MAQUINA_REAL_ENDERECO ?? '' }
  : null;

/** Escapa um texto para uso literal numa expressão regular. */
export function literal(texto: string): string {
  return texto.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
