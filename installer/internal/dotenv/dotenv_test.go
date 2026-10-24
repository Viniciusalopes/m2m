package dotenv

import (
	"strings"
	"testing"
)

const modelo = `# Comentário do topo
ADMIN_USERNAME=admin
ADMIN_PASSWORD=
# APP_SECRET_KEY= comentário que não é chave
APP_SECRET_KEY=
TZ=America/Sao_Paulo
`

func TestGerarMantemComentariosESubstitui(t *testing.T) {
	out := string(Gerar([]byte(modelo), []Par{
		{"ADMIN_PASSWORD", "S3nha#com$especiais"},
		{"APP_SECRET_KEY", "abc+/=="},
		{"M2M_LOCAL_NAME", "NOTEBOOK"},
	}))
	for _, esperado := range []string{
		"# Comentário do topo\n",
		"ADMIN_USERNAME=admin\n",
		"ADMIN_PASSWORD='S3nha#com$especiais'\n",
		"# APP_SECRET_KEY= comentário que não é chave\n",
		"APP_SECRET_KEY=abc+/==\n",
		"TZ=America/Sao_Paulo\n",
		"M2M_LOCAL_NAME=NOTEBOOK\n",
	} {
		if !strings.Contains(out, esperado) {
			t.Errorf("faltou %q em:\n%s", esperado, out)
		}
	}
	if strings.Count(out, "APP_SECRET_KEY=abc") != 1 {
		t.Errorf("chave substituída mais de uma vez:\n%s", out)
	}
}

func TestLerDesfazGerar(t *testing.T) {
	pares := []Par{{"ADMIN_PASSWORD", "S3nha#com$especiais e espaço"}, {"TZ", "UTC"}, {"VAZIA", ""}}
	m := Ler(Gerar([]byte(modelo), pares))
	for _, p := range pares {
		if m[p.Chave] != p.Valor {
			t.Errorf("%s: esperava %q, veio %q", p.Chave, p.Valor, m[p.Chave])
		}
	}
	if m["ADMIN_USERNAME"] != "admin" {
		t.Errorf("chave do modelo perdida: %q", m["ADMIN_USERNAME"])
	}
}

func TestLerAspasDuplasEComentarioNoFim(t *testing.T) {
	m := Ler([]byte("A=\"x y\"\nB=valor # comentário\nexport C=1\n"))
	if m["A"] != "x y" || m["B"] != "valor" || m["C"] != "1" {
		t.Fatalf("leitura inesperada: %v", m)
	}
}
