package br.com.vovolinux.m2m.files;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Operações compostas sobre um {@link FileGateway}. */
public final class FsOps {

    private FsOps() {
    }

    /** Remove o item e, se for diretório, todo o conteúdo (sem seguir links). Ausência não é erro. */
    public static void deleteTree(FileGateway gw, String path) throws IOException {
        FileEntry e;
        try {
            e = gw.lstat(path);
        } catch (NoSuchFileException ex) {
            return;
        }
        if (!e.isDirectory()) {
            gw.delete(path);
            return;
        }
        // pré-ordem para listar, pós-ordem (inversa) para excluir: filhos antes dos pais
        List<FileEntry> order = new ArrayList<>();
        ArrayDeque<String> stack = new ArrayDeque<>();
        stack.push(path);
        while (!stack.isEmpty()) {
            String dir = stack.pop();
            for (FileEntry c : gw.list(dir)) {
                order.add(c);
                if (c.isDirectory()) {
                    stack.push(c.path());
                }
            }
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            gw.delete(order.get(i).path());
        }
        gw.delete(path);
    }

    public static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    public static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
