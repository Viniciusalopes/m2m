// Package arquivo exporta e importa o arquivo .json da instalação: respostas legíveis e segredos num envelope
// Argon2id + AES-256-GCM igual ao do PasswordCipher do back, com os cabeçalhos do arquivo como dados adicionais. Na
// versão 2, o arquivo de reinstalação traz também a configuração do m2m, no envelope que o próprio back exporta.
package arquivo

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"golang.org/x/crypto/argon2"

	"m2m/installer/internal/respostas"
)

const (
	Formato         = "m2m-instalacao"
	Versao          = 2
	FormatoSegredos = "m2m-instalacao-segredos"
	// FormatoConfiguracao é o do envelope da exportação de configuração do back (PasswordCipher).
	FormatoConfiguracao = "m2m-config"
	MinSenha            = 12

	memoriaKiB = 65536
	iteracoes  = 3
	paralelo   = 1
)

// Envelope tem os mesmos campos do PasswordCipher do back.
type Envelope struct {
	Formato string `json:"formato"`
	Versao  int    `json:"versao"`
	Kdf     string `json:"kdf"`
	M       uint32 `json:"m"`
	T       uint32 `json:"t"`
	P       uint8  `json:"p"`
	Sal     string `json:"sal"`
	Nonce   string `json:"nonce"`
	Dados   string `json:"dados"`
}

// Arquivo é o .json da instalação.
type Arquivo struct {
	Formato   string              `json:"formato"`
	Versao    int                 `json:"versao"`
	M2M       string              `json:"m2m"`
	CriadoEm  string              `json:"criadoEm"`
	Respostas respostas.Respostas `json:"respostas"`
	Segredos  Envelope            `json:"segredos"`
	// Configuracao é a configuração do m2m exportada pelo back, cifrada com a mesma senha (só no arquivo de
	// reinstalação, versão 2).
	Configuracao *Envelope `json:"configuracao,omitempty"`
}

// ErrSenhaErrada indica senha incorreta ou seção adulterada: a etiqueta do GCM não confere.
var ErrSenhaErrada = errors.New("senha incorreta ou arquivo adulterado")

// Invalido é um arquivo que não é do formato da instalação do m2m ou de versão não suportada.
type Invalido struct{ Motivo string }

func (e *Invalido) Error() string { return e.Motivo }

func derivar(senha string, sal []byte, m, t uint32, p uint8) []byte {
	return argon2.IDKey([]byte(senha), sal, t, m, p, 32)
}

// Selar cifra o texto com a senha; aad pode ser nil (envelope do back).
func Selar(formato string, texto []byte, senha string, aad []byte) (Envelope, error) {
	sal := make([]byte, 16)
	nonce := make([]byte, 12)
	if _, err := rand.Read(sal); err != nil {
		return Envelope{}, err
	}
	if _, err := rand.Read(nonce); err != nil {
		return Envelope{}, err
	}
	gcm, err := novoGCM(derivar(senha, sal, memoriaKiB, iteracoes, paralelo))
	if err != nil {
		return Envelope{}, err
	}
	b64 := base64.StdEncoding.EncodeToString
	return Envelope{
		Formato: formato, Versao: 1, Kdf: "argon2id", M: memoriaKiB, T: iteracoes, P: paralelo,
		Sal: b64(sal), Nonce: b64(nonce), Dados: b64(gcm.Seal(nil, nonce, texto, aad)),
	}, nil
}

// Abrir decifra o envelope; não confere o campo formato, que é do chamador.
func Abrir(e Envelope, senha string, aad []byte) ([]byte, error) {
	if e.Kdf != "argon2id" || e.Versao != 1 {
		return nil, &Invalido{Motivo: "seção de segredos em formato desconhecido"}
	}
	// mesmos limites do back, contra arquivos que pedem memória ou tempo absurdos
	if e.M > 1_048_576 || e.T > 10 || e.P > 8 || e.M < 8 || e.T < 1 || e.P < 1 {
		return nil, &Invalido{Motivo: "parâmetros de cifra inválidos"}
	}
	dec := base64.StdEncoding.DecodeString
	sal, err1 := dec(e.Sal)
	nonce, err2 := dec(e.Nonce)
	dados, err3 := dec(e.Dados)
	if err := errors.Join(err1, err2, err3); err != nil || len(nonce) != 12 {
		return nil, &Invalido{Motivo: "seção de segredos corrompida"}
	}
	gcm, err := novoGCM(derivar(senha, sal, e.M, e.T, e.P))
	if err != nil {
		return nil, err
	}
	texto, err := gcm.Open(nil, nonce, dados, aad)
	if err != nil {
		return nil, ErrSenhaErrada
	}
	return texto, nil
}

func novoGCM(chave []byte) (cipher.AEAD, error) {
	bloco, err := aes.NewCipher(chave)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(bloco)
}

// aad liga a seção de segredos aos cabeçalhos do arquivo: trocá-la de arquivo falha na etiqueta.
func aad(formato string, versao int, m2m string) []byte {
	return fmt.Appendf(nil, "%s\n%d\n%s", formato, versao, m2m)
}

// Exportar monta o arquivo em memória. configuracao é o envelope exportado pelo back com a mesma senha, ou nil.
func Exportar(r respostas.Respostas, s respostas.Segredos, versaoM2M, senha string, agora time.Time, configuracao []byte) ([]byte, error) {
	if len([]rune(senha)) < MinSenha {
		return nil, fmt.Errorf("a senha da exportação precisa ter no mínimo %d caracteres", MinSenha)
	}
	texto, err := json.Marshal(s)
	if err != nil {
		return nil, err
	}
	env, err := Selar(FormatoSegredos, texto, senha, aad(Formato, Versao, versaoM2M))
	if err != nil {
		return nil, err
	}
	a := Arquivo{
		Formato: Formato, Versao: Versao, M2M: versaoM2M, CriadoEm: agora.Format(time.RFC3339),
		Respostas: r, Segredos: env,
	}
	if configuracao != nil {
		var c Envelope
		if err := json.Unmarshal(configuracao, &c); err != nil || c.Formato != FormatoConfiguracao {
			return nil, errors.New("a configuração exportada pelo m2m veio num formato inesperado")
		}
		a.Configuracao = &c
	}
	return json.MarshalIndent(a, "", "  ")
}

