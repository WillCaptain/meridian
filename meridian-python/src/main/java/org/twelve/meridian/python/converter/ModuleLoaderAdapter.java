package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A {@link PyConverter} adapter that stores a {@link ModuleLoader} inside the
 * shared converter registry under the reserved key {@code "__module_loader__"}.
 *
 * <p>This design lets any converter (notably {@link ImportFromConverter}) retrieve
 * the loader via {@code converters.get("__module_loader__")} without requiring
 * constructor changes to the 40+ existing converter classes.
 *
 * <p>Cycle prevention: a {@link Set} of in-flight module names prevents infinite
 * recursion when two modules import each other.
 */
public class ModuleLoaderAdapter extends PyConverter {

    /** Reserved key used to store this adapter in the shared converter registry. */
    public static final String REGISTRY_KEY = "__module_loader__";

    private final ModuleLoader delegate;
    private final Set<String> inFlight = new HashSet<>();

    public ModuleLoaderAdapter(Map<String, PyConverter> converters, ModuleLoader delegate) {
        super(converters);
        this.delegate = delegate;
    }

    /**
     * Load the given module exactly once, skipping already-loaded or in-flight modules.
     *
     * @return the converted GCP AST, or {@code null} if unavailable / already loaded
     */
    public AST load(String moduleName) {
        if (moduleName == null || moduleName.isBlank()) return null;
        if (!inFlight.add(moduleName)) return null;   // cycle detected — skip
        try {
            return delegate.load(moduleName);
        } finally {
            inFlight.remove(moduleName);
        }
    }

    /** Not used; this converter is only retrieved by key, never dispatched. */
    @Override
    public org.twelve.gcp.ast.Node convert(
            org.twelve.gcp.ast.AST ast,
            Map<String, Object> pyNode,
            org.twelve.gcp.ast.Node parent) {
        return null;
    }
}
