import { TestBed } from '@angular/core/testing';
import { Estado, Importado, Situacao } from './modelo';
import { aplicarEvento } from './progresso/progresso';
import { confirmacaoValida, ETAPAS, Quiz } from './quiz';

function estado(): Estado {
  return {
    versao: 'v1.0.0',
    so: {
      id: 'ubuntu',
      versao: '24.04',
      codinome: 'noble',
      nome: 'Ubuntu 24.04',
      familia: 'ubuntu',
      codinomeUbuntu: 'noble',
    },
    testado: true,
    arquitetura: 'x86_64',
    usuario: 'ana',
    home: '/home/ana',
    hostname: 'notebook',
    enderecos: ['192.168.0.10', 'notebook'],
    padroes: {
      containers: {
        diretorio: '/home/ana/m2m',
        portaHttp: 80,
        portaHttps: 443,
        fuso: 'America/Sao_Paulo',
      },
      usuarios: { usuario: 'admin', email: '' },
      rede: {
        endereco: '192.168.0.10',
        certificado: 'ca-local',
        certificadoArquivo: '',
        chaveArquivo: '',
      },
      maquinas: { localAtivo: true, nome: 'NOTEBOOK', diretorioHost: '/home', raizes: ['/home'] },
      logs: { portaGrafana: 3000, acessoRede: false, retencaoDias: 7 },
    },
    segredos: {
      chaveMestra: 'chave-gerada-0123456789abcdef',
      senhaAdmin: '',
      senhaBanco: 'banco-gerada',
      senhaFila: 'fila-gerada',
      senhaGrafana: 'grafana-gerada',
    },
    existente: { registro: null, diretorio: '', containers: null, volumes: null },
    encontrada: false,
    bloqueia: false,
    minSenha: 12,
    minExportar: 12,
    confirmacao: 'DESINSTALAR',
    clone: '',
    instalacao: null,
  };
}

function importado(comSegredos: boolean): Importado {
  const e = estado();
  return {
    respostas: {
      ...e.padroes,
      containers: { ...e.padroes.containers, diretorio: '/srv/m2m', portaHttps: 8443 },
      maquinas: {
        ...e.padroes.maquinas,
        nome: 'ANTIGA',
        diretorioHost: '/dados',
        raizes: ['/home', '/home/fotos'],
      },
    },
    segredos: comSegredos
      ? {
          chaveMestra: 'chave-antiga-0123456789abcdef',
          senhaAdmin: 'Senha-Antiga-123',
          senhaBanco: 'banco-antigo',
          senhaFila: 'fila-antiga',
          senhaGrafana: 'grafana-antiga',
        }
      : null,
    senhaErrada: !comSegredos,
    m2m: 'v0.9.0',
    criadoEm: '2026-01-10T10:00:00Z',
    problemas: { maquinas: { diretorioHost: 'O diretório /dados não existe nesta máquina.' } },
    configuracao: null,
  };
}

