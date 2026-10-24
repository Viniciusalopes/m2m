package instalacao

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
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
	"m2m/installer/internal/respostas"
	"m2m/installer/internal/sistema"
)

type arq struct {
	nome, conteudo string
	modo           int64
}

func tgz(t *testing.T, arquivos ...arq) []byte {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for _, a := range arquivos {
		modo := a.modo
		if modo == 0 {
			modo = 0o644
		}
		if err := tw.WriteHeader(&tar.Header{Name: a.nome, Mode: modo, Size: int64(len(a.conteudo)), Typeflag: tar.TypeReg}); err != nil {
			t.Fatal(err)
		}
		tw.Write([]byte(a.conteudo))
	}
	tw.Close()
	gz.Close()
	return buf.Bytes()
}

func codigoFalso(t *testing.T) []byte {
	return tgz(t,
		arq{nome: ".env.example", conteudo: "# modelo\nADMIN_PASSWORD=\nAPP_SECRET_KEY=\nTZ=America/Sao_Paulo\n"},
		arq{nome: "docker-compose.yml", conteudo: "name: m2m\nservices: {}\n"},
		arq{nome: "scripts/m2m-up.sh", conteudo: "#!/bin/sh\n", modo: 0o755},
	)
}

func TestExtrair(t *testing.T) {
	dir := t.TempDir()
	if err := Extrair(codigoFalso(t), dir); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(filepath.Join(dir, "scripts/m2m-up.sh"))
	if err != nil || info.Mode().Perm() != 0o755 {
		t.Fatalf("script sem permissão de execução: %v %v", info, err)
	}
	if err := Extrair(tgz(t, arq{nome: "../fora.txt", conteudo: "x"}), dir); err == nil {
		t.Fatal("caminho fora do diretório deveria ser recusado")
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(dir), "fora.txt")); err == nil {
		t.Fatal("arquivo gravado fora do diretório")
	}
}

func TestDiretorioLivre(t *testing.T) {
	base := t.TempDir()
	if msg := DiretorioLivre(filepath.Join(base, "novo")); msg != "" {
		t.Errorf("inexistente: %s", msg)
	}
	ocupado := filepath.Join(base, "ocupado")
	os.MkdirAll(ocupado, 0o755)
	if msg := DiretorioLivre(ocupado); msg != "" {
		t.Errorf("vazio: %s", msg)
	}
	os.WriteFile(filepath.Join(ocupado, "a.txt"), nil, 0o644)
	if msg := DiretorioLivre(ocupado); msg == "" {
		t.Error("não vazio deveria ser recusado")
	}
	os.WriteFile(filepath.Join(ocupado, Marcador), nil, 0o644)
	if msg := DiretorioLivre(ocupado); msg != "" {
		t.Errorf("instalação interrompida deste instalador: %s", msg)
	}
}

const psPronto = `{"Service":"m2m-db","State":"running","Health":"healthy","ExitCode":0}
{"Service":"m2m-db-migrate","State":"exited","Health":"","ExitCode":0}
{"Service":"m2m-back","State":"running","Health":"healthy","ExitCode":0}
{"Service":"m2m-alloy","State":"running","Health":"","ExitCode":0}
`

func execucao(t *testing.T, ex *executor.Falso, mudar func(*respostas.Respostas)) (*Instalacao, string) {
	t.Helper()
	home := t.TempDir()
	r := respostas.Padroes(respostas.Maquina{Home: home, Hostname: "notebook", Fuso: "UTC", Endereco: "192.168.0.10"})
	if mudar != nil {
		mudar(&r)
	}
	s := respostas.GerarSegredos()
	s.SenhaAdmin = "Senha$com#especiais"
	so := sistema.SO{ID: "ubuntu", Versao: "24.04", Codinome: "noble", Nome: "Ubuntu 24.04", Familia: "ubuntu", CodinomeUbuntu: "noble"}
	i := Nova(Config{
		Home: home, Versao: "v1.0.0", Exec: ex, Respostas: r, Segredos: s,
		Codigo:   func() ([]byte, error) { return codigoFalso(t), nil },
		Ambiente: comandos.Ambiente{SO: so, Usuario: "ana"},
		Responde: func(context.Context) error { return nil }, Intervalo: time.Millisecond,
	})
	return i, home
}

