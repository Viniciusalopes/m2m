package servidor

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"testing/fstest"

	"m2m/installer/internal/comandos"
	"m2m/installer/internal/executor"
	"m2m/installer/internal/respostas"
	"m2m/installer/internal/sistema"
)

const token = "token-de-teste"

func novo(t *testing.T) (*Servidor, string) {
	t.Helper()
	home := t.TempDir()
	ex := executor.NovoFalso(nil)
	ex.Programas = map[string]bool{}
	so := sistema.SO{ID: "ubuntu", Versao: "24.04", Codinome: "noble", Nome: "Ubuntu 24.04", Familia: "ubuntu", CodinomeUbuntu: "noble"}
	s := Novo(Config{
		Versao: "teste", Home: home, Usuario: "ana", SO: so, Exec: ex, Token: token,
		Ambiente:  comandos.Ambiente{SO: so, Usuario: "ana"},
		Verificar: VerificadorReal{Raiz: t.TempDir(), HomeDir: home},
		Maquina:   respostas.Maquina{Home: home, Hostname: "notebook", Fuso: "UTC", Endereco: "192.168.0.10"},
		UI:        fstest.MapFS{"index.html": {Data: []byte("<html>quiz</html>")}},
	}, 8765)
	return s, "127.0.0.1:8765"
}

func req(s *Servidor, metodo, alvo, host string, ajustar func(*http.Request)) *httptest.ResponseRecorder {
	return reqCorpo(s, metodo, alvo, host, "{}", ajustar)
}

func reqCorpo(s *Servidor, metodo, alvo, host, corpo string, ajustar func(*http.Request)) *httptest.ResponseRecorder {
	r := httptest.NewRequest(metodo, alvo, strings.NewReader(corpo))
	r.Host = host
	if ajustar != nil {
		ajustar(r)
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	return w
}

func comCookie(r *http.Request) {
	r.AddCookie(&http.Cookie{Name: nomeCookie, Value: token})
}

func TestTokenNaURLViraCookie(t *testing.T) {
	s, host := novo(t)
	w := req(s, "GET", "/?t="+token, host, nil)
	if w.Code != http.StatusSeeOther {
		t.Fatalf("esperava redirecionamento, veio %d", w.Code)
	}
	ck := w.Result().Cookies()
	if len(ck) != 1 || ck[0].Value != token || !ck[0].HttpOnly || ck[0].SameSite != http.SameSiteStrictMode {
		t.Fatalf("cookie inesperado: %+v", ck)
	}
	if w := req(s, "GET", "/", host, comCookie); w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "quiz") {
		t.Fatalf("com o cookie, esperava o quiz: %d %s", w.Code, w.Body)
	}
}

func TestSemTokenRecusa(t *testing.T) {
	s, host := novo(t)
	for _, alvo := range []string{"/", "/api/estado", "/?t=errado"} {
		if w := req(s, "GET", alvo, host, nil); w.Code != http.StatusForbidden {
			t.Errorf("%s sem token: esperava 403, veio %d", alvo, w.Code)
		}
	}
	errado := func(r *http.Request) { r.AddCookie(&http.Cookie{Name: nomeCookie, Value: "outro"}) }
	if w := req(s, "GET", "/api/estado", host, errado); w.Code != http.StatusForbidden {
		t.Errorf("cookie errado: esperava 403, veio %d", w.Code)
	}
}

func TestHostEstranhoRecusa(t *testing.T) {
	s, _ := novo(t)
	for _, host := range []string{"evil.example:8765", "127.0.0.1.evil.example:8765", "192.168.0.10:8765"} {
		if w := req(s, "GET", "/api/estado", host, comCookie); w.Code != http.StatusMisdirectedRequest {
			t.Errorf("Host %s: esperava 421, veio %d", host, w.Code)
		}
	}
	// túnel SSH com outra porta local
	for _, host := range []string{"localhost:8765", "127.0.0.1:18765", "[::1]:8765"} {
		if w := req(s, "GET", "/api/estado", host, comCookie); w.Code != http.StatusOK {
			t.Errorf("Host %s deveria ser aceito: %d", host, w.Code)
		}
	}
}

func TestPostSemCabecalhoRecusa(t *testing.T) {
	s, host := novo(t)
	if w := req(s, "POST", "/api/validar", host, comCookie); w.Code != http.StatusForbidden {
		t.Fatalf("POST sem o cabeçalho: esperava 403, veio %d", w.Code)
	}
	com := func(r *http.Request) { comCookie(r); r.Header.Set(cabecalhoPOST, "1") }
	if w := req(s, "POST", "/api/validar", host, com); w.Code != http.StatusOK {
		t.Fatalf("POST com o cabeçalho: esperava 200, veio %d %s", w.Code, w.Body)
	}
}

func TestDesinstalarExigePalavra(t *testing.T) {
	s, host := novo(t)
	com := func(r *http.Request) { comCookie(r); r.Header.Set(cabecalhoPOST, "1") }
	w := reqCorpo(s, "POST", "/api/desinstalar", host, `{"itens":["volumes"],"confirmacao":"desinstalar"}`, com)
	if w.Code != http.StatusBadRequest || !strings.Contains(w.Body.String(), PalavraConfirmacao) {
		t.Fatalf("esperava recusa sem a palavra exata: %d %s", w.Code, w.Body)
	}
}

func TestPortaOcupadaUsaAProxima(t *testing.T) {
	ocupada, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ocupada.Close()
	p := ocupada.Addr().(*net.TCPAddr).Port
	l, porta, err := Escutar(p)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	if porta == p || !strings.HasPrefix(l.Addr().String(), "127.0.0.1:") {
		t.Fatalf("esperava outra porta em 127.0.0.1, veio %s", l.Addr())
	}
	if l.Addr().String() != "127.0.0.1:"+strconv.Itoa(porta) {
		t.Fatalf("porta informada diferente da escutada: %d %s", porta, l.Addr())
	}
}

func TestModoClone(t *testing.T) {
	s, host := novo(t)
	clone := t.TempDir()
	for _, f := range []string{"docker-compose.yml", ".env.example", "scripts/gerar-certificado-tls.sh"} {
		os.MkdirAll(filepath.Join(clone, filepath.Dir(f)), 0o755)
		os.WriteFile(filepath.Join(clone, f), []byte("name: m2m\n"), 0o644)
	}
	s.cfg.Clone = clone
	s.cfg.Verificar = VerificadorReal{Raiz: t.TempDir(), HomeDir: s.cfg.Home, Clone: clone}

	if p := s.padroes(); p.Containers.Diretorio != clone {
		t.Errorf("o diretório padrão no modo clone deveria ser o clone: %s", p.Containers.Diretorio)
	}
	if msg := s.cfg.Verificar.DiretorioInstalacao(clone); msg != "" {
		t.Errorf("o clone, mesmo com arquivos, deveria ser aceito: %s", msg)
	}
	w := req(s, "GET", "/api/estado", host, comCookie)
	var e map[string]any
	json.Unmarshal(w.Body.Bytes(), &e)
	if e["clone"] != clone || e["bloqueia"] != false {
		t.Errorf("estado no modo clone: clone=%v bloqueia=%v", e["clone"], e["bloqueia"])
	}
	// clone já instalado: não há como escolher outro diretório, então bloqueia
	os.WriteFile(filepath.Join(clone, ".env"), []byte("X=1\n"), 0o600)
	w = req(s, "GET", "/api/estado", host, comCookie)
	json.Unmarshal(w.Body.Bytes(), &e)
	if e["bloqueia"] != true {
		t.Errorf("clone com .env deveria bloquear: %v", e["bloqueia"])
	}
}
