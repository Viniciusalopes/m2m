package sistema

import (
	"net"
	"os"
	"sort"
	"strings"
	"syscall"
)

// DiscoLivre devolve o espaço livre, em bytes, para o usuário no sistema de arquivos do caminho (ou do pai existente).
func DiscoLivre(caminho string) (uint64, error) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(ExistenteMaisProximo(caminho), &st); err != nil {
		return 0, err
	}
	return st.Bavail * uint64(st.Bsize), nil
}

// Maquina devolve o nome da arquitetura do kernel (uname -m).
func Maquina() string {
	var u syscall.Utsname
	if err := syscall.Uname(&u); err != nil {
		return ""
	}
	var b strings.Builder
	for _, c := range u.Machine {
		if c == 0 {
			break
		}
		b.WriteByte(byte(c))
	}
	return b.String()
}

// Hostname devolve o nome da máquina.
func Hostname() string {
	h, err := os.Hostname()
	if err != nil {
		return "localhost"
	}
	return h
}

// Enderecos lista os endereços IPv4 da máquina fora do loopback, mais o nome do host, para a etapa Rede.
func Enderecos() []string {
	var ips []string
	ifaces, _ := net.Interfaces()
	for _, i := range ifaces {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
			continue
		}
		// interfaces virtuais do Docker não servem de endereço público
		if strings.HasPrefix(i.Name, "docker") || strings.HasPrefix(i.Name, "br-") || strings.HasPrefix(i.Name, "veth") {
			continue
		}
		addrs, _ := i.Addrs()
		for _, a := range addrs {
			if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil {
				ips = append(ips, n.IP.String())
			}
		}
	}
	sort.Strings(ips)
	return append(ips, Hostname())
}
