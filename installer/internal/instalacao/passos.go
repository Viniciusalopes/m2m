package instalacao

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"m2m/installer/internal/arquivo"
	"m2m/installer/internal/comandos"
	"m2m/installer/internal/dotenv"
	"m2m/installer/internal/executor"
	"m2m/installer/internal/respostas"
)

// Estados de um passo.
const (
	Aguardando = "aguardando"
	Executando = "executando"
	Concluido  = "concluido"
	Falhou     = "falhou"
)

const maxLog = 400

// Evento vai para o quiz pelo SSE.
type Evento struct {
	Tipo   string `json:"tipo"` // passo, log, fim
	Passo  string `json:"passo,omitempty"`
	Estado string `json:"estado,omitempty"`
	Linha  string `json:"linha,omitempty"`
	Erro   string `json:"erro,omitempty"`
}

// Passo é um dos passos de "Iniciar m2m".
type Passo struct {
	ID     string   `json:"id"`
	Titulo string   `json:"titulo"`
	Estado string   `json:"estado"`
	Erro   string   `json:"erro,omitempty"`
	Log    []string `json:"log"`
}

// Resultado é o que a tela de conclusão mostra.
type Resultado struct {
	URL         string             `json:"url"`
	Usuario     string             `json:"usuario"`
	Diretorio   string             `json:"diretorio"`
	URLGrafana  string             `json:"urlGrafana"`
	Comandos    []comandos.Comando `json:"comandos"`
	Avisos      []string           `json:"avisos"`
	Certificado string             `json:"certificado"`
}

// Situacao é o retrato da instalação para o quiz (também ao reconectar).
type Situacao struct {
	Passos    []Passo    `json:"passos"`
	Rodando   bool       `json:"rodando"`
	Resultado *Resultado `json:"resultado"`
}

// Config reúne o que a instalação usa; os testes trocam o executor, o código e a verificação final.
type Config struct {
	Home   string
	Versao string
	Exec   executor.Executor
	Codigo func() ([]byte, error)
	Clone  bool // modo clone: o diretório de instalação é um clone do repositório e nada é extraído
	// Configuracao vem do arquivo de reinstalação importado: restaurada e reindexada depois de o m2m responder.
	Configuracao *arquivo.Configuracao
	// SenhaArquivo é a senha do arquivo importado, que também abre a configuração (fica só em memória).
	SenhaArquivo string
	Ambiente     comandos.Ambiente
	Respostas    respostas.Respostas
	Segredos     respostas.Segredos
	Emitir       func(Evento)
	// Responde confere o /healthz do m2m; nil usa HTTPS e HTTP em 127.0.0.1.
	Responde  func(ctx context.Context) error
	Prazo     time.Duration // espera pelos containers; padrão 15 min
	Intervalo time.Duration // entre consultas; padrão 5 s
	Agora     func() time.Time
}

// Instalacao executa os passos um de cada vez e retoma a partir do que falhou.
type Instalacao struct {
	mu        sync.Mutex
	cfg       Config
	passos    []*Passo
	rodando   bool
	resultado *Resultado
	caCriada  bool
	nss       bool
	avisos    []string
}

// Nova prepara a instalação com os seis passos do design (decisão 7).
func Nova(cfg Config) *Instalacao {
	if cfg.Prazo == 0 {
		cfg.Prazo = 15 * time.Minute
	}
	if cfg.Intervalo == 0 {
		cfg.Intervalo = 5 * time.Second
	}
	if cfg.Agora == nil {
		cfg.Agora = time.Now
	}
	if cfg.Emitir == nil {
		cfg.Emitir = func(Evento) {}
	}
	if cfg.Responde == nil {
		cfg.Responde = respondeLocal(cfg.Respostas.Containers.PortaHTTPS, cfg.Respostas.Containers.PortaHTTP)
	}
	i := &Instalacao{cfg: cfg}
	codigo := "Extrair o código do m2m"
	if cfg.Clone {
		codigo = "Usar o código do clone"
	}
	for _, p := range [][2]string{
		{"codigo", codigo},
		{"env", "Gravar o .env"},
		{"certificado", "Preparar o certificado HTTPS"},
		{"build", "Construir as imagens"},
		{"subir", "Subir os containers"},
		{"aguardar", "Aguardar o m2m responder"},
	} {
		i.passos = append(i.passos, &Passo{ID: p[0], Titulo: p[1], Estado: Aguardando})
	}
	if cfg.Configuracao != nil {
		i.passos = append(i.passos,
			&Passo{ID: "configuracao", Titulo: "Restaurar a configuração do m2m", Estado: Aguardando},
			&Passo{ID: "reindexar", Titulo: "Reindexar os backups", Estado: Aguardando})
	}
	return i
}

