package org.twelve.meridian.python;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic projection from Meridian / GCP type strings to the closed-world
 * TypeEvalPy benchmark vocabulary.
 *
 * <p>This layer performs <strong>representation only</strong>: generic erasure,
 * {@code None} spelling, {@code Callable} → {@code callable}, and similar renames.
 * It must not read ground truth, narrow {@code Number} to {@code int} without evidence,
 * or invent types. Inference correctness is validated separately via Meridian assertions
 * ({@code int}, {@code Number}, unions, etc.).
 */
public final class TypeEvalPyTypeProjector {

    private TypeEvalPyTypeProjector() {}

    /** Project one Meridian type string to zero or more TypeEvalPy tokens. */
    public static List<String> project(String typeStr) {
        List<String> out = new ArrayList<>();
        if (typeStr == null || typeStr.isBlank()) {
            return out;
        }
        String s = typeStr.trim();
        if (s.startsWith("Union[") && s.endsWith("]")) {
            for (String part : splitTopLevel(s.substring(6, s.length() - 1))) {
                out.addAll(project(part.trim()));
            }
            return dedupe(out);
        }
        if (s.startsWith("Optional[") && s.endsWith("]")) {
            out.addAll(project(s.substring(9, s.length() - 1)));
            out.add("Nonetype");
            return dedupe(out);
        }
        out.add(eraseGenerics(s));
        return out;
    }

    /** Copy a site map and project its {@code type} list for TypeEvalPy export. */
    public static Map<String, Object> projectSite(Map<String, Object> site) {
        Map<String, Object> out = new LinkedHashMap<>(site);
        Object raw = site.get("type");
        if (raw instanceof List<?> list) {
            List<String> projected = new ArrayList<>();
            for (Object item : list) {
                if (item == null) continue;
                projected.addAll(project(String.valueOf(item)));
            }
            out.put("type", dedupe(projected));
        }
        return out;
    }

    static String eraseGenerics(String s) {
        String t = s.trim();
        if (t.startsWith("Callable")) {
            return "callable";
        }
        if (t.equals("None") || t.equals("NoneType") || t.equals("Unit")) {
            return "Nonetype";
        }
        int bracket = t.indexOf('[');
        if (bracket > 0) {
            t = t.substring(0, bracket);
        }
        return switch (t) {
            case "Int", "Integer", "Long" -> "int";
            case "Float", "Double", "Decimal", "Number" -> "float";
            case "String", "str" -> "str";
            case "Bool", "bool" -> "bool";
            case "List", "list", "Array" -> "list";
            case "Dict", "dict" -> "dict";
            case "Tuple", "tuple" -> "tuple";
            case "Set", "set" -> "set";
            default -> t;
        };
    }

    private static List<String> splitTopLevel(String inner) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            parts.add(cur.toString());
        }
        return parts;
    }

    static List<String> dedupe(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s != null && !s.isBlank() && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
