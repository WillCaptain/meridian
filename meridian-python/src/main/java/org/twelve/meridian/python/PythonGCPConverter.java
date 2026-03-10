package org.twelve.meridian.python;

import org.twelve.gcp.ast.ASF;
import org.twelve.gcp.ast.AST;
import org.twelve.meridian.python.converter.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry that maps Python AST node type names to their {@link PyConverter} handlers
 * and drives the top-level conversion from a Python JSON AST to a GCP {@link AST}.
 *
 * <p>This class contains no conversion logic. To support a new Python node type:
 * <ol>
 *   <li>Create a subclass of {@link PyConverter} in the {@code converter} package.</li>
 *   <li>Add one {@code converters.put("NodeTypeName", new MyConverter(converters))} line below.</li>
 * </ol>
 */
public class PythonGCPConverter {

    private final ASF asf;
    private final Map<String, PyConverter> converters = new HashMap<>();

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
        PyConverter root = converters.get("Module");
        if (root != null) root.convert(ast, pyAst, ast.program());
        return ast;
    }

    /** Expose the shared converter map for tests or external extension. */
    public Map<String, PyConverter> converters() {
        return converters;
    }

    // ── registration ──────────────────────────────────────────────────────────

    private void registerAll() {
        // ── module / imports ─────────────────────────────────────────────────
        converters.put("Module",        new ModuleConverter(converters));
        converters.put("Import",        new ImportConverter(converters));
        converters.put("ImportFrom",    new ImportFromConverter(converters));

        // ── declarations ─────────────────────────────────────────────────────
        FunctionDefConverter funcConv = new FunctionDefConverter(converters);
        converters.put("FunctionDef",       funcConv);
        converters.put("AsyncFunctionDef",  funcConv);

        converters.put("ClassDef",      new ClassDefConverter(converters));
        converters.put("AnnAssign",     new AnnAssignConverter(converters));
        converters.put("Assign",        new AssignConverter(converters));
        converters.put("AugAssign",     new AugAssignConverter(converters));   // P0

        // ── statements ───────────────────────────────────────────────────────
        converters.put("Return",        new ReturnConverter(converters));
        converters.put("Expr",          new ExprStatementConverter(converters));
        converters.put("If",            new IfStatementConverter(converters));
        converters.put("For",           new ForConverter(converters));
        converters.put("While",         new WhileConverter(converters));
        converters.put("With",          new WithConverter(converters));
        converters.put("Try",           new TryConverter(converters));

        // ── no-op statements ─────────────────────────────────────────────────
        NoOpConverter noop = new NoOpConverter(converters);
        converters.put("Pass",          noop);
        converters.put("Delete",        noop);
        converters.put("Global",        noop);
        converters.put("Nonlocal",      noop);
        converters.put("Break",         noop);
        converters.put("Continue",      noop);

        // ── expressions ──────────────────────────────────────────────────────
        converters.put("Name",          new NameConverter(converters));
        converters.put("Constant",      new ConstantConverter(converters));
        converters.put("BinOp",         new BinOpConverter(converters));
        converters.put("BoolOp",        new BoolOpConverter(converters));
        converters.put("UnaryOp",       new UnaryOpConverter(converters));
        converters.put("Compare",       new CompareConverter(converters));
        converters.put("Call",          new CallConverter(converters));
        converters.put("Attribute",     new AttributeConverter(converters));
        converters.put("IfExp",         new IfExpConverter(converters));       // P0 fixed
        converters.put("Subscript",     new SubscriptConverter(converters));   // P0

        // ── collection literals ───────────────────────────────────────────────
        converters.put("List",          new ListConverter(converters));
        converters.put("Tuple",         new TupleConverter(converters));
        converters.put("Dict",          new DictConverter(converters));
        converters.put("Set",           new ListConverter(converters));        // treat Set as Array

        // ── comprehensions & generators (P1) ──────────────────────────────────
        ListCompConverter listComp = new ListCompConverter(converters);
        converters.put("ListComp",      listComp);
        converters.put("SetComp",       listComp);                             // same representation
        converters.put("GeneratorExp",  listComp);
        converters.put("DictComp",      new DictCompConverter(converters));

        // ── lambda (P1) ───────────────────────────────────────────────────────
        converters.put("Lambda",        new LambdaConverter(converters));
    }
}
