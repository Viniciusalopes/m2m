package executor

import (
	"context"
	"errors"
	"os/exec"
	"strings"
	"sync"
)

// Resposta é o que o executor falso devolve para um comando.
type Resposta struct {
	Saida  string
	Erro   string // não vazio: o comando falha com esta saída de erro
	Codigo int
}

// Falso é um executor para testes: responde pelo prefixo mais longo do comando e registra as chamadas.
type Falso struct {
	mu         sync.Mutex
	Respostas  map[string]Resposta
	Programas  map[string]bool // programas "no PATH"; nil = todos
	Chamadas   []string
	Entradas   map[string][]byte // entrada padrão recebida, pela linha do comando
	Desconhece bool              // comando sem resposta falha (padrão: sucesso sem saída)
}

// NovoFalso cria um executor falso com as respostas dadas.
func NovoFalso(respostas map[string]Resposta) *Falso {
	return &Falso{Respostas: respostas}
}

func (f *Falso) responder(c Comando) ([]byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	linha := c.String()
	f.Chamadas = append(f.Chamadas, linha)
	if c.Entrada != nil {
		if f.Entradas == nil {
			f.Entradas = map[string][]byte{}
		}
		f.Entradas[linha] = c.Entrada
	}
	if f.Programas != nil && !f.Programas[c.Programa] {
		return nil, &Erro{Comando: linha, Codigo: -1, Causa: exec.ErrNotFound}
	}
	melhor, achou := "", false
	for p := range f.Respostas {
		if strings.HasPrefix(linha, p) && len(p) >= len(melhor) {
			melhor, achou = p, true
		}
	}
	if !achou {
		if f.Desconhece {
			return nil, &Erro{Comando: linha, Codigo: 1, Saida: "sem resposta no executor falso", Causa: errors.New("falso")}
		}
		return nil, nil
	}
	r := f.Respostas[melhor]
	if r.Erro != "" {
		cod := r.Codigo
		if cod == 0 {
			cod = 1
		}
		return []byte(r.Saida), &Erro{Comando: linha, Codigo: cod, Saida: r.Erro, Causa: errors.New(r.Erro)}
	}
	return []byte(r.Saida), nil
}

func (f *Falso) Rodar(_ context.Context, c Comando) ([]byte, error) {
	return f.responder(c)
}

func (f *Falso) Transmitir(_ context.Context, c Comando, linha func(string)) error {
	out, err := f.responder(c)
	for _, l := range strings.Split(strings.TrimRight(string(out), "\n"), "\n") {
		if l != "" {
			linha(l)
		}
	}
	return err
}

func (f *Falso) Procurar(programa string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.Programas == nil || f.Programas[programa]
}

// Chamou diz se algum comando registrado começa com o prefixo.
func (f *Falso) Chamou(prefixo string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, c := range f.Chamadas {
		if strings.HasPrefix(c, prefixo) {
			return true
		}
	}
	return false
}
