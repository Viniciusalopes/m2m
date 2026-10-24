package instalacao

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// Marcador fica no diretório durante a instalação: permite refazer a extração depois de uma falha sem aceitar um
// diretório com outro conteúdo.
const Marcador = ".m2m-instalador"

// DiretorioLivre devolve "" se o diretório não existe, está vazio ou só tem uma instalação interrompida deste
// instalador; senão, o motivo.
func DiretorioLivre(dir string) string {
	entradas, err := os.ReadDir(dir)
	if errors.Is(err, os.ErrNotExist) {
		return gravavel(filepath.Dir(dir))
	}
	if err != nil {
		return "Não foi possível ler " + dir + ": " + err.Error()
	}
	if len(entradas) == 0 {
		return ""
	}
	if _, err := os.Stat(filepath.Join(dir, Marcador)); err == nil {
		return ""
	}
	return "O diretório " + dir + " já existe e não está vazio. Escolha outro ou esvazie-o."
}

// EhClone devolve "" se dir tem os arquivos de um clone do m2m; senão, o motivo.
func EhClone(dir string) string {
	for _, f := range []string{"docker-compose.yml", ".env.example", filepath.Join("scripts", "gerar-certificado-tls.sh")} {
		if _, err := os.Stat(filepath.Join(dir, f)); err != nil {
			return dir + " não parece um clone do m2m: falta " + f + "."
		}
	}
	return ""
}

// ValidarClone devolve "" se dir é um clone do m2m pronto para a primeira instalação (modo clone); senão, o motivo.
func ValidarClone(dir string) string {
	if msg := EhClone(dir); msg != "" {
		return msg
	}
	if _, err := os.Stat(filepath.Join(dir, ".env")); err == nil {
		return "O clone " + dir + " já tem um .env: o m2m já foi instalado a partir dele."
	}
	return ""
}

// gravavel sobe até o primeiro pai existente e confere que dá para criar diretórios nele.
func gravavel(dir string) string {
	for {
		info, err := os.Stat(dir)
		if err == nil {
			if !info.IsDir() {
				return dir + " não é um diretório."
			}
			teste, err := os.MkdirTemp(dir, ".m2m-teste-")
			if err != nil {
				return "Sem permissão para criar o diretório em " + dir + "."
			}
			os.Remove(teste)
			return ""
		}
		pai := filepath.Dir(dir)
		if pai == dir {
			return "Caminho inválido."
		}
		dir = pai
	}
}

// Extrair descompacta o código (tar.gz do git archive) no diretório, recusando caminhos que saiam dele.
func Extrair(tgz []byte, dir string) error {
	gz, err := gzip.NewReader(bytes.NewReader(tgz))
	if err != nil {
		return fmt.Errorf("código embutido corrompido: %w", err)
	}
	tr := tar.NewReader(gz)
	raiz := filepath.Clean(dir) + string(os.PathSeparator)
	for {
		h, err := tr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return fmt.Errorf("código embutido corrompido: %w", err)
		}
		destino := filepath.Join(dir, h.Name)
		if !strings.HasPrefix(destino+string(os.PathSeparator), raiz) {
			return fmt.Errorf("caminho fora do diretório no código embutido: %s", h.Name)
		}
		switch h.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(destino, 0o755); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(destino), 0o755); err != nil {
				return err
			}
			modo := os.FileMode(0o644)
			if h.Mode&0o111 != 0 {
				modo = 0o755
			}
			f, err := os.OpenFile(destino, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, modo)
			if err != nil {
				return err
			}
			_, err = io.Copy(f, tr)
			if errFechar := f.Close(); err == nil {
				err = errFechar
			}
			if err != nil {
				return err
			}
		case tar.TypeSymlink:
			alvo := filepath.Join(filepath.Dir(destino), h.Linkname)
			if filepath.IsAbs(h.Linkname) || !strings.HasPrefix(alvo+string(os.PathSeparator), raiz) {
				return fmt.Errorf("link fora do diretório no código embutido: %s", h.Name)
			}
			os.Remove(destino)
			if err := os.Symlink(h.Linkname, destino); err != nil {
				return err
			}
		}
	}
}
