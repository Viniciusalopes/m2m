import { computed, inject, Injectable, signal } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  FormGroup,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import {
  Erros,
  Estado,
  Importado,
  Respostas,
  ResumoConfiguracao,
  Segredos,
  TipoCertificado,
} from './modelo';

/** Etapas do quiz de instalação, na ordem da spec (Início fica fora do stepper). */
export const ETAPAS = [
  'containers',
  'seguranca',
  'usuarios',
  'rede',
  'maquinas',
  'logs',
  'revisao',
] as const;
export type Etapa = (typeof ETAPAS)[number];

/** Etapas com padrões seguros; Usuários nunca pode ser pulada. */
export const PULAVEIS: ReadonlySet<Etapa> = new Set([
  'containers',
  'seguranca',
  'rede',
  'maquinas',
  'logs',
]);

/** Etapas validadas pelo instalador (portas livres, diretórios, arquivos). */
export const VALIDADAS: ReadonlySet<Etapa> = new Set([
  'containers',
  'seguranca',
  'usuarios',
  'rede',
  'maquinas',
  'logs',
]);

function senhasIguais(g: AbstractControl): ValidationErrors | null {
  const senha = g.get('senhaAdmin')?.value;
  const conf = g.get('confirmacao')?.value;
  return senha && conf && senha !== conf ? { diferentes: true } : null;
}

export interface Importacao {
  m2m: string;
  criadoEm: string;
  semSegredos: boolean;
  /** Configuração do m2m do arquivo de reinstalação, restaurada ao fim da instalação. */
  configuracao: ResumoConfiguracao | null;
}

/** Respostas do quiz em formulários reativos: voltar a uma etapa nunca perde o que foi preenchido. */
@Injectable({ providedIn: 'root' })
export class Quiz {
  private fb = inject(FormBuilder).nonNullable;

  readonly estado = signal<Estado | null>(null);
  readonly importacao = signal<Importacao | null>(null);
  readonly confirmadas = signal<ReadonlySet<Etapa>>(new Set());
  readonly puladas = signal<ReadonlySet<Etapa>>(new Set());
  /** Valores importados que não servem nesta máquina, por etapa. */
  readonly problemas = signal<Record<string, Erros>>({});

  readonly containers = this.fb.group({
    diretorio: ['', Validators.required],
    portaHttp: [80, [Validators.required, Validators.min(1), Validators.max(65535)]],
    portaHttps: [443, [Validators.required, Validators.min(1), Validators.max(65535)]],
    fuso: ['', Validators.required],
  });

  readonly seguranca = this.fb.group({
    chaveInformada: [false],
    chaveMestra: ['', [Validators.required, Validators.minLength(16)]],
    senhaBanco: ['', Validators.required],
    senhaFila: ['', Validators.required],
    guardei: [false, Validators.requiredTrue],
  });

  readonly usuarios = this.fb.group(
    {
      usuario: ['admin', [Validators.required, Validators.pattern(/^[A-Za-z0-9._-]{3,50}$/)]],
      email: ['', Validators.email],
      senhaAdmin: ['', [Validators.required, Validators.minLength(12)]],
      confirmacao: ['', Validators.required],
    },
    { validators: senhasIguais },
  );

  readonly rede = this.fb.group({
    endereco: ['', Validators.required],
    certificado: ['ca-local' as TipoCertificado],
    certificadoArquivo: [''],
    chaveArquivo: [''],
  });

  readonly maquinas = this.fb.group({
    localAtivo: [true],
    nome: ['', Validators.required],
    diretorioHost: ['/home', Validators.required],
    raizes: ['/home', Validators.required],
  });

  readonly logs = this.fb.group({
    portaGrafana: [3000, [Validators.required, Validators.min(1), Validators.max(65535)]],
    acessoRede: [false],
    retencaoDias: [7, [Validators.required, Validators.min(1), Validators.max(365)]],
    senhaGrafana: ['', Validators.required],
  });

