package desinstalacao

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"m2m/installer/internal/arquivo"
	"m2m/installer/internal/comandos"
	"m2m/installer/internal/executor"
	"m2m/installer/internal/instalacao"
	"m2m/installer/internal/respostas"
	"m2m/installer/internal/sistema"
)

var agora = time.Date(2026, 10, 23, 10, 0, 0, 0, time.UTC)

func instalacaoPadrao(home string) respostas.Respostas {
	return respostas.Padroes(respostas.Maquina{Home: home, Hostname: "outra", Fuso: "UTC"})
}

type cenario struct {
	home, dir, backups string
	ex                 *executor.Falso
}

// montar cria uma instalação falsa: diretório com compose e .env, registro, CA, atalhos e um destino de backup LOCAL
// fora do diretório de instalação.
func montar(t *testing.T) cenario {
	t.Helper()
	home := t.TempDir()
	dir := filepath.Join(home, "m2m")
	backups := filepath.Join(home, "backups-m2m")
	os.MkdirAll(dir, 0o755)
	os.MkdirAll(backups, 0o755)
	os.WriteFile(filepath.Join(backups, "perfil-2026.tar.zst"), []byte("backup"), 0o644)
	os.WriteFile(filepath.Join(dir, "docker-compose.yml"), []byte("name: m2m\nservices:\n  m2m-loki:\n    image: grafana/loki:3.6.0\n  m2m-back:\n    image: m2m-back:dev\n"), 0o644)
	os.WriteFile(filepath.Join(dir, ".env"), []byte("HOST_HOME_BIND="+home+"\nAPP_SECRET_KEY=chave-mestra-de-teste-0123456789\nADMIN_PASSWORD=Senha-Admin-123\nPUBLIC_URL=https://192.168.0.10\nM2M_LOCAL_ENABLED=true\n"), 0o600)
	os.MkdirAll(instalacao.DirTLS(home), 0o700)
	os.WriteFile(filepath.Join(instalacao.DirTLS(home), "ca.crt"), []byte("ca"), 0o644)
	instalacao.GravarRegistro(home, instalacao.Registro{M2M: "v1.0.0", Diretorio: dir, Certificado: "ca-local", Atalhos: []string{}})
	os.WriteFile(filepath.Join(home, ".bashrc"), []byte("# meu bashrc\n"), 0o644)
	instalacao.AdicionarAtalhos(home, dir, true)

	ex := executor.NovoFalso(map[string]executor.Resposta{
		"docker ps":        {Saida: "m2m-db-postgresql\trunning\t" + dir + "\nm2m-back-java\trunning\t" + dir + "\n"},
		"docker volume ls": {Saida: "m2m_m2m-db-data\nm2m_m2m-queue-data\n"},
		"docker volume ls -q --filter name=m2m-instalador-": {Saida: "m2m-instalador-go-mod\nm2m-instalador-node-modules\noutro-m2m-instalador-x\n"},
		"docker network ls":        {Saida: "abc123\n"},
		"docker image ls":          {Saida: "m2m-back:dev\nm2m-front:dev\n"},
		"docker exec -e PGOPTIONS": {Saida: "Fotos\tNOTEBOOK\tLOCAL\tlocalhost\t/home/backups-m2m\nDocs\tNAS\tSFTP\t192.168.0.5\t/dados/m2m\n"},
	})
	ex.Programas = map[string]bool{"docker": true}
	return cenario{home: home, dir: dir, backups: backups, ex: ex}
}

func (c cenario) cfg() Config {
	so := sistema.SO{ID: "ubuntu", Versao: "24.04", Codinome: "noble", Nome: "Ubuntu 24.04", Familia: "ubuntu", CodinomeUbuntu: "noble"}
	return Config{Home: c.home, Exec: c.ex, Ambiente: comandos.Ambiente{SO: so, Usuario: "ana"}, CASistema: filepath.Join(c.home, "nao-existe.crt")}
}

