package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;

/**
 * Strategy interface for loading and converting Python modules on-demand during
 * the conversion phase of the meridian-python pipeline.
 *
 * <p>When {@link ImportFromConverter} encounters a {@code from X import Y} statement,
 * it calls {@link #load} so that module {@code X} is parsed, converted, and merged
 * into the same GCP {@link org.twelve.gcp.ast.ASF} before inference runs.
 *
 * <p>Implementations must be idempotent (multiple calls for the same module name
 * must not re-convert it) and must handle cycles gracefully (returning {@code null}
 * if the module is already being loaded).
 *
 * @see ModuleLoaderAdapter
 */
@FunctionalInterface
public interface ModuleLoader {

    /**
     * Load and convert a Python module identified by its dotted name.
     *
     * @param moduleName the dotted Python module name (e.g. {@code "utils"}, {@code "pkg.math"})
     * @return the converted GCP {@link AST}, or {@code null} if the module is not found /
     *         not available for this resolver
     */
    AST load(String moduleName);
}