// Situacao devolve uma cópia do estado atual.
func (i *Instalacao) Situacao() Situacao {
	i.mu.Lock()
	defer i.mu.Unlock()
	s := Situacao{Rodando: i.rodando, Resultado: i.resultado}
	for _, p := range i.passos {
		c := *p
		c.Log = slices.Clone(p.Log)
		s.Passos = append(s.Passos, c)
	}
	return s
}

// ErrEmAndamento é devolvido ao iniciar uma instalação que já está rodando ou concluída.
var ErrEmAndamento = errors.New("a instalação já está em andamento ou concluída")

// Iniciar roda, em segundo plano, os passos a partir do primeiro não concluído.
func (i *Instalacao) Iniciar(ctx context.Context) error {
	i.mu.Lock()
	if i.rodando || i.resultado != nil {
		i.mu.Unlock()
		return ErrEmAndamento
	}
	i.rodando = true
	i.mu.Unlock()
	go i.executar(ctx)
	return nil
}

func (i *Instalacao) executar(ctx context.Context) {
	defer func() {
		i.mu.Lock()
		i.rodando = false
		i.mu.Unlock()
	}()
	acoes := map[string]func(context.Context, *Passo) error{
		"codigo": i.extrair, "env": i.gravarEnv, "certificado": i.certificado,
		"build": i.build, "subir": i.subir, "aguardar": i.aguardar,
		"configuracao": i.restaurarConfiguracao, "reindexar": i.reindexar,
	}
	for _, p := range i.passos {
		if p.Estado == Concluido {
			continue
		}
		i.estado(p, Executando, "")
		if err := acoes[p.ID](ctx, p); err != nil {
			i.estado(p, Falhou, err.Error())
			i.cfg.Emitir(Evento{Tipo: "fim", Erro: err.Error()})
			return
		}
		i.estado(p, Concluido, "")
	}
	res, err := i.concluir()
	if err != nil {
		i.cfg.Emitir(Evento{Tipo: "fim", Erro: err.Error()})
		return
	}
	i.mu.Lock()
	i.resultado = res
	i.mu.Unlock()
	i.cfg.Emitir(Evento{Tipo: "fim"})
}

func (i *Instalacao) estado(p *Passo, estado, erro string) {
	i.mu.Lock()
	p.Estado, p.Erro = estado, erro
	if estado == Executando {
		p.Log = nil
	}
	i.mu.Unlock()
	i.cfg.Emitir(Evento{Tipo: "passo", Passo: p.ID, Estado: estado, Erro: erro})
}

func (i *Instalacao) log(p *Passo, linha string) {
	i.mu.Lock()
	p.Log = append(p.Log, linha)
	if len(p.Log) > maxLog {
		p.Log = p.Log[len(p.Log)-maxLog:]
	}
	i.mu.Unlock()
	i.cfg.Emitir(Evento{Tipo: "log", Passo: p.ID, Linha: linha})
}

func (i *Instalacao) dir() string {
	return filepath.Clean(i.cfg.Respostas.Containers.Diretorio)
}

func (i *Instalacao) extrair(_ context.Context, p *Passo) error {
	dir := i.dir()
	if i.cfg.Clone {
		if msg := ValidarClone(dir); msg != "" {
			return errors.New(msg)
		}
		i.log(p, "Usando o código do clone em "+dir+"; nada é extraído.")
		return nil
	}
	if msg := DiretorioLivre(dir); msg != "" {
		return errors.New(msg)
	}
	tgz, err := i.cfg.Codigo()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(dir, Marcador), []byte("instalação em andamento\n"), 0o644); err != nil {
		return err
	}
	if err := Extrair(tgz, dir); err != nil {
		return err
	}
	i.log(p, "Código do m2m "+i.cfg.Versao+" extraído em "+dir)
	return nil
}

func (i *Instalacao) gravarEnv(_ context.Context, p *Passo) error {
	modelo, err := os.ReadFile(filepath.Join(i.dir(), ".env.example"))
	if err != nil {
		return fmt.Errorf("modelo .env.example ausente no código: %w", err)
	}
	pares := append(respostas.Env(i.cfg.Respostas, i.cfg.Segredos), dotenv.Par{Chave: "M2M_VERSION", Valor: i.cfg.Versao})
	conteudo := dotenv.Gerar(modelo, pares)
	destino := filepath.Join(i.dir(), ".env")
	if err := os.WriteFile(destino, conteudo, 0o600); err != nil {
		return err
	}
	if err := os.Chmod(destino, 0o600); err != nil { // WriteFile não muda o modo de um arquivo que já existia
		return err
	}
	i.log(p, destino+" gravado, legível só pelo dono (0600)")
	return nil
}

