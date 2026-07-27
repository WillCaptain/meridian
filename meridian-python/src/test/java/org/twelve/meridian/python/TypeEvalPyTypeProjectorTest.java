package org.twelve.meridian.python;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypeEvalPyTypeProjectorTest {

    @Test
    void erasesGenericsAndRenamesClosedWorld() {
        assertEquals(List.of("list"), TypeEvalPyTypeProjector.project("list[int]"));
        assertEquals(List.of("callable"), TypeEvalPyTypeProjector.project("Callable[[int], str]"));
        assertEquals(List.of("int", "Nonetype"), TypeEvalPyTypeProjector.project("Optional[int]"));
    }

    @Test
    void numberMapsToFloatNotInt() {
        assertEquals(List.of("float"), TypeEvalPyTypeProjector.project("Number"));
        assertEquals(List.of("int"), TypeEvalPyTypeProjector.project("Int"));
    }

    @Test
    void projectSiteRewritesTypeList() {
        Map<String, Object> site = new LinkedHashMap<>();
        site.put("variable", "x");
        site.put("type", List.of("Number", "Optional[int]"));
        Map<String, Object> out = TypeEvalPyTypeProjector.projectSite(site);
        assertEquals(List.of("float", "int", "Nonetype"), out.get("type"));
    }

    @Test
    void exporterDelegatesToProjector() {
        assertEquals(
                TypeEvalPyTypeProjector.project("dict[str, int]"),
                TypeEvalPySiteExporter.toTypeEvalPyVocab("dict[str, int]"));
    }
}
