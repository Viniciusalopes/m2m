package servidor

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"m2m/installer/internal/arquivo"
	"m2m/installer/internal/desinstalacao"
	"m2m/installer/internal/instalacao"
	"m2m/installer/internal/respostas"
	"m2m/installer/internal/sistema"
)

// PalavraConfirmacao é o que o usuário digita para desinstalar.
const PalavraConfirmacao = "DESINSTALAR"

func (s *Servidor) rotas() {
	m := http.NewServeMux()
	m.HandleFunc("GET /api/estado", s.estado)
	m.HandleFunc("POST /api/prerequisitos", s.prerequisitos)
	m.HandleFunc("POST /api/validar", s.validar)
	m.HandleFunc("POST /api/importar", s.importar)
	m.HandleFunc("POST /api/exportar", s.exportar)
	m.HandleFunc("POST /api/instalar", s.instalar)
	m.HandleFunc("POST /api/instalar/retomar", s.retomar)
	m.HandleFunc("GET /api/instalacao", s.situacao)
	m.HandleFunc("POST /api/atalhos", s.atalhos)
	m.HandleFunc("GET /api/desinstalacao", s.desinstalacao)
	m.HandleFunc("POST /api/desinstalacao/exportar", s.exportarInstalada)
	m.HandleFunc("POST /api/desinstalar", s.desinstalar)
	m.HandleFunc("GET /api/desinstalacao/resultado", s.resultadoDesinstalacao)
	m.HandleFunc("GET /api/eventos", s.sse)
	m.HandleFunc("POST /api/encerrar", s.encerrarQuiz)
	desconhecida := func(w http.ResponseWriter, _ *http.Request) { erroJSON(w, http.StatusNotFound, "rota desconhecida") }
	m.HandleFunc("GET /api/", desconhecida)
	m.HandleFunc("POST /api/", desconhecida)
	m.HandleFunc("GET /", s.estatico)
	s.mux = m
}

func responder(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func erroJSON(w http.ResponseWriter, status int, msg string) {
	responder(w, status, map[string]string{"erro": msg})
}

// ler decodifica o corpo JSON (até 1 MiB).
func ler(w http.ResponseWriter, r *http.Request, v any) bool {
	dec := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := dec.Decode(v); err != nil {
		erroJSON(w, http.StatusBadRequest, "requisição inválida: "+err.Error())
		return false
	}
	return true
}

func (s *Servidor) padroes() respostas.Respostas {
	p := respostas.Padroes(s.cfg.Maquina)
	if s.cfg.Clone != "" {
		p.Containers.Diretorio = s.cfg.Clone
	}
	return p
}

func (s *Servidor) dirPadrao() string {
	if s.cfg.Clone != "" {
		return s.cfg.Clone
	}
	return filepath.Join(s.cfg.Home, "m2m")
}

// bloqueia diz se a instalação não pode seguir; no modo clone, o diretório não pode ser trocado, então um clone já
// instalado (com .env) também bloqueia.
func (s *Servidor) bloqueia(e instalacao.Existente) bool {
	return e.Bloqueia() || (s.cfg.Clone != "" && e.Diretorio == s.cfg.Clone)
}

func (s *Servidor) estado(w http.ResponseWriter, r *http.Request) {
	existente := instalacao.Detectar(r.Context(), s.cfg.Exec, s.cfg.Home, s.dirPadrao())
	responder(w, http.StatusOK, map[string]any{
		"versao":      s.cfg.Versao,
		"so":          s.cfg.SO,
		"testado":     s.cfg.SO.Testado(),
		"arquitetura": sistema.Maquina(),
		"usuario":     s.cfg.Usuario,
		"home":        s.cfg.Home,
		"hostname":    s.cfg.Maquina.Hostname,
		"enderecos":   s.cfg.Enderecos,
		"padroes":     s.padroes(),
		"segredos":    respostas.GerarSegredos(),
		"existente":   existente,
		"encontrada":  existente.Encontrada(),
		"bloqueia":    s.bloqueia(existente),
		"clone":       s.cfg.Clone,
		"minSenha":    respostas.MinSenhaAdmin,
		"minExportar": arquivo.MinSenha,
		"confirmacao": PalavraConfirmacao,
		"instalacao":  s.situacaoAtual(),
	})
}

func (s *Servidor) prerequisitos(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Diretorio string `json:"diretorio"`
		Portas    []int  `json:"portas"`
	}
	if !ler(w, r, &req) {
		return
	}
	if req.Diretorio == "" {
		req.Diretorio = s.dirPadrao()
	}
	if len(req.Portas) == 0 {
		req.Portas = []int{80, 443, 3000}
	}
	responder(w, http.StatusOK, s.cfg.Sonda.Verificar(r.Context(), req.Diretorio, req.Portas))
}

