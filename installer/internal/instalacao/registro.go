package instalacao

import (
	"encoding/json"
	"os"
	"path/filepath"
)

const FormatoRegistro = "m2m-instalacao-registro"

// Origens da instalação: o código extraído do executável ou um clone do repositório.
const (
	OrigemExecutavel = "executavel"
	OrigemClone      = "clone"
)

// Registro é o que a instalação criou, sem segredos; é a fonte da desinstalação (design, decisão 8).
type Registro struct {
	Formato     string   `json:"formato"`
	Versao      int      `json:"versao"`
	M2M         string   `json:"m2m"`
	Diretorio   string   `json:"diretorio"`
	Origem      string   `json:"origem,omitempty"` // vazio nas instalações anteriores ao modo clone: executável
	CriadoEm    string   `json:"criadoEm"`
	Certificado string   `json:"certificado"`
	TLSDir      string   `json:"tlsDir,omitempty"`
	CACriada    bool     `json:"caCriada"`
	NSS         bool     `json:"nss"`
	Atalhos     []string `json:"atalhos"`
}

// DirConfig é ~/.config/m2m, onde ficam o registro e a CA local.
func DirConfig(home string) string {
	return filepath.Join(home, ".config", "m2m")
}

// DirTLS é onde o gerar-certificado-tls.sh guarda a CA e o certificado.
func DirTLS(home string) string {
	return filepath.Join(DirConfig(home), "tls")
}

// CaminhoRegistro é ~/.config/m2m/instalacao.json.
func CaminhoRegistro(home string) string {
	return filepath.Join(DirConfig(home), "instalacao.json")
}

// LerRegistro devolve nil se não há registro.
func LerRegistro(home string) (*Registro, error) {
	b, err := os.ReadFile(CaminhoRegistro(home))
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var r Registro
	if err := json.Unmarshal(b, &r); err != nil {
		return nil, err
	}
	return &r, nil
}

// GravarRegistro grava com modo 0600.
func GravarRegistro(home string, r Registro) error {
	r.Formato, r.Versao = FormatoRegistro, 1
	if err := os.MkdirAll(DirConfig(home), 0o700); err != nil {
		return err
	}
	b, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		return err
	}
	tmp := CaminhoRegistro(home) + ".tmp"
	if err := os.WriteFile(tmp, append(b, '\n'), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, CaminhoRegistro(home))
}
