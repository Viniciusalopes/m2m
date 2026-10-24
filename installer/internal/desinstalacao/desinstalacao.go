// Package desinstalacao localiza a instalação do m2m, lista o que pode ser removido e os destinos de backup (que
// nunca são removidos) e remove os itens escolhidos, só do projeto Compose m2m.
package desinstalacao

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"

	"m2m/installer/internal/arquivo"
	"m2m/installer/internal/comandos"
	"m2m/installer/internal/dotenv"
	"m2m/installer/internal/executor"
	"m2m/installer/internal/instalacao"
	"m2m/installer/internal/respostas"
)

// Item é uma linha da etapa de escolha.
type Item struct {
	ID        string `json:"id"`
	Titulo    string `json:"titulo"`
	Descricao string `json:"descricao"`
	Padrao    bool   `json:"padrao"`
	Sempre    bool   `json:"sempre"`              // removido sempre, sem opção de desmarcar
	Aviso     string `json:"aviso,omitempty"`     // mostrado com o item marcado
	AvisoFica string `json:"avisoFica,omitempty"` // mostrado com o item desmarcado
}

// Destino é o armazenamento de um perfil de backup, como o m2m o vê.
type Destino struct {
	Perfil    string `json:"perfil"`
	Maquina   string `json:"maquina"`
	Protocolo string `json:"protocolo"`
	Host      string `json:"host"`
	Diretorio string `json:"diretorio"`
	NoHost    string `json:"noHost,omitempty"` // caminho real na máquina, para a máquina LOCAL
}

// Destinos é o resultado da consulta ao banco.
type Destinos struct {
	Consultados bool      `json:"consultados"`
	Erro        string    `json:"erro,omitempty"`
	Itens       []Destino `json:"itens"`
}

// Localizada é a instalação encontrada.
type Localizada struct {
	Encontrada bool                   `json:"encontrada"`
	Diretorio  string                 `json:"diretorio"`
	Versao     string                 `json:"versao"`
	CriadoEm   string                 `json:"criadoEm"`
	Registro   *instalacao.Registro   `json:"registro"`
	Containers []instalacao.Container `json:"containers"`
	Volumes    []string               `json:"volumes"`
	TemEnv     bool                   `json:"temEnv"`
	BackNoAr   bool                   `json:"backNoAr"` // com o back rodando, a configuração do m2m entra no arquivo de reinstalação
	Clone      bool                   `json:"clone"`    // instalação feita a partir de um clone do repositório
	Itens      []Item                 `json:"itens"`
	Destinos   Destinos               `json:"destinos"`
	env        map[string]string
}

const containerBanco = "m2m-db-postgresql"

// Localizar procura a instalação pelo registro, pelo diretório padrão e pelos containers do projeto m2m.
func Localizar(ctx context.Context, ex executor.Executor, home, dirPadrao string) Localizada {
	e := instalacao.Detectar(ctx, ex, home, dirPadrao)
	l := Localizada{
		Encontrada: e.Encontrada(), Diretorio: e.Diretorio, Registro: e.Registro,
		Containers: e.Containers, Volumes: e.Volumes, Versao: "desconhecida", Itens: []Item{},
	}
	if !l.Encontrada {
		return l
	}
	if e.Registro != nil {
		l.Versao, l.CriadoEm = e.Registro.M2M, e.Registro.CriadoEm
	}
	if b, err := os.ReadFile(filepath.Join(l.Diretorio, ".env")); err == nil {
		l.TemEnv, l.env = true, dotenv.Ler(b)
	}
	l.Clone = ehClone(l)
	l.BackNoAr = slices.ContainsFunc(l.Containers, func(c instalacao.Container) bool {
		return c.Nome == instalacao.ContainerBack && c.Estado == "running"
	})
	l.Itens = itens(l, home)
	l.Destinos = listarDestinos(ctx, ex, l)
	return l
}

