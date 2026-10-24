// Package sistema lê o que o instalador precisa saber da máquina sem privilégio: distribuição, arquitetura,
// memória, disco, portas em uso, fuso horário e endereços.
package sistema

import (
	"bufio"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata" // fusos conhecidos mesmo sem /usr/share/zoneinfo
)

// SO é a distribuição lida de /etc/os-release. Num derivado do Ubuntu (Linux Mint, Pop!_OS, Zorin OS etc.),
// CodinomeUbuntu é o do Ubuntu de base: é ele que vale para o repositório do Docker e para "testado".
type SO struct {
	ID             string `json:"id"`
	Versao         string `json:"versao"`
	Codinome       string `json:"codinome"`
	Nome           string `json:"nome"`
	Familia        string `json:"familia"`
	CodinomeUbuntu string `json:"codinomeUbuntu"`
}

// codinomesTestados são as versões do Ubuntu validadas a cada release, pelo codinome.
var codinomesTestados = map[string]string{
	"jammy":    "22.04",
	"noble":    "24.04",
	"resolute": "26.04",
}

// LerOSRelease interpreta o formato de /etc/os-release.
func LerOSRelease(r io.Reader) SO {
	v := map[string]string{}
	sc := bufio.NewScanner(r)
	for sc.Scan() {
		linha := strings.TrimSpace(sc.Text())
		if linha == "" || strings.HasPrefix(linha, "#") {
			continue
		}
		k, val, ok := strings.Cut(linha, "=")
		if !ok {
			continue
		}
		v[k] = strings.Trim(val, `"'`)
	}
	so := SO{ID: strings.ToLower(v["ID"]), Versao: v["VERSION_ID"], Codinome: v["VERSION_CODENAME"], Nome: v["PRETTY_NAME"]}
	if so.Codinome == "" {
		so.Codinome = v["UBUNTU_CODENAME"]
	}
	if so.ID == "ubuntu" || contem(strings.Fields(strings.ToLower(v["ID_LIKE"])), "ubuntu") {
		so.Familia = "ubuntu"
		so.CodinomeUbuntu = v["UBUNTU_CODENAME"]
		if so.CodinomeUbuntu == "" && so.ID == "ubuntu" {
			so.CodinomeUbuntu = so.Codinome
		}
	}
	return so
}

func contem(lista []string, valor string) bool {
	for _, v := range lista {
		if v == valor {
			return true
		}
	}
	return false
}

// DetectarSO lê o os-release a partir da raiz dada ("/" na máquina real).
func DetectarSO(raiz string) (SO, error) {
	for _, p := range []string{"etc/os-release", "usr/lib/os-release"} {
		f, err := os.Open(filepath.Join(raiz, p))
		if err != nil {
			continue
		}
		defer f.Close()
		return LerOSRelease(f), nil
	}
	return SO{}, os.ErrNotExist
}

// Suportado diz se a distribuição é o Ubuntu ou um derivado dele com o codinome do Ubuntu de base.
func (s SO) Suportado() bool {
	return s.Familia == "ubuntu" && s.CodinomeUbuntu != ""
}

// Testado diz se o Ubuntu (ou o Ubuntu de base do derivado) está entre as versões validadas.
func (s SO) Testado() bool {
	_, ok := codinomesTestados[s.CodinomeUbuntu]
	return ok
}

// VersoesSuportadas descreve as distribuições e versões validadas, para mensagens.
func VersoesSuportadas() string {
	return "Ubuntu 22.04, 24.04 e 26.04 e derivados (Linux Mint, Pop!_OS, Zorin OS etc.), em amd64"
}

// ArquiteturaSuportada diz se a máquina (uname -m) é amd64, a única arquitetura do instalador.
func ArquiteturaSuportada(maquina string) bool {
	return maquina == "x86_64" || maquina == "amd64"
}

// MemoriaTotal devolve a memória total em bytes, lida de <raiz>/proc/meminfo.
func MemoriaTotal(raiz string) (uint64, error) {
	f, err := os.Open(filepath.Join(raiz, "proc/meminfo"))
	if err != nil {
		return 0, err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		campos := strings.Fields(sc.Text())
		if len(campos) >= 2 && campos[0] == "MemTotal:" {
			kb, err := strconv.ParseUint(campos[1], 10, 64)
			if err != nil {
				return 0, err
			}
			return kb * 1024, nil
		}
	}
	return 0, io.ErrUnexpectedEOF
}

// PortasEmUso devolve as portas TCP em LISTEN, lidas de <raiz>/proc/net/tcp e tcp6. Um usuário comum não consegue
// abrir as portas 80 e 443 para testar, mas pode ler essas tabelas.
func PortasEmUso(raiz string) (map[int]bool, error) {
	portas := map[int]bool{}
	lidos := 0
	for _, nome := range []string{"proc/net/tcp", "proc/net/tcp6"} {
		f, err := os.Open(filepath.Join(raiz, nome))
		if err != nil {
			continue
		}
		lidos++
		sc := bufio.NewScanner(f)
		sc.Scan() // cabeçalho
		for sc.Scan() {
			campos := strings.Fields(sc.Text())
			if len(campos) < 4 || campos[3] != "0A" { // 0A = LISTEN
				continue
			}
			i := strings.LastIndex(campos[1], ":")
			if i < 0 {
				continue
			}
			p, err := strconv.ParseUint(campos[1][i+1:], 16, 16)
			if err == nil {
				portas[int(p)] = true
			}
		}
		f.Close()
	}
	if lidos == 0 {
		return nil, os.ErrNotExist
	}
	return portas, nil
}

// FusoHorario devolve o fuso do sistema (/etc/timezone ou o link /etc/localtime), ou "UTC". Um valor que não é um
// fuso conhecido (há imagens com "/UTC" em /etc/timezone) é ignorado.
func FusoHorario(raiz string) string {
	valido := func(tz string) bool {
		_, err := time.LoadLocation(tz)
		return tz != "" && !strings.HasPrefix(tz, "/") && err == nil
	}
	if b, err := os.ReadFile(filepath.Join(raiz, "etc/timezone")); err == nil {
		if tz := strings.TrimSpace(string(b)); valido(tz) {
			return tz
		}
	}
	if alvo, err := os.Readlink(filepath.Join(raiz, "etc/localtime")); err == nil {
		if _, tz, ok := strings.Cut(filepath.Clean(alvo), "zoneinfo/"); ok && valido(tz) {
			return tz
		}
	}
	return "UTC"
}

// ExistenteMaisProximo sobe pelos diretórios até achar um que exista (para medir o disco de um caminho a criar).
func ExistenteMaisProximo(caminho string) string {
	p := filepath.Clean(caminho)
	for {
		if _, err := os.Stat(p); err == nil {
			return p
		}
		pai := filepath.Dir(p)
		if pai == p {
			return p
		}
		p = pai
	}
}
