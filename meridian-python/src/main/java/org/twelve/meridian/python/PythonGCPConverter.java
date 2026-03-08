package org.twelve.meridian.python;

import org.twelve.gcp.ast.ASF;
import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.ast.Token;
import org.twelve.gcp.common.VariableKind;
import org.twelve.gcp.inference.operator.BinaryOperator;
import org.twelve.gcp.node.expression.*;
import org.twelve.gcp.node.expression.UnaryPosition;
import org.twelve.gcp.node.expression.accessor.MemberAccessor;
import org.twelve.gcp.node.expression.body.FunctionBody;
import org.twelve.gcp.node.expression.identifier.Identifier;
import org.twelve.gcp.node.expression.identifier.SymbolIdentifier;
import org.twelve.gcp.node.expression.typeable.*;
import org.twelve.gcp.node.function.Argument;
import org.twelve.gcp.node.function.FunctionCallNode;
import org.twelve.gcp.node.function.FunctionNode;
import org.twelve.gcp.node.imexport.Import;
import org.twelve.gcp.node.imexport.ImportSpecifier;
import org.twelve.gcp.node.statement.*;
import org.twelve.meridian.python.converter.PyNodeConverter;

import java.util.*;

/**
 * Converts a Python JSON AST (produced by {@code py_ast_dump.py}) into a GCP {@link AST}.
 *
 * <p>The converter follows the same dispatcher pattern as the outline {@code GCPConverter}:
 * a {@code Map<String, PyNodeConverter>} maps each Python AST node type name to its handler.
 * Unknown node types are silently skipped so that unsupported constructs never crash the pipeline.
 */
public class PythonGCPConverter {

    private final ASF asf;
    private final Map<String, PyNodeConverter> converters = new HashMap<>();

    public PythonGCPConverter(ASF asf) {
        this.asf = asf;
        registerAll();
    }

    public PythonGCPConverter() {
        this(new ASF());
    }

    // ── public API ────────────────────────────────────────────────────────────

    public AST convert(Map<String, Object> pyAst) {
        AST ast = asf.newAST();
        PyNodeConverter root = converters.get("Module");
        if (root != null) root.convert(ast, pyAst, ast.program());
        return ast;
    }

    // ── dispatcher helper (accessible to sub-converters) ─────────────────────

    Node dispatch(AST ast, Map<String, Object> pyNode, Node parent) {
        if (pyNode == null) return null;
        String t = (String) pyNode.get("_type");
        PyNodeConverter conv = converters.get(t);
        if (conv == null) return null;
        return conv.convert(ast, pyNode, parent);
    }

    Node dispatch(AST ast, Map<String, Object> pyNode) {
        return dispatch(ast, pyNode, null);
    }

    // ── registration ─────────────────────────────────────────────────────────

    private void registerAll() {
        // Module: iterate top-level body statements
        converters.put("Module", (ast, node, parent) -> {
            for (Map<String, Object> stmt : listOf(node, "body")) {
                dispatch(ast, stmt, parent);
            }
            return null;
        });

        // Import: import foo, bar
        converters.put("Import", (ast, node, parent) -> {
            for (Map<String, Object> alias : listOf(node, "names")) {
                String name = strOf(alias, "name");
                String asName = strOf(alias, "asname");
                List<Identifier> sourceIds = moduleIds(ast, name);
                Import imp = new Import(sourceIds);
                if (asName != null) {
                    imp.specifiers().add(new ImportSpecifier(identifier(ast, name), identifier(ast, asName)));
                }
                ast.addImport(imp);
            }
            return null;
        });

        // ImportFrom: from foo import bar, baz
        converters.put("ImportFrom", (ast, node, parent) -> {
            String module = strOf(node, "module");
            if (module == null) module = "__future__";
            List<Identifier> sourceIds = moduleIds(ast, module);
            List<org.twelve.gcp.common.Pair<Identifier, Identifier>> vars = new ArrayList<>();
            for (Map<String, Object> alias : listOf(node, "names")) {
                String name = strOf(alias, "name");
                String asName = strOf(alias, "asname");
                Identifier imported = identifier(ast, name);
                Identifier local = identifier(ast, asName != null ? asName : name);
                vars.add(new org.twelve.gcp.common.Pair<>(imported, local));
            }
            Import imp = new Import(vars, sourceIds);
            ast.addImport(imp);
            return null;
        });

        // FunctionDef / AsyncFunctionDef
        PyNodeConverter funcConv = (ast, node, parent) -> {
            String name = strOf(node, "name");
            Map<String, Object> argsNode = mapOf(node, "args");
            Map<String, Object> returns = mapOf(node, "returns");

            List<Argument> args = buildArguments(ast, argsNode, name);
            FunctionBody body = buildFunctionBody(ast, node);
            FunctionNode fn = FunctionNode.from(body, args.toArray(new Argument[0]));

            VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.LET);
            decl.declare(identifier(ast, name), fn);
            ast.addStatement(decl);
            return decl;
        };
        converters.put("FunctionDef", funcConv);
        converters.put("AsyncFunctionDef", funcConv);

