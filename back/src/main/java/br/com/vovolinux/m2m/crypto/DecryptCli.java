package br.com.vovolinux.m2m.crypto;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Comando {@code decrypt} (D29): decifra um {@code .tar.zst.enc} fora do m2m, só com o arquivo da chave do perfil.
 *
 * <pre>
 * docker run --rm -v "$PWD:/dados" m2m-back decrypt --key /dados/chave.json --in /dados/X.tar.zst.enc --out /dados/X.tar.zst
 * </pre>
 *
 * Não sobe o Spring nem acessa o banco: a chave exportada basta.
 */
public final class DecryptCli {

    private DecryptCli() {
    }

    /** @return código de saída: 0 sucesso, 1 uso incorreto, 2 falha ao decifrar */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        String key = null;
        String in = null;
        String outFile = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--key" -> key = i + 1 < args.length ? args[++i] : null;
                case "--in" -> in = i + 1 < args.length ? args[++i] : null;
                case "--out" -> outFile = i + 1 < args.length ? args[++i] : null;
                default -> {
                    err.println("Argumento desconhecido: " + args[i]);
                    return usage(err);
                }
            }
        }
        if (key == null || in == null || outFile == null) {
            return usage(err);
        }
        try {
            StreamingAead aead = load(Path.of(key));
            Path destino = Path.of(outFile);
            Path parcial = destino.resolveSibling(destino.getFileName() + ".partial");
            try (InputStream raw = Files.newInputStream(Path.of(in));
                    InputStream clear = aead.newDecryptingStream(raw, ArchiveCrypto.ASSOCIATED_DATA);
                    OutputStream o = Files.newOutputStream(parcial)) {
                clear.transferTo(o);
            } catch (IOException | GeneralSecurityException e) {
                Files.deleteIfExists(parcial);
                throw e;
            }
            Files.move(parcial, destino, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            out.println("Arquivo decifrado em " + destino);
            return 0;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            err.println("Não foi possível decifrar: " + e.getMessage());
            return 2;
        }
    }

    private static int usage(PrintStream err) {
        err.println("Uso: decrypt --key chave.json --in arquivo.tar.zst.enc --out arquivo.tar.zst");
        return 1;
    }

    /** Lê o arquivo exportado pela interface (JSON com o keyset do Tink dentro). */
    static StreamingAead load(Path file) throws IOException, GeneralSecurityException {
        JsonNode root = new JsonMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
        if (!ArchiveCrypto.EXPORT_FORMAT.equals(root.path("formato").asText())) {
            throw new IOException("O arquivo não é uma chave exportada pelo m2m");
        }
        StreamingAeadConfig.register();
        KeysetHandle handle = TinkJsonProtoKeysetFormat.parseKeyset(root.path("keyset").asText(),
                InsecureSecretKeyAccess.get());
        return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead.class);
    }
}