func itens(l Localizada, home string) []Item {
	its := []Item{
		{ID: "containers", Titulo: "Containers e rede do m2m", Sempre: true, Padrao: true,
			Descricao: "Para e remove os containers e as redes do projeto Compose m2m, inclusive os de teste."},
		{ID: "imagens", Titulo: "Imagens construídas do m2m", Padrao: true,
			Descricao: "Apaga as imagens m2m-*:dev construídas nesta máquina. As imagens públicas ficam (podem ser de outros projetos)."},
		{ID: "volumes", Titulo: "Volumes de dados", Padrao: true,
			Descricao: "Apaga os volumes do projeto m2m: banco, fila, logs e Grafana.",
			Aviso: "O banco tem o catálogo dos backups e as chaves dos perfis criptografados. Baixe antes o arquivo de reinstalação: " +
				"com o m2m no ar, ele guarda a configuração e as chaves dos perfis, e a reinstalação que o importar devolve os backups ao catálogo.",
			AvisoFica: "Os volumes ficam nesta máquina, mas uma nova instalação só é possível depois de removê-los. " +
				"Para reinstalar com tudo como está, baixe o arquivo de reinstalação e remova os volumes."},
	}
	switch {
	case l.Diretorio != "" && l.Clone:
		its = append(its,
			Item{ID: "env", Titulo: "Arquivos gerados no clone", Padrao: true,
				Descricao: "Apaga o .env de " + l.Diretorio + " (chave mestra e senhas). O clone do repositório fica."},
			Item{ID: "diretorio", Titulo: "Apagar o clone do repositório", Padrao: false,
				Descricao: "Apaga " + l.Diretorio + " inteiro: o código clonado e o .env, e os volumes de cache que o ./instalar.sh usa para compilar o instalador.",
				Aviso:     "Alterações locais no clone (arquivos editados, commits não enviados) serão perdidas."})
	case l.Diretorio != "":
		its = append(its, Item{ID: "diretorio", Titulo: "Diretório de instalação", Padrao: true,
			Descricao: "Apaga " + l.Diretorio + ", com o código e o .env (chave mestra e senhas)."})
	}
	its = append(its,
		Item{ID: "certificados", Titulo: "Certificados e CA local do m2m", Padrao: true,
			Descricao: "Apaga " + instalacao.DirTLS(home) + " e tira a CA do Chrome/Chromium deste usuário."},
		Item{ID: "atalhos", Titulo: "Atalhos de shell", Padrao: true,
			Descricao: "Tira os atalhos m2m-up e m2m-down criados pelo instalador do ~/.bashrc e do ~/.zshrc."},
	)
	return its
}

// listarDestinos consulta, só para leitura, os destinos de backup dos perfis no banco do m2m.
func listarDestinos(ctx context.Context, ex executor.Executor, l Localizada) Destinos {
	d := Destinos{Itens: []Destino{}}
	rodando := slices.ContainsFunc(l.Containers, func(c instalacao.Container) bool { return c.Nome == containerBanco && c.Estado == "running" })
	if !rodando {
		d.Erro = "O banco do m2m não está rodando: não foi possível listar os destinos de backup. Eles não serão removidos de qualquer forma."
		return d
	}
	usuario, banco := valor(l.env, "POSTGRES_USER", "m2m"), valor(l.env, "POSTGRES_DB", "m2m")
	sql := "select p.name, m.name, m.protocol, coalesce(m.host, ''), p.storage_dir from profile p " +
		"join machine m on m.id = p.storage_machine_id order by lower(p.name)"
	// a sessão só de leitura garante que a consulta não altera nada
	out, err := ex.Rodar(ctx, executor.Comando{Programa: "docker", Args: []string{"exec", "-e", "PGOPTIONS=-c default_transaction_read_only=on",
		containerBanco, "psql", "-U", usuario, "-d", banco, "-X", "-A", "-t", "-F", "\t", "-c", sql}})
	if err != nil {
		d.Erro = "Não foi possível consultar os destinos de backup no banco (" + primeiraLinha(err.Error()) + "). Eles não serão removidos de qualquer forma."
		return d
	}
	d.Consultados = true
	bind := l.env["HOST_HOME_BIND"]
	sc := bufio.NewScanner(strings.NewReader(string(out)))
	for sc.Scan() {
		c := strings.Split(sc.Text(), "\t")
		if len(c) != 5 {
			continue
		}
		dest := Destino{Perfil: c[0], Maquina: c[1], Protocolo: c[2], Host: c[3], Diretorio: c[4]}
		if dest.Protocolo == "LOCAL" && bind != "" {
			dest.NoHost, _ = respostas.RaizNoHost(bind, dest.Diretorio)
		}
		d.Itens = append(d.Itens, dest)
	}
	return d
}