func todos(l Localizada) []string {
	var ids []string
	for _, it := range l.Itens {
		if it.Padrao {
			ids = append(ids, it.ID)
		}
	}
	return ids
}

func TestLocalizarListaItensEDestinos(t *testing.T) {
	c := montar(t)
	l := Localizar(context.Background(), c.ex, c.home, filepath.Join(c.home, "m2m"))
	if !l.Encontrada || l.Diretorio != c.dir || l.Versao != "v1.0.0" || !l.TemEnv {
		t.Fatalf("localização: %+v", l)
	}
	for _, it := range l.Itens {
		if !it.Padrao {
			t.Errorf("todos os itens vêm marcados: %s", it.ID)
		}
		if strings.Contains(strings.ToLower(it.Titulo), "backup") {
			t.Errorf("destino de backup nunca é item removível: %+v", it)
		}
	}
	d := l.Destinos
	if !d.Consultados || len(d.Itens) != 2 || d.Itens[0].NoHost != c.backups || d.Itens[1].NoHost != "" {
		t.Fatalf("destinos: %+v", d)
	}
	if !c.ex.Chamou("docker exec -e PGOPTIONS=-c default_transaction_read_only=on m2m-db-postgresql psql") {
		t.Error("a consulta deveria ser só de leitura")
	}
}

func TestBancoParadoAvisaSemListar(t *testing.T) {
	c := montar(t)
	c.ex.Respostas["docker ps"] = executor.Resposta{Saida: "m2m-db-postgresql\texited\t" + c.dir + "\n"}
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	if l.Destinos.Consultados || !strings.Contains(l.Destinos.Erro, "não está rodando") {
		t.Fatalf("esperava aviso sem consulta: %+v", l.Destinos)
	}
}

func TestNadaInstalado(t *testing.T) {
	home := t.TempDir()
	l := Localizar(context.Background(), executor.NovoFalso(nil), home, filepath.Join(home, "m2m"))
	if l.Encontrada || len(l.Itens) != 0 {
		t.Fatalf("esperava nada encontrado: %+v", l)
	}
}

func TestDesinstalacaoTotal(t *testing.T) {
	c := montar(t)
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	res := Executar(context.Background(), c.cfg(), l, todos(l))
	for _, r := range res.Itens {
		if r.Estado != instalacao.Concluido {
			t.Errorf("%s: %s", r.ID, r.Erro)
		}
	}
	for _, p := range []string{c.dir, instalacao.DirTLS(c.home), instalacao.CaminhoRegistro(c.home)} {
		if _, err := os.Stat(p); err == nil {
			t.Errorf("%s deveria ter sido removido", p)
		}
	}
	if b, _ := os.ReadFile(filepath.Join(c.home, ".bashrc")); string(b) != "# meu bashrc\n" {
		t.Errorf("atalhos não removidos:\n%s", b)
	}
	if b, err := os.ReadFile(filepath.Join(c.backups, "perfil-2026.tar.zst")); err != nil || string(b) != "backup" {
		t.Fatalf("o destino de backup deveria continuar intacto: %v", err)
	}
	for _, esperado := range []string{"docker rm -f m2m-db-postgresql m2m-back-java", "docker network rm abc123",
		"docker volume rm m2m_m2m-db-data m2m_m2m-queue-data", "docker image rm m2m-back:dev m2m-front:dev"} {
		if !c.ex.Chamou(esperado) {
			t.Errorf("faltou %q em %v", esperado, c.ex.Chamadas)
		}
	}
	if len(res.Opcionais) != 3 || !strings.Contains(res.Opcionais[0].Texto, "grafana/loki:3.6.0") || strings.Contains(res.Opcionais[0].Texto, "m2m-back") {
		t.Errorf("opcionais: %+v", res.Opcionais)
	}
}

// chamouExato diz se o comando foi chamado exatamente assim (Chamou casa só o prefixo).
func chamouExato(ex *executor.Falso, comando string) bool {
	for _, c := range ex.Chamadas {
		if c == comando {
			return true
		}
	}
	return false
}

