package br.com.vovolinux.m2m.job;

import java.util.LinkedHashMap;
import java.util.Map;

/** Progresso em memória de um job em execução; leituras e escritas são sincronizadas. */
public class JobProgress {

    private String step;
    private long bytesDone;
    private long bytesTotal;
    private long itemsDone;
    private long itemsTotal;
    private String currentPath;
    private int warnings;
    private boolean dirty = true;
    private boolean stepChanged;

    public synchronized void step(String step) {
        if (!java.util.Objects.equals(this.step, step)) {
            this.step = step;
            this.stepChanged = true;
            this.dirty = true;
        }
    }

    public synchronized void totals(long bytesTotal, long itemsTotal) {
        this.bytesTotal = bytesTotal;
        this.itemsTotal = itemsTotal;
        this.dirty = true;
    }

    public synchronized void current(String path) {
        this.currentPath = path;
        this.dirty = true;
    }

    public synchronized void addBytes(long delta) {
        this.bytesDone += delta;
        this.dirty = true;
    }

    public synchronized void itemDone() {
        this.itemsDone++;
        this.dirty = true;
    }

    public synchronized void warning() {
        this.warnings++;
        this.dirty = true;
    }

    /** Zera contadores ao começar outra etapa que conta de novo (ex.: validação depois da gravação). */
    public synchronized void resetCounters() {
        bytesDone = 0;
        bytesTotal = 0;
        itemsDone = 0;
        itemsTotal = 0;
        dirty = true;
    }

    public synchronized boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    public synchronized boolean consumeStepChanged() {
        boolean c = stepChanged;
        stepChanged = false;
        return c;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", step);
        m.put("bytesDone", bytesDone);
        m.put("bytesTotal", bytesTotal);
        m.put("itemsDone", itemsDone);
        m.put("itemsTotal", itemsTotal);
        m.put("percent", bytesTotal > 0 ? Math.min(100.0, bytesDone * 100.0 / bytesTotal)
                : (itemsTotal > 0 ? Math.min(100.0, itemsDone * 100.0 / itemsTotal) : 0.0));
        m.put("currentPath", currentPath);
        m.put("warnings", warnings);
        return m;
    }

    public synchronized String step() {
        return step;
    }
}
