package instalacao

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"m2m/installer/internal/executor"
)

// O mesmo vetor está no teste do back (InstaladorIT): os dois lados precisam gerar o mesmo token.
func TestTokenInterno(t *testing.T) {
	if tk := TokenInterno("chave-mestra-de-teste-com-mais-de-16-caracteres"); tk != "9de52f59c11500db1701c04ff2110fcdb3694910df0c06b344e13aeaa1bdf47d" {
		t.Fatalf("token: %s", tk)
	}
}

func TestChamarBackSemSegredosNaLinhaDeComando(t *testing.T) {
	ex := executor.NovoFalso(map[string]executor.Resposta{"docker exec -i m2m-back-java curl": {Saida: `{"conteudo":"x"}`}})
	out, err := ChamarBack(context.Background(), ex, "/config/export", map[string]any{"token": "tk-secreto", "senha": "senha-secreta"})
	if err != nil || string(out) != `{"conteudo":"x"}` {
		t.Fatalf("resposta: %s %v", out, err)
	}
	linha := ex.Chamadas[0]
	if strings.Contains(linha, "secret") || !strings.HasSuffix(linha, "http://127.0.0.1:8080/internal/instalador/config/export") {
		t.Fatalf("linha de comando: %s", linha)
	}
	var corpo map[string]string
	if err := json.Unmarshal(ex.Entradas[linha], &corpo); err != nil || corpo["senha"] != "senha-secreta" {
		t.Fatalf("entrada padrão: %s", ex.Entradas[linha])
	}
}

func TestChamarBackMostraOErroDoM2M(t *testing.T) {
	ex := executor.NovoFalso(map[string]executor.Resposta{"docker exec": {Saida: `{"codigo":"configuracao.senha-incorreta","parametros":{}}`, Erro: "curl: (22) 400"}})
	_, err := ChamarBack(context.Background(), ex, "/config/import", map[string]any{})
	if err == nil || !strings.Contains(err.Error(), "configuracao.senha-incorreta") {
		t.Fatalf("erro: %v", err)
	}
}