func esperar(t *testing.T, i *Instalacao) Situacao {
	t.Helper()
	for range 2000 {
		if s := i.Situacao(); !s.Rodando {
			return s
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("a instalação não terminou")
	return Situacao{}
}

func respostasDocker() map[string]executor.Resposta {
	return map[string]executor.Resposta{
		"docker compose -f docker-compose.yml --env-file .env build":               {Saida: "#1 building\n#2 DONE\n"},
		"docker compose -f docker-compose.yml --env-file .env up -d":               {Saida: "Container m2m-back Started\n"},
		"docker compose -f docker-compose.yml --env-file .env config --services":   {Saida: "m2m-db\nm2m-db-migrate\nm2m-back\nm2m-alloy\n"},
		"docker compose -f docker-compose.yml --env-file .env ps -a --format json": {Saida: psPronto},
		"sh scripts/gerar-certificado-tls.sh --sem-sistema":                        {Saida: "m2m: CA local criada\n"},
	}
}

func TestInstalacaoCompleta(t *testing.T) {
	ex := executor.NovoFalso(respostasDocker())
	ex.Programas = map[string]bool{"docker": true, "sh": true} // sem certutil
	i, home := execucao(t, ex, nil)
	if err := i.Iniciar(context.Background()); err != nil {
		t.Fatal(err)
	}
	s := esperar(t, i)
	for _, p := range s.Passos {
		if p.Estado != Concluido {
			t.Fatalf("passo %s: %s %s", p.ID, p.Estado, p.Erro)
		}
	}
	dir := filepath.Join(home, "m2m")
	env, err := os.ReadFile(filepath.Join(dir, ".env"))
	info, _ := os.Stat(filepath.Join(dir, ".env"))
	if err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf(".env: %v %v", info, err)
	}
	// a versão instalada vai para o .env: o front a mostra na tela Sobre
	if !strings.Contains(string(env), "ADMIN_PASSWORD='Senha$com#especiais'") || !strings.Contains(string(env), "# modelo") ||
		!strings.Contains(string(env), "M2M_VERSION=v1.0.0\n") {
		t.Errorf(".env inesperado:\n%s", env)
	}
	reg, err := LerRegistro(home)
	if err != nil || reg == nil || reg.Diretorio != dir || !reg.CACriada || reg.NSS || reg.M2M != "v1.0.0" {
		t.Fatalf("registro: %+v %v", reg, err)
	}
	if info, _ := os.Stat(CaminhoRegistro(home)); info.Mode().Perm() != 0o600 {
		t.Errorf("registro com modo %v", info.Mode())
	}
	if b, _ := os.ReadFile(CaminhoRegistro(home)); strings.Contains(string(b), "Senha$com") {
		t.Error("registro com segredo")
	}
	if _, err := os.Stat(filepath.Join(dir, Marcador)); err == nil {
		t.Error("marcador deveria sair ao fim")
	}
	res := s.Resultado
	if res == nil || res.URL != "https://192.168.0.10" || len(res.Comandos) != 1 || res.Comandos[0].ID != "confiar-ca" {
		t.Fatalf("resultado: %+v", res)
	}
	if len(res.Avisos) != 1 || !strings.Contains(res.Avisos[0], "certutil") {
		t.Errorf("deveria avisar da falta do certutil: %v", res.Avisos)
	}
	if err := i.Iniciar(context.Background()); err != ErrEmAndamento {
		t.Errorf("instalação concluída não pode recomeçar: %v", err)
	}
}

func TestFalhaNoBuildERetomada(t *testing.T) {
	resp := respostasDocker()
	resp["docker compose -f docker-compose.yml --env-file .env build"] = executor.Resposta{Saida: "#5 ERROR\n", Erro: "failed to fetch https://repo.maven.apache.org"}
	ex := executor.NovoFalso(resp)
	i, _ := execucao(t, ex, func(r *respostas.Respostas) { r.Rede.Certificado = respostas.CertificadoAutoassinado })
	i.Iniciar(context.Background())
	s := esperar(t, i)
	estados := map[string]string{}
	for _, p := range s.Passos {
		estados[p.ID] = p.Estado
	}
	if estados["env"] != Concluido || estados["build"] != Falhou || estados["subir"] != Aguardando {
		t.Fatalf("estados após a falha: %v", estados)
	}
	if b := s.Passos[3]; !strings.Contains(b.Erro, "repo.maven.apache.org") || len(b.Log) == 0 {
		t.Fatalf("o passo de build deveria ter o erro e o log: %+v", b)
	}
	// rede de volta: "Tentar de novo" retoma do build, sem extrair de novo
	ex.Respostas["docker compose -f docker-compose.yml --env-file .env build"] = executor.Resposta{Saida: "ok\n"}
	chamadasAntes := len(ex.Chamadas)
	if err := i.Iniciar(context.Background()); err != nil {
		t.Fatal(err)
	}
	s = esperar(t, i)
	if s.Resultado == nil {
		t.Fatalf("esperava concluir na retomada: %+v", s.Passos)
	}
	if !strings.HasSuffix(ex.Chamadas[chamadasAntes], " build") {
		t.Errorf("a retomada deveria começar pelo build: %v", ex.Chamadas[chamadasAntes:])
	}
	if s.Resultado.Certificado != respostas.CertificadoAutoassinado || len(s.Resultado.Comandos) != 0 {
		t.Errorf("autoassinado não tem comando de CA: %+v", s.Resultado)
	}
}

func TestServicoQueParaFalhaComLog(t *testing.T) {
	resp := respostasDocker()
	resp["docker compose -f docker-compose.yml --env-file .env ps -a --format json"] = executor.Resposta{Saida: strings.Replace(psPronto, `"exited","Health":"","ExitCode":0`, `"exited","Health":"","ExitCode":1`, 1)}
	resp["docker compose -f docker-compose.yml --env-file .env logs"] = executor.Resposta{Saida: "ERROR: Migration V9 failed\n"}
	i, _ := execucao(t, executor.NovoFalso(resp), nil)
	i.Iniciar(context.Background())
	s := esperar(t, i)
	ag := s.Passos[5]
	if ag.Estado != Falhou || !strings.Contains(ag.Erro, "m2m-db-migrate") || !strings.Contains(strings.Join(ag.Log, "\n"), "Migration V9") {
		t.Fatalf("aguardar: %+v", ag)
	}
}

func TestPrazoEsgotado(t *testing.T) {
	resp := respostasDocker()
	resp["docker compose -f docker-compose.yml --env-file .env ps -a --format json"] = executor.Resposta{Saida: strings.ReplaceAll(psPronto, `"healthy"`, `"starting"`)}
	i, _ := execucao(t, executor.NovoFalso(resp), nil)
	i.cfg.Prazo = 5 * time.Millisecond
	i.Iniciar(context.Background())
	if s := esperar(t, i); !strings.Contains(s.Passos[5].Erro, "não ficou pronto") {
		t.Fatalf("esperava prazo esgotado: %+v", s.Passos[5])
	}
}

func TestLerPSEmLista(t *testing.T) {
	ss, err := lerPS([]byte(`[{"Service":"m2m-db","State":"running","Health":"healthy"}]`))
	if err != nil || len(ss) != 1 || ss[0].Health != "healthy" {
		t.Fatalf("%+v %v", ss, err)
	}
}

func TestAtalhos(t *testing.T) {
	home := t.TempDir()
	original := "export PATH=$PATH:/opt/bin\n"
	os.WriteFile(filepath.Join(home, ".zshrc"), []byte(original), 0o600)
	arquivos, err := AdicionarAtalhos(home, "/home/ana/m2m", true)
	if err != nil || len(arquivos) != 1 || !strings.HasSuffix(arquivos[0], ".zshrc") {
		t.Fatalf("só o .zshrc existe: %v %v", arquivos, err)
	}
	// repetir não duplica o bloco
	AdicionarAtalhos(home, "/home/ana/m2m", false)
	b, _ := os.ReadFile(filepath.Join(home, ".zshrc"))
	if strings.Count(string(b), inicioBloco) != 1 || !strings.Contains(string(b), "alias m2m-down=") || !strings.Contains(string(b), "up -d --build") {
		t.Fatalf("bloco inesperado:\n%s", b)
	}
	if !strings.Contains(string(b), "alias m2m-down='docker compose -f /home/ana/m2m/docker-compose.yml --env-file /home/ana/m2m/.env down'") {
		t.Errorf("caminhos seguros não precisam de aspas:\n%s", b)
	}
	if info, _ := os.Stat(filepath.Join(home, ".zshrc")); info.Mode().Perm() != 0o600 {
		t.Errorf("modo do arquivo alterado: %v", info.Mode())
	}
	if _, err := RemoverAtalhos(arquivos); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(filepath.Join(home, ".zshrc")); string(b) != original {
		t.Fatalf("a remoção deveria devolver o original:\n%q", b)
	}
}

func TestAtalhosSemArquivoDeShell(t *testing.T) {
	home := t.TempDir()
	arquivos, err := AdicionarAtalhos(home, "/home/ana/m2m", true)
	if err != nil || len(arquivos) != 1 || !strings.HasSuffix(arquivos[0], ".bashrc") {
		t.Fatalf("esperava criar o .bashrc: %v %v", arquivos, err)
	}
}

func TestDeteccao(t *testing.T) {
	home := t.TempDir()
	ex := executor.NovoFalso(map[string]executor.Resposta{
		"docker ps":     {Saida: "m2m-db-postgresql\trunning\t/home/ana/m2m\n"},
		"docker volume": {Saida: "m2m_m2m-db-data\n"},
	})
	e := Detectar(context.Background(), ex, home, filepath.Join(home, "m2m"))
	if !e.Encontrada() || !e.Bloqueia() || len(e.Containers) != 1 || e.Containers[0].Diretorio != "/home/ana/m2m" {
		t.Fatalf("containers do projeto: %+v", e)
	}
	// só um diretório com .env: não bloqueia, basta escolher outro diretório
	vazio := executor.NovoFalso(nil)
	dir := filepath.Join(home, "m2m")
	os.MkdirAll(dir, 0o755)
	os.WriteFile(filepath.Join(dir, ".env"), nil, 0o600)
	e = Detectar(context.Background(), vazio, home, dir)
	if !e.Encontrada() || e.Bloqueia() || e.Diretorio != dir {
		t.Fatalf("só diretório: %+v", e)
	}
}

// cloneFalso cria um clone do m2m (sem .env) e devolve o caminho.
func cloneFalso(t *testing.T, base string) string {
	t.Helper()
	dir := filepath.Join(base, "m2m-clone")
	if err := Extrair(codigoFalso(t), dir); err != nil {
		t.Fatal(err)
	}
	os.MkdirAll(filepath.Join(dir, "scripts"), 0o755)
	os.WriteFile(filepath.Join(dir, "scripts", "gerar-certificado-tls.sh"), []byte("#!/bin/sh\n"), 0o755)
	os.MkdirAll(filepath.Join(dir, ".git"), 0o755)
	return dir
}

func TestValidarClone(t *testing.T) {
	base := t.TempDir()
	dir := cloneFalso(t, base)
	if msg := ValidarClone(dir); msg != "" {
		t.Errorf("clone válido recusado: %s", msg)
	}
	if msg := EhClone(base); msg == "" {
		t.Error("diretório sem os arquivos do m2m aceito como clone")
	}
	os.WriteFile(filepath.Join(dir, ".env"), []byte("X=1\n"), 0o600)
	if msg := ValidarClone(dir); !strings.Contains(msg, ".env") {
		t.Errorf("clone com .env deveria ser recusado: %q", msg)
	}
	if msg := EhClone(dir); msg != "" {
		t.Errorf("clone com .env ainda é um clone: %s", msg)
	}
}

func TestInstalacaoPeloClone(t *testing.T) {
	ex := executor.NovoFalso(respostasDocker())
	ex.Programas = map[string]bool{"docker": true, "sh": true}
	var dir string
	i, home := execucao(t, ex, func(r *respostas.Respostas) {
		dir = cloneFalso(t, t.TempDir())
		r.Containers.Diretorio = dir
	})
	i.cfg.Clone = true
	i.cfg.Codigo = func() ([]byte, error) { t.Fatal("o modo clone não extrai o código"); return nil, nil }
	i.passos[0].Titulo = "Usar o código do clone"
	if err := i.Iniciar(context.Background()); err != nil {
		t.Fatal(err)
	}
	s := esperar(t, i)
	for _, p := range s.Passos {
		if p.Estado != Concluido {
			t.Fatalf("passo %s: %s %s", p.ID, p.Estado, p.Erro)
		}
	}
	if !strings.Contains(strings.Join(s.Passos[0].Log, "\n"), "Usando o código do clone") {
		t.Errorf("log do passo de código: %v", s.Passos[0].Log)
	}
	if _, err := os.Stat(filepath.Join(dir, ".env")); err != nil {
		t.Errorf("o .env deveria estar no clone: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, Marcador)); err == nil {
		t.Error("o modo clone não deixa marcador")
	}
	reg, err := LerRegistro(home)
	if err != nil || reg == nil || reg.Origem != OrigemClone || reg.Diretorio != dir {
		t.Fatalf("registro: %+v %v", reg, err)
	}
}

func TestNovaNoModoClone(t *testing.T) {
	i := Nova(Config{Clone: true})
	if i.passos[0].ID != "codigo" || i.passos[0].Titulo != "Usar o código do clone" {
		t.Errorf("primeiro passo: %+v", i.passos[0])
	}
}

func TestCloneJaInstaladoFalha(t *testing.T) {
	ex := executor.NovoFalso(respostasDocker())
	ex.Programas = map[string]bool{"docker": true, "sh": true}
	i, _ := execucao(t, ex, func(r *respostas.Respostas) {
		dir := cloneFalso(t, t.TempDir())
		os.WriteFile(filepath.Join(dir, ".env"), []byte("X=1\n"), 0o600)
		r.Containers.Diretorio = dir
	})
	i.cfg.Clone = true
	i.Iniciar(context.Background())
	s := esperar(t, i)
	if s.Passos[0].Estado != Falhou || !strings.Contains(s.Passos[0].Erro, ".env") {
		t.Fatalf("passo de código: %+v", s.Passos[0])
	}
}

// backFalso responde às rotas internas do back; a reindexação do diretório em falhar devolve erro.
type backFalso struct {
	*executor.Falso
	falhar      string
	reindexados []map[string]any
}

func (b *backFalso) Rodar(ctx context.Context, c executor.Comando) ([]byte, error) {
	if c.Programa == "docker" && len(c.Args) > 2 && c.Args[0] == "exec" && strings.HasSuffix(c.String(), "/reindex") {
		var corpo map[string]any
		json.Unmarshal(c.Entrada, &corpo)
		b.reindexados = append(b.reindexados, corpo)
		if b.falhar != "" && corpo["diretorio"] == b.falhar {
			return []byte(`{"codigo":"reindexacao.diretorio-inexistente"}`), &executor.Erro{Comando: c.String(), Codigo: 22, Saida: "curl: (22)"}
		}
		return []byte(`{"status":"COMPLETED","resultado":{"created":3,"alreadyKnown":0}}`), nil
	}
	return b.Falso.Rodar(ctx, c)
}

func TestReinstalacaoRestauraAConfiguracaoEReindexa(t *testing.T) {
	resp := respostasDocker()
	resp["docker exec -i m2m-back-java curl"] = executor.Resposta{Saida: `{"machinesCreate":["NAS"],"machinesReplace":[],"profilesCreate":["fotos","docs"],"profilesReplace":[]}`}
	ex := &backFalso{Falso: executor.NovoFalso(resp), falhar: "/nas/backups"}
	ex.Programas = map[string]bool{"docker": true, "sh": true}
	i, _ := execucao(t, ex.Falso, func(r *respostas.Respostas) { r.Maquinas.Nome = "NOVO" })
	i.cfg.Exec = ex
	i.cfg.SenhaArquivo = "senha-do-arquivo"
	i.cfg.Configuracao = &arquivo.Configuracao{Maquinas: 1, Perfis: 2, MaquinaLocal: "VELHO", Bruta: []byte(`{"formato":"m2m-config"}`),
		Armazenamentos: []arquivo.Armazenamento{{Maquina: "VELHO", Diretorio: "/home/backup"}, {Maquina: "NAS", Diretorio: "/nas/backups"}}}
	i.passos = Nova(i.cfg).passos

	i.Iniciar(context.Background())
	s := esperar(t, i)
	estados := map[string]string{}
	for _, p := range s.Passos {
		estados[p.ID] = p.Estado
	}
	if estados["aguardar"] != Concluido || estados["configuracao"] != Concluido || estados["reindexar"] != Falhou {
		t.Fatalf("estados: %v", estados)
	}
	// a LOCAL do arquivo (VELHO) virou a LOCAL desta instalação (NOVO), e a falha no NAS não impediu a LOCAL
	if len(ex.reindexados) != 2 || ex.reindexados[0]["maquina"] != "NOVO" || ex.reindexados[1]["maquina"] != "NAS" {
		t.Fatalf("reindexações: %v", ex.reindexados)
	}
	if !strings.Contains(s.Passos[len(s.Passos)-1].Erro, "NAS:/nas/backups") {
		t.Fatalf("erro do passo: %s", s.Passos[len(s.Passos)-1].Erro)
	}
	// a importação levou o conteúdo e a senha do arquivo, com o token da chave mestra e o administrador
	var pedido map[string]any
	for linha, entrada := range ex.Entradas {
		if strings.HasSuffix(linha, "/config/import") {
			json.Unmarshal(entrada, &pedido)
		}
	}
	if pedido["senha"] != "senha-do-arquivo" || pedido["usuario"] != "admin" || pedido["token"] != TokenInterno(i.cfg.Segredos.ChaveMestra) {
		t.Fatalf("pedido de importação: %v", pedido)
	}

	// com o NAS de volta, "Tentar de novo" só refaz a reindexação
	ex.falhar = ""
	ex.reindexados = nil
	if err := i.Iniciar(context.Background()); err != nil {
		t.Fatal(err)
	}
	s = esperar(t, i)
	if s.Resultado == nil || len(ex.reindexados) != 2 || countPrefix(ex.Falso.Chamadas, "docker compose -f docker-compose.yml --env-file .env build") != 1 {
		t.Fatalf("retomada: %+v %v", s.Resultado, ex.reindexados)
	}
}

func countPrefix(linhas []string, prefixo string) int {
	n := 0
	for _, l := range linhas {
		if strings.HasPrefix(l, prefixo) {
			n++
		}
	}
	return n
}

func TestSemConfiguracaoNaoHaPassosDeRestauracao(t *testing.T) {
	i := Nova(Config{})
	for _, p := range i.passos {
		if p.ID == "configuracao" || p.ID == "reindexar" {
			t.Fatalf("passo inesperado: %s", p.ID)
		}
	}
}
