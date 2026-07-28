package org.twelve.meridian.python;

import org.junit.jupiter.api.Test;
import org.twelve.gcp.ast.AST;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compile surface: unused defs are tree-shaken; callees of kept functions stay.
 */
class CompileSurfacePruneTest {

    @Test
    void unused_function_removed_from_compile_input() throws Exception {
        String lib = """
                def used(n):
                    total = 0
                    for i in range(n):
                        total += i
                    return total

                def unused(n):
                    return n * n
                """;
        String usage = "used(10)\n";

        Path out = Files.createTempDirectory("meridian_prune_");
        CompilePipeline.Outcome outcome = new CompilePipeline().run(new CompilePipeline.Request(
                lib, "hot", usage, true, AnnotationPolicy.ALL_CONCRETE, out, null));

        assertTrue(outcome.compileResult().success(),
                () -> outcome.compileResult().stderr());
        assertTrue(outcome.prunedFunctions().contains("unused"),
                () -> "pruned=" + outcome.prunedFunctions());
        assertFalse(outcome.annotatedSource().contains("def unused"),
                () -> outcome.annotatedSource());
        assertTrue(outcome.annotatedSource().contains("def used"),
                () -> outcome.annotatedSource());
    }

    @Test
    void callee_of_used_function_is_kept() throws Exception {
        String lib = """
                def helper(x):
                    return x + 1

                def used(n):
                    return helper(n)

                def unused(n):
                    return n
                """;
        String usage = "used(3)\n";

        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(lib, usage);
        CompileSourcePruner.Result r = CompileSourcePruner.prune(lib, asts[0], asts[1]);

        assertEquals(Set.of("unused"), r.removed(), () -> "kept=" + r.kept());
        assertTrue(r.kept().contains("used") && r.kept().contains("helper"));
        assertTrue(r.source().contains("def helper"));
        assertTrue(r.source().contains("def used"));
        assertFalse(r.source().contains("def unused"));
    }
}
