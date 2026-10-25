package prerequisitos

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"m2m/installer/internal/comandos"
	"m2m/installer/internal/executor"
	"m2m/installer/internal/sistema"
)

// Porta 80 (0x0050) e 443 (0x01BB) em LISTEN (0A); 8080 (0x1F90) só ESTABLISHED (01).
const procTCP = `  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000:0050 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 1 1 0 100 0 0 10 0
   1: 0100007F:1F90 0100007F:9C40 01 00000000:00000000 00:00000000 00000000  1000        0 2 1 0 20 4 30 10 -1
`
const procTCP6 = `  sl  local_address                         remote_address                        st
   0: 00000000000000000000000000000000:01BB 00000000000000000000000000000000:0000 0A 00000000:00000000
`

func raizFalsa(t *testing.T, memKB string, grupo string) string {
	t.Helper()
	raiz := t.TempDir()
	arquivos := map[string]string{
		"proc/net/tcp":  procTCP,
		"proc/net/tcp6": procTCP6,
		"proc/meminfo":  "MemTotal:       " + memKB + " kB\nMemFree:  100 kB\n",
		"etc/group":     "root:x:0:\n" + grupo + "\n",
		"etc/os-release": `PRETTY_NAME="Ubuntu 24.04.3 LTS"
ID=ubuntu
VERSION_ID="24.04"
VERSION_CODENAME=noble
`,
	}
	for nome, conteudo := range arquivos {
		p := filepath.Join(raiz, nome)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(conteudo), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return raiz
}

func sonda(t *testing.T, raiz string, ex *executor.Falso, livre uint64) Sonda {
	so, err := sistema.DetectarSO(raiz)
	if err != nil {
		t.Fatal(err)
	}
	return Sonda{
		Raiz: raiz, Exec: ex, UID: 1000,
		Ambiente:   comandos.Ambiente{SO: so, Usuario: "ana"},
		Internet:   func(context.Context, string) error { return nil },
		DiscoLivre: func(string) (uint64, error) { return livre, nil },
	}
}

func dockerOk() *executor.Falso {
	return executor.NovoFalso(map[string]executor.Resposta{
		"docker version":         {Saida: "29.1.0\n"},
		"docker compose version": {Saida: "v2.40.0\n"},
		"docker buildx version":  {Saida: "github.com/docker/buildx v0.29.0\n"},
		"docker info":            {Saida: "/var/lib/docker\n"},
	})
}

func item(v Verificacao, id string) Item {
	for _, it := range v.Itens {
		if it.ID == id {
			return it
		}
	}
	return Item{}
}

func TestTudoAtendido(t *testing.T) {
	v := sonda(t, raizFalsa(t, "8000000", "docker:x:999:ana"), dockerOk(), 50<<30).Verificar(context.Background(), "/home/ana/m2m", []int{8081, 3000})
	if v.Bloqueia {
		t.Fatalf("não deveria bloquear: %+v", v.Itens)
	}
	for _, id := range []string{"sistema", "docker", "compose", "openssl", "internet", "portas", "memoria", "disco"} {
		if e := item(v, id).Estado; e != Atendido {
			t.Errorf("%s: esperava atendido, veio %s (%s)", id, e, item(v, id).Detalhe)
		}
	}
}

func TestDockerAusente(t *testing.T) {
	ex := dockerOk()
	ex.Programas = map[string]bool{"openssl": true}
	v := sonda(t, raizFalsa(t, "8000000", ""), ex, 50<<30).Verificar(context.Background(), "/home/ana/m2m", nil)
	d := item(v, "docker")
	if !v.Bloqueia || d.Estado != Pendente || !strings.Contains(d.Detalhe, "não está instalado") {
		t.Fatalf("esperava Docker pendente: %+v", d)
	}
	if c := item(v, "certutil"); c.Estado != Opcional {
		t.Errorf("certutil ausente deveria ser opcional: %+v", c)
	}
}

func TestSemPermissaoNoDocker(t *testing.T) {
	ex := dockerOk()
	ex.Respostas["docker version"] = executor.Resposta{Erro: "permission denied while trying to connect to the Docker daemon socket"}
	v := sonda(t, raizFalsa(t, "8000000", "docker:x:999:"), ex, 50<<30).Verificar(context.Background(), "/x", nil)
	d := item(v, "docker")
	if d.Estado != Pendente || !strings.Contains(d.Detalhe, "não tem permissão") {
		t.Fatalf("esperava o usuário fora do grupo: %+v", d)
	}
	// já no grupo, só falta a sessão nova
	v = sonda(t, raizFalsa(t, "8000000", "docker:x:999:root,ana"), ex, 50<<30).Verificar(context.Background(), "/x", nil)
	if d := item(v, "docker"); d.Estado != Pendente || !strings.Contains(d.Detalhe, "sessão foi aberta antes da inclusão") {
		t.Fatalf("esperava a sessão antiga: %+v", d)
	}
}

func TestDockerParado(t *testing.T) {
	ex := dockerOk()
	ex.Respostas["docker version"] = executor.Resposta{Erro: "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?"}
	v := sonda(t, raizFalsa(t, "8000000", ""), ex, 50<<30).Verificar(context.Background(), "/x", nil)
	if d := item(v, "docker"); d.Estado != Pendente || !strings.Contains(d.Detalhe, "serviço não responde") {
		t.Fatalf("esperava o serviço parado: %+v", d)
	}
}

func TestPortasMemoriaEDisco(t *testing.T) {
	v := sonda(t, raizFalsa(t, "2000000", ""), dockerOk(), 5<<30).Verificar(context.Background(), "/home/ana/m2m", []int{80, 443, 8080})
	if p := item(v, "portas"); p.Estado != Opcional || !strings.Contains(p.Detalhe, "80, 443") || strings.Contains(p.Detalhe, "8080") {
		t.Errorf("portas: %+v", p)
	}
	if m := item(v, "memoria"); m.Estado != Pendente || !strings.Contains(m.Detalhe, "Aumente a memória") {
		t.Errorf("memória de 1,9 GiB deveria bloquear: %+v", m)
	}
	d := item(v, "disco")
	if d.Estado != Pendente || !strings.Contains(d.Detalhe, "5.0 GiB livres") || !strings.Contains(d.Detalhe, "16.0 GiB") ||
		!strings.Contains(d.Detalhe, "Libere espaço ou aumente o disco") || strings.Contains(d.Detalhe, "instalar.sh") {
		t.Errorf("disco: %+v", d)
	}
	if !v.Bloqueia {
		t.Error("memória e disco insuficientes deveriam bloquear")
	}
}

func TestMemoriaDeMaquinaDe4GiB(t *testing.T) {
	// VM de 4096 MB: o kernel mostra 3,8 GiB em MemTotal
	m := item(sonda(t, raizFalsa(t, "4010316", ""), dockerOk(), 50<<30).Verificar(context.Background(), "/x", nil), "memoria")
	if m.Estado != Atendido || !strings.Contains(m.Detalhe, "máquina de 4.0 GiB") {
		t.Errorf("máquina de 4 GiB deveria atender: %+v", m)
	}
	if m := item(sonda(t, raizFalsa(t, "3500000", ""), dockerOk(), 50<<30).Verificar(context.Background(), "/x", nil), "memoria"); m.Estado != Pendente {
		t.Errorf("3,3 GiB deveria bloquear: %+v", m)
	}
}

func TestBuildxAusente(t *testing.T) {
	ex := dockerOk()
	ex.Respostas["docker buildx version"] = executor.Resposta{Erro: "docker: 'buildx' is not a docker command."}
	v := sonda(t, raizFalsa(t, "8000000", "docker:x:999:ana"), ex, 50<<30).Verificar(context.Background(), "/x", nil)
	if c := item(v, "compose"); !v.Bloqueia || c.Estado != Pendente || !strings.Contains(c.Detalhe, "Buildx") {
		t.Fatalf("esperava o Buildx pendente: %+v", c)
	}
}

func TestSemInternet(t *testing.T) {
	s := sonda(t, raizFalsa(t, "8000000", ""), dockerOk(), 50<<30)
	s.Internet = func(_ context.Context, h string) error {
		if h == "registry.npmjs.org" {
			return errors.New("timeout")
		}
		return nil
	}
	v := s.Verificar(context.Background(), "/x", nil)
	if i := item(v, "internet"); i.Estado != Pendente || !strings.Contains(i.Detalhe, "registry.npmjs.org") {
		t.Fatalf("internet: %+v", i)
	}
}

func TestUIDDiferenteAvisa(t *testing.T) {
	s := sonda(t, raizFalsa(t, "8000000", ""), dockerOk(), 50<<30)
	s.UID = 1001
	if u := item(s.Verificar(context.Background(), "/x", nil), "uid"); u.Estado != Opcional {
		t.Fatalf("esperava aviso de UID: %+v", u)
	}
}
