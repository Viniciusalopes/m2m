// Tipos da API do instalador (installer/internal/servidor); os nomes seguem o JSON do Go.

export interface Containers {
  diretorio: string;
  portaHttp: number;
  portaHttps: number;
  fuso: string;
}

export interface Usuarios {
  usuario: string;
  email: string;
}

export type TipoCertificado = 'ca-local' | 'proprio' | 'autoassinado';

export interface Rede {
  endereco: string;
  certificado: TipoCertificado;
  certificadoArquivo: string;
  chaveArquivo: string;
}

export interface Maquinas {
  localAtivo: boolean;
  nome: string;
  diretorioHost: string;
  raizes: string[];
}

export interface Logs {
  portaGrafana: number;
  acessoRede: boolean;
  retencaoDias: number;
}

export interface Respostas {
  containers: Containers;
  usuarios: Usuarios;
  rede: Rede;
  maquinas: Maquinas;
  logs: Logs;
}

export interface Segredos {
  chaveMestra: string;
  senhaAdmin: string;
  senhaBanco: string;
  senhaFila: string;
  senhaGrafana: string;
}

/** Erros por campo de uma etapa: campo → mensagem. */
export type Erros = Record<string, string>;

export interface Comando {
  id: string;
  titulo: string;
  oQueFaz: string;
  paraQueServe: string;
  texto: string;
  privilegio: boolean;
}

export type EstadoItem = 'atendido' | 'pendente' | 'opcional';

export interface ItemPrerequisito {
  id: string;
  titulo: string;
  estado: EstadoItem;
  detalhe: string;
  essencial: boolean;
  comandos: Comando[];
}

export interface Verificacao {
  itens: ItemPrerequisito[];
  bloqueia: boolean;
}

export interface Container {
  nome: string;
  estado: string;
  diretorio: string;
}

export interface Registro {
  m2m: string;
  diretorio: string;
  criadoEm: string;
}

export interface Existente {
  registro: Registro | null;
  diretorio: string;
  containers: Container[] | null;
  volumes: string[] | null;
}

export type EstadoPasso = 'aguardando' | 'executando' | 'concluido' | 'falhou';

export interface Passo {
  id: string;
  titulo: string;
  estado: EstadoPasso;
  erro?: string;
  log: string[] | null;
}

export interface Resultado {
  url: string;
  usuario: string;
  diretorio: string;
  urlGrafana: string;
  comandos: Comando[];
  avisos: string[];
  certificado: TipoCertificado;
}

export interface Situacao {
  passos: Passo[];
  rodando: boolean;
  resultado: Resultado | null;
}

export interface Estado {
  versao: string;
  so: {
    id: string;
    versao: string;
    codinome: string;
    nome: string;
    familia: string;
    codinomeUbuntu: string;
  };
  testado: boolean;
  arquitetura: string;
  usuario: string;
  home: string;
  hostname: string;
  enderecos: string[];
  padroes: Respostas;
  segredos: Segredos;
  existente: Existente;
  encontrada: boolean;
  bloqueia: boolean;
  minSenha: number;
  minExportar: number;
  confirmacao: string;
  /** Modo clone: caminho do clone, que é o diretório de instalação fixo; vazio no executável. */
  clone: string;
  instalacao: Situacao | null;
}

export interface Importado {
  respostas: Respostas;
  segredos: Segredos | null;
  senhaErrada: boolean;
  m2m: string;
  criadoEm: string;
  problemas: Record<string, Erros>;
  /** Só no arquivo de reinstalação, com a senha certa: o que será restaurado ao fim da instalação. */
  configuracao: ResumoConfiguracao | null;
}

export interface ResumoConfiguracao {
  maquinas: number;
  perfis: number;
  maquinaLocal?: string;
  armazenamentos: { maquina: string; diretorio: string }[];
}

/** Evento do SSE de progresso. */
export interface Evento {
  tipo: 'passo' | 'log' | 'fim' | 'item';
  passo?: string;
  estado?: EstadoPasso;
  linha?: string;
  erro?: string;
}

export interface ItemDesinstalacao {
  id: string;
  titulo: string;
  descricao: string;
  padrao: boolean;
  sempre: boolean;
  aviso?: string;
  /** Mostrado com o item desmarcado. */
  avisoFica?: string;
}

export interface Destino {
  perfil: string;
  maquina: string;
  protocolo: string;
  host: string;
  diretorio: string;
  noHost?: string;
}

export interface Localizada {
  encontrada: boolean;
  diretorio: string;
  versao: string;
  criadoEm: string;
  containers: Container[] | null;
  volumes: string[] | null;
  temEnv: boolean;
  /** Com o back no ar, o arquivo de reinstalação leva também a configuração do m2m. */
  backNoAr: boolean;
  itens: ItemDesinstalacao[];
  destinos: { consultados: boolean; erro?: string; itens: Destino[] };
}

export interface ResultadoItem {
  id: string;
  estado: EstadoPasso;
  erro?: string;
}

export interface ResultadoDesinstalacao {
  itens: ResultadoItem[];
  comandos: Comando[];
  opcionais: Comando[];
}
