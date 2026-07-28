package org.twelve.meridian.python.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists Meridian product evaluation artifacts for a future dynamic-language
 * paper. Not tied to the GCP TOPLAS / Outline-port claim.
 *
 * <p>Default directory: {@code meridian-python/docs/meridian-eval/}.
 */
public final class EvalResultArchive {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private EvalResultArchive() {}

    /** Resolve {@code docs/meridian-eval} under the meridian-python module. */
    public static Path defaultRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        Path direct = cwd.resolve("meridian-python/docs/meridian-eval");
        if (Files.isDirectory(direct.getParent()) || Files.exists(cwd.resolve("meridian-python"))) {
            return direct;
        }
        // Running with -pl meridian-python (cwd = module root)
        return cwd.resolve("docs/meridian-eval");
    }

    /**
     * Write JSON + Markdown for a suite. Overwrites {@code <suite>-latest.*}
     * and also keeps a timestamped copy.
     */
    public static Path writeSuite(String suiteId, String title, String jsonBody, String markdownBody)
            throws IOException {
        Path root = defaultRoot();
        Files.createDirectories(root);
        String ts = TS.format(Instant.now());
        Path latestJson = root.resolve(suiteId + "-latest.json");
        Path latestMd = root.resolve(suiteId + "-latest.md");
        Path stampedJson = root.resolve(suiteId + "-" + ts + ".json");
        Path stampedMd = root.resolve(suiteId + "-" + ts + ".md");

        String envelope = """
                {
                  "suite": %s,
                  "title": %s,
                  "product": "meridian",
                  "claim_boundary": "Meridian annotate→mypyc product eval; not GCP TOPLAS / Outline-port SOTA",
                  "captured_at": %s,
                  "payload": %s
                }
                """.formatted(jsonString(suiteId), jsonString(title), jsonString(ts),
                jsonBody == null || jsonBody.isBlank() ? "null" : jsonBody.trim());

        String md = "# " + title + "\n\n"
                + "> Meridian product evaluation (annotate → mypyc vs native CPython).\n"
                + "> Not a GCP core / TOPLAS claim.\n\n"
                + "- Suite: `" + suiteId + "`\n"
                + "- Captured: `" + ts + "`\n\n"
                + (markdownBody == null ? "" : markdownBody)
                + "\n";

        Files.writeString(latestJson, envelope, StandardCharsets.UTF_8);
        Files.writeString(stampedJson, envelope, StandardCharsets.UTF_8);
        Files.writeString(latestMd, md, StandardCharsets.UTF_8);
        Files.writeString(stampedMd, md, StandardCharsets.UTF_8);
        return latestJson;
    }

    /** Build a minimal JSON array from speedup rows for archival. */
    public static String rowsToJson(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            if (i > 0) sb.append(",\n");
            sb.append("  {");
            boolean first = true;
            for (Map.Entry<String, Object> e : r.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(jsonString(e.getKey())).append(": ");
                Object v = e.getValue();
                if (v instanceof Number || v instanceof Boolean) sb.append(v);
                else sb.append(jsonString(String.valueOf(v)));
            }
            sb.append("}");
        }
        sb.append("\n]");
        return sb.toString();
    }

    public static Map<String, Object> row(
            String func, boolean correct,
            double nativeNs, double bareNs, double meridianNs,
            double speedupBare, double speedupMeridian) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("func", func);
        m.put("correct", correct);
        m.put("native_ns", round1(nativeNs));
        m.put("mypyc_bare_ns", round1(bareNs));
        m.put("meridian_ns", round1(meridianNs));
        m.put("speedup_bare", round2(speedupBare));
        m.put("speedup_vs_native", round2(speedupMeridian));
        return m;
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