// Armazenamento é um par máquina e diretório onde os perfis guardam backups (reindexado na reinstalação).
type Armazenamento struct {
	Maquina   string `json:"maquina"`
	Diretorio string `json:"diretorio"`
}

// Configuracao é o resumo da configuração do m2m num arquivo de reinstalação. Bruta é o envelope do back, que a
// instalação devolve a ele na importação; não vai para o quiz.
type Configuracao struct {
	Maquinas       int             `json:"maquinas"`
	Perfis         int             `json:"perfis"`
	MaquinaLocal   string          `json:"maquinaLocal,omitempty"`
	Armazenamentos []Armazenamento `json:"armazenamentos"`
	Bruta          []byte          `json:"-"`
}

// resumir decifra a configuração do back com a senha do arquivo e conta o que ela contém.
func resumir(e Envelope, senha string) (*Configuracao, error) {
	if e.Formato != FormatoConfiguracao {
		return nil, &Invalido{Motivo: "seção de configuração de outro formato"}
	}
	texto, err := Abrir(e, senha, nil)
	if err != nil {
		return nil, err
	}
	var conteudo struct {
		Maquinas     []json.RawMessage `json:"maquinas"`
		MaquinaLocal string            `json:"maquinaLocal"`
		Perfis       []struct {
			Armazenamento string `json:"armazenamento"`
			Diretorio     string `json:"diretorioArmazenamento"`
		} `json:"perfis"`
	}
	if err := json.Unmarshal(texto, &conteudo); err != nil {
		return nil, &Invalido{Motivo: "configuração decifrada em formato inesperado"}
	}
	bruta, err := json.Marshal(e)
	if err != nil {
		return nil, err
	}
	c := &Configuracao{Maquinas: len(conteudo.Maquinas), Perfis: len(conteudo.Perfis), MaquinaLocal: conteudo.MaquinaLocal,
		Armazenamentos: []Armazenamento{}, Bruta: bruta}
	vistos := map[Armazenamento]bool{}
	for _, p := range conteudo.Perfis {
		a := Armazenamento{Maquina: p.Armazenamento, Diretorio: p.Diretorio}
		if a.Maquina != "" && a.Diretorio != "" && !vistos[a] {
			vistos[a] = true
			c.Armazenamentos = append(c.Armazenamentos, a)
		}
	}
	return c, nil
}

// Importado é o resultado da importação. Com senha errada, Segredos é nil e as respostas ainda servem.
type Importado struct {
	Respostas respostas.Respostas `json:"respostas"`
	Segredos  *respostas.Segredos `json:"segredos"`
	M2M       string              `json:"m2m"`
	CriadoEm  string              `json:"criadoEm"`
	// Configuracao vem só no arquivo de reinstalação e com a senha certa.
	Configuracao *Configuracao `json:"configuracao"`
}

// Importar valida o formato e a versão e decifra os segredos. Devolve *Invalido se o arquivo não serve (nada é
// preenchido) e ErrSenhaErrada junto com as respostas se só a senha não confere.
func Importar(dados []byte, senha string) (Importado, error) {
	var cab struct {
		Formato string `json:"formato"`
		Versao  int    `json:"versao"`
	}
	if err := json.Unmarshal(dados, &cab); err != nil {
		return Importado{}, &Invalido{Motivo: "o arquivo não é um JSON válido"}
	}
	if cab.Formato != Formato {
		return Importado{}, &Invalido{Motivo: "o arquivo não é um arquivo de instalação do m2m"}
	}
	if cab.Versao < 1 || cab.Versao > Versao {
		return Importado{}, &Invalido{Motivo: fmt.Sprintf("versão %d do arquivo não suportada por este instalador (aceita da 1 à %d)", cab.Versao, Versao)}
	}
	var a Arquivo
	dec := json.NewDecoder(strings.NewReader(string(dados)))
	if err := dec.Decode(&a); err != nil {
		return Importado{}, &Invalido{Motivo: "conteúdo do arquivo inválido: " + err.Error()}
	}
	if a.Segredos.Formato != FormatoSegredos {
		return Importado{}, &Invalido{Motivo: "seção de segredos ausente ou de outro formato"}
	}
	imp := Importado{Respostas: a.Respostas, M2M: a.M2M, CriadoEm: a.CriadoEm}
	texto, err := Abrir(a.Segredos, senha, aad(a.Formato, a.Versao, a.M2M))
	if err != nil {
		var inv *Invalido
		if errors.As(err, &inv) {
			return Importado{}, err
		}
		return imp, err
	}
	var s respostas.Segredos
	if err := json.Unmarshal(texto, &s); err != nil {
		return Importado{}, &Invalido{Motivo: "segredos decifrados em formato inesperado"}
	}
	imp.Segredos = &s
	if a.Configuracao != nil {
		c, err := resumir(*a.Configuracao, senha)
		if err != nil {
			var inv *Invalido
			if errors.As(err, &inv) {
				return Importado{}, err
			}
			// a senha abriu os segredos: se não abre a configuração, ela foi adulterada
			return Importado{}, &Invalido{Motivo: "a seção de configuração não confere com a senha do arquivo"}
		}
		imp.Configuracao = c
	}
	return imp, nil
}
