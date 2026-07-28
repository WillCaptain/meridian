package org.twelve.meridian.python;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Select the mypyc compile set as the <em>import closure</em> of the primary
 * module within a known package — the productized form of L6's manual
 * {@code compile_modules: [mi_hot, mi_facade]}.
 *
 * <p>Only direct package peers count ({@code import peer} / {@code from peer import …}).
 * Stdlib and third-party names are ignored. Coverage-only modules that primary
 * never imports stay out of the compile set (still materializable as plain .py).
 */
public final class HotCompileSelector {

    private static final Pattern FROM_IMPORT = Pattern.compile(
            "(?m)^\\s*from\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+import\\b");
    private static final Pattern PLAIN_IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*)*)");

    private HotCompileSelector() {}

    /**
     * BFS import closure starting at {@code primary}, restricted to keys of
     * {@code modules}. Primary is always included when present.
     */
    public static List<String> importClosure(String primary, Map<String, String> modules) {
        if (modules == null || modules.isEmpty()) {
            return List.of();
        }
        if (primary == null || primary.isBlank() || !modules.containsKey(primary)) {
            throw new IllegalArgumentException("primary module missing: " + primary);
        }
        Set<String> known = modules.keySet();
        Set<String> ordered = new LinkedHashSet<>();
        Queue<String> q = new ArrayDeque<>();
        q.add(primary);
        ordered.add(primary);
        while (!q.isEmpty()) {
            String cur = q.remove();
            for (String dep : directPackageImports(modules.get(cur), known)) {
                if (ordered.add(dep)) {
                    q.add(dep);
                }
            }
        }
        return new ArrayList<>(ordered);
    }

    static List<String> directPackageImports(String source, Set<String> known) {
        if (source == null || source.isBlank() || known == null || known.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> deps = new LinkedHashSet<>();
        Matcher from = FROM_IMPORT.matcher(source);
        while (from.find()) {
            String name = from.group(1);
            if (known.contains(name)) {
                deps.add(name);
            }
        }
        Matcher imp = PLAIN_IMPORT.matcher(source);
        while (imp.find()) {
            for (String part : imp.group(1).split(",")) {
                String name = part.trim();
                int as = name.indexOf(" as ");
                if (as >= 0) {
                    name = name.substring(0, as).trim();
                }
                if (known.contains(name)) {
                    deps.add(name);
                }
            }
        }
        return new ArrayList<>(deps);
    }
}