        // ClassDef → OutlineDeclarator
        converters.put("ClassDef", (ast, node, parent) -> {
            String name = strOf(node, "name");
            SymbolIdentifier symId = new SymbolIdentifier(ast, new org.twelve.gcp.ast.Token<>(name, 0));

            List<OutlineDefinition> defs = new ArrayList<>();
            defs.add(new OutlineDefinition(symId, new EntityTypeNode(ast)));
            // Collect annotated class variables from the class body
            for (Map<String, Object> stmt : listOf(node, "body")) {
                String t = typeOf(stmt);
                if ("AnnAssign".equals(t)) {
                    Map<String, Object> target = mapOf(stmt, "target");
                    String fieldName = strOf(target, "id");
                    TypeNode typeNode = buildTypeNode(ast, mapOf(stmt, "annotation"));
                    if (fieldName != null && typeNode != null) {
                        defs.add(new OutlineDefinition(
                                new SymbolIdentifier(ast, new org.twelve.gcp.ast.Token<>(fieldName, 0)),
                                typeNode));
                    }
                }
            }

            OutlineDeclarator decl = new OutlineDeclarator(defs);

            // Register methods as VariableDeclarators on the AST
            for (Map<String, Object> stmt : listOf(node, "body")) {
                String t = typeOf(stmt);
                if ("FunctionDef".equals(t) || "AsyncFunctionDef".equals(t)) {
                    dispatch(ast, stmt, parent);
                }
            }

            ast.addStatement(decl);
            return decl;
        });

        // AnnAssign: x: int = expr   or   x: int
        converters.put("AnnAssign", (ast, node, parent) -> {
            Map<String, Object> target = mapOf(node, "target");
            String varName = strOf(target, "id");
            if (varName == null) return null;

            TypeNode typeNode = buildTypeNode(ast, mapOf(node, "annotation"));
            Map<String, Object> valueNode = mapOf(node, "value");
            Expression value = valueNode != null ? (Expression) dispatch(ast, valueNode) : null;

            VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
            if (value != null) {
                decl.declare(identifier(ast, varName), typeNode, value);
            } else {
                decl.declare(identifier(ast, varName), typeNode, null);
            }
            ast.addStatement(decl);
            return decl;
        });

        // Assign: x = expr  (possibly multiple targets: a = b = expr)
        converters.put("Assign", (ast, node, parent) -> {
            Map<String, Object> valueNode = mapOf(node, "value");
            Expression value = valueNode != null ? (Expression) dispatch(ast, valueNode) : null;
            for (Map<String, Object> target : listOf(node, "targets")) {
                String varName = strOf(target, "id");
                if (varName == null) continue;
                VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
                decl.declare(identifier(ast, varName), value);
                ast.addStatement(decl);
            }
            return null;
        });

        // Return statement
        converters.put("Return", (ast, node, parent) -> {
            Map<String, Object> valueNode = mapOf(node, "value");
            Expression retVal = valueNode != null ? (Expression) dispatch(ast, valueNode) : null;
            ReturnStatement ret = new ReturnStatement(retVal);
            ast.addStatement(ret);
            return ret;
        });

