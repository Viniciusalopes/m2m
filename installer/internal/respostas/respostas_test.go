package respostas

import (
	"os"
	"strings"
	"testing"

	"m2m/installer/internal/dotenv"
)

type verificador struct {
	usadas     map[int]bool
	diretorios map[string]bool
	instalacao string
	arquivos   map[string]string
}

func (v verificador) PortaEmUso(p int) bool             { return v.usadas[p] }
func (v verificador) DiretorioExiste(c string) bool     { return v.diretorios[c] }
func (v verificador) DiretorioInstalacao(string) string { return v.instalacao }
func (v verificador) Home() string                      { return "/home/ana" }
func (v verificador) InicioArquivo(c string) ([]byte, error) {
	if s, ok := v.arquivos[c]; ok {
		return []byte(s), nil
	}
	return nil, os.ErrNotExist
}

func maquina() Maquina {
	return Maquina{Home: "/home/ana", Hostname: "notebook-novo.lan", Fuso: "America/Sao_Paulo", Endereco: "192.168.0.10"}
}

func validos() (Respostas, Segredos, verificador) {
	s := GerarSegredos()
	s.SenhaAdmin = "Senha-do-Admin-123"
	return Padroes(maquina()), s, verificador{diretorios: map[string]bool{"/home": true}}
}

func TestPadroesDaSpec(t *testing.T) {
	r := Padroes(maquina())
	if r.Containers.Diretorio != "/home/ana/m2m" || r.Containers.PortaHTTP != 80 || r.Containers.PortaHTTPS != 443 {
		t.Errorf("containers: %+v", r.Containers)
	}
	if r.Usuarios.Usuario != "admin" || r.Rede.Certificado != CertificadoCALocal || r.Rede.Endereco != "192.168.0.10" {
		t.Errorf("usuários ou rede: %+v %+v", r.Usuarios, r.Rede)
	}
	if !r.Maquinas.LocalAtivo || r.Maquinas.Nome != "NOTEBOOK-NOVO" || r.Maquinas.DiretorioHost != "/home" {
		t.Errorf("máquinas: %+v", r.Maquinas)
	}
	if r.Logs.PortaGrafana != 3000 || r.Logs.AcessoRede || r.Logs.RetencaoDias != 7 {
		t.Errorf("logs: %+v", r.Logs)
	}
}

func TestPadroesSaoValidos(t *testing.T) {
	r, s, v := validos()
	if e := ValidarTudo(r, s, v); len(e) > 0 {
		t.Fatalf("os padrões deveriam passar: %v", e)
	}
}

func TestSegredosGerados(t *testing.T) {
	s := GerarSegredos()
	if len(s.ChaveMestra) != 44 || len(s.SenhaBanco) != 24 || s.SenhaBanco == s.SenhaFila || s.SenhaAdmin != "" {
		t.Fatalf("segredos inesperados: %+v", s)
	}
	if strings.Trim(s.SenhaGrafana, alfanumericos) != "" {
		t.Fatalf("senha com caracteres fora do alfabeto: %s", s.SenhaGrafana)
	}
}

func TestPortaEmUsoEDistintas(t *testing.T) {
	r, s, v := validos()
	v.usadas = map[int]bool{443: true}
	e := Validar("containers", r, s, v)
	if !strings.Contains(e["portaHttps"], "443 já está em uso") {
		t.Errorf("porta 443 ocupada: %v", e)
	}
	r.Containers.PortaHTTP, r.Containers.PortaHTTPS = 8443, 8443
	v.usadas = nil
	if e := Validar("containers", r, s, v); e["portaHttps"] == "" {
		t.Errorf("portas iguais deveriam falhar: %v", e)
	}
	r.Logs.PortaGrafana = 8443
	if e := Validar("logs", r, s, v); e["portaGrafana"] == "" {
		t.Errorf("Grafana na porta do m2m deveria falhar: %v", e)
	}
}

func TestDiretorioDeInstalacao(t *testing.T) {
	r, s, v := validos()
	for _, d := range []string{"m2m", "/", "/home", "/home/ana"} {
		r.Containers.Diretorio = d
		if e := Validar("containers", r, s, v); e["diretorio"] == "" {
			t.Errorf("%q deveria ser recusado", d)
		}
	}
	r.Containers.Diretorio = "/home/ana/m2m"
	v.instalacao = "O diretório já existe e não está vazio."
	if e := Validar("containers", r, s, v); e["diretorio"] != v.instalacao {
		t.Errorf("diretório ocupado: %v", e)
	}
}

func TestSenhasEChave(t *testing.T) {
	r, s, v := validos()
	s.SenhaAdmin = "curta-11ch"
	if e := Validar("usuarios", r, s, v); !strings.Contains(e["senhaAdmin"], "12") {
		t.Errorf("senha curta: %v", e)
	}
	s.SenhaAdmin = "tem'aspas-simples"
	if e := Validar("usuarios", r, s, v); e["senhaAdmin"] == "" {
		t.Errorf("aspas simples deveriam ser recusadas: %v", e)
	}
	s.ChaveMestra = "curta"
	if e := Validar("seguranca", r, s, v); e["chaveMestra"] == "" {
		t.Errorf("chave curta: %v", e)
	}
	r.Usuarios.Email = "não é e-mail"
	if e := Validar("usuarios", r, s, v); e["email"] == "" {
		t.Errorf("e-mail inválido: %v", e)
	}
}