func TestManterVolumes(t *testing.T) {
	c := montar(t)
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	Executar(context.Background(), c.cfg(), l, []string{"imagens", "diretorio"})
	if c.ex.Chamou("docker volume rm") {
		t.Error("volumes desmarcados não podem ser removidos")
	}
	if !c.ex.Chamou("docker rm -f") {
		t.Error("containers são sempre removidos")
	}
}

func TestComandoDaCADoSistema(t *testing.T) {
	c := montar(t)
	cfg := c.cfg()
	cfg.CASistema = filepath.Join(c.home, "ca-sistema.crt")
	os.WriteFile(cfg.CASistema, []byte("ca"), 0o644)
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	res := Executar(context.Background(), cfg, l, todos(l))
	if len(res.Comandos) != 1 || res.Comandos[0].ID != "remover-ca" || !strings.HasPrefix(res.Comandos[0].Texto, "sudo") {
		t.Fatalf("esperava o comando para remover a CA do sistema: %+v", res.Comandos)
	}
}

func TestFalhaParcialSegueComOsDemais(t *testing.T) {
	c := montar(t)
	c.ex.Respostas["docker image rm"] = executor.Resposta{Erro: "image is being used by running container"}
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	res := Executar(context.Background(), c.cfg(), l, todos(l))
	estados := map[string]ResultadoItem{}
	for _, r := range res.Itens {
		estados[r.ID] = r
	}
	if estados["imagens"].Estado != instalacao.Falhou || !strings.Contains(estados["imagens"].Erro, "being used") {
		t.Errorf("imagens: %+v", estados["imagens"])
	}
	if estados["diretorio"].Estado != instalacao.Concluido || estados["certificados"].Estado != instalacao.Concluido {
		t.Errorf("os demais itens deveriam ser removidos: %+v", estados)
	}
}

func TestBackupDentroDoDiretorioImpedeARemocao(t *testing.T) {
	c := montar(t)
	os.MkdirAll(filepath.Join(c.dir, "backups"), 0o755)
	os.WriteFile(filepath.Join(c.dir, "backups", "x.tar.zst.enc"), []byte("b"), 0o644)
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	res := Executar(context.Background(), c.cfg(), l, []string{"diretorio"})
	if res.Itens[1].ID != "diretorio" || res.Itens[1].Estado != instalacao.Falhou || !strings.Contains(res.Itens[1].Erro, "x.tar.zst.enc") {
		t.Fatalf("esperava recusa com o motivo: %+v", res.Itens)
	}
	if _, err := os.Stat(filepath.Join(c.dir, "backups", "x.tar.zst.enc")); err != nil {
		t.Fatal("o backup dentro do diretório foi apagado")
	}
}

func TestDestinoLocalDentroDoDiretorio(t *testing.T) {
	c := montar(t)
	c.ex.Respostas["docker exec -e PGOPTIONS"] = executor.Resposta{Saida: "Fotos\tNOTEBOOK\tLOCAL\tlocalhost\t/home/m2m/bk\n"}
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	res := Executar(context.Background(), c.cfg(), l, []string{"diretorio"})
	if res.Itens[1].Estado != instalacao.Falhou || !strings.Contains(res.Itens[1].Erro, "Fotos") {
		t.Fatalf("esperava recusa pelo destino LOCAL: %+v", res.Itens)
	}
}

func TestCaminhosProtegidos(t *testing.T) {
	home := "/home/ana"
	for _, p := range []string{"/", "/home", "/home/ana", "/home/ana/", "relativo/m2m", "/home/../home"} {
		if Protegido(p, home) == nil {
			t.Errorf("%q deveria ser protegido", p)
		}
	}
	if err := Protegido("/home/ana/m2m", home); err != nil {
		t.Errorf("diretório de instalação deveria ser permitido: %v", err)
	}
}

