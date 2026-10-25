// Package comandos monta os cartões de comando opcionais do quiz, que rodam sem privilégio, e os avisos sobre o que
// exige privilégio. O que pede sudo (pacotes, Docker, grupo docker, CA no sistema) é feito pelo instalar.sh, nunca
// pelo quiz.
package comandos

import (
	"strings"

	"m2m/installer/internal/sistema"
)

// Comando é um cartão do quiz: título, o que faz, para que serve e, por último, o texto para copiar.
type Comando struct {
	ID           string `json:"id"`
	Titulo       string `json:"titulo"`
	OQueFaz      string `json:"oQueFaz"`
	ParaQueServe string `json:"paraQueServe"`
	Texto        string `json:"texto"`
}

// Ambiente é o que muda o texto dos comandos e dos avisos.
type Ambiente struct {
	SO      sistema.SO
	Usuario string // nome do usuário que roda o instalador
	// PeloScript diz se o instalador foi aberto pelo instalar.sh, que conclui o que exige privilégio ao encerrar.
	PeloScript bool
}

// CASistema é o arquivo da CA local do m2m na confiança do sistema, gravado e removido pelo instalar.sh.
const CASistema = "/usr/local/share/ca-certificates/m2m-local-ca.crt"

// concluir diz quem faz a ação de sistema: o instalar.sh ao encerrar, ou o instalar.sh --concluir depois.
func (a Ambiente) concluir(acao string) string {
	if a.PeloScript {
		return "O instalar.sh " + acao + ", com sudo, quando você encerrar o instalador."
	}
	return "Para isso, rode no terminal o instalar.sh --concluir, que " + acao + " com sudo."
}

// AvisoConfiarCA explica que a CA local ainda não está na confiança do sistema.
func (a Ambiente) AvisoConfiarCA() string {
	return "A CA local do m2m ainda não está na confiança do sistema (curl, wget e navegadores fora do Chrome avisam " +
		"que o certificado não é confiável). " + a.concluir("a instala")
}

// AvisoRemoverCA explica que a CA local continua na confiança do sistema depois da desinstalação.
func (a Ambiente) AvisoRemoverCA() string {
	return "A CA local do m2m continua na confiança do sistema. " + a.concluir("a retira e pergunta se você quer remover também o Docker")
}

// RemoverImagensPublicas apaga as imagens públicas usadas pelo m2m (opcional na desinstalação).
func RemoverImagensPublicas(imagens []string) Comando {
	return Comando{
		ID:           "remover-imagens-publicas",
		Titulo:       "Opcional: apagar as imagens públicas usadas pelo m2m",
		OQueFaz:      "Apaga as imagens " + strings.Join(imagens, ", ") + ". O Docker recusa apagar as que estiverem em uso por outro container.",
		ParaQueServe: "Libera espaço em disco. O instalador não as apaga porque outros projetos podem usar as mesmas imagens.",
		Texto:        "docker image rm " + strings.Join(imagens, " "),
	}
}

// LimparCacheBuild apaga o cache de build do Docker (opcional na desinstalação).
func LimparCacheBuild() Comando {
	return Comando{
		ID:           "limpar-cache-build",
		Titulo:       "Opcional: apagar o cache de build do Docker",
		OQueFaz:      "Apaga o cache de build de todos os projetos desta máquina.",
		ParaQueServe: "Libera os gigabytes usados no build do m2m. O próximo build de qualquer projeto baixa tudo de novo.",
		Texto:        "docker builder prune -f",
	}
}
