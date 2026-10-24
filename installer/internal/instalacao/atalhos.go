package instalacao

import (
	"os"
	"path/filepath"
	"strings"
)

const (
	inicioBloco = "# >>> m2m >>>"
	fimBloco    = "# <<< m2m <<<"
)

// ArquivosShell são os arquivos de inicialização que existem no home; sem nenhum, o ~/.bashrc.
func ArquivosShell(home string) []string {
	var existentes []string
	for _, nome := range []string{".bashrc", ".zshrc"} {
		p := filepath.Join(home, nome)
		if _, err := os.Stat(p); err == nil {
			existentes = append(existentes, p)
		}
	}
	if len(existentes) == 0 {
		existentes = []string{filepath.Join(home, ".bashrc")}
	}
	return existentes
}

// aspas protege um valor para o shell; caminhos só com caracteres seguros saem sem aspas.
func aspas(s string) string {
	if s != "" && strings.Trim(s, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/._-+=:@,") == "" {
		return s
	}
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

// BlocoAtalhos monta o bloco com m2m-up e m2m-down. Com a CA local, m2m-up passa pelo script que renova o
// certificado; com certificado próprio ou autoassinado, sobe direto (o script reescreveria o TLS_* do .env).
func BlocoAtalhos(dir string, comCALocal bool) string {
	compose := "docker compose -f " + aspas(filepath.Join(dir, "docker-compose.yml")) + " --env-file " + aspas(filepath.Join(dir, ".env"))
	up := "sh " + aspas(filepath.Join(dir, "scripts", "m2m-up.sh"))
	if !comCALocal {
		up = compose + " up -d --build"
	}
	return inicioBloco + "\n" +
		"# Atalhos criados pelo instalador do m2m; a desinstalação remove este bloco.\n" +
		"alias m2m-up=" + aspas(up) + "\n" +
		"alias m2m-down=" + aspas(compose+" down") + "\n" +
		fimBloco + "\n"
}

// AdicionarAtalhos grava o bloco nos arquivos de shell (substituindo um bloco anterior) e devolve os alterados.
func AdicionarAtalhos(home, dir string, comCALocal bool) ([]string, error) {
	var alterados []string
	for _, p := range ArquivosShell(home) {
		atual, err := os.ReadFile(p)
		if err != nil && !os.IsNotExist(err) {
			return alterados, err
		}
		texto := semBloco(string(atual))
		if texto != "" && !strings.HasSuffix(texto, "\n") {
			texto += "\n"
		}
		texto += "\n" + BlocoAtalhos(dir, comCALocal)
		if err := os.WriteFile(p, []byte(texto), modo(p)); err != nil {
			return alterados, err
		}
		alterados = append(alterados, p)
	}
	return alterados, nil
}

// RemoverAtalhos tira o bloco dos arquivos dados e devolve os que tinham o bloco.
func RemoverAtalhos(arquivos []string) ([]string, error) {
	var alterados []string
	for _, p := range arquivos {
		atual, err := os.ReadFile(p)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			return alterados, err
		}
		novo := semBloco(string(atual))
		if novo == string(atual) {
			continue
		}
		if err := os.WriteFile(p, []byte(novo), modo(p)); err != nil {
			return alterados, err
		}
		alterados = append(alterados, p)
	}
	return alterados, nil
}

func modo(p string) os.FileMode {
	if info, err := os.Stat(p); err == nil {
		return info.Mode().Perm()
	}
	return 0o644
}

// semBloco remove o bloco entre os marcadores e a linha em branco que o antecede.
func semBloco(texto string) string {
	ini := strings.Index(texto, inicioBloco)
	if ini < 0 {
		return texto
	}
	fim := strings.Index(texto[ini:], fimBloco)
	if fim < 0 {
		return texto
	}
	fim = ini + fim + len(fimBloco)
	if fim < len(texto) && texto[fim] == '\n' {
		fim++
	}
	antes := texto[:ini]
	if strings.HasSuffix(antes, "\n\n") {
		antes = antes[:len(antes)-1]
	}
	return antes + texto[fim:]
}