describe('Quiz', () => {
  let quiz: Quiz;

  beforeEach(() => {
    quiz = TestBed.inject(Quiz);
    quiz.iniciar(estado());
  });

  it('começa com os padrões e os segredos gerados', () => {
    const r = quiz.respostas();
    expect(r.containers.diretorio).toBe('/home/ana/m2m');
    expect(r.maquinas.raizes).toEqual(['/home']);
    expect(quiz.segredos().chaveMestra).toBe('chave-gerada-0123456789abcdef');
    expect(quiz.segredos().senhaAdmin).toBe('');
  });

  it('voltar a uma etapa não perde as respostas das outras', () => {
    quiz.containers.controls.portaHttps.setValue(8443);
    quiz.rede.controls.endereco.setValue('m2m.lan');
    quiz.confirmar('containers');
    quiz.confirmar('rede');
    // "voltar" para Containers e alterar o fuso não mexe na Rede nem desfaz a confirmação
    quiz.containers.controls.fuso.setValue('America/Manaus');
    const r = quiz.respostas();
    expect(r.containers.portaHttps).toBe(8443);
    expect(r.containers.fuso).toBe('America/Manaus');
    expect(r.rede.endereco).toBe('m2m.lan');
    expect(quiz.confirmadas().has('rede')).toBe(true);
  });

  it('Pré-requisitos e Usuários não podem ser pulados', () => {
    expect(quiz.podePular('prerequisitos')).toBe(false);
    expect(quiz.podePular('usuarios')).toBe(false);
    quiz.pular('usuarios');
    expect(quiz.confirmadas().has('usuarios')).toBe(false);
    expect(quiz.usuarios.valid).toBe(false); // sem a senha do administrador
    for (const etapa of ['containers', 'seguranca', 'rede', 'maquinas', 'logs'] as const) {
      expect(quiz.podePular(etapa)).toBe(true);
    }
  });

  it('pular aplica os padrões e marca a etapa', () => {
    quiz.logs.controls.portaGrafana.setValue(4000);
    quiz.logs.controls.acessoRede.setValue(true);
    quiz.pular('logs');
    expect(quiz.respostas().logs).toEqual({
      portaGrafana: 3000,
      acessoRede: false,
      retencaoDias: 7,
    });
    expect(quiz.puladas().has('logs')).toBe(true);
    expect(quiz.confirmadas().has('logs')).toBe(true);
    quiz.editar('logs');
    expect(quiz.puladas().has('logs')).toBe(false);
  });

  it('pular a Segurança mantém a chave gerada', () => {
    quiz.pular('seguranca');
    expect(quiz.seguranca.valid).toBe(true);
    expect(quiz.segredos().chaveMestra).toBe('chave-gerada-0123456789abcdef');
  });

  it('a Segurança exige confirmar que a chave foi guardada', () => {
    expect(quiz.seguranca.valid).toBe(false);
    quiz.seguranca.controls.guardei.setValue(true);
    expect(quiz.seguranca.valid).toBe(true);
  });

  it('sem acesso LOCAL os campos do host ficam fora da validação', () => {
    quiz.maquinas.controls.diretorioHost.setValue('');
    expect(quiz.maquinas.valid).toBe(false);
    quiz.maquinas.controls.localAtivo.setValue(false);
    expect(quiz.maquinas.valid).toBe(true);
    expect(quiz.maquinas.controls.nome.disabled).toBe(true);
    expect(quiz.respostas().maquinas.localAtivo).toBe(false);
  });

  it('certificado próprio exige os dois arquivos', () => {
    quiz.rede.controls.certificado.setValue('proprio');
    expect(quiz.rede.valid).toBe(false);
    quiz.rede.controls.certificadoArquivo.setValue('/etc/ssl/m2m.crt');
    quiz.rede.controls.chaveArquivo.setValue('/etc/ssl/m2m.key');
    expect(quiz.rede.valid).toBe(true);
  });

  it('a importação preenche todas as etapas e exige confirmar cada uma', () => {
    quiz.confirmar('containers');
    quiz.preencher(importado(true));
    const r = quiz.respostas();
    expect(r.containers.diretorio).toBe('/srv/m2m');
    expect(r.containers.portaHttps).toBe(8443);
    expect(r.maquinas.raizes).toEqual(['/home', '/home/fotos']);
    expect(quiz.segredos()).toEqual({
      chaveMestra: 'chave-antiga-0123456789abcdef',
      senhaAdmin: 'Senha-Antiga-123',
      senhaBanco: 'banco-antigo',
      senhaFila: 'fila-antiga',
      senhaGrafana: 'grafana-antiga',
    });
    expect(quiz.usuarios.controls.confirmacao.value).toBe('Senha-Antiga-123');
    expect(quiz.confirmadas().size).toBe(0);
    for (const etapa of ETAPAS) {
      expect(quiz.podePular(etapa)).toBe(false);
    }
  });

  it('destaca os valores importados que não servem nesta máquina', () => {
    quiz.preencher(importado(true));
    expect(quiz.problemas()['maquinas']).toBeTruthy();
    expect(quiz.maquinas.controls.diretorioHost.getError('servidor')).toContain('/dados');
    quiz.maquinas.controls.diretorioHost.setValue('/home');
    expect(quiz.maquinas.controls.diretorioHost.getError('servidor')).toBeNull();
  });

  it('importação com senha errada segue com os segredos gerados e sem a senha do administrador', () => {
    quiz.preencher(importado(false));
    expect(quiz.importacao()?.semSegredos).toBe(true);
    expect(quiz.segredos().chaveMestra).toBe('chave-gerada-0123456789abcdef');
    expect(quiz.segredos().senhaAdmin).toBe('');
    expect(quiz.respostas().containers.diretorio).toBe('/srv/m2m');
  });

  it('guarda o resumo da configuração do arquivo de reinstalação', () => {
    const imp = {
      ...importado(true),
      configuracao: {
        maquinas: 2,
        perfis: 3,
        armazenamentos: [{ maquina: 'NAS', diretorio: '/b' }],
      },
    };
    quiz.preencher(imp);
    expect(quiz.importacao()?.configuracao?.perfis).toBe(3);
    quiz.iniciar(estado());
    expect(quiz.importacao()).toBeNull();
  });

  it('no modo clone, a importação mantém o diretório do clone', () => {
    const e = estado();
    quiz.iniciar({
      ...e,
      clone: '/home/ana/m2m-clone',
      padroes: {
        ...e.padroes,
        containers: { ...e.padroes.containers, diretorio: '/home/ana/m2m-clone' },
      },
    });
    expect(quiz.respostas().containers.diretorio).toBe('/home/ana/m2m-clone');
    quiz.preencher(importado(true));
    expect(quiz.respostas().containers.diretorio).toBe('/home/ana/m2m-clone');
    expect(quiz.respostas().containers.portaHttps).toBe(8443);
  });
});

describe('confirmação da desinstalação', () => {
  it('só aceita a palavra exata', () => {
    expect(confirmacaoValida('', 'DESINSTALAR')).toBe(false);
    expect(confirmacaoValida('desinstalar', 'DESINSTALAR')).toBe(false);
    expect(confirmacaoValida('DESINSTALA', 'DESINSTALAR')).toBe(false);
    expect(confirmacaoValida(' DESINSTALAR ', 'DESINSTALAR')).toBe(true);
  });
});

describe('aplicarEvento', () => {
  it('atualiza o passo e acumula o log', () => {
    let s: Situacao = {
      rodando: true,
      resultado: null,
      passos: [
        { id: 'build', titulo: 'Construir', estado: 'aguardando', log: null },
        { id: 'subir', titulo: 'Subir', estado: 'aguardando', log: null },
      ],
    };
    s = aplicarEvento(s, { tipo: 'passo', passo: 'build', estado: 'executando' });
    s = aplicarEvento(s, { tipo: 'log', passo: 'build', linha: '#1 DONE' });
    s = aplicarEvento(s, { tipo: 'passo', passo: 'build', estado: 'falhou', erro: 'sem rede' });
    expect(s.passos[0]).toEqual({
      id: 'build',
      titulo: 'Construir',
      estado: 'falhou',
      erro: 'sem rede',
      log: ['#1 DONE'],
    });
    expect(s.passos[1].estado).toBe('aguardando');
  });
});
