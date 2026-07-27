package org.twelve.meridian.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeEvalPySiteExporterTest {

    @TempDir Path tmp;

    @Test
    void vocabErasesGenerics() {
        assertEquals(List.of("list"), TypeEvalPySiteExporter.toTypeEvalPyVocab("list[int]"));
        assertEquals(List.of("callable"), TypeEvalPySiteExporter.toTypeEvalPyVocab("Callable[[int], str]"));
        assertEquals(List.of("int", "Nonetype"), TypeEvalPySiteExporter.toTypeEvalPyVocab("Optional[int]"));
    }

    @Test
    void sitesMatchTypeEvalPyStyleLocations() throws Exception {
        Path py = tmp.resolve("main.py");
        Files.writeString(py, """
                def one():
                    return 1

                x = one()
                """);

        PythonInferencer.InferResult r = new PythonInferencer().inferFileDetailed(py.toFile());
        List<Map<String, Object>> sites =
                new TypeEvalPySiteExporter().collect(r.inference());

        Map<String, Object> fr = find(sites, s -> s.containsKey("function")
                && !s.containsKey("parameter") && "one".equals(s.get("function")));
        Map<String, Object> lv = find(sites, s -> "x".equals(s.get("variable")));

        assertEquals(1, fr.get("line_number"));
        assertEquals(5, fr.get("col_offset"));
        assertTrue(((List<?>) fr.get("type")).contains("int"), () -> String.valueOf(sites));

        assertEquals(4, lv.get("line_number"));
        assertEquals(1, lv.get("col_offset"));
        assertTrue(((List<?>) lv.get("type")).contains("int"), () -> String.valueOf(lv));
    }

    @Test
    void parameterSitesUseArgColumns() throws Exception {
        Path py = tmp.resolve("main.py");
        Files.writeString(py, """
                def add(a, b):
                    return a + b

                x = add(1, 2)
                """);

        PythonInferencer.InferResult r = new PythonInferencer().inferFileDetailed(py.toFile());
        List<Map<String, Object>> sites =
                new TypeEvalPySiteExporter().collect(r.inference());

        Map<String, Object> fpA = find(sites, s -> "a".equals(s.get("parameter")));
        assertEquals(1, fpA.get("line_number"));
        assertEquals(9, fpA.get("col_offset"));
        assertTrue(((List<?>) fpA.get("type")).contains("int"), () -> String.valueOf(fpA));
    }

    @Test
    void containerAndQualifiedMethodAdapters() throws Exception {
        Path py = tmp.resolve("main.py");
        Files.writeString(py, """
                class MyClass:
                    def func1(self):
                        return 42

                keys = ["a", "b"]
                dict1 = {"a": 1, "b": 2}
                a, *b, c = MyClass.func1, MyClass.func1, MyClass.func1
                """);

        PythonInferencer.InferResult r = new PythonInferencer().inferFileDetailed(py.toFile());
        List<Map<String, Object>> sites =
                new TypeEvalPySiteExporter().collect(r.inference());

        assertTrue(sites.stream().anyMatch(s -> "MyClass.func1".equals(s.get("function"))),
                () -> String.valueOf(sites));
        assertTrue(sites.stream().anyMatch(s -> "keys[0]".equals(s.get("variable"))),
                () -> String.valueOf(sites));
        assertTrue(sites.stream().anyMatch(s -> "dict1['a']".equals(s.get("variable"))),
                () -> String.valueOf(sites));
        assertTrue(sites.stream().anyMatch(s -> "b[0]".equals(s.get("variable"))),
                () -> String.valueOf(sites));
    }

    @Test
    void nestedNumericCallsSpecializePerSiteWithoutCollapsingFr() throws Exception {
        Path py = tmp.resolve("main.py");
        Files.writeString(py, """
                def add(x, y):
                    return x + y

                def square(x):
                    return x * x

                a = square(add(2, 3))
                b = square(add(2.1, 3.2))
                """);

        PythonInferencer.InferResult r = new PythonInferencer().inferFileDetailed(py.toFile());
        List<Map<String, Object>> sites =
                new TypeEvalPySiteExporter().collect(r.inference());

        Map<String, Object> squareFr = find(sites, s -> "square".equals(s.get("function"))
                && !s.containsKey("parameter") && !s.containsKey("variable"));
        Map<String, Object> squareFp = find(sites, s -> "square".equals(s.get("function"))
                && "x".equals(s.get("parameter")));
        Map<String, Object> a = find(sites, s -> "a".equals(s.get("variable")));
        Map<String, Object> b = find(sites, s -> "b".equals(s.get("variable")));

        assertEquals(List.of("float", "int"), sortedTypes(squareFr), () -> String.valueOf(sites));
        assertEquals(List.of("float", "int"), sortedTypes(squareFp), () -> String.valueOf(sites));
        assertEquals(List.of("int"), sortedTypes(a), () -> String.valueOf(sites));
        assertEquals(List.of("float"), sortedTypes(b), () -> String.valueOf(sites));
    }

    @Test
    void importedFactoryDoubleCallPeelsToConcrete() throws Exception {
        Path dir = tmp.resolve("imp");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("to_import.py"), """
                def return_func():
                    return "Hello from return_func"

                def func():
                    return return_func
                """);
        Path py = dir.resolve("main.py");
        Files.writeString(py, """
                from to_import import func

                a = func()()
                """);

        PythonInferencer.InferResult r = new PythonInferencer().inferFileDetailed(py.toFile());
        List<Map<String, Object>> sites =
                new TypeEvalPySiteExporter().collect(r.inference());

        assertEquals(List.of("str"), sortedTypes(find(sites, s -> "a".equals(s.get("variable")))),
                () -> String.valueOf(sites));
    }

    @Test
    void zeroArgDefaultDictKeySpecializesCallLv() throws Exception {
        Path py = tmp.resolve("main.py");
        Files.writeString(py, """
                def func1(key="a"):
                    return d[key]()

                def func2():
                    return "Hello from func2"

                def func3():
                    return 42

                d = {"a": func2, "b": func3}

                e = func1()
                f = func1("b")
                """);

        PythonInferencer.InferResult r = new PythonInferencer().inferFileDetailed(py.toFile());
        List<Map<String, Object>> sites =
                new TypeEvalPySiteExporter().collect(r.inference());

        assertEquals(List.of("str"), sortedTypes(find(sites, s -> "e".equals(s.get("variable")))),
                () -> String.valueOf(sites));
        assertEquals(List.of("int"), sortedTypes(find(sites, s -> "f".equals(s.get("variable")))),
                () -> String.valueOf(sites));
    }

    @Test
    void dictSlotFromFactoryCallPeelsOnCall() throws Exception {
        Path py = tmp.resolve("main.py");
        Files.writeString(py, """
                def func2():
                    return "Hello from func2"

                def func1():
                    return func2

                d = {"a": func1()}
                e = d["a"]()
                """);

        PythonInferencer.InferResult r = new PythonInferencer().inferFileDetailed(py.toFile());
        List<Map<String, Object>> sites =
                new TypeEvalPySiteExporter().collect(r.inference());

        assertEquals(List.of("callable"),
                sortedTypes(find(sites, s -> "d['a']".equals(s.get("variable")))),
                () -> String.valueOf(sites));
        assertEquals(List.of("str"), sortedTypes(find(sites, s -> "e".equals(s.get("variable")))),
                () -> String.valueOf(sites));
    }

    @Test
    void returnedDictLiteralProjectsThroughBinder() throws Exception {
        Path py = tmp.resolve("main.py");
        Files.writeString(py, """
                def func5():
                    return {"a": "Hello"}

                f = func5
                m = f()
                """);

        PythonInferencer.InferResult r = new PythonInferencer().inferFileDetailed(py.toFile());
        List<Map<String, Object>> sites =
                new TypeEvalPySiteExporter().collect(r.inference());

        assertEquals(List.of("dict"), sortedTypes(find(sites, s -> "m".equals(s.get("variable")))),
                () -> String.valueOf(sites));
        assertEquals(List.of("str"), sortedTypes(find(sites, s -> "m['a']".equals(s.get("variable")))),
                () -> String.valueOf(sites));
    }

    @Test
    void nestedIntOnlyCallKeepsIntOverNumberFloat() throws Exception {
        Path py = tmp.resolve("main.py");
        Files.writeString(py, """
                def add_one(x):
                    return x + 1

                def double(x):
                    return x * 2

                result = double(add_one(5))
                """);

        PythonInferencer.InferResult r = new PythonInferencer().inferFileDetailed(py.toFile());
        List<Map<String, Object>> sites =
                new TypeEvalPySiteExporter().collect(r.inference());

        Map<String, Object> doubleFr = find(sites, s -> "double".equals(s.get("function"))
                && !s.containsKey("parameter") && !s.containsKey("variable"));
        Map<String, Object> doubleFp = find(sites, s -> "double".equals(s.get("function"))
                && "x".equals(s.get("parameter")));
        Map<String, Object> result = find(sites, s -> "result".equals(s.get("variable")));

        assertEquals(List.of("int"), doubleFr.get("type"), () -> String.valueOf(sites));
        assertEquals(List.of("int"), doubleFp.get("type"), () -> String.valueOf(sites));
        assertEquals(List.of("int"), result.get("type"), () -> String.valueOf(sites));
    }

    private static Map<String, Object> find(List<Map<String, Object>> sites,
                                            java.util.function.Predicate<Map<String, Object>> pred) {
        return sites.stream().filter(pred).findFirst()
                .orElseThrow(() -> new AssertionError("site not found in " + sites));
    }

    @SuppressWarnings("unchecked")
    private static List<String> sortedTypes(Map<String, Object> site) {
        List<String> types = new ArrayList<>((List<String>) site.get("type"));
        Collections.sort(types);
        return types;
    }
}
