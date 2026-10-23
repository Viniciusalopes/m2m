// Gera as sobrescritas em rem dos tamanhos do Angular Material (escala compacta, issue reduzir-proporcoes).
//
// O Material define seus tamanhos em px, em tokens (var(--mat-x, 56px)) e em declarações fixas. Com
// html { font-size: 90% } só o que está em rem escala; este script lê o CSS dos componentes que o app
// realmente usa (no build de produção em dist/) e escreve, com cada px ÷ 16 em rem:
//   src/theme/_escala.scss           tokens --mat-* em html (mixin tokens)
//   src/theme/_escala-material.scss  regras com `html` na frente para vencer as do componente
// Bordas e contornos de até 2 px não são escalados.
//
// Uso (depois de uma atualização do Angular Material ou de um componente novo no app):
//   npm run build && npm run escala
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(raiz, 'dist/front/browser');
const MIN_PX = 2; // acima disso converte
// Listas (menu lateral): a regra-base do MDC (ex.: margin-right 32px) é superada por uma regra do Material com token
// (--mat-list-…-end-space, 16px) de mais especificidade; com o prefixo `html` a base voltaria a vencer. Os tokens já escalam.
const IGNORAR = /\.(mdc-list|mat-mdc-list|mat-mdc-nav-list)/;

const PX = /(?<![\w.#-])(-?\d*\.?\d+)px\b/g;
const rem = (px) => `${(Number(px) / 16).toFixed(4).replace(/\.?0+$/, '')}rem`;
const converte = (v) => v.replace(PX, (m, n) => (Math.abs(Number(n)) <= MIN_PX ? m : rem(n)));
const temPx = (v) => [...v.matchAll(PX)].some((m) => Math.abs(Number(m[1])) > MIN_PX);
const semNs = (s) => s.replaceAll('%NS%', '');

const fontes = readdirSync(dist).filter((f) => f.endsWith('.js'));
const tokens = new Map(); // --mat-x -> valor original em px
const fixas = new Map(); // "selector\0prop" -> valor

/** true se a posição está dentro de um @media/@supports/@container (essas regras não são globais). */
function dentroDeAtRule(s, pos) {
  let prof = 0;
  for (let i = pos - 1; i >= 0 && pos - i < 4000; i--) {
    const c = s[i];
    if (c === '}') prof++;
    else if (c === '{') {
      if (prof === 0) {
        const ini = Math.max(
          s.lastIndexOf('}', i - 1),
          s.lastIndexOf(';', i - 1),
          s.lastIndexOf('{', i - 1),
        );
        return /^\s*@(media|supports|container|layer)/.test(s.slice(ini + 1, i));
      }
      prof--;
    }
  }
  return false;
}

/** Divide o seletor em vírgulas de nível 0 (ignora as de :not(a, b)). */
function partes(sel) {
  const out = [];
  let prof = 0;
  let atual = '';
  for (const c of sel) {
    if (c === '(' || c === '[') prof++;
    if (c === ')' || c === ']') prof--;
    if (c === ',' && prof === 0) {
      out.push(atual.trim());
      atual = '';
    } else atual += c;
  }
  out.push(atual.trim());
  return out.filter(Boolean);
}

for (const f of fontes) {
  const s = readFileSync(join(dist, f), 'utf8');
  // Tokens: var(--mat-x, <fallback com px>) em qualquer propriedade
  for (const m of s.matchAll(/var\(--(?:%NS%)?(mat-[a-z0-9-]+),\s*([^()]*?)\)/g)) {
    if (temPx(m[2]) && !tokens.has(m[1])) tokens.set(m[1], m[2].trim());
  }
  // Declarações fixas em regras de componentes do Material
  // O seletor inteiro vai até a { anterior/aspas/;: cortá-lo no meio perderia prefixos como [dir=rtl]
  for (const m of s.matchAll(/([^{};`"']+)\{([^{}]*)\}/g)) {
    const sel = semNs(m[1].trim());
    if (!/\.(mat|mdc|cdk)-/.test(sel) || sel.length > 600 || sel.startsWith('@')) continue;
    if (/\[dir=["']?rtl/.test(sel) || sel.includes('html') || IGNORAR.test(sel)) continue;
    if (dentroDeAtRule(s, m.index + m[1].length)) continue;
    for (const d of m[2].split(';')) {
      const i = d.indexOf(':');
      if (i < 0) continue;
      const prop = d.slice(0, i).trim();
      const val = semNs(d.slice(i + 1).trim());
      if (prop.startsWith('--') || prop === 'stroke-width' || prop === 'box-shadow') continue;
      // o que sobra depois de tirar var(--x, fallback) já é tratado pelos tokens
      const resto = val.replace(/var\(--[^(),]+,\s*[^()]*\)/g, '');
      if (temPx(resto)) fixas.set(`${sel}\0${prop}`, val);
    }
  }
}

const cabecalho = (
  corpo,
) => `// GERADO por scripts/gerar-escala-material.mjs (npm run escala); não edite à mão.
// ${corpo}
`;

// --- tokens ---
const linhasTokens = [...tokens]
  .filter(([, v]) => temPx(v))
  .sort(([a], [b]) => a.localeCompare(b))
  .map(([k, v]) => `  --${k}: ${converte(v)};`);
for (const [k, px] of Object.entries({
  'mat-sys-corner-extra-small': 4,
  'mat-sys-corner-small': 8,
  'mat-sys-corner-medium': 12,
  'mat-sys-corner-large': 16,
  'mat-sys-corner-extra-large': 28,
})) {
  linhasTokens.push(`  --${k}: ${rem(px)};`);
}
writeFileSync(
  join(raiz, 'src/theme/_escala.scss'),
  `${cabecalho('Tokens de tamanho do Angular Material em rem (px ÷ 16): acompanham html { font-size: 90% } de styles.scss e o zoom do navegador.\n// Incluído em styles.scss, dentro do html e depois do mat.theme, para prevalecer sobre o tema.')}
@mixin tokens {
${linhasTokens.join('\n')}
}
`,
);

// --- regras fixas ---
const porSeletor = new Map();
for (const [chave, val] of fixas) {
  const [sel, prop] = chave.split('\0');
  const decl = `${prop}: ${converte(val)};`;
  if (!porSeletor.has(sel)) porSeletor.set(sel, []);
  porSeletor.get(sel).push(decl);
}
const regras = [...porSeletor]
  .sort(([a], [b]) => a.localeCompare(b))
  .map(
    ([sel, decls]) =>
      `${partes(sel)
        .map((p) => `html ${p}`)
        .join(',\n')} {\n  ${decls.join('\n  ')}\n}`,
  )
  .join('\n\n');
writeFileSync(
  join(raiz, 'src/theme/_escala-material.scss'),
  `${cabecalho('Medidas fixas em px dos componentes do Material convertidas para rem. O prefixo `html` na frente do seletor vence a regra\n// original do componente (mesma ordem de grandeza, um elemento a mais) sem precisar de !important.')}
${regras}
`,
);
console.log(
  `${linhasTokens.length} tokens, ${fixas.size} declarações em ${porSeletor.size} seletores`,
);
