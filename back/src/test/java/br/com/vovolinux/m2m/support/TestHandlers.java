package br.com.vovolinux.m2m.support;

import br.com.vovolinux.m2m.job.Job;
import br.com.vovolinux.m2m.job.JobContext;
import br.com.vovolinux.m2m.job.JobHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Handler de job de teste (tipo TEST): fica parado até o teste liberar, registra a ordem e a concorrência. */
@TestConfiguration(proxyBeanMethods = false)
public class TestHandlers {

    public static class TestJobHandler implements JobHandler {

        public final List<String> started = new CopyOnWriteArrayList<>();
        public final List<String> finished = new CopyOnWriteArrayList<>();
        public final List<String> recovered = new CopyOnWriteArrayList<>();
        public final AtomicInteger concurrent = new AtomicInteger();
        public final AtomicInteger maxConcurrent = new AtomicInteger();
        private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        public final Set<Long> machinesToAbort = ConcurrentHashMap.newKeySet();

        @Override
        public String type() {
            return "TEST";
        }

        private CountDownLatch gate(String name) {
            return gates.computeIfAbsent(name, n -> new CountDownLatch(1));
        }

        /** Libera o job com esse nome para terminar. */
        public void release(String name) {
            gate(name).countDown();
        }

        public void reset() {
            started.clear();
            finished.clear();
            recovered.clear();
            maxConcurrent.set(0);
            concurrent.set(0);
            gates.clear();
            machinesToAbort.clear();
        }

        @Override
        public void run(JobContext ctx) throws Exception {
            String name = ctx.params().get("name").asText();
            String mode = ctx.params().has("mode") ? ctx.params().get("mode").asText() : "wait";
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            started.add(name);
            try {
                ctx.progress().step("TESTE");
                ctx.progress().totals(1000, 10);
                ctx.progress().addBytes(250);
                ctx.progress().current("arquivo-" + name + ".txt");
                if (mode.equals("fail")) {
                    throw new IllegalStateException("falha de teste");
                }
                if (mode.equals("warn")) {
                    ctx.message("WARN", "teste.aviso", Map.of("item", name));
                    ctx.completeWithWarnings();
                    return;
                }
                CountDownLatch g = gate(name);
                while (!g.await(20, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (mode.equals("io") && ctx.token().isCancelled()) {
                        // simula a leitura que falha porque a conexão foi fechada à força
                        throw new IOException("Connection closed");
                    }
                    ctx.token().throwIfCancelled();
                }
            } finally {
                concurrent.decrementAndGet();
                finished.add(name);
            }
        }

        @Override
        public void recover(Job job) {
            recovered.add(String.valueOf(job.id()));
        }

        @Override
        public Set<Long> machinesOf(Job job, tools.jackson.databind.JsonNode params) {
            return machinesToAbort;
        }
    }

    @Bean
    TestJobHandler testJobHandler() {
        return new TestJobHandler();
    }
}
