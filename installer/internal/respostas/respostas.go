// Package respostas define as respostas do quiz por etapa, os padrões, a validação de cada campo e a geração dos
// segredos.
package respostas

import (
	"crypto/rand"
	"encoding/base64"
	"math/big"
	"path/filepath"
	"strings"
)

type Containers struct {
	Diretorio  string `json:"diretorio"`
	PortaHTTP  int    `json:"portaHttp"`
	PortaHTTPS int    `json:"portaHttps"`
	Fuso       string `json:"fuso"`
}

type Usuarios struct {
	Usuario string `json:"usuario"`
	Email   string `json:"email"`
}

// Tipos de certificado da etapa Rede.
const (
	CertificadoCALocal      = "ca-local"
	CertificadoProprio      = "proprio"
	CertificadoAutoassinado = "autoassinado"
)

type Rede struct {
	Endereco           string `json:"endereco"`
	Certificado        string `json:"certificado"`
	CertificadoArquivo string `json:"certificadoArquivo"`
	ChaveArquivo       string `json:"chaveArquivo"`
}

type Maquinas struct {
	LocalAtivo    bool     `json:"localAtivo"`
	Nome          string   `json:"nome"`
	DiretorioHost string   `json:"diretorioHost"`
	Raizes        []string `json:"raizes"`
}

type Logs struct {
	PortaGrafana int  `json:"portaGrafana"`
	AcessoRede   bool `json:"acessoRede"`
	RetencaoDias int  `json:"retencaoDias"`
}

// Respostas são as escolhas não secretas do quiz; vão legíveis no arquivo .json da instalação.
type Respostas struct {
	Containers Containers `json:"containers"`
	Usuarios   Usuarios   `json:"usuarios"`
	Rede       Rede       `json:"rede"`
	Maquinas   Maquinas   `json:"maquinas"`
	Logs       Logs       `json:"logs"`
}

// Segredos vão só cifrados no arquivo .json e nunca são gravados em disco pelo instalador, exceto no .env.
type Segredos struct {
	ChaveMestra  string `json:"chaveMestra"`
	SenhaAdmin   string `json:"senhaAdmin"`
	SenhaBanco   string `json:"senhaBanco"`
	SenhaFila    string `json:"senhaFila"`
	SenhaGrafana string `json:"senhaGrafana"`
}

// Maquina é o que os padrões precisam saber da máquina.
type Maquina struct {
	Home     string
	Hostname string
	Fuso     string
	Endereco string // primeiro endereço detectado
}

// MontagemHost é onde o diretório do host aparece no m2m: o compose monta HOST_HOME_BIND em /hostfs/home.
const MontagemHost = "/home"

// Padroes devolve as respostas padrão da spec para esta máquina.
func Padroes(m Maquina) Respostas {
	return Respostas{
		Containers: Containers{Diretorio: filepath.Join(m.Home, "m2m"), PortaHTTP: 80, PortaHTTPS: 443, Fuso: m.Fuso},
		Usuarios:   Usuarios{Usuario: "admin"},
		Rede:       Rede{Endereco: m.Endereco, Certificado: CertificadoCALocal},
		Maquinas:   Maquinas{LocalAtivo: true, Nome: NomeMaquina(m.Hostname), DiretorioHost: "/home", Raizes: []string{MontagemHost}},
		Logs:       Logs{PortaGrafana: 3000, RetencaoDias: 7},
	}
}

// NomeMaquina converte o hostname no nome padrão da máquina LOCAL (maiúsculas, sem domínio).
func NomeMaquina(hostname string) string {
	h, _, _ := strings.Cut(hostname, ".")
	h = strings.ToUpper(strings.TrimSpace(h))
	if h == "" {
		return "LOCAL"
	}
	return h
}

// GerarSegredos gera a chave mestra e as senhas internas; a senha do administrador fica para o usuário.
func GerarSegredos() Segredos {
	return Segredos{
		ChaveMestra:  GerarChaveMestra(),
		SenhaBanco:   GerarSenha(24),
		SenhaFila:    GerarSenha(24),
		SenhaGrafana: GerarSenha(24),
	}
}

// GerarChaveMestra devolve 32 bytes aleatórios em base64, como "openssl rand -base64 32".
func GerarChaveMestra() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return base64.StdEncoding.EncodeToString(b)
}

const alfanumericos = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

// GerarSenha devolve uma senha alfanumérica de n caracteres (crypto/rand).
func GerarSenha(n int) string {
	var b strings.Builder
	max := big.NewInt(int64(len(alfanumericos)))
	for range n {
		i, err := rand.Int(rand.Reader, max)
		if err != nil {
			panic(err)
		}
		b.WriteByte(alfanumericos[i.Int64()])
	}
	return b.String()
}