func valor(m map[string]string, k, padrao string) string {
	if v := m[k]; v != "" {
		return v
	}
	return padrao
}

func primeiraLinha(s string) string {
	s, _, _ = strings.Cut(strings.TrimSpace(s), "\n")
	return s
}

// Exportar monta o arquivo de reinstalação a partir do .env instalado. Com o back no ar, inclui a configuração do
// m2m (do administrador da instalação), exportada pelo próprio back com a mesma senha.
func Exportar(ctx context.Context, ex executor.Executor, l Localizada, home, senha string, padrao respostas.Respostas, agora time.Time) ([]byte, error) {
	if !l.TemEnv {
		return nil, errors.New("a instalação não tem .env para exportar")
	}
	if len([]rune(senha)) < arquivo.MinSenha {
		return nil, fmt.Errorf("a senha da exportação precisa ter no mínimo %d caracteres", arquivo.MinSenha)
	}
	r, s := respostas.DoEnv(l.env, l.Diretorio, instalacao.DirTLS(home), padrao)
	var cfg []byte
	if l.BackNoAr {
		out, err := instalacao.ChamarBack(ctx, ex, "/config/export", map[string]any{
			"token": instalacao.TokenInterno(l.env["APP_SECRET_KEY"]), "usuario": valor(l.env, "ADMIN_USERNAME", "admin"),
			"senha": senha,
		})
		if err != nil {
			return nil, fmt.Errorf("não foi possível exportar a configuração do m2m: %w", err)
		}
		var resp struct {
			Conteudo string `json:"conteudo"`
		}
		if err := json.Unmarshal(out, &resp); err != nil || resp.Conteudo == "" {
			return nil, errors.New("o m2m devolveu a configuração num formato inesperado")
		}
		cfg = []byte(resp.Conteudo)
	}
	versao := l.Versao
	if versao == "desconhecida" {
		versao = "dev"
	}
	return arquivo.Exportar(r, s, versao, senha, agora, cfg)
}

// ResultadoItem é o desfecho de um item removido.
type ResultadoItem struct {
	ID     string `json:"id"`
	Estado string `json:"estado"` // concluido, falhou
	Erro   string `json:"erro,omitempty"`
}

// Resultado reúne os itens e os comandos para o terminal.
type Resultado struct {
	Itens     []ResultadoItem    `json:"itens"`
	Comandos  []comandos.Comando `json:"comandos"`
	Opcionais []comandos.Comando `json:"opcionais"`
}

// Config é o que a remoção usa.
type Config struct {
	Home     string
	Exec     executor.Executor
	Ambiente comandos.Ambiente
	Emitir   func(instalacao.Evento)
	// CASistema é o arquivo da CA no sistema; existindo, a desinstalação mostra o comando para removê-lo.
	CASistema string
}

