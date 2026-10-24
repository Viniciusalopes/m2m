// Package comandos monta os comandos que o usuário cola no terminal para o que o instalador não pode fazer como
// usuário comum, para Ubuntu e derivados. Todos os modelos ficam neste arquivo. O instalador nunca roda sudo.
package comandos

import (
	"fmt"
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
	Privilegio   bool   `json:"privilegio"`
}

// Ambiente é o que muda o texto dos comandos.
type Ambiente struct {
	SO         sistema.SO
	Usuario    string // nome do usuário que roda o instalador
	Executavel string // caminho do próprio instalador, para relançá-lo
}

const caCA = "/usr/local/share/ca-certificates/m2m-local-ca.crt"

// comoRoot monta um bloco de linhas para rodar como root: "sudo sh -eu -c '…'". As linhas não podem ter aspas simples.
func (a Ambiente) comoRoot(linhas ...string) string {
	return "sudo sh -eu -c '\n" + strings.Join(linhas, "\n") + "\n'"
}

func (a Ambiente) senhaPedida() string {
	return " O terminal pede a sua senha (sudo)."
}

// Aspas protege um valor para o shell com aspas simples; valores só com caracteres seguros saem sem aspas.
func Aspas(s string) string {
	if s != "" && strings.Trim(s, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/._-+=:@,") == "" {
		return s
	}
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

// duplas protege um caminho dentro de um bloco comoRoot, que já está entre aspas simples.
func duplas(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `"`, `\"`, "$", `\$`, "`", "\\`")
	return `"` + r.Replace(s) + `"`
}

// PacotesBase instala as ferramentas usadas pelo instalador e pelo m2m no host.
func (a Ambiente) PacotesBase() Comando {
	return Comando{
		ID:           "pacotes-base",
		Titulo:       "Instalar as ferramentas básicas",
		OQueFaz:      "Atualiza a lista de pacotes do " + a.nomeSO() + " e instala ca-certificates, curl, openssl e libnss3-tools." + a.senhaPedida(),
		ParaQueServe: "O openssl cria o certificado HTTPS do m2m, o curl baixa a chave do repositório do Docker e o libnss3-tools (certutil) faz o Chrome confiar no certificado.",
		Texto: a.comoRoot(
			"apt-get update",
			"apt-get install -y ca-certificates curl openssl libnss3-tools",
		),
		Privilegio: true,
	}
}

func (a Ambiente) nomeSO() string {
	if a.SO.Nome != "" {
		return a.SO.Nome
	}
	return a.SO.ID + " " + a.SO.Versao
}

// InstalarDocker adiciona o repositório oficial do Docker para Ubuntu e instala o Engine, o Buildx e o Compose v2. Num
// derivado, o repositório é o do Ubuntu de base (UBUNTU_CODENAME).
func (a Ambiente) InstalarDocker() Comando {
	return Comando{
		ID:     "instalar-docker",
		Titulo: "Instalar o Docker",
		OQueFaz: fmt.Sprintf("Adiciona o repositório oficial do Docker para o Ubuntu (%s), com a chave de assinatura em /etc/apt/keyrings, "+
			"e instala o Docker Engine, o Buildx e o Docker Compose v2 no %s.", a.SO.CodinomeUbuntu, a.nomeSO()) + a.senhaPedida(),
		ParaQueServe: "O m2m roda em containers: o Docker constrói as imagens nesta máquina e o Compose sobe o banco, a fila, o back, o front e os logs.",
		Texto: a.comoRoot(
			"apt-get update",
			"apt-get install -y ca-certificates curl",
			"install -m 0755 -d /etc/apt/keyrings",
			"curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc",
			"chmod a+r /etc/apt/keyrings/docker.asc",
			`echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu `+a.SO.CodinomeUbuntu+` stable" > /etc/apt/sources.list.d/docker.list`,
			"apt-get update",
			"apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin",
		),
		Privilegio: true,
	}
}

// ComplementosUbuntu instala o Compose v2 e o Buildx do próprio Ubuntu, para o Docker do pacote docker.io:
// o repositório oficial trocaria o Engine já instalado.
func (a Ambiente) ComplementosUbuntu() Comando {
	return Comando{
		ID:           "complementos-docker",
		Titulo:       "Instalar o Docker Compose v2 e o Buildx",
		OQueFaz:      "Instala os pacotes docker-compose-v2 e docker-buildx do " + a.nomeSO() + ", que acompanham o Docker (docker.io) já instalado." + a.senhaPedida(),
		ParaQueServe: "O Compose sobe o banco, a fila, o back, o front e os logs; o Buildx constrói as imagens do m2m nesta máquina.",
		Texto: a.comoRoot(
			"apt-get update",
			"apt-get install -y docker-compose-v2 docker-buildx",
		),
		Privilegio: true,
	}
}

