package org.twelve.meridian.python;

import org.twelve.gcp.ast.AST;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared Meridian inference facts after GCP + Python semantic refinement.
 *
 * <p>Does not know TypeEvalPy FR/FP/LV vocabulary. Consumers:
 * <ul>
 *   <li>{@link PythonAnnotationWriter} — production annotations</li>
 *   <li>{@link TypeEvalPySiteExporter} — benchmark site serialization</li>
 *   <li>{@link TypeAnnotationGenerator} — stubs</li>
 * </ul>
 */
public final class PythonInferenceResult {

    private final AST gcpAst;
    private final Map<String, Object> pyAst;
    private final String fileName;
    private final Path sourcePath;

    /** callee bare/attr name → per-arg observed types (union across call sites). */
    private final Map<String, List<List<String>>> callSiteArgTypes;

    /** container element path → types, e.g. {@code d['foo']} → [int]. */
    private final Map<String, List<String>> containerElements;

    /** binder → type when the binder is invoked (callable slots). */
    private final Map<String, List<String>> callReturns;

    /** function bare/qname → param name → types refined from call sites. */
    private final Map<String, Map<String, List<String>>> refinedParams;

    /** {@code Class.method} → return types (receiver-sensitive). */
    private final Map<String, List<String>> methodReturns;

    /** assignment target → type of a call result ({@code y = f()}, {@code z = obj.m()}). */
    private final Map<String, List<String>> callResults;

    /** variable → nominal class from {@code x = Class(...)}. */
    private final Map<String, String> receiverTypes;

    public PythonInferenceResult(
            AST gcpAst,
            Map<String, Object> pyAst,
            String fileName,
            Path sourcePath,
            Map<String, List<List<String>>> callSiteArgTypes,
            Map<String, List<String>> containerElements,
            Map<String, List<String>> callReturns,
            Map<String, Map<String, List<String>>> refinedParams,
            Map<String, List<String>> methodReturns,
            Map<String, List<String>> callResults,
            Map<String, String> receiverTypes) {
        this.gcpAst = gcpAst;
        this.pyAst = pyAst;
        this.fileName = fileName;
        this.sourcePath = sourcePath;
        this.callSiteArgTypes = immutableNestedLists(callSiteArgTypes);
        this.containerElements = Map.copyOf(containerElements);
        this.callReturns = Map.copyOf(callReturns);
        this.refinedParams = copyParams(refinedParams);
        this.methodReturns = Map.copyOf(methodReturns);
        this.callResults = Map.copyOf(callResults);
        this.receiverTypes = Map.copyOf(receiverTypes);
    }

    public AST gcpAst() {
        return gcpAst;
    }

    public Map<String, Object> pyAst() {
        return pyAst;
    }

    public String fileName() {
        return fileName;
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public Map<String, List<List<String>>> callSiteArgTypes() {
        return callSiteArgTypes;
    }

    public Map<String, List<String>> containerElements() {
        return containerElements;
    }

    public Map<String, List<String>> callReturns() {
        return callReturns;
    }

    public Map<String, Map<String, List<String>>> refinedParams() {
        return refinedParams;
    }

    public Map<String, List<String>> methodReturns() {
        return methodReturns;
    }

    public Map<String, List<String>> callResults() {
        return callResults;
    }

    public Map<String, String> receiverTypes() {
        return receiverTypes;
    }

    /**
     * Annotation-writer keys: {@code func#param} → first Meridian type token.
     */
    public Map<String, String> annotationHints() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<String>>> fn : refinedParams.entrySet()) {
            for (Map.Entry<String, List<String>> p : fn.getValue().entrySet()) {
                List<String> types = p.getValue();
                if (types == null || types.isEmpty()) continue;
                out.put(fn.getKey() + "#" + p.getKey(), types.get(0));
            }
        }
        for (Map.Entry<String, List<String>> e : callResults.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            out.putIfAbsent(e.getKey(), e.getValue().get(0));
        }
        return out;
    }

    private static Map<String, List<List<String>>> immutableNestedLists(
            Map<String, List<List<String>>> in) {
        Map<String, List<List<String>>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<List<String>>> e : in.entrySet()) {
            List<List<String>> args = e.getValue().stream().map(List::copyOf).toList();
            out.put(e.getKey(), List.copyOf(args));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Map<String, List<String>>> copyParams(
            Map<String, Map<String, List<String>>> in) {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<String>>> e : in.entrySet()) {
            Map<String, List<String>> inner = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> p : e.getValue().entrySet()) {
                inner.put(p.getKey(), List.copyOf(p.getValue()));
            }
            out.put(e.getKey(), Collections.unmodifiableMap(inner));
        }
        return Collections.unmodifiableMap(out);
    }
}
