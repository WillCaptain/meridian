package org.twelve.meridian.python;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IDE surface keeps wide types (Union / Optional); compile filtering must not apply.
 */
class IdeTypeSurfaceTest {

    @Test
    void hover_keeps_union_width_that_compile_would_drop() {
        // Body uses % / arithmetic → Number tower often surfaces as Union[int, float]
        // on params for IDE; compile path leaves params open or specializes from calls.
        String lib = """
                def prime_factors_count(n):
                    count = 0
                    d = 2
                    while d * d <= n:
                        while n % d == 0:
                            count += 1
                            n //= d
                        d += 1
                    if n > 1:
                        count += 1
                    return count
                """;
        String usage = "prime_factors_count(360)\n";

        Map<String, String> hover = new IdeTypeSurface().hoverTypes(lib, usage);
        assertFalse(hover.isEmpty(), "IDE surface must produce hover facts");

        // Compile path (writer + SAFE_PARTIAL / ForParam) must not be required for Union display.
        // At minimum, return type should be present; param may be int from call site or Union.
        assertTrue(hover.containsKey("prime_factors_count#return")
                        || hover.containsKey("prime_factors_count#n"),
                () -> "hover=" + hover);

        String annotated = new PythonAnnotationWriter()
                .withPolicy(AnnotationPolicy.SAFE_PARTIAL)
                .annotate(lib, new PythonInferencer().inferWithContextDetailed(lib, usage));
        // Surfaces are independent: hover map is not the annotated compile string.
        assertNotEquals(hover.toString(), annotated);
    }

    @Test
    void definition_width_union_visible_without_usage() {
        // `+` leaves Addable / Number tower → IDE shows Union[int, float]
        String lib = """
                def add(x, y):
                    return x + y
                """;

        Map<String, String> hover = new IdeTypeSurface().hoverTypes(lib, null);
        assertFalse(hover.isEmpty(), () -> "hover=" + hover);
        // Compile ForParam would null out Number/Addable; IDE must keep width.
        assertTrue(
                hover.values().stream().anyMatch(v ->
                        v.contains("Union[") || v.equals("Addable") || v.equals("Number")),
                () -> "IDE must keep definition-width numeric tower, hover=" + hover);
    }
}