// Executar remove os itens escolhidos na ordem segura; a falha de um não impede os demais.
func Executar(ctx context.Context, cfg Config, l Localizada, escolhidos []string) Resultado {
	if cfg.Emitir == nil {
		cfg.Emitir = func(instalacao.Evento) {}
	}
	if cfg.CASistema == "" {
		cfg.CASistema = "/usr/local/share/ca-certificates/" + instalacao.NomeCA + ".crt"
	}
	quer := map[string]bool{"containers": true}
	for _, id := range escolhidos {
		quer[id] = true
	}
	publicas := imagensPublicas(l.Diretorio)
	res := Resultado{Itens: []ResultadoItem{}, Comandos: []comandos.Comando{}, Opcionais: []comandos.Comando{}}
	acoes := []struct {
		id   string
		acao func() error
	}{
		{"containers", func() error { return removerContainers(ctx, cfg.Exec) }},
		{"volumes", func() error { return removerVolumes(ctx, cfg.Exec) }},
		{"imagens", func() error { return removerImagens(ctx, cfg.Exec) }},
		{"certificados", func() error { return removerCertificados(ctx, cfg) }},
		{"atalhos", func() error { return removerAtalhos(cfg.Home, l) }},
		{"env", func() error { return removerEnv(cfg.Home, l) }},
		{"diretorio", func() error { return removerDiretorioECache(ctx, cfg, l) }},
	}
	for _, a := range acoes {
		if !quer[a.id] {
			continue
		}
		cfg.Emitir(instalacao.Evento{Tipo: "item", Passo: a.id, Estado: instalacao.Executando})
		r := ResultadoItem{ID: a.id, Estado: instalacao.Concluido}
		if err := a.acao(); err != nil {
			r.Estado, r.Erro = instalacao.Falhou, err.Error()
		}
		res.Itens = append(res.Itens, r)
		cfg.Emitir(instalacao.Evento{Tipo: "item", Passo: a.id, Estado: r.Estado, Erro: r.Erro})
	}
	// o registro sai ao fim; ~/.config/m2m só se ficou vazio
	if err := os.Remove(instalacao.CaminhoRegistro(cfg.Home)); err != nil && !os.IsNotExist(err) {
		res.Itens = append(res.Itens, ResultadoItem{ID: "registro", Estado: instalacao.Falhou, Erro: err.Error()})
	}
	os.Remove(instalacao.DirConfig(cfg.Home))

	if _, err := os.Stat(cfg.CASistema); err == nil {
		res.Comandos = append(res.Comandos, cfg.Ambiente.RemoverCA())
	}
	if len(publicas) > 0 {
		res.Opcionais = append(res.Opcionais, comandos.RemoverImagensPublicas(publicas))
	}
	res.Opcionais = append(res.Opcionais, comandos.LimparCacheBuild(), cfg.Ambiente.RemoverDocker())
	cfg.Emitir(instalacao.Evento{Tipo: "fim"})
	return res
}

func removerContainers(ctx context.Context, ex executor.Executor) error {
	cs, err := instalacao.ContainersDoProjeto(ctx, ex)
	if err != nil {
		return err
	}
	if len(cs) > 0 {
		args := []string{"rm", "-f"}
		for _, c := range cs {
			args = append(args, c.Nome)
		}
		if _, err := ex.Rodar(ctx, executor.Comando{Programa: "docker", Args: args}); err != nil {
			return err
		}
	}
	redes, err := instalacao.RedesDoProjeto(ctx, ex)
	if err != nil || len(redes) == 0 {
		return err
	}
	_, err = ex.Rodar(ctx, executor.Comando{Programa: "docker", Args: append([]string{"network", "rm"}, redes...)})
	return err
}

func removerVolumes(ctx context.Context, ex executor.Executor) error {
	vs, err := instalacao.VolumesDoProjeto(ctx, ex)
	if err != nil || len(vs) == 0 {
		return err
	}
	_, err = ex.Rodar(ctx, executor.Comando{Programa: "docker", Args: append([]string{"volume", "rm"}, vs...)})
	return err
}

