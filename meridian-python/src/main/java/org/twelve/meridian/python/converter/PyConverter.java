package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.ast.Token;
import org.twelve.gcp.inference.operator.BinaryOperator;
import org.twelve.gcp.inference.operator.UnaryOperator;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.LiteralNode;
import org.twelve.gcp.node.expression.accessor.ArrayAccessor;
import org.twelve.gcp.node.expression.body.Body;
import org.twelve.gcp.node.expression.identifier.Identifier;
import org.twelve.gcp.node.expression.typeable.*;
import org.twelve.gcp.node.statement.Statement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Abstract base for all Python AST → GCP node converters.
 *
 * <p>All converters share a single {@code Map<String, PyConverter>} registry.
 * Each subclass handles exactly one Python AST node type.
 * To add support for a new node, create a subclass and register it in
 * {@link org.twelve.meridian.python.PythonGCPConverter}.
 */
public abstract class PyConverter {

    protected final Map<String, PyConverter> converters;

    protected PyConverter(Map<String, PyConverter> converters) {
        this.converters = converters;
    }

    /**
     * Convert a Python JSON AST node into a GCP {@link Node}.
     *
     * @param ast    the GCP AST context
     * @param pyNode the Python JSON AST node ({@code _type} field identifies the kind)
     * @param parent the parent GCP node (e.g. a {@link Body} to append statements to)
     * @return the converted GCP node, or {@code null} if conversion is not applicable
     */
    public abstract Node convert(AST ast, Map<String, Object> pyNode, Node parent);

    public Node convert(AST ast, Map<String, Object> pyNode) {
        return convert(ast, pyNode, null);
    }

    // ── dispatch helpers ─────────────────────────────────────────────────────

    protected Node dispatch(AST ast, Map<String, Object> pyNode, Node parent) {
        if (pyNode == null) return null;
        String type = typeOf(pyNode);
        if (type == null) return null;
        PyConverter conv = converters.get(type);
        if (conv == null) return null;
        return conv.convert(ast, pyNode, parent);
    }

    protected Node dispatch(AST ast, Map<String, Object> pyNode) {
        return dispatch(ast, pyNode, null);
    }

    // ── statement placement ──────────────────────────────────────────────────

    /**
     * Add a statement to the nearest enclosing body (parent), or to the module
     * program body when parent is null.
     */
    protected static void addStatement(AST ast, Node parent, Statement stmt) {
        if (parent instanceof Body body) {
            body.addStatement(stmt);
        } else {
            ast.addStatement(stmt);
        }
    }

    // ── type annotation builder ──────────────────────────────────────────────

    /**
     * Build a GCP {@link TypeNode} from a Python annotation AST node.
     * Supports: simple names (int, str, float, bool, None),
     * generics (list[T], Optional[T]), union (X | Y, Union[X,Y]).
     */
    protected static TypeNode buildTypeNode(AST ast, Map<String, Object> annotation) {
        if (annotation == null) return null;
        String t = typeOf(annotation);
        if (t == null) return null;

        return switch (t) {
            case "Name" -> {
                String name = strOf(annotation, "id");
                yield name == null ? null : namedType(ast, name);
            }
            case "Attribute" -> {
                String attr = strOf(annotation, "attr");
                yield attr == null ? null : namedType(ast, attr);
            }
            case "Subscript" -> {
                String outerName = subscriptTypeName(annotation);
                Map<String, Object> sliceNode = mapOf(annotation, "slice");
                if ("Optional".equals(outerName)) {
                    TypeNode inner = buildTypeNode(ast, sliceNode);
                    yield inner == null ? null : new OptionTypeNode(inner);
                }
                if ("list".equalsIgnoreCase(outerName) || "List".equals(outerName)) {
                    TypeNode inner = buildTypeNode(ast, sliceNode);
                    yield inner == null ? new ArrayTypeNode(ast) : new ArrayTypeNode(ast, inner);
                }
                if ("dict".equalsIgnoreCase(outerName) || "Dict".equals(outerName)) {
                    yield new IdentifierTypeNode(identifier(ast, "Dict"));
                }
                if ("Union".equals(outerName)) {
                    List<Map<String, Object>> elts = listOf(sliceNode, "elts");
                    if (elts.size() >= 2) {
                        TypeNode left = buildTypeNode(ast, elts.get(0));
                        TypeNode right = buildTypeNode(ast, elts.get(1));
                        if (left != null && right != null) yield new OptionTypeNode(left, right);
                    }
                }
                if ("Tuple".equals(outerName)) {
                    yield new IdentifierTypeNode(identifier(ast, "Tuple"));
                }
                yield outerName == null ? null : namedType(ast, outerName);
            }
            case "BinOp" -> {
                String opType = typeOf(mapOf(annotation, "op"));
                if ("BitOr".equals(opType)) {
                    TypeNode left = buildTypeNode(ast, mapOf(annotation, "left"));
                    TypeNode right = buildTypeNode(ast, mapOf(annotation, "right"));
                    if (left != null && right != null) yield new OptionTypeNode(left, right);
                    yield left;
                }
                yield null;
            }
            case "Constant" -> {
                if (annotation.get("value") == null) yield namedType(ast, "Unit");
                yield null;
            }
            default -> null;
        };
    }

