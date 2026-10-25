// Package prerequisitos verifica, sem privilégio, se a máquina está pronta para construir e rodar o m2m. Quem prepara
// a máquina é o instalar.sh; o quiz só mostra o que falta e orienta a rodá-lo.
package prerequisitos

import (
	"bufio"
	"context"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"m2m/installer/internal/comandos"
	"m2m/installer/internal/executor"
	"m2m/installer/internal/sistema"
)

// Estados de um item.
const (
	Atendido = "atendido"
	Pendente = "pendente" // essencial e não atendido: bloqueia o avanço
	Opcional = "opcional" // não atendido, mas não impede a instalação
)

// Mínimos para construir e rodar o m2m, medidos numa VM Ubuntu 24.04 de 4 GiB e 1 CPU: pico de 1,6 GiB de RAM no
// build e cerca de 5 GiB de disco entre imagens, cache de build e dados iniciais; 16 GiB deixam folga para os backups.
const (
	MinMemoria = 4 << 30
	MinDisco   = 16 << 30
	// O MemTotal desconta o que o firmware e o kernel reservam: uma máquina de 4 GiB mostra cerca de 3,8 GiB.
	folgaMemoria = 512 << 20
)

// HostsInternet são os repositórios usados pelo build.
var HostsInternet = []string{"download.docker.com", "registry-1.docker.io", "repo.maven.apache.org", "registry.npmjs.org"}

type Item struct {
	ID        string `json:"id"`
	Titulo    string `json:"titulo"`
	Estado    string `json:"estado"`
	Detalhe   string `json:"detalhe"`
	Essencial bool   `json:"essencial"`
}

type Verificacao struct {
	Itens    []Item `json:"itens"`
	Bloqueia bool   `json:"bloqueia"`
}

// Sonda reúne o acesso à máquina; os testes trocam cada parte.
type Sonda struct {
	Raiz       string // "/" na máquina real; um diretório falso nos testes (proc/, etc/)
	Exec       executor.Executor
	Ambiente   comandos.Ambiente
	UID        int
	Internet   func(ctx context.Context, host string) error
	DiscoLivre func(caminho string) (uint64, error)
}

// Real devolve a sonda da máquina real.
func Real(amb comandos.Ambiente) Sonda {
	return Sonda{Raiz: "/", Exec: executor.Sistema{}, Ambiente: amb, UID: os.Getuid(), Internet: alcancar, DiscoLivre: sistema.DiscoLivre}
}

func alcancar(ctx context.Context, host string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, "https://"+host+"/", nil)
	if err != nil {
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	resp.Body.Close()
	return nil
}

func estado(ok, essencial bool) string {
	switch {
	case ok:
		return Atendido
	case essencial:
		return Pendente
	default:
		return Opcional
	}
}

// Verificar roda todas as verificações para o diretório de instalação e as portas padrão.
func (s Sonda) Verificar(ctx context.Context, diretorio string, portas []int) Verificacao {
	var itens []Item
	itens = append(itens, s.itemSistema())
	docker, acessivel := s.itemDocker(ctx)
	itens = append(itens, docker, s.itemCompose(ctx, acessivel))
	itens = append(itens, s.itemFerramenta("openssl", "OpenSSL", true, "cria o certificado HTTPS do m2m"))
	itens = append(itens, s.itemFerramenta("certutil", "certutil (libnss3-tools)", false, "faz o Chrome confiar no certificado do m2m"))
	itens = append(itens, s.itemInternet(ctx))
	itens = append(itens, s.itemPortas(portas), s.itemMemoria(), s.itemDisco(ctx, diretorio, acessivel))
	if it, ok := s.itemUID(); ok {
		itens = append(itens, it)
	}
	v := Verificacao{Itens: itens}
	for _, it := range itens {
		if it.Estado == Pendente {
			v.Bloqueia = true
		}
	}
	return v
}

func (s Sonda) itemSistema() Item {
	so := s.Ambiente.SO
	it := Item{ID: "sistema", Titulo: "Sistema e arquitetura", Essencial: true, Estado: estado(so.Suportado(), true)}
	it.Detalhe = fmt.Sprintf("%s, %s.", so.Nome, sistema.Maquina())
	if so.Suportado() && !so.Testado() {
		it.Detalhe += " Esta versão não está entre as testadas (" + sistema.VersoesSuportadas() + "); a instalação segue, mas pode precisar de ajustes."
	}
	return it
}