func removerImagens(ctx context.Context, ex executor.Executor) error {
	out, err := ex.Rodar(ctx, executor.Comando{Programa: "docker", Args: []string{"image", "ls", "--filter", "reference=m2m-*:dev",
		"--format", "{{.Repository}}:{{.Tag}}"}})
	if err != nil {
		return err
	}
	var imgs []string
	for _, l := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		if l = strings.TrimSpace(l); strings.HasPrefix(l, "m2m-") && strings.HasSuffix(l, ":dev") {
			imgs = append(imgs, l)
		}
	}
	if len(imgs) == 0 {
		return nil
	}
	_, err = ex.Rodar(ctx, executor.Comando{Programa: "docker", Args: append([]string{"image", "rm"}, imgs...)})
	return err
}

func removerCertificados(ctx context.Context, cfg Config) error {
	errNSS := instalacao.RemoverNSS(ctx, cfg.Exec, cfg.Home)
	dir := instalacao.DirTLS(cfg.Home)
	if err := Protegido(dir, cfg.Home); err != nil {
		return err
	}
	return errors.Join(errNSS, os.RemoveAll(dir))
}

func removerAtalhos(home string, l Localizada) error {
	arquivos := instalacao.ArquivosShell(home)
	if l.Registro != nil {
		arquivos = append(arquivos, l.Registro.Atalhos...)
	}
	slices.Sort(arquivos)
	_, err := instalacao.RemoverAtalhos(slices.Compact(arquivos))
	return err
}

// ehClone diz se a instalação foi feita a partir de um clone: pelo registro ou, sem ele, por um .git no diretório.
func ehClone(l Localizada) bool {
	if l.Registro != nil && l.Registro.Origem != "" {
		return l.Registro.Origem == instalacao.OrigemClone
	}
	if l.Diretorio == "" {
		return false
	}
	_, err := os.Stat(filepath.Join(l.Diretorio, ".git"))
	return err == nil
}

