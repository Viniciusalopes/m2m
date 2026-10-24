// Instalador do m2m: executável independente que serve o quiz de instalação e desinstalação em 127.0.0.1.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"os/user"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"

	embedded "m2m/installer/embed"
	"m2m/installer/internal/comandos"
	"m2m/installer/internal/executor"
	"m2m/installer/internal/instalacao"
	"m2m/installer/internal/prerequisitos"
	"m2m/installer/internal/respostas"
	"m2m/installer/internal/servidor"
	"m2m/installer/internal/sistema"
)

func main() {
	porta := flag.Int("porta", 8765, "porta local do quiz (a próxima livre se estiver ocupada)")
	semNavegador := flag.Bool("sem-navegador", false, "não abre o navegador, só mostra o endereço")
	versao := flag.Bool("versao", false, "mostra a versão do m2m embutida e sai")
	clone := flag.String("clone", "", "modo clone: instala usando este clone do repositório como diretório (usado pelo instalar.sh)")
	flag.Parse()
	if *versao {
		fmt.Println(embedded.Versao())
		return
	}
	if err := rodar(*porta, *semNavegador, *clone); err != nil {
		fmt.Fprintln(os.Stderr, "m2m-instalador:", err)
		os.Exit(1)
	}
}

func rodar(portaPedida int, semNavegador bool, clone string) error {
	// recusa sistema e arquitetura antes de criar qualquer arquivo ou abrir o servidor
	so, err := sistema.DetectarSO("/")
	if err != nil || !so.Suportado() {
		nome := "desconhecido"
		if err == nil {
			nome = so.Nome
		}
		return fmt.Errorf("sistema não suportado (%s). O instalador funciona em: %s", nome, sistema.VersoesSuportadas())
	}
	if maq := sistema.Maquina(); !sistema.ArquiteturaSuportada(maq) {
		return fmt.Errorf("arquitetura não suportada (%s). O instalador funciona em: %s", maq, sistema.VersoesSuportadas())
	}
	if os.Geteuid() == 0 {
		return fmt.Errorf("rode o instalador como o seu usuário, sem sudo: os comandos que precisam de administrador são mostrados no quiz")
	}
	if clone != "" {
		abs, err := filepath.Abs(clone)
		if err != nil {
			return err
		}
		clone = filepath.Clean(abs)
		// um clone já instalado (com .env) segue: o quiz mostra a instalação encontrada e oferece a desinstalação
		if msg := instalacao.EhClone(clone); msg != "" {
			return errors.New(msg)
		}
	}
	u, err := user.Current()
	if err != nil {
		return err
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	exe, _ := os.Executable()
	ex := executor.Sistema{}
	amb := comandos.Ambiente{SO: so, Usuario: u.Username, Executavel: exe}
	enderecos := sistema.Enderecos()
	maquina := respostas.Maquina{Home: home, Hostname: sistema.Hostname(), Fuso: sistema.FusoHorario("/"), Endereco: enderecos[0]}

	l, porta, err := servidor.Escutar(portaPedida)
	if err != nil {
		return err
	}
	srv := servidor.Novo(servidor.Config{
		Versao: embedded.Versao(), Home: home, Usuario: u.Username, SO: so, Ambiente: amb, Exec: ex,
		Sonda: prerequisitos.Real(amb), Verificar: servidor.VerificadorReal{Raiz: "/", HomeDir: home, Clone: clone},
		Maquina: maquina, Enderecos: enderecos, UI: embedded.UI(), Codigo: embedded.Codigo, Clone: clone,
	}, porta)

	fmt.Printf("Instalador do m2m %s (%s, %s)\n\n", embedded.Versao(), so.Nome, runtime.GOARCH)
	if clone != "" {
		fmt.Printf("Modo clone: o m2m será instalado em %s, usando o código deste clone.\n\n", clone)
	}
	if !so.Testado() {
		fmt.Printf("Aviso: %s não está entre as versões testadas (%s).\n\n", so.Nome, sistema.VersoesSuportadas())
	}
	grafico := os.Getenv("DISPLAY") != "" || os.Getenv("WAYLAND_DISPLAY") != ""
	if grafico && !semNavegador && ex.Procurar("xdg-open") {
		fmt.Println("Abrindo o quiz no navegador. Se ele não abrir, use este endereço:")
		fmt.Println("  " + srv.URL())
		cmd := exec.Command("xdg-open", srv.URL())
		cmd.Stdout, cmd.Stderr = nil, nil
		_ = cmd.Start()
		go func() { _ = cmd.Wait() }()
	} else if grafico {
		fmt.Println("Abra o quiz no navegador:")
		fmt.Println("  " + srv.URL())
	} else {
		fmt.Println("Esta sessão não tem interface gráfica. No seu computador, abra um túnel SSH para esta máquina:")
		fmt.Printf("  ssh -L %d:127.0.0.1:%d %s@%s\n", porta, porta, u.Username, hostSSH())
		fmt.Println("e, com o túnel aberto, abra no navegador do seu computador:")
		fmt.Println("  " + srv.URL())
	}
	fmt.Println("\nO endereço contém um token de uso único: não o compartilhe. Ctrl+C encerra o instalador.")

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if err := srv.Rodar(ctx, l); err != nil {
		return err
	}
	fmt.Println("Instalador encerrado.")
	return nil
}

// hostSSH é o endereço desta máquina visto pelo cliente SSH (SSH_CONNECTION), ou o hostname.
func hostSSH() string {
	if campos := strings.Fields(os.Getenv("SSH_CONNECTION")); len(campos) >= 3 {
		if strings.Contains(campos[2], ":") {
			return "[" + campos[2] + "]"
		}
		return campos[2]
	}
	return sistema.Hostname()
}
