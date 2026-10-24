package servidor

import (
	"sync"

	"m2m/installer/internal/instalacao"
)

// difusor entrega os eventos de progresso a cada conexão SSE aberta.
type difusor struct {
	mu       sync.Mutex
	ouvintes map[chan instalacao.Evento]struct{}
}

func novoDifusor() *difusor {
	return &difusor{ouvintes: map[chan instalacao.Evento]struct{}{}}
}

func (d *difusor) assinar() chan instalacao.Evento {
	c := make(chan instalacao.Evento, 1024)
	d.mu.Lock()
	d.ouvintes[c] = struct{}{}
	d.mu.Unlock()
	return c
}

func (d *difusor) cancelar(c chan instalacao.Evento) {
	d.mu.Lock()
	delete(d.ouvintes, c)
	d.mu.Unlock()
}

func (d *difusor) conexoes() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return len(d.ouvintes)
}

// publicar não bloqueia: com a fila cheia, a linha de log se perde (o quiz recupera o estado pela consulta).
func (d *difusor) publicar(e instalacao.Evento) {
	d.mu.Lock()
	defer d.mu.Unlock()
	for c := range d.ouvintes {
		select {
		case c <- e:
		default:
		}
	}
}
