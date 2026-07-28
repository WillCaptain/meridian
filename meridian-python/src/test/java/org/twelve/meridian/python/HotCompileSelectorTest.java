package org.twelve.meridian.python;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotCompileSelectorTest {

    @Test
    void import_closure_follows_from_import_and_skips_coverage_only() {
        Map<String, String> modules = new LinkedHashMap<>();
        modules.put("mi_facade", "from mi_hot import take\n\ndef take_sum(n, xs):\n    return sum(take(n, xs))\n");
        modules.put("mi_hot", "def take(n, xs):\n    return xs[:n]\n");
        modules.put("mi_more_full", "def unused_helper(x):\n    return x\n");

        List<String> hot = HotCompileSelector.importClosure("mi_facade", modules);
        assertEquals(List.of("mi_facade", "mi_hot"), hot);
        assertTrue(!hot.contains("mi_more_full"));
    }

    @Test
    void plain_import_and_as_alias() {
        Map<String, String> modules = Map.of(
                "a", "import b as bb\nimport sys\n",
                "b", "x = 1\n",
                "c", "y = 2\n"
        );
        assertEquals(List.of("a", "b"), HotCompileSelector.importClosure("a", modules));
    }
}