func (i *Instalacao) certificado(ctx context.Context, p *Passo) error {
	switch i.cfg.Respostas.Rede.Certificado {
	case respostas.CertificadoProprio:
		i.log(p, "Usando o certificado informado: "+i.cfg.Respostas.Rede.CertificadoArquivo)
		return nil
	case respostas.CertificadoAutoassinado:
		i.log(p, "O m2m vai gerar um certificado autoassinado ao subir; o navegador mostrará um aviso.")
		return nil
	}
	caCrt := filepath.Join(DirTLS(i.cfg.Home), "ca.crt")
	_, errAntes := os.Stat(caCrt)
	err := i.cfg.Exec.Transmitir(ctx, executor.Comando{
		Programa: "sh", Args: []string{"scripts/gerar-certificado-tls.sh", "--sem-sistema"}, Dir: i.dir(),
		Env: []string{"M2M_ENV_FILE=" + filepath.Join(i.dir(), ".env")},
	}, func(l string) { i.log(p, l) })
	if err != nil {
		return err
	}
	if errAntes != nil {
		i.caCriada = true
	}
	ok, err := RegistrarNSS(ctx, i.cfg.Exec, i.cfg.Home, caCrt)
	switch {
	case err != nil:
		i.aviso("Não foi possível registrar a CA no Chrome: " + err.Error())
	case ok:
		i.nss = true
		i.log(p, "CA local registrada no Chrome/Chromium deste usuário (feche e reabra o navegador).")
	default:
		i.aviso("O certutil não está instalado, então o Chrome ainda não confia no certificado. Instale o libnss3-tools e rode sh " +
			filepath.Join(i.dir(), "scripts", "gerar-certificado-tls.sh") + ": ele registra a CA no sistema e no Chrome.")
	}
	return nil
}

func (i *Instalacao) aviso(msg string) {
	i.mu.Lock()
	i.avisos = append(i.avisos, msg)
	i.mu.Unlock()
}

func (i *Instalacao) compose(args ...string) executor.Comando {
	return executor.Comando{
		Programa: "docker",
		Args:     append([]string{"compose", "-f", "docker-compose.yml", "--env-file", ".env"}, args...),
		Dir:      i.dir(),
		Env:      []string{"BUILDKIT_PROGRESS=plain"},
	}
}

func (i *Instalacao) build(ctx context.Context, p *Passo) error {
	return i.cfg.Exec.Transmitir(ctx, i.compose("build"), func(l string) { i.log(p, l) })
}

func (i *Instalacao) subir(ctx context.Context, p *Passo) error {
	return i.cfg.Exec.Transmitir(ctx, i.compose("up", "-d"), func(l string) { i.log(p, l) })
}

// servico é uma linha de "docker compose ps --format json".
type servico struct {
	Service  string `json:"Service"`
	State    string `json:"State"`
	Health   string `json:"Health"`
	ExitCode int    `json:"ExitCode"`
}

// unicos são os serviços que rodam uma vez e terminam.
var unicos = map[string]bool{"m2m-db-migrate": true, "m2m-loki-init": true}

// lerPS aceita as duas saídas do Compose: um JSON por linha (atual) ou uma lista (versões antigas).
func lerPS(b []byte) ([]servico, error) {
	t := strings.TrimSpace(string(b))
	var ss []servico
	if strings.HasPrefix(t, "[") {
		err := json.Unmarshal([]byte(t), &ss)
		return ss, err
	}
	for _, l := range linhas([]byte(t)) {
		var s servico
		if err := json.Unmarshal([]byte(l), &s); err != nil {
			return nil, err
		}
		ss = append(ss, s)
	}
	return ss, nil
}

// avaliar devolve se todos estão prontos, o serviço que falhou (se algum) e um resumo.
func avaliar(esperados []string, ss []servico) (bool, string, string) {
	por := map[string]servico{}
	for _, s := range ss {
		por[s.Service] = s
	}
	pronto := true
	var resumo []string
	for _, nome := range esperados {
		s, ok := por[nome]
		switch {
		case !ok:
			pronto = false
			resumo = append(resumo, nome+": não criado")
		case unicos[nome]:
			if s.State == "exited" && s.ExitCode != 0 {
				return false, nome, ""
			}
			if s.State != "exited" {
				pronto = false
			}
			resumo = append(resumo, nome+": "+s.State)
		case s.State == "exited" || s.State == "dead":
			return false, nome, ""
		default:
			if s.State != "running" || (s.Health != "" && s.Health != "healthy") {
				pronto = false
			}
			estado := s.State
			if s.Health != "" {
				estado += " (" + s.Health + ")"
			}
			resumo = append(resumo, nome+": "+estado)
		}
	}
	return pronto, "", strings.Join(resumo, ", ")
}

