package br.com.vovolinux.m2m.sync;

import br.com.vovolinux.m2m.compare.GatewayTree;
import br.com.vovolinux.m2m.compare.Node;
import br.com.vovolinux.m2m.compare.TreeComparator;
import br.com.vovolinux.m2m.files.FileGateway;
import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.job.CancellationToken;
import br.com.vovolinux.m2m.job.JobFailure;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Monta o plano de sincronização com o comparador (D11): o que criar, atualizar, substituir e excluir. */
@Component
public class SyncPlanner {

    /** Diretório do perfil com o caminho de destino mapeado. */
    public record DirPair(int index, String sourcePath, String targetPath) {
    }

    private final TreeComparator comparator;
    private final JsonMapper json;

    public SyncPlanner(TreeComparator comparator, JsonMapper json) {
        this.comparator = comparator;
        this.json = json;
    }

    /**
     * @param content compara também o SHA-256 ("comparar conteúdo")
     * @param planFile arquivo NDJSON onde o plano é gravado
     * @throws JobFailure {@code sincronizacao.origem-ausente} se um diretório de origem não existe ou não lista
     */
    public SyncPlan plan(FileGateway src, FileGateway dst, String srcLabel, String dstLabel, List<DirPair> dirs,
            boolean content, Path planFile, CancellationToken token) throws IOException {
        List<SyncPlan.DirSummary> summaries = new ArrayList<>();
        long[] total = new long[7]; // create, update, replace, delete, srcFiles, srcBytes, specials
        boolean modes = true;
        boolean links = true;
        try (BufferedWriter out = Files.newBufferedWriter(planFile, StandardCharsets.UTF_8)) {
            for (DirPair d : dirs) {
                token.throwIfCancelled();
                GatewayTree s = new GatewayTree(src, d.sourcePath(), srcLabel);
                GatewayTree t = new GatewayTree(dst, d.targetPath(), dstLabel);
                if (s.root().filter(Node::isDir).isEmpty()) {
                    throw new JobFailure("sincronizacao.origem-ausente", Map.of("diretorio", d.sourcePath()));
                }
                try {
                    src.list(d.sourcePath()); // erro de listagem também aborta
                } catch (NoSuchFileException e) {
                    throw new JobFailure("sincronizacao.origem-ausente", Map.of("diretorio", d.sourcePath()));
                } catch (IOException e) {
                    throw new JobFailure("sincronizacao.origem-ausente", Map.of("diretorio", d.sourcePath(),
                            "detalhe", String.valueOf(e.getMessage())));
                }
                boolean targetExists = t.root().filter(Node::isDir).isPresent();
                long[] c = new long[6]; // create, update, replace, delete, srcItems, tgtItems
                java.util.ArrayList<String> replacedDirs = new ArrayList<>();
                TreeComparator.Stats stats = comparator.compare(s, t,
                        content ? TreeComparator.Mode.FULL : TreeComparator.Mode.QUICK,
                        TreeComparator.MTIME_TOLERANCE_MILLIS, token, new TreeComparator.Visitor() {
                            private void emit(PlanItem item) {
                                try {
                                    out.write(json.writeValueAsString(item));
                                    out.write('\n');
                                } catch (IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            }

                            @Override
                            public void onlySource(String rel, Node n) {
                                c[0]++;
                                c[4]++;
                                if (n.type() == FileType.FILE) {
                                    total[4]++;
                                    total[5] += n.size();
                                }
                                emit(new PlanItem(d.index(), PlanItem.CREATE, rel, n.type().name(), n.size(),
                                        n.mtimeMillis(), n.mode(), n.linkTarget(), null, 0, 0, null, null));
                            }

                            @Override
                            public void onlyTarget(String rel, Node n) {
                                c[3]++;
                                c[5]++;
                                emit(new PlanItem(d.index(), PlanItem.DELETE, rel, null, 0, 0, null, null,
                                        n.type().name(), n.size(), n.mtimeMillis(), n.mode(), n.linkTarget()));
                            }

                            @Override
                            public void different(String rel, Node sn, Node tn, Set<TreeComparator.Reason> reasons) {
                                c[4]++;
                                c[5]++;
                                if (sn.type() == FileType.FILE) {
                                    total[4]++;
                                    total[5] += sn.size();
                                }
                                String op = reasons.contains(TreeComparator.Reason.TYPE) ? PlanItem.REPLACE
                                        : PlanItem.UPDATE;
                                if (op.equals(PlanItem.REPLACE)) {
                                    c[2]++;
                                    replacedDirs.add(rel);
                                } else {
                                    c[1]++;
                                }
                                emit(new PlanItem(d.index(), op, rel, sn.type().name(), sn.size(), sn.mtimeMillis(),
                                        sn.mode(), sn.linkTarget(), tn.type().name(), tn.size(), tn.mtimeMillis(),
                                        tn.mode(), tn.linkTarget()));
                            }

                            @Override
                            public void same(String rel, Node sn, Node tn) {
                                c[4]++;
                                c[5]++;
                                if (sn.type() == FileType.FILE) {
                                    total[4]++;
                                    total[5] += sn.size();
                                }
                            }
                        });
                total[6] += stats.specialsIgnored;
                modes &= stats.modesCompared;
                links &= stats.linksCompared;
                summaries.add(new SyncPlan.DirSummary(d.index(), d.sourcePath(), d.targetPath(), true, targetExists,
                        c[0], c[1], c[2], c[3], c[4], c[5]));
                total[0] += c[0];
                total[1] += c[1];
                total[2] += c[2];
                total[3] += c[3];
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        return new SyncPlan(planFile, summaries, total[0], total[1], total[2], total[3], total[4], total[5], total[6],
                modes, links);
    }
}
