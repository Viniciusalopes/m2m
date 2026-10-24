package arquivo

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"m2m/installer/internal/respostas"
)

var (
	resp = respostas.Padroes(respostas.Maquina{Home: "/home/ana", Hostname: "notebook", Fuso: "America/Sao_Paulo", Endereco: "192.168.0.10"})
	seg  = respostas.Segredos{ChaveMestra: "chave-mestra-de-teste-0123456789", SenhaAdmin: "Senha-Admin-123", SenhaBanco: "b", SenhaFila: "f", SenhaGrafana: "g"}
)

const senha = "senha-da-exportacao"

func exportar(t *testing.T) []byte {
	t.Helper()
	b, err := Exportar(resp, seg, "v1.2.3", senha, time.Date(2026, 10, 23, 10, 0, 0, 0, time.UTC), nil)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestIdaEVolta(t *testing.T) {
	b := exportar(t)
	imp, err := Importar(b, senha)
	if err != nil {
		t.Fatal(err)
	}
	if imp.Segredos == nil || *imp.Segredos != seg {
		t.Fatalf("segredos diferentes: %+v", imp.Segredos)
	}
	if imp.Respostas.Containers.Diretorio != "/home/ana/m2m" || imp.M2M != "v1.2.3" {
		t.Fatalf("respostas diferentes: %+v", imp)
	}
}

func TestSegredosSoCifrados(t *testing.T) {
	b := string(exportar(t))
	for _, s := range []string{seg.ChaveMestra, seg.SenhaAdmin} {
		if strings.Contains(b, s) {
			t.Fatalf("segredo em claro no arquivo: %s", s)
		}
	}
	if !strings.Contains(b, `"diretorio": "/home/ana/m2m"`) || !strings.Contains(b, `"formato": "m2m-instalacao"`) {
		t.Fatalf("respostas ou cabeçalho ilegíveis:\n%s", b)
	}
}

func TestSenhaErradaDevolveAsRespostas(t *testing.T) {
	imp, err := Importar(exportar(t), "outra-senha-qualquer")
	if !errors.Is(err, ErrSenhaErrada) {
		t.Fatalf("esperava senha errada, veio %v", err)
	}
	if imp.Segredos != nil || imp.Respostas.Usuarios.Usuario != "admin" {
		t.Fatalf("esperava respostas sem segredos: %+v", imp)
	}
}

func TestArquivoAdulterado(t *testing.T) {
	var a map[string]any
	_ = json.Unmarshal(exportar(t), &a)
	a["m2m"] = "v9.9.9" // cabeçalho é dado adicional do GCM
	b, _ := json.Marshal(a)
	if _, err := Importar(b, senha); !errors.Is(err, ErrSenhaErrada) {
		t.Fatalf("cabeçalho trocado deveria falhar na etiqueta, veio %v", err)
	}
}

func TestFormatoDesconhecido(t *testing.T) {
	casos := map[string]string{
		"não é JSON":    "isto não é json",
		"outro formato": `{"formato":"m2m-config","versao":1}`,
		"versão futura": `{"formato":"m2m-instalacao","versao":3}`,
		"sem segredos":  `{"formato":"m2m-instalacao","versao":1,"respostas":{}}`,
	}
	for nome, conteudo := range casos {
		_, err := Importar([]byte(conteudo), senha)
		var inv *Invalido
		if !errors.As(err, &inv) {
			t.Errorf("%s: esperava arquivo inválido, veio %v", nome, err)
		}
	}
}

func TestSenhaCurtaNaExportacao(t *testing.T) {
	if _, err := Exportar(resp, seg, "dev", "curta", time.Now(), nil); err == nil {
		t.Fatal("senha de menos de 12 caracteres deveria ser recusada")
	}
}

// Envelope gerado com o algoritmo de PasswordCipher.encrypt do back (Argon2id m=65536 t=3 p=1 e AES-256-GCM,
// BouncyCastle 1.86), com a senha "senha-de-teste-123": o instalador abre o mesmo envelope.
const envelopeDoBack = `{"formato":"m2m-config","versao":1,"kdf":"argon2id","m":65536,"t":3,"p":1,"sal":"h3PKoVz4YAtG73OJm0ahhQ==","nonce":"au965bU0f/kG2QE3","dados":"rvWy4BsTGMqxCRa0PuXLWT5iq0/MkKwXFo7nashcLbBLSyihPtlG"}`

func TestCompativelComOPasswordCipher(t *testing.T) {
	var e Envelope
	if err := json.Unmarshal([]byte(envelopeDoBack), &e); err != nil {
		t.Fatal(err)
	}
	texto, err := Abrir(e, "senha-de-teste-123", nil)
	if err != nil {
		t.Fatal(err)
	}
	if string(texto) != `{"teste":"compatível"}` {
		t.Fatalf("texto inesperado: %s", texto)
	}
}

func TestParametrosAbsurdosRecusados(t *testing.T) {
	e := Envelope{Kdf: "argon2id", Versao: 1, M: 4_000_000, T: 3, P: 1}
	var inv *Invalido
	if _, err := Abrir(e, senha, nil); !errors.As(err, &inv) {
		t.Fatalf("esperava parâmetros inválidos, veio %v", err)
	}
}

// configuracaoDoBack imita a exportação de configuração do back: o envelope m2m-config com a mesma senha.
func configuracaoDoBack(t *testing.T, conteudo string) []byte {
	t.Helper()
	env, err := Selar(FormatoConfiguracao, []byte(conteudo), senha, nil)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal(env)
	return b
}

func TestArquivoDeReinstalacaoComConfiguracao(t *testing.T) {
	cfg := configuracaoDoBack(t, `{"maquinaLocal":"NOTEBOOK","maquinas":[{"nome":"NAS"},{"nome":"VPS"}],
		"perfis":[{"armazenamento":"NAS","diretorioArmazenamento":"/backups"},
		          {"armazenamento":"NAS","diretorioArmazenamento":"/backups"},
		          {"armazenamento":"NOTEBOOK","diretorioArmazenamento":"/home/backup"}]}`)
	b, err := Exportar(resp, seg, "v1.2.3", senha, time.Now(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(b), "NAS") {
		t.Fatal("a configuração deveria ir só cifrada")
	}
	imp, err := Importar(b, senha)
	if err != nil {
		t.Fatal(err)
	}
	c := imp.Configuracao
	if c == nil || c.Maquinas != 2 || c.Perfis != 3 || c.MaquinaLocal != "NOTEBOOK" || len(c.Armazenamentos) != 2 {
		t.Fatalf("resumo da configuração: %+v", c)
	}
	if c.Armazenamentos[0] != (Armazenamento{Maquina: "NAS", Diretorio: "/backups"}) || len(c.Bruta) == 0 {
		t.Fatalf("armazenamentos: %+v", c.Armazenamentos)
	}
	// a configuração bruta volta ao back como ele a exportou
	var env Envelope
	if err := json.Unmarshal(c.Bruta, &env); err != nil || env.Formato != FormatoConfiguracao {
		t.Fatalf("configuração bruta: %s", c.Bruta)
	}
	if _, err := Importar(b, "outra-senha-qualquer"); !errors.Is(err, ErrSenhaErrada) {
		t.Fatalf("senha errada: %v", err)
	}
}

func TestConfiguracaoDeOutroFormatoNaExportacao(t *testing.T) {
	env, _ := Selar("outro", []byte("{}"), senha, nil)
	b, _ := json.Marshal(env)
	if _, err := Exportar(resp, seg, "v1", senha, time.Now(), b); err == nil {
		t.Fatal("deveria recusar uma configuração que não é do back")
	}
}

func TestArquivoDaVersao1AindaImporta(t *testing.T) {
	texto, _ := json.Marshal(seg)
	env, err := Selar(FormatoSegredos, texto, senha, aad(Formato, 1, "v1.0.0"))
	if err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal(Arquivo{Formato: Formato, Versao: 1, M2M: "v1.0.0", Respostas: resp, Segredos: env})
	imp, err := Importar(b, senha)
	if err != nil || imp.Segredos == nil || imp.Configuracao != nil {
		t.Fatalf("arquivo da versão 1: %+v %v", imp, err)
	}
}
