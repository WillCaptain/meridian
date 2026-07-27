package org.twelve.meridian.python;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which inferred annotations are safe to inject into Python source for
 * mypy / mypyc consumption.
 *
 * <p>{@link #SAFE_PARTIAL} (default) only keeps concrete, expressible types and
 * drops an entire function's annotations when any of its inferred slots were
 * non-concrete — half-annotated defs break mypyc more often than they help.
 * {@link #ALL_CONCRETE} keeps every concrete annotation even when a function
 * signature is incomplete (used by aggressive benchmarks).
 */
public enum AnnotationPolicy {
    /** Inject only complete, concrete annotations suitable for mypy/mypyc. */
    SAFE_PARTIAL,
    /** Inject every concrete annotation; incomplete signatures are allowed. */
    ALL_CONCRETE;

    private static final Pattern NON_CONCRETE = Pattern.compile(
            "\\bAny\\b|\\bUNKNOWN\\b|Entity\\{|⚠️");

    public static AnnotationPolicy defaultPolicy() {
        return SAFE_PARTIAL;
    }

    /**
     * Filter a collected {@code name → type} map according to this policy.
     * Keys use writer conventions: bare vars, {@code func#param}, {@code func#return}.
     */
    public Map<String, String> filter(Map<String, String> nameToType) {
        if (nameToType == null || nameToType.isEmpty()) return Map.of();

        Set<String> taintedFunctions = new HashSet<>();
        for (Map.Entry<String, String> e : nameToType.entrySet()) {
            String key = e.getKey();
            int hash = key.indexOf('#');
            if (hash < 0) continue;
            if (!isConcrete(e.getValue())) {
                taintedFunctions.add(key.substring(0, hash));
            }
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : nameToType.entrySet()) {
            if (!isConcrete(e.getValue())) continue;
            String key = e.getKey();
            int hash = key.indexOf('#');
            if (this == SAFE_PARTIAL && hash >= 0
                    && taintedFunctions.contains(key.substring(0, hash))) {
                // Incomplete signature: skip every slot for this function.
                continue;
            }
            // Module-level callables are easy to confuse with values; only
            // ALL_CONCRETE injects bare Callable annotations on assigns.
            if (this == SAFE_PARTIAL && hash < 0 && isCallableType(e.getValue())) {
                continue;
            }
            out.put(key, e.getValue());
        }
        return out;
    }

    static boolean isConcrete(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) return false;
        String t = typeStr.trim();
        if (NON_CONCRETE.matcher(t).find()) return false;
        // Number alone is not a PEP-484 type; generator must already have refined it.
        if (t.equals("Number") || t.equals("Addable") || t.equals("OperateAble")) return false;
        return true;
    }

    private static boolean isCallableType(String typeStr) {
        String t = typeStr.trim();
        return t.startsWith("Callable[") || t.equals("Callable");
    }
}
