// Package servidor serve o quiz e a API do instalador só em 127.0.0.1, exigindo o token de uso único em toda
// requisição (design, decisão 2).
package servidor

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"m2m/installer/internal/arquivo"
	"m2m/installer/internal/comandos"
	"m2m/installer/internal/desinstalacao"
	"m2m/installer/internal/executor"
	"m2m/installer/internal/instalacao"
	"m2m/installer/internal/prerequisitos"
	"m2m/installer/internal/respostas"
	"m2m/installer/internal/sistema"
)

const (
	nomeCookie      = "m2m_instalador"
	cabecalhoPOST   = "X-M2M-Instalador"
	ociosoMaximo    = 2 * time.Hour
	ociosoConcluido = 30 * time.Minute // depois do fim, só o prazo para baixar o .json
)

// Config é o que o servidor recebe do main (e dos testes).
type Config struct {
	Versao     string
	Home       string
	Usuario    string
	SO         sistema.SO
	Ambiente   comandos.Ambiente
	Exec       executor.Executor
	Sonda      prerequisitos.Sonda
	Verificar  respostas.Verificador
	Maquina    respostas.Maquina
	Enderecos  []string
	UI         fs.FS
	Codigo     func() ([]byte, error)
	Clone      string // modo clone: caminho absoluto do clone, que é o diretório de instalação fixo
	Token      string // vazio: gerado
	Agora      func() time.Time
	Instalacao func(instalacao.Config) instalacao.Config // ajuste da config da instalação (testes)
}

// Servidor guarda o estado da execução em memória: nada de segredo vai para o disco além do .env.
type Servidor struct {
	cfg      Config
	porta    int
	eventos  *difusor
	mux      *http.ServeMux
	encerrar chan struct{}
	fechar   sync.Once

	mu          sync.Mutex
	ultimoUso   time.Time
	concluido   bool
	inst        *instalacao.Instalacao
	importada   *configuracaoImportada
	localizada  *desinstalacao.Localizada
	desinstRes  *desinstalacao.Resultado
	desinstRoda bool
}

// configuracaoImportada é a configuração do m2m do último arquivo de reinstalação importado, com a senha que a abre;
// fica só em memória até a instalação.
type configuracaoImportada struct {
	cfg   *arquivo.Configuracao
	senha string
}

// GerarToken devolve 256 bits aleatórios em hexadecimal.
func GerarToken() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

// Novo monta o servidor para a porta já reservada.
func Novo(cfg Config, porta int) *Servidor {
	if cfg.Token == "" {
		cfg.Token = GerarToken()
	}
	if cfg.Agora == nil {
		cfg.Agora = time.Now
	}
	s := &Servidor{cfg: cfg, porta: porta, eventos: novoDifusor(), encerrar: make(chan struct{}), ultimoUso: cfg.Agora()}
	s.rotas()
	return s
}

// Token devolve o token desta execução.
func (s *Servidor) Token() string { return s.cfg.Token }

// URL é o endereço com o token, mostrado no terminal.
func (s *Servidor) URL() string {
	return "http://127.0.0.1:" + strconv.Itoa(s.porta) + "/?t=" + s.cfg.Token
}

// Encerrado fecha quando o quiz pede o fim.
func (s *Servidor) Encerrado() <-chan struct{} { return s.encerrar }

// Ocioso diz se o servidor passou do prazo sem uso (sem requisição e sem conexão de progresso aberta).
func (s *Servidor) Ocioso() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	limite := ociosoMaximo
	if s.concluido {
		limite = ociosoConcluido
	}
	return s.eventos.conexoes() == 0 && s.cfg.Agora().Sub(s.ultimoUso) > limite
}

// Escutar abre 127.0.0.1 na porta pedida ou na próxima livre (até 20 tentativas).
func Escutar(porta int) (net.Listener, int, error) {
	var ultimo error
	for p := porta; p < porta+20; p++ {
		l, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(p))
		if err == nil {
			return l, p, nil
		}
		ultimo = err
	}
	return nil, 0, fmt.Errorf("nenhuma porta livre entre %d e %d: %w", porta, porta+19, ultimo)
}

// hostLocal aceita só nomes de loopback, contra DNS rebinding. A porta pode ser outra: num túnel SSH o
// usuário pode encaminhar uma porta local diferente da do instalador.
func hostLocal(host string) bool {
	nome, _, err := net.SplitHostPort(host)
	if err != nil {
		nome = host
	}
	return nome == "127.0.0.1" || nome == "localhost" || nome == "::1"
}

// ServeHTTP aplica as proteções antes de qualquer rota.
func (s *Servidor) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !hostLocal(r.Host) {
		http.Error(w, "Host não permitido.", http.StatusMisdirectedRequest)
		return
	}
	if r.URL.Path == "/" && r.URL.Query().Has("t") {
		if !iguais(r.URL.Query().Get("t"), s.cfg.Token) {
			http.Error(w, "Token inválido. Use o endereço mostrado no terminal.", http.StatusForbidden)
			return
		}
		http.SetCookie(w, &http.Cookie{Name: nomeCookie, Value: s.cfg.Token, Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode})
		http.Redirect(w, r, "/", http.StatusSeeOther)
		return
	}
	c, err := r.Cookie(nomeCookie)
	if err != nil || !iguais(c.Value, s.cfg.Token) {
		http.Error(w, "Acesso negado. Abra o endereço com o token mostrado no terminal do instalador.", http.StatusForbidden)
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead && r.Header.Get(cabecalhoPOST) == "" {
		http.Error(w, "Requisição recusada.", http.StatusForbidden)
		return
	}
	s.mu.Lock()
	s.ultimoUso = s.cfg.Agora()
	s.mu.Unlock()
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("X-Frame-Options", "DENY")
	w.Header().Set("Referrer-Policy", "no-referrer")
	s.mux.ServeHTTP(w, r)
}

func iguais(a, b string) bool {
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// estatico serve a interface do quiz; caminhos desconhecidos fora de /api caem no index.html.
func (s *Servidor) estatico(w http.ResponseWriter, r *http.Request) {
	nome := strings.TrimPrefix(r.URL.Path, "/")
	if nome == "" {
		nome = "index.html"
	}
	if _, err := fs.Stat(s.cfg.UI, nome); err != nil {
		nome = "index.html"
	}
	if _, err := fs.Stat(s.cfg.UI, "index.html"); err != nil {
		http.Error(w, "Este executável foi gerado sem a interface do quiz: use scripts/gerar-instalador.sh.", http.StatusNotFound)
		return
	}
	http.ServeFileFS(w, r, s.cfg.UI, nome)
}

// Rodar serve até o contexto acabar, o quiz pedir o fim ou o servidor ficar ocioso.
func (s *Servidor) Rodar(ctx context.Context, l net.Listener) error {
	srv := &http.Server{Handler: s, ReadHeaderTimeout: 10 * time.Second}
	erro := make(chan error, 1)
	go func() { erro <- srv.Serve(l) }()
	tique := time.NewTicker(time.Minute)
	defer tique.Stop()
	for {
		select {
		case err := <-erro:
			if errors.Is(err, http.ErrServerClosed) {
				return nil
			}
			return err
		case <-ctx.Done():
			return desligar(srv)
		case <-s.encerrar:
			return desligar(srv)
		case <-tique.C:
			if s.Ocioso() {
				fmt.Println("Instalador encerrado por falta de uso.")
				return desligar(srv)
			}
		}
	}
}

func desligar(srv *http.Server) error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	err := srv.Shutdown(ctx)
	if errors.Is(err, context.DeadlineExceeded) {
		return srv.Close() // conexões SSE abertas
	}
	return err
}
