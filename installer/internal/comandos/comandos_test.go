package comandos

import (
	"strings"
	"testing"

	"m2m/installer/internal/sistema"
)

var distros = []sistema.SO{
	{ID: "ubuntu", Versao: "22.04", Codinome: "jammy", Nome: "Ubuntu 22.04", Familia: "ubuntu", CodinomeUbuntu: "jammy"},
	{ID: "ubuntu", Versao: "24.04", Codinome: "noble", Nome: "Ubuntu 24.04", Familia: "ubuntu", CodinomeUbuntu: "noble"},
	{ID: "ubuntu", Versao: "26.04", Codinome: "resolute", Nome: "Ubuntu 26.04", Familia: "ubuntu", CodinomeUbuntu: "resolute"},
	{ID: "linuxmint", Versao: "22", Codinome: "wilma", Nome: "Linux Mint 22", Familia: "ubuntu", CodinomeUbuntu: "noble"},
}

func TestInstalarDockerPorDistribuicao(t *testing.T) {
	for _, so := range distros {
		c := Ambiente{SO: so, Usuario: "ana"}.InstalarDocker()
		repo := "https://download.docker.com/linux/ubuntu " + so.CodinomeUbuntu + " stable"
		if !strings.Contains(c.Texto, repo) {
			t.Errorf("%s: repositório errado:\n%s", so.Nome, c.Texto)
		}
		if !strings.Contains(c.Texto, "/linux/ubuntu/gpg") || !strings.Contains(c.Texto, "docker-compose-plugin") {
			t.Errorf("%s: faltou a chave ou o Compose:\n%s", so.Nome, c.Texto)
		}
		if !strings.HasPrefix(c.Texto, "sudo sh -eu -c '\n") || strings.Count(c.Texto, "'") != 2 {
			t.Errorf("%s: o bloco precisa ser um único sudo sh -c entre aspas simples:\n%s", so.Nome, c.Texto)
		}
		if c.OQueFaz == "" || c.ParaQueServe == "" || !c.Privilegio {
			t.Errorf("%s: comando sem explicação", so.Nome)
		}
	}
}

func TestDerivadoUsaCodinomeDoUbuntu(t *testing.T) {
	c := Ambiente{SO: distros[3], Usuario: "ana"}.InstalarDocker()
	if strings.Contains(c.Texto, "wilma") || !strings.Contains(c.Texto, " noble stable") {
		t.Errorf("o Mint deve usar o repositório do Ubuntu de base:\n%s", c.Texto)
	}
	if !strings.Contains(c.OQueFaz, "Linux Mint 22") {
		t.Errorf("a explicação deve citar o sistema detectado: %s", c.OQueFaz)
	}
}

func TestSempreSudo(t *testing.T) {
	a := Ambiente{SO: distros[1], Usuario: "ana"}
	for _, c := range []Comando{a.PacotesBase(), a.InstalarDocker(), a.ComplementosUbuntu(), a.GrupoDocker(), a.ConfiarCA("/home/ana/.config/m2m/tls/ca.crt"), a.RemoverCA(), a.RemoverDocker()} {
		if !strings.HasPrefix(c.Texto, "sudo sh -eu -c '\n") || strings.Contains(c.Texto, "su -c") {
			t.Errorf("%s: esperava sudo sh -c:\n%s", c.ID, c.Texto)
		}
		if !strings.Contains(c.OQueFaz, "senha (sudo)") {
			t.Errorf("%s: deveria avisar que pede a senha do sudo", c.ID)
		}
	}
}

func TestGrupoERelancar(t *testing.T) {
	a := Ambiente{SO: distros[1], Usuario: "ana", Executavel: "/home/ana/Downloads/m2m instalador"}
	if g := a.GrupoDocker(); !strings.Contains(g.Texto, "usermod -aG docker ana") {
		t.Errorf("grupo: %s", g.Texto)
	}
	if r := a.Relancar(); r.Texto != `sg docker -c ''\''/home/ana/Downloads/m2m instalador'\'''` || r.Privilegio {
		t.Errorf("relançar: %s", r.Texto)
	}
	a.Executavel = "/home/ana/m2m-instalador"
	if r := a.Relancar(); r.Texto != "sg docker -c /home/ana/m2m-instalador" {
		t.Errorf("relançar sem espaços: %s", r.Texto)
	}
}

func TestConfiarCAComCaminhoEntreAspasDuplas(t *testing.T) {
	c := Ambiente{SO: distros[1]}.ConfiarCA("/home/ana/.config/m2m/tls/ca.crt")
	if !strings.Contains(c.Texto, `install -m 644 "/home/ana/.config/m2m/tls/ca.crt" /usr/local/share/ca-certificates/m2m-local-ca.crt`) {
		t.Errorf("confiar na CA:\n%s", c.Texto)
	}
}
