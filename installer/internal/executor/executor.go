// Package executor roda programas externos (docker, openssl, certutil) atrás de uma interface, para os testes
// trocarem por um executor falso.
package executor

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"sync"
)

// Comando descreve uma execução: programa, argumentos, diretório e variáveis extras de ambiente.
type Comando struct {
	Programa string
	Args     []string
	Dir      string
	Env      []string
	// Entrada vai para a entrada padrão do comando (ex.: segredos que não podem ir na linha de comando); não aparece
	// em String() nem nos logs.
	Entrada []byte
}

func (c Comando) String() string {
	return strings.TrimSpace(c.Programa + " " + strings.Join(c.Args, " "))
}

// Erro é a falha de um comando, com o código de saída e o fim da saída de erro.
type Erro struct {
	Comando string
	Codigo  int
	Saida   string
	Causa   error
}

func (e *Erro) Error() string {
	msg := strings.TrimSpace(e.Saida)
	if msg == "" && e.Causa != nil {
		msg = e.Causa.Error()
	}
	return fmt.Sprintf("%s: %s", e.Comando, msg)
}

func (e *Erro) Unwrap() error { return e.Causa }

// NaoEncontrado indica que o programa não está no PATH.
func NaoEncontrado(err error) bool {
	return errors.Is(err, exec.ErrNotFound)
}

// Executor roda comandos.
type Executor interface {
	// Rodar espera o fim e devolve a saída padrão; a saída de erro vai no Erro.
	Rodar(ctx context.Context, c Comando) ([]byte, error)
	// Transmitir entrega cada linha da saída padrão e de erro a linha() enquanto o comando roda.
	Transmitir(ctx context.Context, c Comando, linha func(string)) error
	// Procurar diz se o programa está no PATH.
	Procurar(programa string) bool
}

// Sistema roda os comandos de verdade.
type Sistema struct{}

func preparar(ctx context.Context, c Comando) *exec.Cmd {
	cmd := exec.CommandContext(ctx, c.Programa, c.Args...)
	cmd.Dir = c.Dir
	if c.Entrada != nil {
		cmd.Stdin = bytes.NewReader(c.Entrada)
	}
	if len(c.Env) > 0 {
		cmd.Env = append(os.Environ(), c.Env...)
	}
	return cmd
}

func (Sistema) Rodar(ctx context.Context, c Comando) ([]byte, error) {
	cmd := preparar(ctx, c)
	var out, errb bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errb
	if err := cmd.Run(); err != nil {
		return out.Bytes(), &Erro{Comando: c.String(), Codigo: codigo(err), Saida: fim(errb.String()+out.String(), 4000), Causa: err}
	}
	return out.Bytes(), nil
}

func (Sistema) Transmitir(ctx context.Context, c Comando, linha func(string)) error {
	cmd := preparar(ctx, c)
	pr, pw := io.Pipe()
	cmd.Stdout = pw
	cmd.Stderr = pw
	var ultimas []string
	var mu sync.Mutex
	done := make(chan struct{})
	go func() {
		defer close(done)
		sc := bufio.NewScanner(pr)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			l := sc.Text()
			mu.Lock()
			ultimas = append(ultimas, l)
			if len(ultimas) > 30 {
				ultimas = ultimas[len(ultimas)-30:]
			}
			mu.Unlock()
			linha(l)
		}
		_, _ = io.Copy(io.Discard, pr)
	}()
	err := cmd.Start()
	if err == nil {
		err = cmd.Wait()
	}
	_ = pw.Close()
	<-done
	if err != nil {
		mu.Lock()
		defer mu.Unlock()
		return &Erro{Comando: c.String(), Codigo: codigo(err), Saida: strings.Join(ultimas, "\n"), Causa: err}
	}
	return nil
}

func (Sistema) Procurar(programa string) bool {
	_, err := exec.LookPath(programa)
	return err == nil
}

func codigo(err error) int {
	var ee *exec.ExitError
	if errors.As(err, &ee) {
		return ee.ExitCode()
	}
	return -1
}

func fim(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[len(s)-n:]
}