func TestDiretorioSemComposeDoM2M(t *testing.T) {
	c := montar(t)
	os.WriteFile(filepath.Join(c.dir, "docker-compose.yml"), []byte("name: outro\n"), 0o644)
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	res := Executar(context.Background(), c.cfg(), l, []string{"diretorio"})
	if res.Itens[1].Estado != instalacao.Falhou {
		t.Fatalf("diretório de outro projeto não pode ser apagado: %+v", res.Itens)
	}
}

// configuracaoDoBack responde à rota de exportação do back com uma configuração cifrada com a senha dada.
func configuracaoDoBack(t *testing.T, c cenario, senha string) {
	t.Helper()
	env, err := arquivo.Selar(arquivo.FormatoConfiguracao, []byte(`{"maquinaLocal":"NOTEBOOK","maquinas":[{"nome":"NAS"}],"perfis":[{"armazenamento":"NAS","diretorioArmazenamento":"/backups"}]}`), senha, nil)
	if err != nil {
		t.Fatal(err)
	}
	envJSON, _ := json.Marshal(env)
	resp, _ := json.Marshal(map[string]string{"conteudo": string(envJSON)})
	c.ex.Respostas["docker exec -i m2m-back-java curl"] = executor.Resposta{Saida: string(resp)}
}

func TestExportarAntesDeRemover(t *testing.T) {
	c := montar(t)
	configuracaoDoBack(t, c, "senha-da-exportacao")
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	if !l.BackNoAr {
		t.Fatal("o back está rodando no cenário")
	}
	b, err := Exportar(context.Background(), c.ex, l, c.home, "senha-da-exportacao", instalacaoPadrao(c.home), agora)
	if err != nil {
		t.Fatal(err)
	}
	// o pedido ao back leva o token da chave mestra e o administrador, só pela entrada padrão
	var linha string
	for _, ch := range c.ex.Chamadas {
		if strings.HasPrefix(ch, "docker exec -i m2m-back-java curl") {
			linha = ch
		}
	}
	var pedido map[string]string
	if err := json.Unmarshal(c.ex.Entradas[linha], &pedido); err != nil ||
		pedido["token"] != instalacao.TokenInterno("chave-mestra-de-teste-0123456789") || pedido["usuario"] != "admin" {
		t.Fatalf("pedido ao back: %s", c.ex.Entradas[linha])
	}
	if imp, err := arquivo.Importar(b, "senha-da-exportacao"); err != nil || imp.Configuracao == nil || imp.Configuracao.Maquinas != 1 {
		t.Fatalf("configuração no arquivo de reinstalação: %+v %v", imp.Configuracao, err)
	}
	if strings.Contains(string(b), "chave-mestra-de-teste") || !strings.Contains(string(b), `"endereco": "192.168.0.10"`) {
		t.Fatalf("exportação inesperada:\n%s", b)
	}
	// reinstalar importando o arquivo reaproveita a chave mestra da instalação removida
	imp, err := arquivo.Importar(b, "senha-da-exportacao")
	if err != nil || imp.Segredos.ChaveMestra != "chave-mestra-de-teste-0123456789" || imp.Respostas.Containers.Diretorio != c.dir {
		t.Fatalf("importação: %+v %v", imp, err)
	}
}

// montarClone transforma o cenário numa instalação feita pelo clone.
func montarClone(t *testing.T) cenario {
	c := montar(t)
	os.MkdirAll(filepath.Join(c.dir, ".git"), 0o755)
	os.WriteFile(filepath.Join(c.dir, "README.md"), []byte("m2m\n"), 0o644)
	reg, _ := instalacao.LerRegistro(c.home)
	reg.Origem = instalacao.OrigemClone
	instalacao.GravarRegistro(c.home, *reg)
	return c
}

