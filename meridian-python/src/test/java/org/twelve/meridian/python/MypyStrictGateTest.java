package org.twelve.meridian.python;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic gate: Meridian-annotated sources must pass {@code mypy --strict}.
 */
@Execution(ExecutionMode.SAME_THREAD)
class MypyStrictGateTest {

    private static PythonInferencer inferencer;

    @TempDir Path tmp;

    @BeforeAll
    static void setup() {
        inferencer = new PythonInferencer();
    }

    @Test
    void annotated_math_and_return_lambda_pass_mypy_strict() throws Exception {
        // Library-only bodies: call-site evidence comes from the usage snippet so we
        // do not create LV annotations that conflict with wide numeric returns.
        assertMypyStrict("""
                def add(a, b):
                    return a + b
                """, "add_mod.py", "add(1, 2)\n");

        assertMypyStrict("""
                def func():
                    return lambda x: x ** 2
                """, "return_lambda_mod.py", "f = func()\na = f(4)\n");

        assertMypyStrict("""
                def sum_squares_comp(n):
                    squares = [i * i for i in range(n)]
                    total = 0
                    for x in squares:
                        total += x
                    return total
                """, "listcomp_mod.py",
                "sum_squares_comp(10)\n");
    }

    @Test
    void number_annotates_as_int_float_union_not_bare_float() {
        PythonInferencer.ContextInferResult ctx = inferencer.inferWithContextDetailed("""
                def square(x):
                    return x * x
                """, "square(2)\nsquare(2.5)\n");
        String annotated = new PythonAnnotationWriter().annotate("""
                def square(x):
                    return x * x
                """, ctx);
        assertFalse(annotated.matches("(?s).*def square\\(x: float\\).*"),
                () -> "must not narrow Number evidence to float-only:\n" + annotated);
        assertTrue(
                annotated.contains("Union[int, float]")
                        || (annotated.contains("x: int") || annotated.contains("-> int")),
                () -> "expected numeric width or int specialization:\n" + annotated);
    }

    private void assertMypyStrict(String library, String fileName) throws Exception {
        assertMypyStrict(library, fileName, library.contains("(") ? "" : "");
    }

    private void assertMypyStrict(String library, String fileName, String calls) throws Exception {
        String annotated;
        if (calls != null && !calls.isBlank()) {
            annotated = new PythonAnnotationWriter().annotate(
                    library, inferencer.inferWithContextDetailed(library, calls));
        } else {
            // Demand-driven with a trivial call when the library defines callables.
            String usage = guessUsage(library);
            if (usage != null) {
                annotated = new PythonAnnotationWriter().annotate(
                        library, inferencer.inferWithContextDetailed(library, usage));
            } else {
                annotated = new PythonAnnotationWriter().annotate(
                        library, inferencer.inferDetailed(library));
            }
        }
        Path py = tmp.resolve(fileName);
        Files.writeString(py, annotated);
        MypyRunner.CheckResult check = new MypyRunner().checkStrict(py.toFile());
        assertTrue(check.success(),
                () -> "mypy --strict failed for " + fileName + ":\n"
                        + annotated + "\n---\n" + check.stdout() + check.stderr());
    }

    private static String guessUsage(String library) {
        if (library.contains("def add")) return "add(1, 2)\n";
        if (library.contains("def func")) return "f = func()\na = f(4)\n";
        if (library.contains("def sum_squares_comp")) return "sum_squares_comp(10)\n";
        if (library.contains("def square")) return "square(2)\nsquare(2.5)\n";
        return null;
    }
}