func (i *Instalacao) aguardar(ctx context.Context, p *Passo) error {
	out, err := i.cfg.Exec.Rodar(ctx, i.compose("config", "--services"))
	if err != nil {
		return err
	}
	esperados := linhas(out)
	limite := i.cfg.Agora().Add(i.cfg.Prazo)
	ultimo, ultimoErro := "", ""
	for {
		out, err := i.cfg.Exec.Rodar(ctx, i.compose("ps", "-a", "--format", "json"))
		if err != nil {
			return err
		}
		ss, err := lerPS(out)
		if err != nil {
			return fmt.Errorf("saída inesperada do docker compose ps: %w", err)
		}
		pronto, falhou, resumo := avaliar(esperados, ss)
		if falhou != "" {
			if logs, err := i.cfg.Exec.Rodar(ctx, i.compose("logs", "--no-color", "--tail", "60", falhou)); err == nil {
				for _, l := range linhas(logs) {
					i.log(p, l)
				}
			}
			return fmt.Errorf("o serviço %s parou com erro; veja o log acima", falhou)
		}
		if resumo != ultimo {
			i.log(p, resumo)
			ultimo = resumo
		}
		if pronto {
			if err := i.cfg.Responde(ctx); err == nil {
				i.log(p, "O m2m respondeu em "+respostas.URLPublica(i.cfg.Respostas))
				return nil
			} else if err.Error() != ultimoErro {
				ultimoErro = err.Error()
				i.log(p, "Containers prontos; aguardando o /healthz: "+ultimoErro)
			}
		}
		if i.cfg.Agora().After(limite) {
			return fmt.Errorf("o m2m não ficou pronto em %s; confira o log acima e tente de novo", i.cfg.Prazo)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(i.cfg.Intervalo):
		}
	}
}

// pedidoBack monta o corpo das rotas internas do back: token da chave mestra e o administrador da instalação.
func (i *Instalacao) pedidoBack(campos map[string]any) map[string]any {
	campos["token"] = TokenInterno(i.cfg.Segredos.ChaveMestra)
	campos["usuario"] = i.cfg.Respostas.Usuarios.Usuario
	return campos
}

// restaurarConfiguracao importa no m2m a configuração do arquivo de reinstalação (máquinas e perfis casados pelo nome:
// repetir não duplica nada).
func (i *Instalacao) restaurarConfiguracao(ctx context.Context, p *Passo) error {
	out, err := ChamarBack(ctx, i.cfg.Exec, "/config/import", i.pedidoBack(map[string]any{
		"senha": i.cfg.SenhaArquivo, "conteudo": string(i.cfg.Configuracao.Bruta),
	}))
	if err != nil {
		return err
	}
	var r struct {
		MaquinasCriadas      []string `json:"machinesCreate"`
		MaquinasSubstituidas []string `json:"machinesReplace"`
		PerfisCriados        []string `json:"profilesCreate"`
		PerfisSubstituidos   []string `json:"profilesReplace"`
	}
	if err := json.Unmarshal(out, &r); err != nil {
		return fmt.Errorf("resposta inesperada do m2m na importação: %w", err)
	}
	i.log(p, fmt.Sprintf("Máquinas: %d criada(s), %d atualizada(s). Perfis: %d criado(s), %d atualizado(s).",
		len(r.MaquinasCriadas), len(r.MaquinasSubstituidas), len(r.PerfisCriados), len(r.PerfisSubstituidos)))
	return nil
}

