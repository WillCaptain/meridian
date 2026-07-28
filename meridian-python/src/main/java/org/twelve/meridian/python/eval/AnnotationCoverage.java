package org.twelve.meridian.python.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight annotation coverage over Meridian-annotated Python source.
 * Counts top-level {@code def} params/returns (not nested methods).
 */
public final class AnnotationCoverage {

    private static final Pattern DEF = Pattern.compile(
            "(?m)^def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*(->\\s*([^:]+))?\\s*:");

    private AnnotationCoverage() {}

    public record Stats(
            int funcsTotal,
            int funcsWithReturn,
            int paramsTotal,
            int paramsAnnotated,
            List<String> unannotatedFuncs,
            Map<String, Boolean> returnAnnotatedByFunc
    ) {
        public double paramCoverage() {
            return paramsTotal == 0 ? 1.0 : (double) paramsAnnotated / paramsTotal;
        }

        public double returnCoverage() {
            return funcsTotal == 0 ? 1.0 : (double) funcsWithReturn / funcsTotal;
        }
    }

    public static Stats measure(String annotatedSource) {
        if (annotatedSource == null || annotatedSource.isBlank()) {
            return new Stats(0, 0, 0, 0, List.of(), Map.of());
        }
        // Flatten signature newlines for multi-line defs.
        String flat = annotatedSource.replaceAll("\\\\\\s*\\n", " ");
        Matcher m = DEF.matcher(flat);
        int funcs = 0;
        int withRet = 0;
        int params = 0;
        int annotated = 0;
        List<String> unannotated = new ArrayList<>();
        Map<String, Boolean> retMap = new LinkedHashMap<>();
        while (m.find()) {
            String name = m.group(1);
            if (name.startsWith("_") && name.length() > 1 && Character.isDigit(name.charAt(1))) {
                // specialized clones _f_int etc. still count
            }
            funcs++;
            boolean hasRet = m.group(3) != null && !m.group(3).isBlank();
            if (hasRet) withRet++;
            retMap.put(name, hasRet);
            String args = m.group(2) == null ? "" : m.group(2).trim();
            if (args.isEmpty()) {
                continue;
            }
            int depth = 0;
            StringBuilder cur = new StringBuilder();
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < args.length(); i++) {
                char c = args.charAt(i);
                if (c == '[' || c == '(') depth++;
                else if (c == ']' || c == ')') depth--;
                if (c == ',' && depth == 0) {
                    parts.add(cur.toString().trim());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            if (!cur.isEmpty()) parts.add(cur.toString().trim());
            boolean anyMissing = false;
            for (String p : parts) {
                if (p.isEmpty() || p.startsWith("*") || "self".equals(p) || "cls".equals(p)) {
                    continue;
                }
                // strip defaults: name: T = v  /  name = v
                String head = p;
                int eq = indexOutside(head, '=');
                if (eq >= 0) head = head.substring(0, eq).trim();
                params++;
                if (head.contains(":")) {
                    annotated++;
                } else {
                    anyMissing = true;
                }
            }
            if (anyMissing || !hasRet) {
                unannotated.add(name);
            }
        }
        return new Stats(funcs, withRet, params, annotated, unannotated, retMap);
    }

    private static int indexOutside(String s, char ch) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '(') depth++;
            else if (c == ']' || c == ')') depth--;
            else if (c == ch && depth == 0) return i;
        }
        return -1;
    }
}