        // Expr (expression as statement)
        converters.put("Expr", (ast, node, parent) -> {
            Map<String, Object> valueNode = mapOf(node, "value");
            if (valueNode == null) return null;
            Expression expr = (Expression) dispatch(ast, valueNode);
            if (expr == null) return null;
            ExpressionStatement stmt = new ExpressionStatement(expr);
            ast.addStatement(stmt);
            return stmt;
        });

        // Name: variable reference
        converters.put("Name", (ast, node, parent) ->
                identifier(ast, strOf(node, "id")));

        // Constant: literal value
        converters.put("Constant", (ast, node, parent) -> {
            Object val = node.get("value");
            if (val == null) return LiteralNode.parse(ast, new Token<>(null, 0));
            if (val instanceof Integer || val instanceof Long) {
                return LiteralNode.parse(ast, new Token<>((long)(int)(Integer)val, 0));
            }
            if (val instanceof Double || val instanceof Float) {
                return LiteralNode.parse(ast, new Token<>((double)(Double)val, 0));
            }
            if (val instanceof Boolean) {
                return LiteralNode.parse(ast, new Token<>((Boolean)val, 0));
            }
            // String or fallback
            return LiteralNode.parse(ast, new Token<>(val.toString(), 0));
        });

        // BinOp: left op right
        converters.put("BinOp", (ast, node, parent) -> {
            Expression left  = (Expression) dispatch(ast, mapOf(node, "left"));
            Expression right = (Expression) dispatch(ast, mapOf(node, "right"));
            String opType = typeOf(mapOf(node, "op"));
            BinaryOperator op = pyArithOpToGcp(opType);
            if (left == null || right == null || op == null) return null;
            return new BinaryExpression(left, right, new org.twelve.gcp.node.operator.OperatorNode<>(ast, op));
        });

        // Compare: a < b,  a == b, etc.
        converters.put("Compare", (ast, node, parent) -> {
            Expression left = (Expression) dispatch(ast, mapOf(node, "left"));
            List<Map<String, Object>> ops = listOf(node, "ops");
            List<Map<String, Object>> comparators = listOf(node, "comparators");
            if (left == null || ops.isEmpty()) return null;
            Expression result = left;
            for (int i = 0; i < ops.size(); i++) {
                BinaryOperator op = pyCmpOpToGcp(typeOf(ops.get(i)));
                Expression right = (Expression) dispatch(ast, comparators.get(i));
                if (op != null && right != null) {
                    result = new BinaryExpression(
                            (Expression) result, right,
                            new org.twelve.gcp.node.operator.OperatorNode<>(ast, op));
                }
            }
            return result;
        });

        // Call: func(args, ...)
        converters.put("Call", (ast, node, parent) -> {
            Expression callee = (Expression) dispatch(ast, mapOf(node, "func"));
            if (callee == null) return null;
            List<Expression> argExprs = new ArrayList<>();
            for (Map<String, Object> arg : listOf(node, "args")) {
                Expression e = (Expression) dispatch(ast, arg);
                if (e != null) argExprs.add(e);
            }
            return new FunctionCallNode(callee, argExprs.toArray(new Expression[0]));
        });

        // Attribute: obj.attr  (MemberAccessor)
        converters.put("Attribute", (ast, node, parent) -> {
            Expression obj = (Expression) dispatch(ast, mapOf(node, "value"));
            String attr = strOf(node, "attr");
            if (obj == null || attr == null) return null;
            return new MemberAccessor(obj, identifier(ast, attr));
        });

        // List literal
        PyNodeConverter listConv = (ast, node, parent) -> {
            List<Expression> elems = new ArrayList<>();
            for (Map<String, Object> elt : listOf(node, "elts")) {
                Expression e = (Expression) dispatch(ast, elt);
                if (e != null) elems.add(e);
            }
            return new ArrayNode(ast, elems.toArray(new Expression[0]));
        };
        converters.put("List", listConv);
        // Tuple literal (treated as array for now)
        converters.put("Tuple", listConv);

