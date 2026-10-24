// Package dotenv gera o .env a partir do .env.example, mantendo os comentários, e lê um .env existente.
package dotenv

import (
	"bufio"
	"bytes"
	"strings"
)

// Par é uma variável do .env.
type Par struct {
	Chave string
	Valor string
}

// seguro são os caracteres que dispensam aspas: o Compose interpola "$" e trata "#" como comentário.
const seguro = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_./:@+,=-"

// Formatar devolve o valor como deve ir no .env: sem aspas quando seguro, senão entre aspas simples (o Compose não
// interpola valores entre aspas simples). Valores com aspas simples são recusados na validação das respostas.
func Formatar(v string) string {
	if strings.Trim(v, seguro) == "" {
		return v
	}
	return "'" + v + "'"
}

// Gerar substitui no modelo o valor das chaves dadas, mantém comentários e demais linhas, e acrescenta no fim as chaves
// que o modelo não tem.
func Gerar(modelo []byte, pares []Par) []byte {
	valores := map[string]string{}
	for _, p := range pares {
		valores[p.Chave] = p.Valor
	}
	usadas := map[string]bool{}
	var out bytes.Buffer
	sc := bufio.NewScanner(bytes.NewReader(modelo))
	for sc.Scan() {
		linha := sc.Text()
		chave, _, ok := strings.Cut(linha, "=")
		chave = strings.TrimSpace(chave)
		if v, existe := valores[chave]; ok && existe && !strings.HasPrefix(strings.TrimSpace(linha), "#") && !usadas[chave] {
			out.WriteString(chave + "=" + Formatar(v) + "\n")
			usadas[chave] = true
			continue
		}
		out.WriteString(linha + "\n")
	}
	var faltantes []Par
	for _, p := range pares {
		if !usadas[p.Chave] {
			faltantes = append(faltantes, p)
			usadas[p.Chave] = true
		}
	}
	if len(faltantes) > 0 {
		out.WriteString("\n# --- Acrescentadas pelo instalador ---\n")
		for _, p := range faltantes {
			out.WriteString(p.Chave + "=" + Formatar(p.Valor) + "\n")
		}
	}
	return out.Bytes()
}

// Ler interpreta um .env: ignora comentários e linhas vazias, tira aspas simples ou duplas; a última ocorrência vale.
func Ler(dados []byte) map[string]string {
	m := map[string]string{}
	sc := bufio.NewScanner(bytes.NewReader(dados))
	for sc.Scan() {
		linha := strings.TrimSpace(sc.Text())
		if linha == "" || strings.HasPrefix(linha, "#") {
			continue
		}
		linha = strings.TrimPrefix(linha, "export ")
		chave, valor, ok := strings.Cut(linha, "=")
		if !ok {
			continue
		}
		valor = strings.TrimSpace(valor)
		if len(valor) >= 2 && (valor[0] == '\'' || valor[0] == '"') && valor[len(valor)-1] == valor[0] {
			valor = valor[1 : len(valor)-1]
		} else if i := strings.Index(valor, " #"); i >= 0 {
			valor = strings.TrimSpace(valor[:i])
		}
		m[strings.TrimSpace(chave)] = valor
	}
	return m
}
