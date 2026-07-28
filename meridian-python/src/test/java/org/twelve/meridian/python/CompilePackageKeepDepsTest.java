package org.twelve.meridian.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Product gate: keep_deps + import-closure hot compile (L6 automation).
 */
class CompilePackageKeepDepsTest {

    @TempDir Path tmp;

    @Test
    void keep_deps_import_closure_compiles_hot_keeps_dep_params() throws Exception {
        Map<String, String> modules = new LinkedHashMap<>();
        modules.put("mi_hot", """
                def take(n, iterable):
                    out = []
                    i = 0
                    for x in iterable:
                        if i >= n:
                            break
                        out.append(x)
                        i += 1
                    return out

                def quantify(iterable):
                    n = 0
                    for x in iterable:
                        if x:
                            n += 1
                    return n
                """);
        modules.put("mi_facade", """
                from mi_hot import take, quantify

                def take_sum(n, iterable):
                    total = 0
                    for x in take(n, iterable):
                        total += x
                    return total

                def quantify_bool(iterable):
                    return quantify(iterable)
                """);
        modules.put("mi_coverage_only", """
                def never_imported(x):
                    return x
                """);

        String usage = """
                from mi_facade import take_sum, quantify_bool
                take_sum(3, [1, 2, 3, 4])
                quantify_bool([True, False, True])
                """;

        Path out = tmp.resolve("pkg_out");
        CompilePipeline.PackageOutcome outcome = new CompilePipeline().runPackage(
                new CompilePipeline.PackageRequest(
                        modules,
                        "mi_facade",
                        usage,
                        List.of(),
                        true,
                        "keep_deps",
                        AnnotationPolicy.SAFE_PARTIAL,
                        out
                ));

        assertEquals(List.of("mi_facade", "mi_hot"), outcome.compileModules());
        assertEquals(MypycAnnotationPrep.Mode.KEEP_DEPS, outcome.annotationMode());
        assertTrue(outcome.compileResult().success(),
                () -> outcome.compileResult().stderr());

        String hot = outcome.mypycSources().get("mi_hot");
        String facade = outcome.mypycSources().get("mi_facade");
        // keep_deps: param anns on hot deps; primary facade stripped of all anns.
        assertTrue(hot.contains("iterable: list") || hot.contains(": int"),
                () -> "expected kept param anns on mi_hot:\n" + hot);
        assertFalse(facade.contains(": list") || facade.contains(": int"),
                () -> "primary should strip param anns under keep_deps:\n" + facade);
        assertTrue(Files.isRegularFile(out.resolve("mi_coverage_only.py")),
                "coverage-only module still materialized");
        assertTrue(Files.list(out).anyMatch(p -> {
            String n = p.getFileName().toString();
            return n.endsWith(".so") || n.endsWith(".pyd");
        }), "mypyc extension expected");
    }
}
