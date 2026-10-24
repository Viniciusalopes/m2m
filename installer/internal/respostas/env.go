package respostas

import (
	"net"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"

	"m2m/installer/internal/dotenv"
)

// URLPublica monta o PUBLIC_URL: https, o endereço e a porta HTTPS quando não é 443.
func URLPublica(r Respostas) string {
	host := strings.TrimSpace(r.Rede.Endereco)
	if ip := net.ParseIP(host); ip != nil && ip.To4() == nil {
		host = "[" + host + "]"
	}
	if r.Containers.PortaHTTPS != 443 {
		host += ":" + strconv.Itoa(r.Containers.PortaHTTPS)
	}
	return "https://" + host
}

// Env traduz as respostas e os segredos nas variáveis do .env. Com a CA local, TLS_* ficam vazios: o
// gerar-certificado-tls.sh os preenche.
func Env(r Respostas, s Segredos) []dotenv.Par {
	certificado, chave := "", ""
	if r.Rede.Certificado == CertificadoProprio {
		certificado, chave = r.Rede.CertificadoArquivo, r.Rede.ChaveArquivo
	}
	local, nome, bind, raizes := "false", "", "", MontagemHost
	if r.Maquinas.LocalAtivo {
		local, nome, bind = "true", strings.TrimSpace(r.Maquinas.Nome), filepath.Clean(r.Maquinas.DiretorioHost)
		raizes = strings.Join(r.Maquinas.Raizes, ",")
	}
	grafanaBind := "127.0.0.1"
	if r.Logs.AcessoRede {
		grafanaBind = "0.0.0.0"
	}
	return []dotenv.Par{
		{Chave: "ADMIN_USERNAME", Valor: r.Usuarios.Usuario},
		{Chave: "ADMIN_PASSWORD", Valor: s.SenhaAdmin},
		{Chave: "ADMIN_EMAIL", Valor: r.Usuarios.Email},
		{Chave: "APP_SECRET_KEY", Valor: s.ChaveMestra},
		{Chave: "M2M_LOCAL_ENABLED", Valor: local},
		{Chave: "M2M_LOCAL_NAME", Valor: nome},
		{Chave: "HOST_HOME_BIND", Valor: bind},
		{Chave: "HOST_ROOTS", Valor: raizes},
		{Chave: "PUBLIC_URL", Valor: URLPublica(r)},
		{Chave: "HTTP_PORT", Valor: strconv.Itoa(r.Containers.PortaHTTP)},
		{Chave: "HTTPS_PORT", Valor: strconv.Itoa(r.Containers.PortaHTTPS)},
		{Chave: "TLS_CERT_PATH", Valor: certificado},
		{Chave: "TLS_KEY_PATH", Valor: chave},
		{Chave: "GRAFANA_ADMIN_PASSWORD", Valor: s.SenhaGrafana},
		{Chave: "GRAFANA_PORT", Valor: strconv.Itoa(r.Logs.PortaGrafana)},
		{Chave: "GRAFANA_BIND", Valor: grafanaBind},
		{Chave: "LOKI_RETENTION", Valor: strconv.Itoa(r.Logs.RetencaoDias*24) + "h"},
		{Chave: "POSTGRES_PASSWORD", Valor: s.SenhaBanco},
		{Chave: "RABBITMQ_PASSWORD", Valor: s.SenhaFila},
		{Chave: "TZ", Valor: r.Containers.Fuso},
	}
}

// DoEnv reconstrói as respostas e os segredos de um .env instalado (exportação na desinstalação). O que faltar fica
// com os padrões dados.
func DoEnv(env map[string]string, diretorio, tlsDir string, padrao Respostas) (Respostas, Segredos) {
	r := padrao
	r.Containers.Diretorio = diretorio
	inteiro := func(chave string, atual int) int {
		if n, err := strconv.Atoi(env[chave]); err == nil {
			return n
		}
		return atual
	}
	texto := func(chave, atual string) string {
		if v, ok := env[chave]; ok {
			return v
		}
		return atual
	}
	r.Containers.PortaHTTP = inteiro("HTTP_PORT", r.Containers.PortaHTTP)
	r.Containers.PortaHTTPS = inteiro("HTTPS_PORT", r.Containers.PortaHTTPS)
	r.Containers.Fuso = texto("TZ", r.Containers.Fuso)
	r.Usuarios.Usuario = texto("ADMIN_USERNAME", r.Usuarios.Usuario)
	r.Usuarios.Email = texto("ADMIN_EMAIL", r.Usuarios.Email)
	if u, err := url.Parse(env["PUBLIC_URL"]); err == nil && u.Hostname() != "" {
		r.Rede.Endereco = u.Hostname()
	}
	cert := env["TLS_CERT_PATH"]
	switch {
	case cert == "":
		r.Rede.Certificado = CertificadoAutoassinado
	case tlsDir != "" && filepath.Dir(filepath.Clean(cert)) == filepath.Clean(tlsDir):
		r.Rede.Certificado = CertificadoCALocal
	default:
		r.Rede.Certificado = CertificadoProprio
		r.Rede.CertificadoArquivo, r.Rede.ChaveArquivo = cert, env["TLS_KEY_PATH"]
	}
	r.Maquinas.LocalAtivo = env["M2M_LOCAL_ENABLED"] == "true"
	if r.Maquinas.LocalAtivo {
		r.Maquinas.Nome = texto("M2M_LOCAL_NAME", r.Maquinas.Nome)
		if r.Maquinas.Nome == "" {
			r.Maquinas.Nome = padrao.Maquinas.Nome
		}
		r.Maquinas.DiretorioHost = texto("HOST_HOME_BIND", r.Maquinas.DiretorioHost)
		if v := env["HOST_ROOTS"]; v != "" {
			r.Maquinas.Raizes = strings.Split(v, ",")
		}
	}
	r.Logs.PortaGrafana = inteiro("GRAFANA_PORT", r.Logs.PortaGrafana)
	r.Logs.AcessoRede = env["GRAFANA_BIND"] == "0.0.0.0"
	if h, ok := strings.CutSuffix(env["LOKI_RETENTION"], "h"); ok {
		if n, err := strconv.Atoi(h); err == nil && n >= 24 {
			r.Logs.RetencaoDias = n / 24
		}
	}
	s := Segredos{
		ChaveMestra:  env["APP_SECRET_KEY"],
		SenhaAdmin:   env["ADMIN_PASSWORD"],
		SenhaBanco:   env["POSTGRES_PASSWORD"],
		SenhaFila:    env["RABBITMQ_PASSWORD"],
		SenhaGrafana: env["GRAFANA_ADMIN_PASSWORD"],
	}
	return r, s
}
