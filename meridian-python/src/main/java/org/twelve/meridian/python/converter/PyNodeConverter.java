package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;

import java.util.Map;

/**
 * Functional interface for converting a Python AST JSON node into a GCP {@link Node}.
 *
 * <p>Each implementation handles a single Python AST node type (identified by its
 * {@code "_type"} key).  Implementations are registered in {@code PythonGCPConverter}
 * and dispatched by node type at conversion time.
 *
 * <p>Unlike the outline {@code Converter} which operates on MSLL {@code ParseNode}
 * objects, this interface operates on the JSON-deserialized Python {@code ast} output
 * (represented as {@code Map<String, Object>}).
 */
@FunctionalInterface
public interface PyNodeConverter {

    /**
     * Convert the given Python AST JSON node to a GCP {@link Node}.
     *
     * @param ast    the target GCP AST
     * @param pyNode the current Python AST JSON node ({@code _type} identifies the class)
     * @param parent the GCP parent node (may be {@code null})
     * @return the resulting GCP node, or {@code null} if nothing should be added
     */
    Node convert(AST ast, Map<String, Object> pyNode, Node parent);
}
