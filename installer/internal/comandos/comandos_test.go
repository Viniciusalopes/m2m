package comandos

import (
	"strings"
	"testing"
)

func TestAvisosDizemQuemConclui(t *testing.T) {
	pelo := Ambiente{PeloScript: true}
	if a := pelo.AvisoConfiarCA(); !strings.Contains(a, "quando você encerrar o instalador") || strings.Contains(a, "--concluir") {
		t.Errorf("aberto pelo script: %q", a)
	}
	direto := Ambiente{}
	if a := direto.AvisoConfiarCA(); !strings.Contains(a, "instalar.sh --concluir") {
		t.Errorf("aberto direto: %q", a)
	}
	if a := pelo.AvisoRemoverCA(); !strings.Contains(a, "remover também o Docker") {
		t.Errorf("remover CA: %q", a)
	}
}

func TestOpcionaisSemPrivilegio(t *testing.T) {
	for _, c := range []Comando{RemoverImagensPublicas([]string{"grafana/loki:3.6.0"}), LimparCacheBuild()} {
		if strings.Contains(c.Texto, "sudo") || c.OQueFaz == "" || c.ParaQueServe == "" {
			t.Errorf("%s: %+v", c.ID, c)
		}
	}
}
