package br.com.vovolinux.m2m.sync;

/**
 * Uma operação do plano de sincronização, com os atributos dos dois lados.
 *
 * @param dir índice do diretório do perfil (dir-N)
 * @param op  CREATE (só na origem), UPDATE (arquivo diferente), REPLACE (tipo mudou: apaga o destino e cria),
 *            DELETE (só no destino)
 * @param st  tipo no lado da origem (FILE, DIR, SYMLINK); {@code null} em DELETE
 * @param tt  tipo no lado do destino; {@code null} em CREATE
 */
public record PlanItem(
        int dir,
        String op,
        String path,
        String st,
        long ss,
        long sm,
        Integer sx,
        String sl,
        String tt,
        long ts,
        long tm,
        Integer tx,
        String tl) {

    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String REPLACE = "REPLACE";
    public static final String DELETE = "DELETE";
}
