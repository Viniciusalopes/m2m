package br.com.vovolinux.m2m.transfer;

import br.com.vovolinux.m2m.files.FileType;
import br.com.vovolinux.m2m.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.http.HttpStatus;

/**
 * Filtro de listagem (D22): condições sobre nome, caminho, tamanho, data e permissões combinadas por TODAS, QUALQUER
 * UMA ou NENHUMA. Um item que satisfaz o filtro fica oculto. Avaliado no backend; nunca afeta jobs.
 */
public final class TransferFilter {

    public record Condition(String field, String op, String value) {
    }

    public record Definition(String match, Boolean caseSensitive, List<Condition> conditions) {
    }

    /** Os atributos de um item que o filtro enxerga. */
    public record Subject(String name, String path, FileType type, long size, long mtimeMillis, Integer mode) {
    }

    private interface Test {
        boolean test(Subject s);
    }

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final String match;
    private final List<Test> tests;

    private TransferFilter(String match, List<Test> tests) {
        this.match = match;
        this.tests = tests;
    }

    /** Filtro vazio: nada fica oculto. */
    public static final TransferFilter NONE = new TransferFilter("ANY", List.of());

    public boolean isEmpty() {
        return tests.isEmpty();
    }

    /** {@code true} se o item satisfaz o filtro (e portanto deve ser ocultado). */
    public boolean hides(Subject s) {
        if (tests.isEmpty()) {
            return false;
        }
        return switch (match) {
            case "ALL" -> tests.stream().allMatch(t -> t.test(s));
            case "NONE" -> tests.stream().noneMatch(t -> t.test(s));
            default -> tests.stream().anyMatch(t -> t.test(s));
        };
    }

    public static TransferFilter compile(Definition d) {
        if (d == null || d.conditions() == null || d.conditions().isEmpty()) {
            return NONE;
        }
        String match = d.match() == null ? "ANY" : d.match().toUpperCase(Locale.ROOT);
        if (!List.of("ALL", "ANY", "NONE").contains(match)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "filtro.combinacao-invalida");
        }
        boolean cs = Boolean.TRUE.equals(d.caseSensitive());
        List<Test> tests = new ArrayList<>();
        Subject probe = new Subject("", "", FileType.FILE, 0, 0, 0);
        for (Condition c : d.conditions()) {
            Test t = compile(c, cs);
            t.test(probe); // operador inválido falha agora, não no meio da listagem
            tests.add(t);
        }
        return new TransferFilter(match, tests);
    }

    private static Test compile(Condition c, boolean cs) {
        String field = c.field() == null ? "" : c.field().toUpperCase(Locale.ROOT);
        String op = c.op() == null ? "" : c.op().toUpperCase(Locale.ROOT);
        String v = c.value() == null ? "" : c.value();
        return switch (field) {
            case "NAME" -> text(op, v, cs, Subject::name);
            case "PATH" -> text(op, v, cs, Subject::path);
            case "SIZE" -> {
                long n = number(v);
                yield s -> s.type() == FileType.FILE && switch (op) {
                    case "EQ" -> s.size() == n;
                    case "NE" -> s.size() != n;
                    case "GT" -> s.size() > n;
                    case "LT" -> s.size() < n;
                    default -> throw invalid(field, op);
                };
            }
            case "MTIME" -> {
                long t = instant(v);
                yield s -> switch (op) {
                    case "BEFORE" -> s.mtimeMillis() < t;
                    case "AFTER" -> s.mtimeMillis() > t;
                    default -> throw invalid(field, op);
                };
            }
            case "PERMISSIONS" -> {
                int m;
                try {
                    m = Integer.parseInt(v.strip(), 8);
                } catch (NumberFormatException e) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "filtro.valor-invalido", java.util.Map.of("valor", v));
                }
                yield s -> s.mode() != null && switch (op) {
                    case "EQUALS" -> (s.mode() & 0777) == m;
                    case "HAS" -> (s.mode() & m) == m;
                    default -> throw invalid(field, op);
                };
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "filtro.campo-invalido", java.util.Map.of("campo", field));
        };
    }

    private static Test text(String op, String v, boolean cs, java.util.function.Function<Subject, String> get) {
        if (op.equals("REGEX")) {
            Pattern p;
            try {
                p = Pattern.compile(v, cs ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            } catch (PatternSyntaxException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "filtro.regex-invalida", java.util.Map.of("expressao", v));
            }
            return s -> p.matcher(get.apply(s)).find();
        }
        String needle = cs ? v : v.toLowerCase(Locale.ROOT);
        java.util.function.Function<Subject, String> norm = s -> cs ? get.apply(s) : get.apply(s).toLowerCase(Locale.ROOT);
        return switch (op) {
            case "CONTAINS" -> s -> norm.apply(s).contains(needle);
            case "EQUALS" -> s -> norm.apply(s).equals(needle);
            case "STARTS" -> s -> norm.apply(s).startsWith(needle);
            case "ENDS" -> s -> norm.apply(s).endsWith(needle);
            default -> throw invalid("TEXT", op);
        };
    }

    private static long number(String v) {
        try {
            return Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "filtro.valor-invalido", java.util.Map.of("valor", v));
        }
    }

    /** Aceita instante ISO-8601 ({@code 2026-10-01T10:00:00Z}) ou só a data ({@code 2026-10-01}, em São Paulo). */
    private static long instant(String v) {
        try {
            return Instant.parse(v.strip()).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(v.strip()).atStartOfDay(ZONE).toInstant().toEpochMilli();
            } catch (DateTimeParseException e2) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "filtro.valor-invalido", java.util.Map.of("valor", v));
            }
        }
    }

    private static ApiException invalid(String field, String op) {
        return new ApiException(HttpStatus.BAD_REQUEST, "filtro.operador-invalido",
                java.util.Map.of("campo", field, "operador", op));
    }
}