func TestItensNoModoClone(t *testing.T) {
	c := montarClone(t)
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	if !l.Clone {
		t.Fatal("a instalação pelo clone deveria ser reconhecida")
	}
	por := map[string]Item{}
	for _, it := range l.Itens {
		por[it.ID] = it
	}
	if env, ok := por["env"]; !ok || !env.Padrao {
		t.Errorf("o .env do clone deveria vir marcado: %+v", env)
	}
	if clone, ok := por["diretorio"]; !ok || clone.Padrao || clone.Aviso == "" {
		t.Errorf("apagar o clone deveria vir desmarcado e com aviso: %+v", clone)
	}
}

func TestDesinstalarCloneMantendoOClone(t *testing.T) {
	c := montarClone(t)
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	res := Executar(context.Background(), c.cfg(), l, todos(l))
	for _, it := range res.Itens {
		if it.Estado != instalacao.Concluido {
			t.Fatalf("item %s: %s", it.ID, it.Erro)
		}
	}
	if _, err := os.Stat(filepath.Join(c.dir, ".env")); err == nil {
		t.Error("o .env deveria sair")
	}
	for _, f := range []string{"README.md", "docker-compose.yml", ".git"} {
		if _, err := os.Stat(filepath.Join(c.dir, f)); err != nil {
			t.Errorf("o clone deveria ficar (%s): %v", f, err)
		}
	}
	if c.ex.Chamou("docker volume rm m2m-instalador-") {
		t.Errorf("com o clone mantido, o cache do ./instalar.sh fica: %v", c.ex.Chamadas)
	}
}

func TestDesinstalarApagandoOClone(t *testing.T) {
	c := montarClone(t)
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	res := Executar(context.Background(), c.cfg(), l, append(todos(l), "diretorio"))
	for _, it := range res.Itens {
		if it.Estado != instalacao.Concluido {
			t.Fatalf("item %s: %s", it.ID, it.Erro)
		}
	}
	if _, err := os.Stat(c.dir); err == nil {
		t.Error("o clone deveria ter sido apagado")
	}
	if !chamouExato(c.ex, "docker volume rm m2m-instalador-go-mod m2m-instalador-node-modules") {
		t.Errorf("os volumes de cache do ./instalar.sh deveriam sair junto com o clone: %v", c.ex.Chamadas)
	}
	if _, err := os.Stat(filepath.Join(c.backups, "perfil-2026.tar.zst")); err != nil {
		t.Errorf("o destino de backup deveria continuar: %v", err)
	}
}

func TestExportarComOM2MForaDoAr(t *testing.T) {
	c := montar(t)
	c.ex.Respostas["docker ps"] = executor.Resposta{Saida: "m2m-db-postgresql\texited\t" + c.dir + "\nm2m-back-java\texited\t" + c.dir + "\n"}
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	if l.BackNoAr {
		t.Fatal("o back está parado")
	}
	b, err := Exportar(context.Background(), c.ex, l, c.home, "senha-da-exportacao", instalacaoPadrao(c.home), agora)
	if err != nil {
		t.Fatal(err)
	}
	if c.ex.Chamou("docker exec -i m2m-back-java") {
		t.Error("não deveria chamar o back parado")
	}
	if imp, err := arquivo.Importar(b, "senha-da-exportacao"); err != nil || imp.Configuracao != nil || imp.Segredos == nil {
		t.Fatalf("arquivo só com a instalação: %+v %v", imp, err)
	}
}

func TestExportarComFalhaNoBack(t *testing.T) {
	c := montar(t)
	c.ex.Respostas["docker exec -i m2m-back-java curl"] = executor.Resposta{Saida: `{"codigo":"instalador.acesso-negado"}`, Erro: "curl: (22)"}
	l := Localizar(context.Background(), c.ex, c.home, c.dir)
	if _, err := Exportar(context.Background(), c.ex, l, c.home, "senha-da-exportacao", instalacaoPadrao(c.home), agora); err == nil ||
		!strings.Contains(err.Error(), "instalador.acesso-negado") {
		t.Fatalf("erro esperado com o motivo do back: %v", err)
	}
}