type corpoRespostas struct {
	Etapa     string              `json:"etapa"`
	Respostas respostas.Respostas `json:"respostas"`
	Segredos  respostas.Segredos  `json:"segredos"`
	// RestaurarConfiguracao pede para restaurar a configuração do arquivo de reinstalação importado.
	RestaurarConfiguracao bool `json:"restaurarConfiguracao"`
}

func (s *Servidor) validar(w http.ResponseWriter, r *http.Request) {
	var req corpoRespostas
	if !ler(w, r, &req) {
		return
	}
	if req.Etapa == "" {
		responder(w, http.StatusOK, map[string]any{"etapas": respostas.ValidarTudo(req.Respostas, req.Segredos, s.cfg.Verificar)})
		return
	}
	responder(w, http.StatusOK, map[string]any{"erros": respostas.Validar(req.Etapa, req.Respostas, req.Segredos, s.cfg.Verificar)})
}

func (s *Servidor) importar(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Conteudo string `json:"conteudo"`
		Senha    string `json:"senha"`
	}
	if !ler(w, r, &req) {
		return
	}
	imp, err := arquivo.Importar([]byte(req.Conteudo), req.Senha)
	var inv *arquivo.Invalido
	if errors.As(err, &inv) {
		erroJSON(w, http.StatusUnprocessableEntity, inv.Motivo)
		return
	}
	senhaErrada := errors.Is(err, arquivo.ErrSenhaErrada)
	if err != nil && !senhaErrada {
		erroJSON(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.mu.Lock()
	s.importada = nil
	if imp.Configuracao != nil {
		s.importada = &configuracaoImportada{cfg: imp.Configuracao, senha: req.Senha}
	}
	s.mu.Unlock()
	// problemas desta máquina (porta ocupada, diretório inexistente); sem os segredos, só as respostas contam
	seg := respostas.GerarSegredos()
	seg.SenhaAdmin = "senha-provisoria-valida"
	if imp.Segredos != nil {
		seg = *imp.Segredos
	}
	responder(w, http.StatusOK, map[string]any{
		"respostas":    imp.Respostas,
		"segredos":     imp.Segredos,
		"senhaErrada":  senhaErrada,
		"m2m":          imp.M2M,
		"criadoEm":     imp.CriadoEm,
		"configuracao": imp.Configuracao,
		"problemas":    respostas.ValidarTudo(imp.Respostas, seg, s.cfg.Verificar),
	})
}

func baixar(w http.ResponseWriter, nome string, conteudo []byte) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="`+nome+`"`)
	_, _ = w.Write(conteudo)
}

func (s *Servidor) nomeArquivo() string {
	return "m2m-instalacao-" + respostas.NomeMaquina(s.cfg.Maquina.Hostname) + "-" + s.cfg.Agora().Format("2006-01-02") + ".json"
}

// exportar monta o .json em memória e o envia como download; nada é gravado em disco.
func (s *Servidor) exportar(w http.ResponseWriter, r *http.Request) {
	var req struct {
		corpoRespostas
		Senha string `json:"senha"`
	}
	if !ler(w, r, &req) {
		return
	}
	b, err := arquivo.Exportar(req.Respostas, req.Segredos, s.cfg.Versao, req.Senha, s.cfg.Agora(), nil)
	if err != nil {
		erroJSON(w, http.StatusBadRequest, err.Error())
		return
	}
	baixar(w, s.nomeArquivo(), b)
}

func (s *Servidor) situacaoAtual() *instalacao.Situacao {
	s.mu.Lock()
	inst := s.inst
	s.mu.Unlock()
	if inst == nil {
		return nil
	}
	sit := inst.Situacao()
	return &sit
}

func (s *Servidor) instalar(w http.ResponseWriter, r *http.Request) {
	var req corpoRespostas
	if !ler(w, r, &req) {
		return
	}
	s.mu.Lock()
	if s.inst != nil {
		s.mu.Unlock()
		erroJSON(w, http.StatusConflict, "a instalação já foi iniciada; use Tentar de novo")
		return
	}
	s.mu.Unlock()
	if e := instalacao.Detectar(r.Context(), s.cfg.Exec, s.cfg.Home, s.dirPadrao()); s.bloqueia(e) {
		erroJSON(w, http.StatusConflict, "já existe uma instalação do m2m nesta máquina; desinstale-a antes")
		return
	}
	if s.cfg.Clone != "" {
		req.Respostas.Containers.Diretorio = s.cfg.Clone // o diretório é fixo no modo clone, venha o que vier do quiz
	}
	if erros := respostas.ValidarTudo(req.Respostas, req.Segredos, s.cfg.Verificar); len(erros) > 0 {
		responder(w, http.StatusBadRequest, map[string]any{"erro": "há campos inválidos", "etapas": erros})
		return
	}
	cfg := instalacao.Config{
		Home: s.cfg.Home, Versao: s.cfg.Versao, Exec: s.cfg.Exec, Codigo: s.cfg.Codigo, Clone: s.cfg.Clone != "", Ambiente: s.cfg.Ambiente,
		Respostas: req.Respostas, Segredos: req.Segredos, Emitir: s.eventos.publicar,
	}
	s.mu.Lock()
	if req.RestaurarConfiguracao && s.importada != nil {
		cfg.Configuracao, cfg.SenhaArquivo = s.importada.cfg, s.importada.senha
	}
	s.mu.Unlock()
	if s.cfg.Instalacao != nil {
		cfg = s.cfg.Instalacao(cfg)
	}
	inst := instalacao.Nova(cfg)
	s.mu.Lock()
	s.inst = inst
	s.mu.Unlock()
	if err := inst.Iniciar(context.WithoutCancel(r.Context())); err != nil {
		erroJSON(w, http.StatusConflict, err.Error())
		return
	}
	responder(w, http.StatusAccepted, inst.Situacao())
}

func (s *Servidor) retomar(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	inst := s.inst
	s.mu.Unlock()
	if inst == nil {
		erroJSON(w, http.StatusConflict, "nenhuma instalação iniciada")
		return
	}
	if err := inst.Iniciar(context.WithoutCancel(r.Context())); err != nil {
		erroJSON(w, http.StatusConflict, err.Error())
		return
	}
	responder(w, http.StatusAccepted, inst.Situacao())
}

func (s *Servidor) situacao(w http.ResponseWriter, _ *http.Request) {
	sit := s.situacaoAtual()
	if sit == nil {
		erroJSON(w, http.StatusNotFound, "nenhuma instalação iniciada")
		return
	}
	if sit.Resultado != nil {
		s.mu.Lock()
		s.concluido = true
		s.mu.Unlock()
	}
	responder(w, http.StatusOK, sit)
}

func (s *Servidor) atalhos(w http.ResponseWriter, _ *http.Request) {
	sit := s.situacaoAtual()
	if sit == nil || sit.Resultado == nil {
		erroJSON(w, http.StatusConflict, "a instalação ainda não terminou")
		return
	}
	arquivos, err := instalacao.CriarAtalhos(s.cfg.Home)
	if err != nil {
		erroJSON(w, http.StatusInternalServerError, err.Error())
		return
	}
	responder(w, http.StatusOK, map[string]any{"arquivos": arquivos})
}

func (s *Servidor) desinstalacao(w http.ResponseWriter, r *http.Request) {
	l := desinstalacao.Localizar(r.Context(), s.cfg.Exec, s.cfg.Home, s.dirPadrao())
	s.mu.Lock()
	s.localizada = &l
	s.mu.Unlock()
	responder(w, http.StatusOK, l)
}

func (s *Servidor) localizadaAtual() *desinstalacao.Localizada {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.localizada
}

func (s *Servidor) exportarInstalada(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Senha string `json:"senha"`
	}
	if !ler(w, r, &req) {
		return
	}
	l := s.localizadaAtual()
	if l == nil || !l.Encontrada {
		erroJSON(w, http.StatusConflict, "nenhuma instalação localizada")
		return
	}
	b, err := desinstalacao.Exportar(r.Context(), s.cfg.Exec, *l, s.cfg.Home, req.Senha, s.padroes(), s.cfg.Agora())
	if err != nil {
		erroJSON(w, http.StatusBadRequest, err.Error())
		return
	}
	baixar(w, s.nomeArquivo(), b)
}

func (s *Servidor) desinstalar(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Itens       []string `json:"itens"`
		Confirmacao string   `json:"confirmacao"`
	}
	if !ler(w, r, &req) {
		return
	}
	if req.Confirmacao != PalavraConfirmacao {
		erroJSON(w, http.StatusBadRequest, fmt.Sprintf("digite %s para confirmar", PalavraConfirmacao))
		return
	}
	l := s.localizadaAtual()
	if l == nil || !l.Encontrada {
		erroJSON(w, http.StatusConflict, "nenhuma instalação localizada")
		return
	}
	s.mu.Lock()
	if s.desinstRoda || s.desinstRes != nil {
		s.mu.Unlock()
		erroJSON(w, http.StatusConflict, "a desinstalação já foi feita ou está em andamento")
		return
	}
	s.desinstRoda = true
	s.mu.Unlock()
	cfg := desinstalacao.Config{Home: s.cfg.Home, Exec: s.cfg.Exec, Ambiente: s.cfg.Ambiente, Emitir: s.eventos.publicar}
	go func() {
		res := desinstalacao.Executar(context.Background(), cfg, *l, req.Itens)
		s.mu.Lock()
		s.desinstRes, s.desinstRoda, s.concluido = &res, false, true
		s.mu.Unlock()
	}()
	responder(w, http.StatusAccepted, map[string]bool{"rodando": true})
}

func (s *Servidor) resultadoDesinstalacao(w http.ResponseWriter, _ *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	responder(w, http.StatusOK, map[string]any{"rodando": s.desinstRoda, "resultado": s.desinstRes})
}

// sse transmite os eventos de progresso; um comentário a cada 20 s mantém a conexão.
func (s *Servidor) sse(w http.ResponseWriter, r *http.Request) {
	fl, ok := w.(http.Flusher)
	if !ok {
		erroJSON(w, http.StatusInternalServerError, "streaming não suportado")
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Connection", "keep-alive")
	c := s.eventos.assinar()
	defer s.eventos.cancelar(c)
	fmt.Fprint(w, ": conectado\n\n")
	fl.Flush()
	pulso := time.NewTicker(20 * time.Second)
	defer pulso.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-s.encerrar:
			return
		case <-pulso.C:
			fmt.Fprint(w, ": pulso\n\n")
			fl.Flush()
		case e := <-c:
			b, _ := json.Marshal(e)
			fmt.Fprintf(w, "data: %s\n\n", b)
			fl.Flush()
		}
	}
}

func (s *Servidor) encerrarQuiz(w http.ResponseWriter, _ *http.Request) {
	responder(w, http.StatusOK, map[string]bool{"encerrado": true})
	go func() {
		time.Sleep(500 * time.Millisecond)
		s.fechar.Do(func() { close(s.encerrar) })
	}()
}

// VerificadorReal valida os campos contra esta máquina.
type VerificadorReal struct {
	Raiz    string // "/" na máquina real
	HomeDir string
	Clone   string // modo clone: o clone é aceito como diretório de instalação
}

func (v VerificadorReal) PortaEmUso(porta int) bool {
	usadas, err := sistema.PortasEmUso(v.Raiz)
	return err == nil && usadas[porta]
}

func (v VerificadorReal) DiretorioExiste(caminho string) bool {
	info, err := os.Stat(caminho)
	return err == nil && info.IsDir()
}

func (v VerificadorReal) DiretorioInstalacao(caminho string) string {
	if v.Clone != "" && filepath.Clean(caminho) == v.Clone {
		return instalacao.ValidarClone(caminho)
	}
	return instalacao.DiretorioLivre(caminho)
}

func (v VerificadorReal) InicioArquivo(caminho string) ([]byte, error) {
	f, err := os.Open(caminho)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	b := make([]byte, 64*1024)
	n, err := io.ReadFull(f, b)
	if err != nil && !errors.Is(err, io.ErrUnexpectedEOF) && !errors.Is(err, io.EOF) {
		return nil, err
	}
	return b[:n], nil
}

func (v VerificadorReal) Home() string { return v.HomeDir }
