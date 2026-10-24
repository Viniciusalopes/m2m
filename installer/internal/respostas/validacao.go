package respostas

import (
	"fmt"
	"net"
	"net/mail"
	"path/filepath"
	"regexp"
	"strings"
	"time"
	_ "time/tzdata" // valida o fuso mesmo numa máquina mínima sem /usr/share/zoneinfo
)

// Etapas validáveis, na ordem do quiz.
var Etapas = []string{"containers", "seguranca", "usuarios", "rede", "maquinas", "logs"}

// Verificador consulta a máquina para validar os campos.
type Verificador interface {
	PortaEmUso(porta int) bool
	DiretorioExiste(caminho string) bool
	// DiretorioInstalacao devolve "" se o caminho pode receber a instalação (vazio ou inexistente, pai gravável).
	DiretorioInstalacao(caminho string) string
	// InicioArquivo lê o começo de um arquivo, para conferir que é legível e PEM.
	InicioArquivo(caminho string) ([]byte, error)
	Home() string
}

// Erros por campo de uma etapa: campo → mensagem.
type Erros map[string]string

const (
	MinSenhaAdmin  = 12
	MinChaveMestra = 16 // mesmo critério do back (CryptoService)
)

var (
	reUsuario = regexp.MustCompile(`^[A-Za-z0-9._-]{3,50}$`)
	reNome    = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9 ._-]{0,99}$`)
	reHost    = regexp.MustCompile(`^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$`)
)

// Validar confere os campos de uma etapa.
func Validar(etapa string, r Respostas, s Segredos, v Verificador) Erros {
	e := Erros{}
	switch etapa {
	case "containers":
		validarContainers(r, v, e)
	case "seguranca":
		validarSegredo(e, "chaveMestra", "chave mestra", s.ChaveMestra, MinChaveMestra)
		validarSegredo(e, "senhaBanco", "senha do banco", s.SenhaBanco, 12)
		validarSegredo(e, "senhaFila", "senha da fila", s.SenhaFila, 12)
	case "usuarios":
		if !reUsuario.MatchString(r.Usuarios.Usuario) {
			e["usuario"] = "Use de 3 a 50 letras, números, ponto, hífen ou sublinhado."
		}
		validarSegredo(e, "senhaAdmin", "senha do administrador", s.SenhaAdmin, MinSenhaAdmin)
		if r.Usuarios.Email != "" {
			if a, err := mail.ParseAddress(r.Usuarios.Email); err != nil || a.Address != r.Usuarios.Email {
				e["email"] = "E-mail inválido."
			}
		}
	case "rede":
		validarRede(r.Rede, v, e)
	case "maquinas":
		validarMaquinas(r.Maquinas, v, e)
	case "logs":
		validarPorta(e, "portaGrafana", r.Logs.PortaGrafana, v)
		if r.Logs.PortaGrafana == r.Containers.PortaHTTP || r.Logs.PortaGrafana == r.Containers.PortaHTTPS {
			e["portaGrafana"] = "A porta do Grafana precisa ser diferente das portas HTTP e HTTPS do m2m."
		}
		validarSegredo(e, "senhaGrafana", "senha do Grafana", s.SenhaGrafana, 12)
		if r.Logs.RetencaoDias < 1 || r.Logs.RetencaoDias > 365 {
			e["retencaoDias"] = "Informe de 1 a 365 dias."
		}
	}
	return e
}

// ValidarTudo confere todas as etapas; só devolve as que têm erro.
func ValidarTudo(r Respostas, s Segredos, v Verificador) map[string]Erros {
	todos := map[string]Erros{}
	for _, etapa := range Etapas {
		if e := Validar(etapa, r, s, v); len(e) > 0 {
			todos[etapa] = e
		}
	}
	return todos
}

func validarContainers(r Respostas, v Verificador, e Erros) {
	c := r.Containers
	switch dir := filepath.Clean(c.Diretorio); {
	case !filepath.IsAbs(c.Diretorio):
		e["diretorio"] = "Informe um caminho absoluto, como " + filepath.Join(v.Home(), "m2m") + "."
	case dir == "/" || dir == filepath.Clean(v.Home()) || dir == "/home":
		e["diretorio"] = "Escolha um diretório próprio para o m2m, como " + filepath.Join(v.Home(), "m2m") + "."
	default:
		if msg := v.DiretorioInstalacao(dir); msg != "" {
			e["diretorio"] = msg
		}
	}
	validarPorta(e, "portaHttp", c.PortaHTTP, v)
	validarPorta(e, "portaHttps", c.PortaHTTPS, v)
	if c.PortaHTTP == c.PortaHTTPS && c.PortaHTTP != 0 {
		e["portaHttps"] = "As portas HTTP e HTTPS precisam ser diferentes."
	}
	if c.Fuso == "" {
		e["fuso"] = "Informe o fuso horário."
	} else if _, err := time.LoadLocation(c.Fuso); err != nil {
		e["fuso"] = "Fuso horário desconhecido. Use o formato Região/Cidade, como America/Sao_Paulo."
	}
}

func validarPorta(e Erros, campo string, porta int, v Verificador) {
	switch {
	case porta < 1 || porta > 65535:
		e[campo] = "Informe uma porta de 1 a 65535."
	case v.PortaEmUso(porta):
		e[campo] = fmt.Sprintf("A porta %d já está em uso por outro programa nesta máquina.", porta)
	}
}

// validarSegredo exige o tamanho mínimo e caracteres que o .env aceita.
func validarSegredo(e Erros, campo, nome, valor string, minimo int) {
	switch {
	case len([]rune(valor)) < minimo:
		e[campo] = fmt.Sprintf("A %s precisa ter no mínimo %d caracteres.", nome, minimo)
	case strings.ContainsAny(valor, "'\n\r"):
		e[campo] = fmt.Sprintf("A %s não pode ter aspas simples nem quebra de linha.", nome)
	}
}

func validarRede(r Rede, v Verificador, e Erros) {
	end := strings.TrimSpace(r.Endereco)
	ip := net.ParseIP(end)
	switch {
	case end == "":
		e["endereco"] = "Informe o endereço pelo qual o m2m será aberto."
	case ip == nil && !reHost.MatchString(end):
		e["endereco"] = "Informe só o nome ou o IP, sem https:// nem porta."
	}
	switch r.Certificado {
	case CertificadoCALocal:
		if ip != nil && !ipPrivado(ip) {
			e["certificado"] = "A CA local do m2m só cobre IPs de redes privadas. Para um IP público, use um nome DNS, um certificado próprio ou o autoassinado."
		}
	case CertificadoProprio:
		validarPEM(e, "certificadoArquivo", r.CertificadoArquivo, "CERTIFICATE", v)
		validarPEM(e, "chaveArquivo", r.ChaveArquivo, "PRIVATE KEY", v)
	case CertificadoAutoassinado:
	default:
		e["certificado"] = "Escolha o tipo de certificado."
	}
}

func validarPEM(e Erros, campo, caminho, tipo string, v Verificador) {
	if !filepath.IsAbs(caminho) {
		e[campo] = "Informe o caminho absoluto do arquivo."
		return
	}
	b, err := v.InicioArquivo(caminho)
	if err != nil {
		e[campo] = "Não foi possível ler o arquivo: " + err.Error()
		return
	}
	if !strings.Contains(string(b), "-----BEGIN") || !strings.Contains(string(b), tipo) {
		e[campo] = "O arquivo não está no formato PEM esperado (" + tipo + ")."
	}
}

// ipPrivado segue as restrições de nome da CA local (scripts/gerar-certificado-tls.sh).
func ipPrivado(ip net.IP) bool {
	return ip.IsLoopback() || ip.IsPrivate()
}

func validarMaquinas(m Maquinas, v Verificador, e Erros) {
	if !m.LocalAtivo {
		return
	}
	if !reNome.MatchString(strings.TrimSpace(m.Nome)) {
		e["nome"] = "Use até 100 letras, números, espaço, ponto, hífen ou sublinhado."
	}
	host := filepath.Clean(m.DiretorioHost)
	if !filepath.IsAbs(m.DiretorioHost) || host == "/" {
		e["diretorioHost"] = "Informe o caminho absoluto de um diretório do host, como /home."
		return
	}
	if !v.DiretorioExiste(host) {
		e["diretorioHost"] = "O diretório " + host + " não existe nesta máquina."
		return
	}
	if len(m.Raizes) == 0 {
		e["raizes"] = "Informe ao menos uma raiz."
		return
	}
	for _, raiz := range m.Raizes {
		if real, ok := RaizNoHost(host, raiz); !ok {
			e["raizes"] = "Cada raiz precisa começar com " + MontagemHost + ", que é como o m2m enxerga " + host + "."
			return
		} else if !v.DiretorioExiste(real) {
			e["raizes"] = "A raiz " + raiz + " corresponde a " + real + ", que não existe nesta máquina."
			return
		}
	}
}

// RaizNoHost traduz uma raiz como o m2m a vê (/home/...) para o caminho real no host.
func RaizNoHost(diretorioHost, raiz string) (string, bool) {
	r := filepath.Clean(raiz)
	if r != MontagemHost && !strings.HasPrefix(r, MontagemHost+"/") {
		return "", false
	}
	return filepath.Join(diretorioHost, strings.TrimPrefix(r, MontagemHost)), true
}