// itemDocker distingue Docker ausente, parado e sem permissão. Devolve também se o Docker está acessível.
func (s Sonda) itemDocker(ctx context.Context) (Item, bool) {
	it := Item{ID: "docker", Titulo: "Docker Engine", Essencial: true}
	if !s.Exec.Procurar("docker") {
		it.Estado, it.Detalhe = Pendente, "O Docker não está instalado."
		return it, false
	}
	out, err := s.Exec.Rodar(ctx, executor.Comando{Programa: "docker", Args: []string{"version", "--format", "{{.Server.Version}}"}})
	if err == nil {
		it.Estado, it.Detalhe = Atendido, "Docker "+strings.TrimSpace(string(out))+", acessível pelo usuário "+s.Ambiente.Usuario+" sem sudo."
		return it, true
	}
	msg := err.Error()
	it.Estado = Pendente
	switch {
	case strings.Contains(msg, "permission denied"):
		if s.noGrupoDocker() {
			it.Detalhe = "O usuário " + s.Ambiente.Usuario + " já está no grupo docker, mas esta sessão foi aberta antes da inclusão (o instalar.sh abre o instalador com o grupo valendo)."
		} else {
			it.Detalhe = "O Docker está instalado, mas o usuário " + s.Ambiente.Usuario + " não tem permissão de usá-lo."
		}
	default:
		it.Detalhe = "O Docker está instalado, mas o serviço não responde."
	}
	return it, false
}

// noGrupoDocker lê etc/group para saber se o usuário já pertence ao grupo docker (inclusão feita, sessão antiga).
func (s Sonda) noGrupoDocker() bool {
	f, err := os.Open(filepath.Join(s.Raiz, "etc/group"))
	if err != nil {
		return false
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		campos := strings.Split(sc.Text(), ":")
		if len(campos) == 4 && campos[0] == "docker" {
			for _, u := range strings.Split(campos[3], ",") {
				if u == s.Ambiente.Usuario {
					return true
				}
			}
		}
	}
	return false
}

func (s Sonda) itemCompose(ctx context.Context, acessivel bool) Item {
	it := Item{ID: "compose", Titulo: "Docker Compose v2 e Buildx", Essencial: true, Estado: Pendente}
	if !s.Exec.Procurar("docker") {
		it.Detalhe = "Instalados junto com o Docker."
		return it
	}
	out, err := s.Exec.Rodar(ctx, executor.Comando{Programa: "docker", Args: []string{"compose", "version", "--short"}})
	versao := strings.TrimPrefix(strings.TrimSpace(string(out)), "v")
	maior, _ := strconv.Atoi(strings.SplitN(versao, ".", 2)[0])
	_, errBuildx := s.Exec.Rodar(ctx, executor.Comando{Programa: "docker", Args: []string{"buildx", "version"}})
	switch {
	case err != nil || maior < 2:
		it.Detalhe = "O plugin Docker Compose v2 não está instalado."
	case errBuildx != nil:
		it.Detalhe = "O plugin Buildx, usado pelo Compose para construir as imagens, não está instalado."
	default:
		it.Estado, it.Detalhe = Atendido, "Compose "+versao+" e Buildx instalados."
	}
	return it
}

func (s Sonda) itemFerramenta(programa, nome string, essencial bool, uso string) Item {
	it := Item{ID: programa, Titulo: nome, Essencial: essencial}
	ok := s.Exec.Procurar(programa)
	it.Estado = estado(ok, essencial)
	if ok {
		it.Detalhe = "Instalado: " + uso + "."
	} else {
		it.Detalhe = "Não instalado: " + uso + "."
	}
	return it
}

func (s Sonda) itemInternet(ctx context.Context) Item {
	it := Item{ID: "internet", Titulo: "Acesso à internet", Essencial: true}
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	var mu sync.Mutex
	var falhas []string
	var wg sync.WaitGroup
	for _, h := range HostsInternet {
		wg.Go(func() {
			if err := s.Internet(ctx, h); err != nil {
				mu.Lock()
				falhas = append(falhas, h)
				mu.Unlock()
			}
		})
	}
	wg.Wait()
	sort.Strings(falhas)
	if len(falhas) == 0 {
		it.Estado, it.Detalhe = Atendido, "Os repositórios do build respondem: "+strings.Join(HostsInternet, ", ")+"."
	} else {
		it.Estado = Pendente
		it.Detalhe = "Sem resposta de " + strings.Join(falhas, ", ") + ". O build baixa pacotes e imagens desses endereços: confira a rede, o DNS e um eventual proxy e verifique de novo."
	}
	return it
}