// IniciarDocker liga o serviço do Docker, instalado mas parado.
func (a Ambiente) IniciarDocker() Comando {
	return Comando{
		ID:           "iniciar-docker",
		Titulo:       "Iniciar o Docker",
		OQueFaz:      "Liga o serviço do Docker agora e a cada inicialização da máquina." + a.senhaPedida(),
		ParaQueServe: "O Docker está instalado, mas o serviço não está respondendo; sem ele o m2m não sobe.",
		Texto:        a.comoRoot("systemctl enable --now docker"),
		Privilegio:   true,
	}
}

// GrupoDocker inclui o usuário no grupo docker.
func (a Ambiente) GrupoDocker() Comando {
	return Comando{
		ID:     "grupo-docker",
		Titulo: "Permitir que " + a.Usuario + " use o Docker",
		OQueFaz: "Inclui o usuário " + a.Usuario + " no grupo docker." + a.senhaPedida() +
			" Pertencer ao grupo docker equivale a ter poder de administrador na máquina, como o m2m já exige hoje.",
		ParaQueServe: "O instalador e os atalhos m2m-up e m2m-down usam o Docker sem sudo. O grupo só vale numa sessão nova: depois deste comando, use o comando seguinte para abrir o instalador de novo.",
		Texto:        a.comoRoot("usermod -aG docker " + a.Usuario),
		Privilegio:   true,
	}
}

// Relancar abre o instalador numa sessão com o grupo docker aplicado.
func (a Ambiente) Relancar() Comando {
	return Comando{
		ID:           "relancar",
		Titulo:       "Abrir o instalador de novo com o grupo docker",
		OQueFaz:      "Encerre este instalador com Ctrl+C no terminal e rode o comando abaixo: ele abre o instalador numa sessão em que o grupo docker já vale. Sair e entrar de novo na sessão também funciona.",
		ParaQueServe: "A inclusão no grupo docker não vale para os programas que já estavam abertos, inclusive este instalador.",
		Texto:        "sg docker -c " + Aspas(Aspas(a.Executavel)),
	}
}

// ConfiarCA instala a CA local do m2m na confiança do sistema.
func (a Ambiente) ConfiarCA(caCrt string) Comando {
	return Comando{
		ID:     "confiar-ca",
		Titulo: "Confiar no certificado do m2m nesta máquina",
		OQueFaz: "Copia a CA local do m2m (" + caCrt + ") para " + caCA + " e atualiza a lista de autoridades confiáveis do sistema." +
			a.senhaPedida(),
		ParaQueServe: "Sem isso, programas da máquina (curl, wget e o navegador fora do Chrome) avisam que o certificado do m2m não é confiável. No Chrome o instalador já registrou a CA, se o certutil estiver instalado. Para o Firefox e outros dispositivos, importe o mesmo arquivo manualmente.",
		Texto: a.comoRoot(
			"install -m 644 "+duplas(caCrt)+" "+caCA,
			"update-ca-certificates",
		),
		Privilegio: true,
	}
}

// RemoverCA tira a CA local do m2m da confiança do sistema (desinstalação).
func (a Ambiente) RemoverCA() Comando {
	return Comando{
		ID:           "remover-ca",
		Titulo:       "Tirar o certificado do m2m da confiança do sistema",
		OQueFaz:      "Apaga " + caCA + " e reconstrói a lista de autoridades confiáveis do sistema." + a.senhaPedida(),
		ParaQueServe: "A CA local do m2m foi apagada; sem este comando, o sistema continua confiando nela.",
		Texto:        a.comoRoot("rm -f "+caCA, "update-ca-certificates --fresh"),
		Privilegio:   true,
	}
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

// RemoverDocker desinstala o Docker (opcional na desinstalação; o instalador nunca faz isso).
func (a Ambiente) RemoverDocker() Comando {
	return Comando{
		ID:     "remover-docker",
		Titulo: "Opcional: desinstalar o Docker",
		OQueFaz: "Remove os pacotes do Docker, o repositório oficial e todos os containers, imagens e volumes da máquina, de qualquer projeto." +
			a.senhaPedida(),
		ParaQueServe: "Só para quem instalou o Docker apenas para o m2m. Se outro projeto usa o Docker, não rode.",
		Texto: a.comoRoot(
			"apt-get purge -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin docker-ce-rootless-extras",
			"rm -rf /var/lib/docker /var/lib/containerd",
			"rm -f /etc/apt/sources.list.d/docker.list /etc/apt/keyrings/docker.asc",
		),
		Privilegio: true,
	}
}
