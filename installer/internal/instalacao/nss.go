package instalacao

import (
	"context"
	"os"
	"path/filepath"

	"m2m/installer/internal/executor"
)

// NomeCA é o apelido da CA local no NSS e no sistema (o mesmo do gerar-certificado-tls.sh).
const NomeCA = "m2m-local-ca"

func dbNSS(home string) string {
	return "sql:" + filepath.Join(home, ".pki", "nssdb")
}

// RegistrarNSS faz o Chrome/Chromium do usuário confiar na CA local; não exige privilégio. Sem certutil, não faz nada.
func RegistrarNSS(ctx context.Context, ex executor.Executor, home, caCrt string) (bool, error) {
	if !ex.Procurar("certutil") {
		return false, nil
	}
	dir := filepath.Join(home, ".pki", "nssdb")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return false, err
	}
	if _, err := os.Stat(filepath.Join(dir, "cert9.db")); os.IsNotExist(err) {
		if _, err := ex.Rodar(ctx, executor.Comando{Programa: "certutil", Args: []string{"-d", dbNSS(home), "-N", "--empty-password"}}); err != nil {
			return false, err
		}
	}
	// substitui uma entrada antiga (CA recriada)
	_, _ = ex.Rodar(ctx, executor.Comando{Programa: "certutil", Args: []string{"-d", dbNSS(home), "-D", "-n", NomeCA}})
	if _, err := ex.Rodar(ctx, executor.Comando{Programa: "certutil", Args: []string{"-d", dbNSS(home), "-A", "-t", "C,,", "-n", NomeCA, "-i", caCrt}}); err != nil {
		return false, err
	}
	return true, nil
}

// RemoverNSS tira a CA local do NSS do usuário; sem certutil ou sem a entrada, não faz nada.
func RemoverNSS(ctx context.Context, ex executor.Executor, home string) error {
	if !ex.Procurar("certutil") {
		return nil
	}
	if _, err := ex.Rodar(ctx, executor.Comando{Programa: "certutil", Args: []string{"-d", dbNSS(home), "-L", "-n", NomeCA}}); err != nil {
		return nil
	}
	_, err := ex.Rodar(ctx, executor.Comando{Programa: "certutil", Args: []string{"-d", dbNSS(home), "-D", "-n", NomeCA}})
	return err
}