func TestRedeECertificado(t *testing.T) {
	r, s, v := validos()
	r.Rede.Endereco = "https://m2m.lan:443"
	if e := Validar("rede", r, s, v); e["endereco"] == "" {
		t.Errorf("endereço com esquema: %v", e)
	}
	r.Rede.Endereco = "203.0.113.5"
	if e := Validar("rede", r, s, v); e["certificado"] == "" {
		t.Errorf("IP público com CA local deveria falhar: %v", e)
	}
	r.Rede.Endereco = "m2m.exemplo.com.br"
	if e := Validar("rede", r, s, v); len(e) > 0 {
		t.Errorf("nome DNS com CA local é aceito: %v", e)
	}
	r.Rede.Certificado = CertificadoProprio
	r.Rede.CertificadoArquivo, r.Rede.ChaveArquivo = "/etc/ssl/m2m.crt", "/etc/ssl/m2m.key"
	v.arquivos = map[string]string{"/etc/ssl/m2m.crt": "-----BEGIN CERTIFICATE-----", "/etc/ssl/m2m.key": "texto qualquer"}
	e := Validar("rede", r, s, v)
	if e["certificadoArquivo"] != "" || e["chaveArquivo"] == "" {
		t.Errorf("certificado próprio: %v", e)
	}
}

func TestMaquinas(t *testing.T) {
	r, s, v := validos()
	r.Maquinas.DiretorioHost = "/mnt/dados"
	if e := Validar("maquinas", r, s, v); !strings.Contains(e["diretorioHost"], "não existe") {
		t.Errorf("diretório do host inexistente: %v", e)
	}
	v.diretorios["/mnt/dados"] = true
	r.Maquinas.Raizes = []string{"/home/fotos"}
	if e := Validar("maquinas", r, s, v); !strings.Contains(e["raizes"], "/mnt/dados/fotos") {
		t.Errorf("raiz traduzida para o host: %v", e)
	}
	r.Maquinas.Raizes = []string{"/srv"}
	if e := Validar("maquinas", r, s, v); e["raizes"] == "" {
		t.Errorf("raiz fora de /home: %v", e)
	}
	r.Maquinas.LocalAtivo, r.Maquinas.DiretorioHost = false, "lixo"
	if e := Validar("maquinas", r, s, v); len(e) > 0 {
		t.Errorf("com LOCAL desligado nada é validado: %v", e)
	}
}

func TestEnv(t *testing.T) {
	r, s, _ := validos()
	r.Containers.PortaHTTPS = 8443
	r.Logs.AcessoRede = true
	m := map[string]string{}
	for _, p := range Env(r, s) {
		m[p.Chave] = p.Valor
	}
	esperado := map[string]string{
		"PUBLIC_URL": "https://192.168.0.10:8443", "M2M_LOCAL_ENABLED": "true", "M2M_LOCAL_NAME": "NOTEBOOK-NOVO",
		"HOST_HOME_BIND": "/home", "HOST_ROOTS": "/home", "GRAFANA_BIND": "0.0.0.0", "LOKI_RETENTION": "168h",
		"APP_SECRET_KEY": s.ChaveMestra, "ADMIN_PASSWORD": s.SenhaAdmin, "TLS_CERT_PATH": "", "TZ": "America/Sao_Paulo",
	}
	for k, v := range esperado {
		if m[k] != v {
			t.Errorf("%s: esperava %q, veio %q", k, v, m[k])
		}
	}
	r.Maquinas.LocalAtivo = false
	for _, p := range Env(r, s) {
		if (p.Chave == "HOST_HOME_BIND" || p.Chave == "M2M_LOCAL_NAME") && p.Valor != "" {
			t.Errorf("sem LOCAL, %s deveria ficar vazio: %q", p.Chave, p.Valor)
		}
	}
}

func TestEnvIPv6NoPublicURL(t *testing.T) {
	r, s, _ := validos()
	r.Rede.Endereco = "fd00::10"
	for _, p := range Env(r, s) {
		if p.Chave == "PUBLIC_URL" && p.Valor != "https://[fd00::10]" {
			t.Fatalf("PUBLIC_URL: %s", p.Valor)
		}
	}
}

// O .env instalado reconstrói as mesmas respostas e segredos (exportação na desinstalação).
func TestDoEnvDesfazEnv(t *testing.T) {
	r, s, _ := validos()
	r.Containers.PortaHTTP, r.Containers.PortaHTTPS = 8080, 8443
	r.Maquinas.Raizes = []string{"/home", "/home/fotos"}
	r.Logs.RetencaoDias, r.Logs.AcessoRede = 30, true
	r.Usuarios.Email = "ana@exemplo.test"
	env := dotenv.Ler(dotenv.Gerar([]byte("ADMIN_USERNAME=admin\n"), Env(r, s)))
	env["TLS_CERT_PATH"] = "/home/ana/.config/m2m/tls/server.crt" // preenchido pelo script da CA local
	r2, s2 := DoEnv(env, r.Containers.Diretorio, "/home/ana/.config/m2m/tls", Padroes(Maquina{Home: "/x", Hostname: "outra"}))
	if s2 != s {
		t.Errorf("segredos: %+v != %+v", s2, s)
	}
	if r2.Containers != r.Containers || r2.Usuarios != r.Usuarios || r2.Rede != r.Rede || r2.Logs != r.Logs {
		t.Errorf("respostas:\n%+v\n%+v", r2, r)
	}
	if strings.Join(r2.Maquinas.Raizes, ",") != "/home,/home/fotos" || r2.Maquinas.Nome != "NOTEBOOK-NOVO" {
		t.Errorf("máquinas: %+v", r2.Maquinas)
	}
}

func TestRaizNoHost(t *testing.T) {
	if p, ok := RaizNoHost("/mnt/dados", "/home/fotos"); !ok || p != "/mnt/dados/fotos" {
		t.Errorf("tradução: %s %v", p, ok)
	}
	if _, ok := RaizNoHost("/home", "/homebrew"); ok {
		t.Error("/homebrew não está em /home")
	}
}