        // UnaryOp: -x, not x
        converters.put("UnaryOp", (ast, node, parent) -> {
            String opType = typeOf(mapOf(node, "op"));
            Expression operand = (Expression) dispatch(ast, mapOf(node, "operand"));
            if (operand == null) return null;
            org.twelve.gcp.inference.operator.UnaryOperator op = pyUnaryOpToGcp(opType);
            if (op == null) return operand;
            return new UnaryExpression(operand,
                    new org.twelve.gcp.node.operator.OperatorNode<>(ast, op),
                    org.twelve.gcp.node.expression.UnaryPosition.PREFIX);
        });

        // IfExp: a if cond else b — simplified: just return the "body" branch for now
        converters.put("IfExp", (ast, node, parent) ->
                dispatch(ast, mapOf(node, "body"), parent));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<Argument> buildArguments(AST ast, Map<String, Object> argsNode, String funcName) {
        List<Argument> args = new ArrayList<>();
        if (argsNode == null) return args;
        List<Map<String, Object>> argList = listOf(argsNode, "args");
        for (Map<String, Object> argNode : argList) {
            String argName = strOf(argNode, "arg");
            if ("self".equals(argName)) continue; // self is GCP's 'this', skip
            TypeNode typeNode = buildTypeNode(ast, mapOf(argNode, "annotation"));
            args.add(new Argument(identifier(ast, argName), typeNode));
        }
        return args;
    }

    private FunctionBody buildFunctionBody(AST ast, Map<String, Object> funcNode) {
        FunctionBody body = new FunctionBody(ast);
        for (Map<String, Object> stmt : listOf(funcNode, "body")) {
            // Simple pass / docstring → skip
            String t = typeOf(stmt);
            if ("Pass".equals(t)) continue;
            if ("Expr".equals(t) && isDocstring(stmt)) continue;
            Node node = dispatch(ast, stmt);
            if (node instanceof org.twelve.gcp.node.statement.Statement s) {
                body.addStatement(s);
            }
        }
        return body;
    }

    private boolean isDocstring(Map<String, Object> exprStmt) {
        Map<String, Object> val = mapOf(exprStmt, "value");
        if (val == null) return false;
        return "Constant".equals(typeOf(val)) && val.get("value") instanceof String;
    }

    /**
     * Build a GCP {@link TypeNode} from a Python annotation AST node.
     * Supports: simple names (int, str, float, bool, None),
     * generics (list[T], dict[K,V], Optional[T]), union (X | Y).
     */
    TypeNode buildTypeNode(AST ast, Map<String, Object> annotation) {
        if (annotation == null) return null;
        String t = typeOf(annotation);
        if (t == null) return null;

        switch (t) {
            case "Name" -> {
                String name = strOf(annotation, "id");
                return name == null ? null : namedType(ast, name);
            }
            case "Attribute" -> {
                // typing.Optional, typing.List, etc.
                String attr = strOf(annotation, "attr");
                return attr == null ? null : namedType(ast, attr);
            }
            case "Subscript" -> {
                // list[int], Optional[str], dict[str, int], Union[X, Y]
                String outerName = subscriptName(annotation);
                Map<String, Object> sliceNode = mapOf(annotation, "slice");
                if ("Optional".equals(outerName)) {
                    TypeNode inner = buildTypeNode(ast, sliceNode);
                    return inner == null ? null : new OptionTypeNode(inner);
                }
                if ("list".equalsIgnoreCase(outerName) || "List".equals(outerName)) {
                    TypeNode inner = buildTypeNode(ast, sliceNode);
                    return inner == null ? new ArrayTypeNode(ast) : new ArrayTypeNode(ast, inner);
                }
                // Fallback: treat as named
                return outerName == null ? null : namedType(ast, outerName);
            }
            case "BinOp" -> {
                // X | Y  union type (Python 3.10+)
                String opType = typeOf(mapOf(annotation, "op"));
                if ("BitOr".equals(opType)) {
                    TypeNode left  = buildTypeNode(ast, mapOf(annotation, "left"));
                    TypeNode right = buildTypeNode(ast, mapOf(annotation, "right"));
                    if (left != null && right != null) return new OptionTypeNode(left, right);
                    if (left != null) return left;
                }
                return null;
            }
            case "Constant" -> {
                // None annotation
                if (annotation.get("value") == null) return namedType(ast, "Unit");
                return null;
            }
            default -> { return null; }
        }
    }

