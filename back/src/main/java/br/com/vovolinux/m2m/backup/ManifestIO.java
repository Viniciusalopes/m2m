package br.com.vovolinux.m2m.backup;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Leitura e gravação do manifesto em streaming: os itens vivem num arquivo NDJSON temporário durante o backup e o
 * JSON final é montado sem manter a lista na memória; a leitura percorre {@code items} item a item.
 */
@Component
public class ManifestIO {

    private final JsonMapper json;
    /** Leitores que continuam no mesmo parser, sem exigir o fim do documento depois de cada valor. */
    private final ObjectReader itemReader;
    private final ObjectReader valueReader;

    public ManifestIO(JsonMapper json) {
        this.json = json;
        this.itemReader = json.readerFor(Manifest.Item.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.valueReader = json.readerFor(Object.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    // ---- NDJSON temporário ----

    public BufferedWriter openItemsWriter(Path file) throws IOException {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8);
    }

    public void writeItem(Writer w, Manifest.Item item) throws IOException {
        w.write(json.writeValueAsString(item));
        w.write('\n');
    }

    public void forEachItemNdjson(Path file, Consumer<Manifest.Item> consumer) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    consumer.accept(json.readValue(line, Manifest.Item.class));
                }
            }
        }
    }

    // ---- JSON final ----

    /** Escreve {@code {...cabeçalho..., "items":[ ... ]}} lendo os itens do NDJSON. */
    public void writeManifest(OutputStream out, Manifest.Header header, Path itemsNdjson) throws IOException {
        String head = json.writeValueAsString(header);
        // remove a chave de fechamento para acrescentar "items"
        String prefix = head.substring(0, head.length() - 1);
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        w.write(prefix);
        w.write(",\"items\":[");
        boolean first = true;
        try (BufferedReader r = Files.newBufferedReader(itemsNdjson, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (!first) {
                    w.write(',');
                }
                w.write(line);
                first = false;
            }
        }
        w.write("]}");
        w.flush();
    }

    public record Parsed(Manifest.Header header) {
    }

    /**
     * Lê o manifesto em streaming: devolve o cabeçalho e entrega cada item ao consumidor. O cabeçalho precisa vir
     * antes de {@code items} (ordem em que é gravado).
     */
    public Manifest.Header read(InputStream in, Consumer<Manifest.Item> onItem) throws IOException {
        // O cabeçalho é reconstruído a partir dos campos antes de "items".
        java.util.Map<String, Object> header = new java.util.LinkedHashMap<>();
        try (JsonParser p = json.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Manifesto inválido");
            }
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                p.nextToken();
                if (field.equals("items")) {
                    if (p.currentToken() != JsonToken.START_ARRAY) {
                        throw new IOException("Manifesto inválido: items");
                    }
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        onItem.accept(itemReader.readValue(p));
                    }
                } else {
                    header.put(field, valueReader.readValue(p));
                }
            }
        }
        return json.convertValue(header, Manifest.Header.class);
    }

    public Manifest.Header readHeaderOnly(InputStream in) throws IOException {
        return read(in, item -> {
        });
    }
}
