package org.twelve.meridian.python;

final class TestCodeSamples {

    static final String HOF_CALLABLE_CODE = """
            def apply_and_sum(fn, lst, n):
                total = 0.0
                for i in range(n):
                    total += fn(lst[i])
                return total

            def apply_and_count_positive(fn, lst, n):
                count = 0
                for i in range(n):
                    if fn(lst[i]) > 0.0:
                        count += 1
                return count
            """;

    static final String HOF_CALLABLE_CONTEXT = """
            data = [1.0, 4.0, 9.0, 16.0, 25.0]
            r1 = apply_and_sum(lambda x: x * 2.0, data, 5)
            r3 = apply_and_count_positive(lambda x: x - 5.0, data, 5)
            """;

    private TestCodeSamples() {
    }
}
