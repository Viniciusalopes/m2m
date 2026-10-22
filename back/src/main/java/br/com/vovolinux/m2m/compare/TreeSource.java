package br.com.vovolinux.m2m.compare;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Um dos lados da comparação: lista os filhos de um diretório relativo e sabe obter o hash de um arquivo. */
public interface TreeSource {

    /** A raiz da árvore; vazio se ela não existe (o outro lado é então comparado contra o "nada"). */
    Optional<Node> root() throws IOException;

    /** Filhos do diretório (caminho relativo à raiz; "" é a raiz), em qualquer ordem. */
    List<Node> children(String relDir) throws IOException;

    /** SHA-256 (hex) do arquivo; pode ler o conteúdo ou usar hash remoto/do manifesto. */
    String sha256(String relPath, Node node) throws IOException;

    boolean preservesMode();

    boolean preservesLinks();

    /** Descrição para relatórios (ex.: "NOTEBOOK-REMOTO:/home/x"). */
    String describe();
}
