package sistema

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLerOSRelease(t *testing.T) {
	casos := []struct {
		nome, texto        string
		familia, base      string
		suportado, testado bool
	}{
		{"Ubuntu 24.04", "PRETTY_NAME=\"Ubuntu 24.04.3 LTS\"\nID=ubuntu\nVERSION_ID=\"24.04\"\nVERSION_CODENAME=noble\nUBUNTU_CODENAME=noble\n", "ubuntu", "noble", true, true},
		{"Ubuntu 22.04 sem UBUNTU_CODENAME", "ID=ubuntu\nVERSION_ID=\"22.04\"\nVERSION_CODENAME=jammy\n", "ubuntu", "jammy", true, true},
		{"Ubuntu 26.04", "ID=ubuntu\nVERSION_ID=\"26.04\"\nVERSION_CODENAME=resolute\nUBUNTU_CODENAME=resolute\n", "ubuntu", "resolute", true, true},
		{"Ubuntu 25.10", "ID=ubuntu\nVERSION_ID=\"25.10\"\nUBUNTU_CODENAME=questing\n", "ubuntu", "questing", true, false},
		{"Linux Mint 22", "ID=linuxmint\nID_LIKE=\"ubuntu debian\"\nVERSION_ID=\"22\"\nVERSION_CODENAME=wilma\nUBUNTU_CODENAME=noble\n", "ubuntu", "noble", true, true},
		{"Pop!_OS 22.04", "ID=pop\nID_LIKE=\"ubuntu debian\"\nVERSION_ID=\"22.04\"\nVERSION_CODENAME=jammy\nUBUNTU_CODENAME=jammy\n", "ubuntu", "jammy", true, true},
		{"derivado sem codinome", "ID=algum\nID_LIKE=ubuntu\nVERSION_ID=\"1\"\n", "ubuntu", "", false, false},
		{"Debian 13", "ID=debian\nVERSION_ID=\"13\"\nVERSION_CODENAME=trixie\n", "", "", false, false},
		{"Fedora", "ID=fedora\nVERSION_ID=42\n", "", "", false, false},
	}
	for _, c := range casos {
		so := LerOSRelease(strings.NewReader(c.texto))
		if so.Familia != c.familia || so.CodinomeUbuntu != c.base || so.Suportado() != c.suportado || so.Testado() != c.testado {
			t.Errorf("%s: %+v suportado=%v testado=%v", c.nome, so, so.Suportado(), so.Testado())
		}
	}
}

func TestArquitetura(t *testing.T) {
	if !ArquiteturaSuportada("x86_64") || !ArquiteturaSuportada("amd64") {
		t.Error("amd64 recusado")
	}
	for _, m := range []string{"aarch64", "arm64", "armv7l", "i686", ""} {
		if ArquiteturaSuportada(m) {
			t.Errorf("%q aceita", m)
		}
	}
}

func TestFusoHorario(t *testing.T) {
	raiz := t.TempDir()
	if FusoHorario(raiz) != "UTC" {
		t.Error("sem arquivos, esperava UTC")
	}
	os.MkdirAll(filepath.Join(raiz, "etc"), 0o755)
	os.WriteFile(filepath.Join(raiz, "etc/timezone"), []byte("/UTC\n"), 0o644)                 // inválido: vale o link
	os.Symlink("/usr/share/zoneinfo//America/Sao_Paulo", filepath.Join(raiz, "etc/localtime")) // barra dupla, como no Ubuntu
	if tz := FusoHorario(raiz); tz != "America/Sao_Paulo" {
		t.Errorf("pelo link: %s", tz)
	}
	os.WriteFile(filepath.Join(raiz, "etc/timezone"), []byte("America/Manaus\n"), 0o644)
	if tz := FusoHorario(raiz); tz != "America/Manaus" {
		t.Errorf("por /etc/timezone: %s", tz)
	}
}

func TestExistenteMaisProximo(t *testing.T) {
	raiz := t.TempDir()
	if p := ExistenteMaisProximo(filepath.Join(raiz, "a", "b", "c")); p != raiz {
		t.Errorf("esperava %s, veio %s", raiz, p)
	}
}
