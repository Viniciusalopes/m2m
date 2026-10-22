package br.com.vovolinux.m2m.sync;

import br.com.vovolinux.m2m.files.FileEntry;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.job.CancellationToken;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Listagem completa dos diretórios de destino (tipo, tamanho, data), usada para o estado pós-sincronização. */
public final class DestListing {

    /** Item da listagem: {@code path} é relativo ao diretório {@code dir}. */
    public record Entry(int dir, String path, String type, long size, long mtime) {
    }

    private DestListing() {
    }

    /** Percorre as raízes (índice = posição + 1) e grava uma linha JSON por item; ignora itens especiais. */
    public static long write(FileGateway gw, List<String> roots, BufferedWriter out, JsonMapper json,
            CancellationToken token) throws IOException {
        long count = 0;
        for (int i = 0; i < roots.size(); i++) {
            String root = roots.get(i);
            try {
                if (!gw.lstat(root).isDirectory()) {
                    continue;
                }
            } catch (NoSuchFileException e) {
                continue;
            }
            ArrayDeque<String[]> stack = new ArrayDeque<>();
            stack.push(new String[] {root, ""});
            while (!stack.isEmpty()) {
                token.throwIfCancelled();
                String[] cur = stack.pop();
                List<FileEntry> kids = new ArrayList<>(gw.list(cur[0]));
                kids.sort(Comparator.comparing(FileEntry::name));
                List<String[]> subs = new ArrayList<>();
                for (FileEntry e : kids) {
                    if (e.type() == FileType.SPECIAL) {
                        continue;
                    }
                    String rel = cur[1].isEmpty() ? e.name() : cur[1] + "/" + e.name();
                    out.write(json.writeValueAsString(new Entry(i + 1, rel, e.type().name(),
                            e.type() == FileType.FILE ? e.size() : 0, e.mtime().toEpochMilli())));
                    out.write('\n');
                    count++;
                    if (e.isDirectory()) {
                        subs.add(new String[] {e.path(), rel});
                    }
                }
                for (int k = subs.size() - 1; k >= 0; k--) {
                    stack.push(subs.get(k));
                }
            }
        }
        return count;
    }
}