    private String subscriptName(Map<String, Object> subscript) {
        Map<String, Object> val = mapOf(subscript, "value");
        if (val == null) return null;
        String t = typeOf(val);
        if ("Name".equals(t)) return strOf(val, "id");
        if ("Attribute".equals(t)) return strOf(val, "attr");
        return null;
    }

    private TypeNode namedType(AST ast, String name) {
        String normalized = switch (name) {
            case "int"   -> "Int";
            case "float" -> "Float";
            case "str"   -> "String";
            case "bool"  -> "Bool";
            case "None", "NoneType" -> "Unit";
            case "bytes" -> "String";
            case "any", "Any"  -> null; // unknown/any
            default -> name; // user-defined type name used as-is
        };
        if (normalized == null) return null;
        return new IdentifierTypeNode(identifier(ast, normalized));
    }

    private Identifier identifier(AST ast, String name) {
        if (name == null) return null;
        return new Identifier(ast, new Token<>(name, 0));
    }

    // ── operator mappings ─────────────────────────────────────────────────────

    private BinaryOperator pyArithOpToGcp(String op) {
        if (op == null) return null;
        return switch (op) {
            case "Add"      -> BinaryOperator.ADD;
            case "Sub"      -> BinaryOperator.SUBTRACT;
            case "Mult"     -> BinaryOperator.MULTIPLY;
            case "Div"      -> BinaryOperator.DIVIDE;
            case "Mod"      -> BinaryOperator.MODULUS;
            case "BitOr"    -> BinaryOperator.BITWISE_OR;
            case "BitAnd"   -> BinaryOperator.BITWISE_AND;
            case "BitXor"   -> BinaryOperator.BITWISE_XOR;
            case "LShift"   -> BinaryOperator.BITWISE_SHIFT_LEFT;
            case "RShift"   -> BinaryOperator.BITWISE_SHIFT_RIGHT;
            default         -> null;
        };
    }

    private BinaryOperator pyCmpOpToGcp(String op) {
        if (op == null) return null;
        return switch (op) {
            case "Eq"    -> BinaryOperator.EQUALS;
            case "NotEq" -> BinaryOperator.NOT_EQUALS;
            case "Lt"    -> BinaryOperator.LESS_THAN;
            case "LtE"   -> BinaryOperator.LESS_OR_EQUAL;
            case "Gt"    -> BinaryOperator.GREATER_THAN;
            case "GtE"   -> BinaryOperator.GREATER_OR_EQUAL;
            default      -> null;
        };
    }

    private org.twelve.gcp.inference.operator.UnaryOperator pyUnaryOpToGcp(String op) {
        if (op == null) return null;
        return switch (op) {
            case "USub" -> org.twelve.gcp.inference.operator.UnaryOperator.NEGATE;
            case "Not"  -> org.twelve.gcp.inference.operator.UnaryOperator.BANG;
            default     -> null;
        };
    }

    /** Split a dotted module path into a list of Identifiers, e.g. "os.path" → [os, path]. */
    private List<Identifier> moduleIds(AST ast, String dotted) {
        List<Identifier> ids = new ArrayList<>();
        if (dotted == null || dotted.isBlank()) return ids;
        for (String part : dotted.split("\\.")) {
            ids.add(identifier(ast, part));
        }
        return ids;
    }

    // ── json helpers ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<String, Object> node, String key) {
        Object v = node.get(key);
        return (v instanceof Map) ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> node, String key) {
        Object v = node.get(key);
        return (v instanceof List) ? (List<Map<String, Object>>) v : Collections.emptyList();
    }

    private static String strOf(Map<String, Object> node, String key) {
        Object v = node.get(key);
        return v == null ? null : v.toString();
    }

    private static String typeOf(Map<String, Object> node) {
        if (node == null) return null;
        return (String) node.get("_type");
    }
}
