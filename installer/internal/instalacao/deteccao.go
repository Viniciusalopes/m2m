package instalacao

import (
	"context"
	"os"
	"path/filepath"
	"strings"

	"m2m/installer/internal/executor"
)

// Projeto é o nome do projeto Compose do m2m (name: m2m no docker-compose.yml).
const Projeto = "m2m"

const rotuloProjeto = "label=com.docker.compose.project=" + Projeto

// Container é um container do projeto m2m.
type Container struct {
	Nome      string `json:"nome"`
	Estado    string `json:"estado"`    // running, exited...
	Diretorio string `json:"diretorio"` // working_dir do Compose
}

// Existente é o que já existe do m2m na máquina.
type Existente struct {
	Registro   *Registro   `json:"registro"`
	Diretorio  string      `json:"diretorio"` // diretório com .env (registrado, padrão ou dos containers)
	Containers []Container `json:"containers"`
	Volumes    []string    `json:"volumes"`
}

// Encontrada diz se há qualquer sinal do m2m.
func (e Existente) Encontrada() bool {
	return e.Registro != nil || e.Diretorio != "" || len(e.Containers) > 0 || len(e.Volumes) > 0
}

// Bloqueia diz se a instalação não pode prosseguir: só um diretório com .env se resolve escolhendo outro diretório.
func (e Existente) Bloqueia() bool {
	return e.Registro != nil || len(e.Containers) > 0 || len(e.Volumes) > 0
}

func linhas(b []byte) []string {
	var out []string
	for _, l := range strings.Split(strings.TrimSpace(string(b)), "\n") {
		if l = strings.TrimSpace(l); l != "" {
			out = append(out, l)
		}
	}
	return out
}

// ContainersDoProjeto lista os containers (inclusive parados) do projeto m2m.
func ContainersDoProjeto(ctx context.Context, ex executor.Executor) ([]Container, error) {
	out, err := ex.Rodar(ctx, executor.Comando{Programa: "docker", Args: []string{"ps", "-a", "--filter", rotuloProjeto,
		"--format", `{{.Names}}	{{.State}}	{{.Label "com.docker.compose.project.working_dir"}}`}})
	if err != nil {
		return nil, err
	}
	var cs []Container
	for _, l := range linhas(out) {
		c := strings.SplitN(l, "\t", 3)
		for len(c) < 3 {
			c = append(c, "")
		}
		cs = append(cs, Container{Nome: c[0], Estado: c[1], Diretorio: c[2]})
	}
	return cs, nil
}

// VolumesDoProjeto lista os volumes do projeto m2m.
func VolumesDoProjeto(ctx context.Context, ex executor.Executor) ([]string, error) {
	out, err := ex.Rodar(ctx, executor.Comando{Programa: "docker", Args: []string{"volume", "ls", "-q", "--filter", rotuloProjeto}})
	if err != nil {
		return nil, err
	}
	return linhas(out), nil
}

// RedesDoProjeto lista as redes do projeto m2m.
func RedesDoProjeto(ctx context.Context, ex executor.Executor) ([]string, error) {
	out, err := ex.Rodar(ctx, executor.Comando{Programa: "docker", Args: []string{"network", "ls", "-q", "--filter", rotuloProjeto}})
	if err != nil {
		return nil, err
	}
	return linhas(out), nil
}

func temEnv(dir string) bool {
	if dir == "" {
		return false
	}
	_, err := os.Stat(filepath.Join(dir, ".env"))
	return err == nil
}

// Detectar procura o registro, um diretório com .env (registrado, padrão ou dos containers) e containers e volumes do
// projeto m2m. Sem acesso ao Docker, só o registro e o diretório contam.
func Detectar(ctx context.Context, ex executor.Executor, home, dirPadrao string) Existente {
	var e Existente
	e.Registro, _ = LerRegistro(home)
	if ex.Procurar("docker") {
		e.Containers, _ = ContainersDoProjeto(ctx, ex)
		e.Volumes, _ = VolumesDoProjeto(ctx, ex)
	}
	candidatos := []string{}
	if e.Registro != nil {
		candidatos = append(candidatos, e.Registro.Diretorio)
	}
	for _, c := range e.Containers {
		candidatos = append(candidatos, c.Diretorio)
	}
	candidatos = append(candidatos, dirPadrao)
	for _, d := range candidatos {
		if temEnv(d) {
			e.Diretorio = d
			break
		}
	}
	if e.Diretorio == "" && e.Registro != nil {
		e.Diretorio = e.Registro.Diretorio
	}
	return e
}