// removerEnv apaga só o que a instalação gerou no clone: o .env e o marcador de instalação interrompida.
func removerEnv(home string, l Localizada) error {
	dir := filepath.Clean(l.Diretorio)
	if err := Protegido(dir, home); err != nil {
		return err
	}
	if !composeDoM2M(dir) {
		return fmt.Errorf("%s não tem o docker-compose.yml do m2m; apague o .env manualmente se for mesmo a instalação", dir)
	}
	var errs []error
	for _, f := range []string{".env", instalacao.Marcador} {
		if err := os.Remove(filepath.Join(dir, f)); err != nil && !os.IsNotExist(err) {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}

// removerDiretorio só apaga o diretório localizado que tenha o docker-compose.yml do m2m e nenhum backup dentro.
func removerDiretorio(home string, l Localizada) error {
	dir := filepath.Clean(l.Diretorio)
	if err := Protegido(dir, home); err != nil {
		return err
	}
	if !composeDoM2M(dir) {
		return fmt.Errorf("%s não tem o docker-compose.yml do m2m; apague-o manualmente se for mesmo a instalação", dir)
	}
	for _, d := range l.Destinos.Itens {
		if d.NoHost != "" && dentro(d.NoHost, dir) {
			return fmt.Errorf("o destino de backup do perfil %s (%s) fica dentro de %s; mova os backups e apague o diretório manualmente", d.Perfil, d.NoHost, dir)
		}
	}
	if b := backupDentro(dir); b != "" {
		return fmt.Errorf("há arquivos de backup dentro de %s (%s); mova-os e apague o diretório manualmente", dir, b)
	}
	if err := os.RemoveAll(dir); err != nil {
		if errors.Is(err, fs.ErrPermission) {
			// o ./instalar.sh da v1.0.0 deixava no clone arquivos do build criados como root
			return fmt.Errorf("%w; parte de %s pertence a outro usuário (em geral, arquivos de build criados como root por um container); apague o resto com: sudo rm -rf %s", err, dir, dir)
		}
		return err
	}
	return nil
}

// removerDiretorioECache apaga o diretório e, se ele era um clone, os volumes de cache do ./instalar.sh, que só servem
// para compilar o instalador a partir do clone.
func removerDiretorioECache(ctx context.Context, cfg Config, l Localizada) error {
	if err := removerDiretorio(cfg.Home, l); err != nil || !l.Clone {
		return err
	}
	return removerCacheDoInstalador(ctx, cfg.Exec)
}

// prefixoCacheInstalador nomeia os volumes de cache do scripts/gerar-instalador.sh (node_modules e caches do Go).
const prefixoCacheInstalador = "m2m-instalador-"

func removerCacheDoInstalador(ctx context.Context, ex executor.Executor) error {
	out, err := ex.Rodar(ctx, executor.Comando{Programa: "docker", Args: []string{"volume", "ls", "-q", "--filter", "name=" + prefixoCacheInstalador}})
	if err != nil {
		return err
	}
	var vs []string
	for _, v := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		if v = strings.TrimSpace(v); strings.HasPrefix(v, prefixoCacheInstalador) { // o filtro do Docker casa no meio do nome
			vs = append(vs, v)
		}
	}
	if len(vs) == 0 {
		return nil
	}
	_, err = ex.Rodar(ctx, executor.Comando{Programa: "docker", Args: append([]string{"volume", "rm"}, vs...)})
	return err
}

// Protegido recusa caminhos que nunca podem ser apagados: relativos, a raiz, /home, o home e seus ancestrais.
func Protegido(caminho, home string) error {
	p := filepath.Clean(caminho)
	h := filepath.Clean(home)
	switch {
	case !filepath.IsAbs(caminho):
		return fmt.Errorf("caminho relativo recusado: %s", caminho)
	case p == "/" || p == "/home" || p == h || dentro(h, p):
		return fmt.Errorf("caminho protegido, nunca apagado pelo instalador: %s", p)
	}
	return nil
}

// dentro diz se p fica em dir ou abaixo dele.
func dentro(p, dir string) bool {
	p, dir = filepath.Clean(p), filepath.Clean(dir)
	return p == dir || strings.HasPrefix(p, dir+string(os.PathSeparator))
}

func composeDoM2M(dir string) bool {
	b, err := os.ReadFile(filepath.Join(dir, "docker-compose.yml"))
	if err != nil {
		return false
	}
	for _, l := range strings.Split(string(b), "\n") {
		if strings.TrimSpace(l) == "name: "+instalacao.Projeto {
			return true
		}
	}
	return false
}

// backupDentro procura arquivos de backup do m2m (.tar.zst e .tar.zst.enc) no diretório.
func backupDentro(dir string) string {
	achado := ""
	_ = filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil || achado != "" {
			return nil
		}
		if !d.IsDir() && (strings.HasSuffix(p, ".tar.zst") || strings.HasSuffix(p, ".tar.zst.enc")) {
			achado = p
			return fs.SkipAll
		}
		return nil
	})
	return achado
}

// imagensPublicas lê do docker-compose.yml as imagens que não são construídas pelo m2m.
func imagensPublicas(dir string) []string {
	var imgs []string
	for _, nome := range []string{"docker-compose.yml", "docker-compose.test.yml"} {
		b, err := os.ReadFile(filepath.Join(dir, nome))
		if err != nil {
			continue
		}
		for _, l := range strings.Split(string(b), "\n") {
			if v, ok := strings.CutPrefix(strings.TrimSpace(l), "image:"); ok {
				v = strings.Trim(strings.TrimSpace(v), `"'`)
				if v != "" && !strings.HasPrefix(v, "m2m-") && !slices.Contains(imgs, v) {
					imgs = append(imgs, v)
				}
			}
		}
	}
	return imgs
}