  readonly importado = computed(() => this.importacao() !== null);

  constructor() {
    // sem acesso LOCAL nada do host é montado: os campos ficam desabilitados e fora da validação
    this.maquinas.controls.localAtivo.valueChanges.subscribe((ativo) =>
      this.sincronizarLocal(ativo),
    );
    // o certificado próprio exige os dois caminhos
    this.rede.controls.certificado.valueChanges.subscribe((tipo) =>
      this.sincronizarCertificado(tipo),
    );
  }

  private sincronizarLocal(ativo: boolean): void {
    const { nome, diretorioHost, raizes } = this.maquinas.controls;
    for (const c of [nome, diretorioHost, raizes]) {
      if (ativo) {
        c.enable({ emitEvent: false });
      } else {
        c.disable({ emitEvent: false });
      }
    }
  }

  private sincronizarCertificado(tipo: TipoCertificado): void {
    for (const c of [this.rede.controls.certificadoArquivo, this.rede.controls.chaveArquivo]) {
      c.setValidators(tipo === 'proprio' ? Validators.required : null);
      c.updateValueAndValidity({ emitEvent: false });
    }
  }

  /** Formulário de cada etapa validada. */
  grupo(etapa: Etapa): FormGroup | null {
    switch (etapa) {
      case 'containers':
        return this.containers;
      case 'seguranca':
        return this.seguranca;
      case 'usuarios':
        return this.usuarios;
      case 'rede':
        return this.rede;
      case 'maquinas':
        return this.maquinas;
      case 'logs':
        return this.logs;
      default:
        return null;
    }
  }

  /** Começa do zero: padrões da máquina e segredos gerados pelo instalador. */
  iniciar(e: Estado): void {
    this.estado.set(e);
    this.importacao.set(null);
    this.problemas.set({});
    this.confirmadas.set(new Set());
    this.puladas.set(new Set());
    for (const etapa of PULAVEIS) {
      this.aplicarPadroes(etapa);
    }
    this.usuarios.reset({
      usuario: e.padroes.usuarios.usuario,
      email: '',
      senhaAdmin: '',
      confirmacao: '',
    });
  }

  /** Volta a etapa aos padrões (usado ao pular). */
  aplicarPadroes(etapa: Etapa): void {
    const e = this.estado();
    if (!e) {
      return;
    }
    const p = e.padroes;
    switch (etapa) {
      case 'containers':
        this.containers.reset(p.containers);
        break;
      case 'seguranca':
        this.seguranca.reset({
          chaveInformada: false,
          chaveMestra: e.segredos.chaveMestra,
          senhaBanco: e.segredos.senhaBanco,
          senhaFila: e.segredos.senhaFila,
          guardei: false,
        });
        break;
      case 'rede':
        this.rede.reset(p.rede);
        break;
      case 'maquinas':
        this.maquinas.reset({ ...p.maquinas, raizes: p.maquinas.raizes.join(', ') });
        break;
      case 'logs':
        this.logs.reset({ ...p.logs, senhaGrafana: e.segredos.senhaGrafana });
        break;
    }
  }

  podePular(etapa: Etapa): boolean {
    return PULAVEIS.has(etapa) && !this.importado();
  }

  /** Pula a etapa: aplica os padrões e a marca como pulada (a Revisão mostra os padrões). */
  pular(etapa: Etapa): void {
    if (!this.podePular(etapa)) {
      return;
    }
    this.aplicarPadroes(etapa);
    if (etapa === 'seguranca') {
      this.seguranca.controls.guardei.setValue(true);
    }
    this.puladas.update((s) => new Set(s).add(etapa));
    this.confirmar(etapa);
  }

  confirmar(etapa: Etapa): void {
    this.confirmadas.update((s) => new Set(s).add(etapa));
  }

  /** Alterar uma etapa pulada deixa de contar como pulada. */
  editar(etapa: Etapa): void {
    this.puladas.update((s) => {
      const n = new Set(s);
      n.delete(etapa);
      return n;
    });
  }

