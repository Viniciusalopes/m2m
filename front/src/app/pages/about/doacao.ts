/** Forma de doação lida de assets/doacao.json: um link (abre em nova aba) ou um valor para copiar (Pix, texto). */
export interface FormaDoacao {
  tipo: 'link' | 'pix' | 'texto';
  nome: string;
  descricao: string;
  valor: string;
}

const TIPOS = ['link', 'pix', 'texto'];

function texto(v: unknown): v is string {
  return typeof v === 'string' && v.trim() !== '';
}

/**
 * Valida o conteúdo de doacao.json e devolve só as formas bem formadas. Um arquivo inválido vira lista vazia: a tela
 * Sobre mostra apenas o convite. Links só com https, para não abrir esquemas como javascript:.
 */
export function formasDoacao(conteudo: unknown): FormaDoacao[] {
  const formas = (conteudo as { formas?: unknown } | null)?.formas;
  if (!Array.isArray(formas)) {
    return [];
  }
  return formas
    .filter((f): f is FormaDoacao => {
      if (!f || !TIPOS.includes(f.tipo) || !texto(f.nome) || !texto(f.valor)) {
        return false;
      }
      if (f.descricao !== undefined && typeof f.descricao !== 'string') {
        return false;
      }
      return f.tipo !== 'link' || /^https:\/\/\S+$/.test(f.valor);
    })
    .map((f) => ({ ...f, descricao: f.descricao ?? '' }));
}
