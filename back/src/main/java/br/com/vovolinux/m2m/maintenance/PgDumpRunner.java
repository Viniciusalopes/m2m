package br.com.vovolinux.m2m.maintenance;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

/** Executa o {@code pg_dump} do cliente PostgreSQL 18 incluído na imagem e entrega o SQL em {@code out}. */
@Component
public class PgDumpRunner {

    private final String url;
    private final String user;
    private final String password;
    private final String command;

    @Autowired
    public PgDumpRunner(DataSourceProperties datasource, @Value("${m2m.db-backup.command:pg_dump}") String command) {
        this(datasource.getUrl(), datasource.getUsername(), datasource.getPassword(), command);
    }

    protected PgDumpRunner(String url, String user, String password, String command) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.command = command;
    }

    public void dump(OutputStream out) throws IOException {
        URI uri = URI.create(url.substring("jdbc:".length()));
        String db = uri.getPath().substring(1);
        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        ProcessBuilder pb = new ProcessBuilder(List.of(command, "--no-owner", "--no-privileges", "-h", uri.getHost(),
                "-p", String.valueOf(port), "-U", user, "-d", db));
        pb.environment().put("PGPASSWORD", password);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        try {
            p.getInputStream().transferTo(out);
            String erro = new String(p.getErrorStream().readAllBytes());
            if (p.waitFor() != 0) {
                throw new IOException("pg_dump falhou: " + erro.strip());
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("pg_dump interrompido", e);
        }
    }
}