// reindexar devolve ao catálogo os backups que ficaram no disco, diretório por diretório; a falha num não impede os
// demais, e "Tentar de novo" repete todos (o que já está no catálogo é ignorado pelo m2m).
func (i *Instalacao) reindexar(ctx context.Context, p *Passo) error {
	c := i.cfg.Configuracao
	local := strings.TrimSpace(i.cfg.Respostas.Maquinas.Nome)
	var falhas []string
	for _, a := range c.Armazenamentos {
		maquina := a.Maquina
		if c.MaquinaLocal != "" && maquina == c.MaquinaLocal && i.cfg.Respostas.Maquinas.LocalAtivo && local != "" {
			maquina = local // a LOCAL desta instalação pode ter outro nome
		}
		out, err := ChamarBack(ctx, i.cfg.Exec, "/reindex", i.pedidoBack(map[string]any{
			"maquina": maquina, "diretorio": a.Diretorio,
		}))
		var r struct {
			Status    string `json:"status"`
			Resultado struct {
				Criados    int `json:"created"`
				Conhecidos int `json:"alreadyKnown"`
			} `json:"resultado"`
		}
		if err == nil {
			err = json.Unmarshal(out, &r)
		}
		if err == nil && r.Status != "COMPLETED" && r.Status != "COMPLETED_WITH_WARNINGS" {
			err = fmt.Errorf("a reindexação terminou como %s", r.Status)
		}
		if err != nil {
			i.log(p, fmt.Sprintf("%s:%s não pôde ser lido: %v", maquina, a.Diretorio, err))
			falhas = append(falhas, maquina+":"+a.Diretorio)
			continue
		}
		i.log(p, fmt.Sprintf("%s:%s: %d backup(s) de volta ao catálogo, %d já conhecido(s)", maquina, a.Diretorio,
			r.Resultado.Criados, r.Resultado.Conhecidos))
	}
	if len(falhas) > 0 {
		return fmt.Errorf("não foi possível reindexar %s; confira se as máquinas estão ligadas e tente de novo",
			strings.Join(falhas, ", "))
	}
	return nil
}

// respondeLocal confere o /healthz do front em 127.0.0.1, por HTTPS e, se falhar, por HTTP.
func respondeLocal(https, httpPorta int) func(context.Context) error {
	cliente := &http.Client{
		Timeout: 5 * time.Second,
		// o certificado é emitido para o endereço público, não para 127.0.0.1 visto daqui
		Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}},
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	return func(ctx context.Context) error {
		var ultimo error
		for _, u := range []string{"https://127.0.0.1:" + strconv.Itoa(https) + "/healthz", "http://127.0.0.1:" + strconv.Itoa(httpPorta) + "/healthz"} {
			req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
			if err != nil {
				return err
			}
			resp, err := cliente.Do(req)
			if err != nil {
				ultimo = err
				continue
			}
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				return nil
			}
			ultimo = fmt.Errorf("%s respondeu %d", u, resp.StatusCode)
		}
		return ultimo
	}
}

func (i *Instalacao) concluir() (*Resultado, error) {
	r := i.cfg.Respostas
	origem := OrigemExecutavel
	if i.cfg.Clone {
		origem = OrigemClone
	}
	reg := Registro{
		M2M: i.cfg.Versao, Diretorio: i.dir(), Origem: origem, CriadoEm: i.cfg.Agora().Format(time.RFC3339),
		Certificado: r.Rede.Certificado, CACriada: i.caCriada, NSS: i.nss, Atalhos: []string{},
	}
	if r.Rede.Certificado == respostas.CertificadoCALocal {
		reg.TLSDir = DirTLS(i.cfg.Home)
	}
	if err := GravarRegistro(i.cfg.Home, reg); err != nil {
		return nil, fmt.Errorf("não foi possível gravar o registro da instalação: %w", err)
	}
	os.Remove(filepath.Join(i.dir(), Marcador))
	res := &Resultado{
		URL: respostas.URLPublica(r), Usuario: r.Usuarios.Usuario, Diretorio: i.dir(),
		Certificado: r.Rede.Certificado, Comandos: []comandos.Comando{},
	}
	host := "127.0.0.1"
	if r.Logs.AcessoRede {
		host = r.Rede.Endereco
	}
	res.URLGrafana = "https://" + host + ":" + strconv.Itoa(r.Logs.PortaGrafana)
	if r.Rede.Certificado == respostas.CertificadoCALocal {
		res.Comandos = append(res.Comandos, i.cfg.Ambiente.ConfiarCA(filepath.Join(DirTLS(i.cfg.Home), "ca.crt")))
	}
	i.mu.Lock()
	res.Avisos = append([]string{}, i.avisos...)
	i.mu.Unlock()
	return res, nil
}

// CriarAtalhos grava os atalhos m2m-up e m2m-down e anota os arquivos no registro.
func CriarAtalhos(home string) ([]string, error) {
	reg, err := LerRegistro(home)
	if err != nil || reg == nil {
		return nil, errors.New("registro da instalação não encontrado")
	}
	arquivos, err := AdicionarAtalhos(home, reg.Diretorio, reg.Certificado == respostas.CertificadoCALocal)
	for _, a := range arquivos {
		if !slices.Contains(reg.Atalhos, a) {
			reg.Atalhos = append(reg.Atalhos, a)
		}
	}
	if errReg := GravarRegistro(home, *reg); err == nil {
		err = errReg
	}
	return arquivos, err
}
