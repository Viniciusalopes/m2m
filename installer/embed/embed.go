// Package embedded guarda o que o executável leva dentro: a interface do quiz, o código do m2m e a versão.
// O conteúdo é gerado por scripts/gerar-instalador.sh; no repositório só existem os .gitkeep.
package embedded

import (
	"embed"
	"errors"
	"io/fs"
	"strings"
)

//go:embed all:conteudo
var conteudo embed.FS

// ErrSemCodigo indica um executável gerado sem o script de geração (sem o código do m2m).
var ErrSemCodigo = errors.New("este executável não contém o código do m2m: gere-o com scripts/gerar-instalador.sh")

// UI devolve os arquivos da interface do quiz.
func UI() fs.FS {
	sub, err := fs.Sub(conteudo, "conteudo/ui")
	if err != nil {
		panic(err)
	}
	return sub
}

// Codigo devolve o código do m2m em tar.gz (git archive).
func Codigo() ([]byte, error) {
	b, err := conteudo.ReadFile("conteudo/m2m.tar.gz")
	if err != nil {
		return nil, ErrSemCodigo
	}
	return b, nil
}

// Versao devolve a versão do m2m embutida (git describe), ou "dev".
func Versao() string {
	b, err := conteudo.ReadFile("conteudo/versao")
	if err != nil {
		return "dev"
	}
	if v := strings.TrimSpace(string(b)); v != "" {
		return v
	}
	return "dev"
}