  respostas(): Respostas {
    const c = this.containers.getRawValue();
    const u = this.usuarios.getRawValue();
    const m = this.maquinas.getRawValue();
    const l = this.logs.getRawValue();
    return {
      containers: { ...c, portaHttp: Number(c.portaHttp), portaHttps: Number(c.portaHttps) },
      usuarios: { usuario: u.usuario.trim(), email: u.email.trim() },
      rede: this.rede.getRawValue(),
      maquinas: {
        localAtivo: m.localAtivo,
        nome: m.nome.trim(),
        diretorioHost: m.diretorioHost.trim(),
        raizes: m.raizes
          .split(',')
          .map((r) => r.trim())
          .filter((r) => r !== ''),
      },
      logs: {
        portaGrafana: Number(l.portaGrafana),
        acessoRede: l.acessoRede,
        retencaoDias: Number(l.retencaoDias),
      },
    };
  }

  segredos(): Segredos {
    const s = this.seguranca.getRawValue();
    return {
      chaveMestra: s.chaveMestra.trim(),
      senhaAdmin: this.usuarios.controls.senhaAdmin.value,
      senhaBanco: s.senhaBanco,
      senhaFila: s.senhaFila,
      senhaGrafana: this.logs.controls.senhaGrafana.value,
    };
  }

  /**
   * Preenche todas as etapas com o arquivo importado. Nenhuma etapa fica confirmada: o usuário percorre cada uma.
   * Sem os segredos (senha errada), ficam os gerados e a senha do administrador em branco.
   */
  preencher(imp: Importado): void {
    const e = this.estado();
    const r = imp.respostas;
    const s = imp.segredos;
    this.confirmadas.set(new Set());
    this.puladas.set(new Set());
    // no modo clone, o diretório é o do clone, não o do arquivo importado
    this.containers.reset(e?.clone ? { ...r.containers, diretorio: e.clone } : r.containers);
    this.seguranca.reset({
      chaveInformada: s !== null,
      chaveMestra: s?.chaveMestra ?? e?.segredos.chaveMestra ?? '',
      senhaBanco: s?.senhaBanco ?? e?.segredos.senhaBanco ?? '',
      senhaFila: s?.senhaFila ?? e?.segredos.senhaFila ?? '',
      guardei: false,
    });
    this.usuarios.reset({
      usuario: r.usuarios.usuario,
      email: r.usuarios.email,
      senhaAdmin: s?.senhaAdmin ?? '',
      confirmacao: s?.senhaAdmin ?? '',
    });
    this.rede.reset(r.rede);
    this.maquinas.reset({ ...r.maquinas, raizes: (r.maquinas.raizes ?? []).join(', ') });
    this.logs.reset({ ...r.logs, senhaGrafana: s?.senhaGrafana ?? e?.segredos.senhaGrafana ?? '' });
    this.importacao.set({
      m2m: imp.m2m,
      criadoEm: imp.criadoEm,
      semSegredos: s === null,
      configuracao: imp.configuracao ?? null,
    });
    this.problemas.set(imp.problemas ?? {});
    for (const [etapa, erros] of Object.entries(imp.problemas ?? {})) {
      this.aplicarErros(etapa as Etapa, erros);
    }
  }

  /** Mostra nos campos os erros vindos do instalador; somem quando o valor muda. */
  aplicarErros(etapa: Etapa, erros: Erros): void {
    const g = this.grupo(etapa);
    if (!g) {
      return;
    }
    for (const [campo, msg] of Object.entries(erros)) {
      const ctl = g.get(campo);
      if (ctl) {
        ctl.setErrors({ ...(ctl.errors ?? {}), servidor: msg });
        ctl.markAsTouched();
      }
    }
  }
}

/** A remoção só é liberada com a palavra exata. */
export function confirmacaoValida(digitado: string, palavra: string): boolean {
  return digitado.trim() === palavra;
}