    private static String subscriptTypeName(Map<String, Object> subscript) {
        Map<String, Object> val = mapOf(subscript, "value");
        if (val == null) return null;
        String t = typeOf(val);
        if ("Name".equals(t)) return strOf(val, "id");
        if ("Attribute".equals(t)) return strOf(val, "attr");
        return null;
    }

    private static TypeNode namedType(AST ast, String name) {
        String normalized = switch (name) {
            case "int" -> "Int";
            case "float" -> "Float";
            case "str" -> "String";
            case "bool" -> "Bool";
            case "None", "NoneType" -> "Unit";
            case "bytes" -> "String";
            case "any", "Any" -> null;
            default -> name;
        };
        if (normalized == null) return null;
        return new IdentifierTypeNode(identifier(ast, normalized));
    }

    // ── operator mappings ─────────────────────────────────────────────────────

    protected static BinaryOperator pyArithOpToGcp(String op) {
        if (op == null) return null;
        return switch (op) {
            case "Add" -> BinaryOperator.ADD;
            case "Sub" -> BinaryOperator.SUBTRACT;
            case "Mult" -> BinaryOperator.MULTIPLY;
            case "Div" -> BinaryOperator.DIVIDE;
            case "Mod" -> BinaryOperator.MODULUS;
            case "FloorDiv" -> BinaryOperator.DIVIDE;
            case "BitOr" -> BinaryOperator.BITWISE_OR;
            case "BitAnd" -> BinaryOperator.BITWISE_AND;
            case "BitXor" -> BinaryOperator.BITWISE_XOR;
            case "LShift" -> BinaryOperator.BITWISE_SHIFT_LEFT;
            case "RShift" -> BinaryOperator.BITWISE_SHIFT_RIGHT;
            default -> null;
        };
    }

    protected static BinaryOperator pyCmpOpToGcp(String op) {
        if (op == null) return null;
        return switch (op) {
            case "Eq" -> BinaryOperator.EQUALS;
            case "NotEq" -> BinaryOperator.NOT_EQUALS;
            case "Lt" -> BinaryOperator.LESS_THAN;
            case "LtE" -> BinaryOperator.LESS_OR_EQUAL;
            case "Gt" -> BinaryOperator.GREATER_THAN;
            case "GtE" -> BinaryOperator.GREATER_OR_EQUAL;
            default -> null;
        };
    }

    protected static UnaryOperator pyUnaryOpToGcp(String op) {
        if (op == null) return null;
        return switch (op) {
            case "USub" -> UnaryOperator.NEGATE;
            case "Not" -> UnaryOperator.BANG;
            default -> null;
        };
    }

    // ── iterator helpers ─────────────────────────────────────────────────────

    /**
     * Build an expression whose inferred type equals the <em>element type</em>
     * of the given Python iterable node.
     *
     * <ul>
     *   <li>{@code range(...)} → {@code LiteralNode<Long>(0)} — always integer.</li>
     *   <li>Any other iterable → {@code ArrayAccessor(iterExpr, 0)} — element type
     *       flows through GCP's ArrayAccessor inference.</li>
     * </ul>
     */
    protected Expression buildIterElementExpr(AST ast, Map<String, Object> iter) {
        if (isRangeCall(iter)) {
            return LiteralNode.parse(ast, new Token<>(0L, 0));
        }
        Expression iterExpr = (Expression) dispatch(ast, iter);
        if (iterExpr == null) return null;
        return new ArrayAccessor(ast, iterExpr, LiteralNode.parse(ast, new Token<>(0L, 0)));
    }

    /** Returns {@code true} when the Python AST node is a call to {@code range(...)}. */
    protected static boolean isRangeCall(Map<String, Object> iter) {
        if (!"Call".equals(typeOf(iter))) return false;
        Map<String, Object> func = mapOf(iter, "func");
        return func != null && "range".equals(strOf(func, "id"));
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapOf(Map<String, Object> node, String key) {
        Object v = node.get(key);
        return (v instanceof Map) ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listOf(Map<String, Object> node, String key) {
        Object v = node.get(key);
        return (v instanceof List) ? (List<Map<String, Object>>) v : Collections.emptyList();
    }

    public static String strOf(Map<String, Object> node, String key) {
        Object v = node.get(key);
        return v == null ? null : v.toString();
    }

    public static String typeOf(Map<String, Object> node) {
        if (node == null) return null;
        return (String) node.get("_type");
    }

    public static Identifier identifier(AST ast, String name) {
        if (name == null) return null;
        return new Identifier(ast, new Token<>(name, 0));
    }

    public static List<Identifier> moduleIds(AST ast, String dotted) {
        List<Identifier> ids = new ArrayList<>();
        if (dotted == null || dotted.isBlank()) return ids;
        for (String part : dotted.split("\\.")) {
            ids.add(identifier(ast, part));
        }
        return ids;
    }
}