func (s Sonda) itemPortas(portas []int) Item {
	it := Item{ID: "portas", Titulo: "Portas padrão", Estado: Atendido}
	usadas, err := sistema.PortasEmUso(s.Raiz)
	if err != nil {
		it.Estado, it.Detalhe = Opcional, "Não foi possível ler as portas em uso; elas serão conferidas na etapa Containers."
		return it
	}
	var ocupadas []string
	for _, p := range portas {
		if usadas[p] {
			ocupadas = append(ocupadas, strconv.Itoa(p))
		}
	}
	if len(ocupadas) == 0 {
		it.Detalhe = "Livres: " + juntar(portas) + "."
	} else {
		it.Estado = Opcional
		it.Detalhe = "Em uso por outro programa: " + strings.Join(ocupadas, ", ") + ". Escolha outras portas nas etapas Containers e Logs."
	}
	return it
}

func juntar(portas []int) string {
	s := make([]string, len(portas))
	for i, p := range portas {
		s[i] = strconv.Itoa(p)
	}
	return strings.Join(s, ", ")
}

func gib(b uint64) string {
	return strconv.FormatFloat(float64(b)/(1<<30), 'f', 1, 64) + " GiB"
}

func (s Sonda) itemMemoria() Item {
	it := Item{ID: "memoria", Titulo: "Memória", Essencial: true}
	total, err := sistema.MemoriaTotal(s.Raiz)
	switch {
	case err != nil:
		it.Estado, it.Detalhe = Pendente, "Não foi possível ler /proc/meminfo: "+err.Error()
	case total+folgaMemoria < MinMemoria:
		it.Estado, it.Detalhe = Pendente, "A máquina tem "+gib(total)+"; o build e o m2m precisam de "+gib(MinMemoria)+". Aumente a memória da máquina."
	case total < MinMemoria:
		it.Estado, it.Detalhe = Atendido, gib(total)+" visíveis ao sistema, o que corresponde a uma máquina de "+gib(MinMemoria)+", o mínimo para o build e o m2m."
	default:
		it.Estado, it.Detalhe = Atendido, gib(total)+" (mínimo "+gib(MinMemoria)+")."
	}
	return it
}

// itemDisco mede o diretório de instalação e o diretório de dados do Docker, onde ficam imagens e volumes.
func (s Sonda) itemDisco(ctx context.Context, diretorio string, dockerAcessivel bool) Item {
	it := Item{ID: "disco", Titulo: "Espaço em disco", Essencial: true, Estado: Atendido}
	dadosDocker := "/var/lib/docker"
	if dockerAcessivel {
		if out, err := s.Exec.Rodar(ctx, executor.Comando{Programa: "docker", Args: []string{"info", "--format", "{{.DockerRootDir}}"}}); err == nil && strings.TrimSpace(string(out)) != "" {
			dadosDocker = strings.TrimSpace(string(out))
		}
	}
	var partes []string
	for _, p := range []string{diretorio, dadosDocker} {
		livre, err := s.DiscoLivre(p)
		if err != nil {
			it.Estado = Pendente
			partes = append(partes, "não foi possível medir "+p+": "+err.Error())
			continue
		}
		if livre < MinDisco {
			it.Estado = Pendente
		}
		partes = append(partes, fmt.Sprintf("%s livres em %s", gib(livre), sistema.ExistenteMaisProximo(p)))
	}
	it.Detalhe = strings.Join(partes, "; ") + " (necessário " + gib(MinDisco) + ")."
	if it.Estado == Pendente {
		it.Detalhe += " Libere espaço ou aumente o disco."
	}
	return it
}

// itemUID avisa quando o usuário não é o UID 1000, com o qual o back acessa os arquivos do host (máquina LOCAL).
func (s Sonda) itemUID() (Item, bool) {
	if s.UID == 1000 {
		return Item{}, false
	}
	return Item{
		ID: "uid", Titulo: "Usuário dos arquivos do host", Estado: Opcional,
		Detalhe: fmt.Sprintf("O m2m acessa os arquivos do host (máquina LOCAL) como UID 1000, e o seu usuário é UID %d: "+
			"o m2m pode não ter permissão de ler e gravar nos seus arquivos. Se for usar a máquina LOCAL, ajuste as permissões depois.", s.UID),
	}, true
}
