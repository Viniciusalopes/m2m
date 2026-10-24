package instalacao

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"

	"m2m/installer/internal/executor"
)

// ContainerBack é o container do back, onde ficam as rotas internas do instalador.
const ContainerBack = "m2m-back-java"

// TokenInterno é o token das rotas internas do instalador: HMAC-SHA256 de "m2m-instalador" com a chave mestra
// (InstallerController do back).
func TokenInterno(chaveMestra string) string {
	m := hmac.New(sha256.New, []byte(chaveMestra))
	m.Write([]byte("m2m-instalador"))
	return hex.EncodeToString(m.Sum(nil))
}

// ChamarBack faz um POST numa rota interna do back de dentro do container (docker exec ... curl), com o corpo JSON
// pela entrada padrão: nem a senha nem o token aparecem na linha de comando. Devolve a resposta do back.
func ChamarBack(ctx context.Context, ex executor.Executor, rota string, corpo map[string]any) ([]byte, error) {
	b, err := json.Marshal(corpo)
	if err != nil {
		return nil, err
	}
	out, err := ex.Rodar(ctx, executor.Comando{Programa: "docker", Args: []string{"exec", "-i", ContainerBack,
		"curl", "-sS", "--fail-with-body", "-X", "POST", "-H", "Content-Type: application/json", "--data-binary", "@-",
		"http://127.0.0.1:8080/internal/instalador" + rota}, Entrada: b})
	if err != nil {
		// com --fail-with-body, a saída traz o erro do back ({"codigo": ...})
		var e struct {
			Codigo     string         `json:"codigo"`
			Parametros map[string]any `json:"parametros"`
		}
		if json.Unmarshal(out, &e) == nil && e.Codigo != "" {
			return nil, fmt.Errorf("o m2m recusou a operação: %s %v", e.Codigo, e.Parametros)
		}
		var ee *executor.Erro
		if errors.As(err, &ee) {
			return nil, fmt.Errorf("não foi possível falar com o m2m (%s): %s", ContainerBack, ee.Saida)
		}
		return nil, err
	}
	return out, nil
}
