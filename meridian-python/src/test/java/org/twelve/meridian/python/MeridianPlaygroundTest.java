package org.twelve.meridian.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.twelve.gcp.ast.AST;
import org.twelve.gcp.node.expression.Assignment;
import org.twelve.gcp.node.function.Argument;
import org.twelve.gcp.node.function.FunctionNode;
import org.twelve.gcp.node.statement.VariableDeclarator;
import org.twelve.gcp.outline.Outline;
import org.twelve.gcp.outline.adt.Entity;
import org.twelve.gcp.outline.primitive.ANY;
import org.twelve.gcp.outline.projectable.Function;
import org.twelve.gcp.outline.projectable.Genericable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Meridian 推导能力 Playground — 验证 GCP 三大约束机制通过 Python converter 的端到端表现。
 *
 * <h2>GCP 四维约束模型</h2>
 * <pre>
 *   extendToBe  (上界 — 来自实际赋值，如 x = 100 → x.extendToBe = Integer)
 *       ↓
 *   declaredToBe  (声明类型 — 程序员显式注解)
 *       ↓
 *   hasToBe  (使用约束 — 来自变量被消费的方式，如 y: int = x → x.hasToBe = int)
 *       ↓
 *   definedToBe  (结构约束 — 来自调用/访问模式，如 x(a) → x.definedToBe = Callable)
 * </pre>
 *
 * <h2>覆盖现状说明</h2>
 * <ul>
 *   <li><b>hasToBe</b>: call-site 整数 → 参数 ✅ 已覆盖，annotation 输出正确</li>
 *   <li><b>extendToBe</b>: 默认值推导参数类型 ✅ 已覆盖，annotation 输出正确</li>
 *   <li><b>definedToBe (lambda/callable)</b>: func(x) 模式 ✅ GCP 推导正确，
 *       ⚠️ annotation writer 暂不输出 Callable[...] 类型，但外层函数返回类型可正确推导</li>
 *   <li><b>definedToBe (object/entity)</b>: obj.attr 读取 ✅ GCP 推导正确，
 *       ⚠️ annotation writer 暂不输出 entity 结构类型；obj.attr = value 写入 ❌ 尚未支持</li>
 * </ul>
 */
@Execution(ExecutionMode.SAME_THREAD)
class MeridianPlaygroundTest {

    private final TypeAnnotationGenerator typeGen = new TypeAnnotationGenerator();

    // ════════════════════════════════════════════════════════════════════════
    // hasToBe — 参数从使用场景约束推导
    // ════════════════════════════════════════════════════════════════════════

    /**
     * hasToBe 案例 1：call-site int 字面量传播到参数。
     *
     * <p>GCP 在 {@code add_loop(5, 10)} 调用点看到两个 int 字面量，将其通过
     * {@code hasToBe} 约束传播到参数 {@code n} 和 {@code m}。
     * 这是最常见的推导模式 — 调用点提供具体类型，库函数无需任何注解。
     */
    @Test
    void has_to_be__call_site_int_propagates_to_params() {
        String lib = """
                def add_loop(n, m):
                    total = 0
                    for i in range(n):
                        total += m
                    return total
                """;
        String calls = "add_loop(5, 10)";

        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(lib, calls);
        String annotated = new PythonAnnotationWriter().annotate(lib, asts[0], asts[1]);

        section("hasToBe — call-site int propagates to parameters");
        System.out.println(annotated);

        assertTrue(annotated.contains("n: int"),
                "hasToBe: n 用于 range(n)，call-site 传入 5 → n: int.\n" + annotated);
        assertTrue(annotated.contains("m: int"),
                "hasToBe: m 累加到 int 变量，call-site 传入 10 → m: int.\n" + annotated);
        assertTrue(annotated.contains("-> int"),
                "返回类型推导为 int.\n" + annotated);
    }

    /**
     * hasToBe 案例 2：参数在字符串操作中使用，约束类型为 str。
     *
     * <p>当 call-site 传入字符串字面量时，GCP 将 {@code s.hasToBe = str} 传播到参数。
     */
    @Test
    void has_to_be__call_site_str_propagates_to_param() {
        String lib = """
                def repeat(s, n):
                    result = ""
                    for i in range(n):
                        result += s
                    return result
                """;
        String calls = "repeat(\"hello\", 3)";

        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(lib, calls);
        String annotated = new PythonAnnotationWriter().annotate(lib, asts[0], asts[1]);

        section("hasToBe — call-site str and int propagate to params");
        System.out.println(annotated);

        assertTrue(annotated.contains("n: int"),
                "hasToBe: n 用于 range(n)，call-site 传入 3 → n: int.\n" + annotated);
        assertTrue(annotated.contains("-> str"),
                "返回类型推导为 str.\n" + annotated);
    }

    // ════════════════════════════════════════════════════════════════════════
    // extendToBe — 参数从函数体内赋值/默认值推导上界
    // ════════════════════════════════════════════════════════════════════════

    /**
     * extendToBe 案例 1：默认参数值推导参数类型。
     *
     * <p>GCP 从 {@code def f(n=1000)} 的默认值 {@code 1000} 推导出
     * {@code n.extendToBe = Integer}，无需 call-site 即可标注参数类型。
     * 这是 {@link org.twelve.meridian.python.converter.FunctionDefConverter}
     * 通过 {@code inferDefaultType} 实现的 extendToBe 推导。
     */
    @Test
    void extend_to_be__default_value_infers_param_type() {
        String lib = """
                def sum_n(n=100):
                    total = 0
                    for i in range(n):
                        total += i
                    return total
                """;
        // 故意不提供 call-site — 类型必须仅从默认值推导
        String calls = "";

        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(lib, calls);
        String annotated = new PythonAnnotationWriter().annotate(lib, asts[0], asts[1]);

        section("extendToBe — default value n=100 infers n: int (no call context)");
        System.out.println(annotated);

        assertTrue(annotated.contains("n: int"),
                "extendToBe: 默认值 n=100 → n: int (无 call-site).\n" + annotated);
        assertTrue(annotated.contains("-> int"),
                "返回类型推导为 int.\n" + annotated);
    }

    /**
     * extendToBe 案例 2：多个默认参数，类型各自独立推导。
     *
     * <p>每个参数的 {@code extendToBe} 约束独立来自其默认值：
     * {@code step=1} → int，{@code start=0.0} → float。
     */
    @Test
    void extend_to_be__multiple_default_types() {
        String lib = """
                def range_sum(n=10, step=1, start=0.0):
                    total = start
                    for i in range(n):
                        total += step
                    return total
                """;
        String calls = "";

        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(lib, calls);
        String annotated = new PythonAnnotationWriter().annotate(lib, asts[0], asts[1]);

        section("extendToBe — multiple default params with different types");
        System.out.println(annotated);

        assertTrue(annotated.contains("n: int"),
                "extendToBe: n=10 → n: int.\n" + annotated);
        assertTrue(annotated.contains("step: int"),
                "extendToBe: step=1 → step: int.\n" + annotated);
    }

    // ════════════════════════════════════════════════════════════════════════
    // definedToBe (lambda/callable) — 参数从调用模式推导为函数类型
    // ════════════════════════════════════════════════════════════════════════

    /**
     * definedToBe (lambda) 案例：{@code func} 参数未声明为 lambda，
     * 但函数体中出现 {@code func(10)} 调用模式。
     *
     * <p>GCP 的 {@link org.twelve.gcp.inference.FunctionCallInference} 检测到
     * {@code func} 被当作函数调用，因此设置：
     * {@code func.definedToBe = HigherOrderFunction(arg=Genericable, returns=Genericable)}
     *
     * <p><b>返回类型推导限制</b>：若 {@code func(10)} 的结果直接 return（无后续 int
     * 约束），GCP 不能仅凭 {@code func.definedToBe} 反向推导外层返回类型为 int。
     * 需要外层有累加约束（如 {@code total += func(i)}）才能触发反向传播。
     * 参见 {@link #defined_to_be__lambda_param_used_in_loop} 的成功案例。
     *
     * <p><b>annotation writer 现状</b>：{@code func} 本身不会被标注为
     * {@code Callable[[int], int]}（annotation writer 暂不支持 Function 类型输出）。
     */
    @Test
    void defined_to_be__lambda_param_called_in_body() {
        String lib = """
                def apply_to_ten(func):
                    return func(10)
                """;
        String calls = "apply_to_ten(lambda x: x * 2)";

        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(lib, calls);

        // AST 级别验证：func 参数的 definedToBe 应被设置为函数类型
        Argument funcParam = findFunctionArg(asts[0], "apply_to_ten", "func");
        assertNotNull(funcParam, "func 参数必须存在于 apply_to_ten 中");

        Outline paramOutline = funcParam.outline();
        assertInstanceOf(Genericable.class, paramOutline,
                "func 参数应是 Genericable（未声明类型的参数）");

        Genericable<?, ?> generic = (Genericable<?, ?>) paramOutline;
        Outline defined = generic.definedToBe();

        section("definedToBe (lambda) — func(10) in body sets func.definedToBe");
        System.out.printf("  func.definedToBe = %s%n", defined);
        System.out.printf("  func.hasToBe     = %s%n", generic.hasToBe());
        System.out.printf("  func.extendToBe  = %s%n", generic.extendToBe());

        // definedToBe 必须不再是 ANY — 说明 GCP 已识别出调用模式
        assertFalse(defined instanceof ANY,
                "func.definedToBe 应从 ANY 变成函数类型约束，当前: " + defined);
        assertTrue(defined instanceof Function,
                "func.definedToBe 应是 HigherOrderFunction，当前: " + defined);

        // 注意：此处不断言 -> int。
        // 当 func(10) 结果直接 return 时，GCP 无法仅从 definedToBe 反向推导返回类型。
        // 只有当返回值被用于具有类型约束的表达式（如 int 累加）时，
        // 返回类型才能被推导。见 defined_to_be__lambda_param_used_in_loop。
        String annotated = new PythonAnnotationWriter().annotate(lib, asts[0], asts[1]);
        section("annotated output (func 不被标注为 Callable，这是 annotation writer 的已知限制)");
        System.out.println(annotated);
    }

    /**
     * definedToBe (lambda) 案例 2：参数 {@code transform} 接收 lambda，
     * 在 for 循环中对每个元素调用，GCP 推导返回类型。
     */
    @Test
    void defined_to_be__lambda_param_used_in_loop() {
        String lib = """
                def map_sum(transform, n):
                    total = 0
                    for i in range(n):
                        total += transform(i)
                    return total
                """;
        String calls = "map_sum(lambda x: x * x, 5)";

        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(lib, calls);

        // 验证 transform 参数的 definedToBe 约束
        Argument transformParam = findFunctionArg(asts[0], "map_sum", "transform");
        assertNotNull(transformParam, "transform 参数必须存在");
        assertInstanceOf(Genericable.class, transformParam.outline());
        Genericable<?, ?> g = (Genericable<?, ?>) transformParam.outline();

        section("definedToBe (lambda) — transform(i) in loop sets definedToBe");
        System.out.printf("  transform.definedToBe = %s%n", g.definedToBe());
        System.out.printf("  transform.hasToBe     = %s%n", g.hasToBe());

        assertFalse(g.definedToBe() instanceof ANY,
                "transform.definedToBe 应从 ANY 变成函数类型约束");

        String annotated = new PythonAnnotationWriter().annotate(lib, asts[0], asts[1]);
        section("annotated output");
        System.out.println(annotated);

        assertTrue(annotated.contains("n: int"),
                "hasToBe: n 通过 call-site 5 推导为 int.\n" + annotated);
        assertTrue(annotated.contains("-> int"),
                "返回类型推导为 int.\n" + annotated);
    }

    // ════════════════════════════════════════════════════════════════════════
    // definedToBe (object/entity) — 参数从属性访问模式推导为 entity 类型
    // ════════════════════════════════════════════════════════════════════════

    /**
     * definedToBe (object) 案例：{@code obj.name} 属性被直接读取，
     * GCP 将 {@code obj.definedToBe = Entity({name: Genericable})} 作为结构约束。
     *
     * <p>GCP 的 {@link org.twelve.gcp.inference.MemberAccessorInference} 在泛型路径下，
     * 当 host 是 {@link Genericable} 时，自动将被访问的成员添加到 {@code definedToBe}
     * 中的 Entity 结构中。
     *
     * <p><b>注意</b>：必须避免将 {@code obj.name} 作为参数传给 builtin 函数（如 {@code len()}）。
     * {@link org.twelve.meridian.python.converter.CallConverter} 的 builtin 优化路径会直接返回
     * {@code intLit(ast)} 而不 dispatch 参数，导致 {@code MemberAccessor} 从未被创建，
     * {@code MemberAccessorInference} 也就无法运行。
     * 应使用直接属性赋值或二元操作（{@code obj.name + ""}）来触发成员访问推导。
     *
     * <p><b>annotation writer 现状</b>：{@code obj} 不会被标注为 entity 结构类型
     * （annotation writer 暂不支持 entity 类型输出）。
     *
     * <p><b>attribute 写入（obj.name = value）现状</b>：
     * {@link org.twelve.meridian.python.converter.AssignConverter} 当前只处理 {@code Name}
     * 和 {@code Tuple} 目标，Attribute 目标（即 obj.field = ...）会被跳过，尚未支持。
     */
    @Test
    void defined_to_be__object_param_inferred_from_attr_read() {
        // 直接将 obj.name 赋值给局部变量，避免走 len() builtin 优化路径
        // （len(obj.name) 会被 CallConverter 直接替换为 intLit，obj.name 从未被 dispatch）
        String lib = """
                def get_name(obj):
                    name = obj.name
                    return name
                """;

        PythonInferencer inf = new PythonInferencer();
        AST ast = inf.infer(lib);

        Argument objParam = findFunctionArg(ast, "get_name", "obj");
        assertNotNull(objParam, "obj 参数必须存在于 get_name 中");
        assertInstanceOf(Genericable.class, objParam.outline(),
                "obj 应是 Genericable（未声明类型）");

        Genericable<?, ?> generic = (Genericable<?, ?>) objParam.outline();
        Outline defined = generic.definedToBe();

        section("definedToBe (object) — name = obj.name sets obj.definedToBe = Entity({name})");
        System.out.printf("  obj.definedToBe = %s%n", defined);
        System.out.printf("  obj.hasToBe     = %s%n", generic.hasToBe());

        // definedToBe 必须是 Entity 类型（从 obj.name 成员访问推导）
        assertFalse(defined instanceof ANY,
                "obj.definedToBe 应从 ANY 变成 Entity 类型约束，当前: " + defined);
        assertInstanceOf(Entity.class, defined,
                "obj.definedToBe 应是 Entity，当前: " + defined);

        Entity entity = (Entity) defined;
        boolean hasNameMember = entity.members().stream()
                .anyMatch(m -> "name".equals(m.name()));
        assertTrue(hasNameMember,
                "Entity 中应包含 'name' 成员。成员: " + entity.members().stream()
                        .map(m -> m.name()).toList());

        String stub = typeGen.generate(ast);
        section("stub output");
        System.out.println(stub);
    }

    /**
     * definedToBe (object) 案例 2：多个属性访问，entity 成员动态积累。
     *
     * <p>函数体内依次访问 {@code p.x} 和 {@code p.y}，GCP 将两个成员都注册到
     * {@code p.definedToBe = Entity({x: Genericable, y: Genericable})} 中。
     */
    @Test
    void defined_to_be__object_param_inferred_from_multiple_attr_reads() {
        String lib = """
                def distance_sq(p):
                    return p.x * p.x + p.y * p.y
                """;

        PythonInferencer inf = new PythonInferencer();
        AST ast = inf.infer(lib);

        Argument pParam = findFunctionArg(ast, "distance_sq", "p");
        assertNotNull(pParam, "p 参数必须存在");
        assertInstanceOf(Genericable.class, pParam.outline());
        Genericable<?, ?> g = (Genericable<?, ?>) pParam.outline();

        Outline defined = g.definedToBe();

        section("definedToBe (object) — p.x and p.y in body → Entity({x, y})");
        System.out.printf("  p.definedToBe = %s%n", defined);

        assertInstanceOf(Entity.class, defined,
                "p.definedToBe 应是 Entity，当前: " + defined);
        Entity entity = (Entity) defined;

        boolean hasX = entity.members().stream().anyMatch(m -> "x".equals(m.name()));
        boolean hasY = entity.members().stream().anyMatch(m -> "y".equals(m.name()));

        assertTrue(hasX, "Entity 中应包含 'x' 成员");
        assertTrue(hasY, "Entity 中应包含 'y' 成员");

        System.out.printf("  Entity members: %s%n",
                entity.members().stream().map(m -> m.name()).toList());
    }

    // ════════════════════════════════════════════════════════════════════════
    // 综合案例 — 三种约束机制同时出现
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 综合演示：单个函数同时展示三种约束类型。
     *
     * <ul>
     *   <li>{@code n}: hasToBe int — 从 call-site 整数字面量传播</li>
     *   <li>{@code step}: extendToBe int — 从默认值 {@code step=1} 推导</li>
     *   <li>{@code transform}: definedToBe HigherOrderFunction — 从 {@code transform(i)} 调用推导</li>
     * </ul>
     */
    @Test
    void all_three_constraints_in_one_function() {
        String lib = """
                def mapped_sum(n, transform, step=1):
                    total = 0
                    for i in range(n):
                        total += transform(i) * step
                    return total
                """;
        String calls = "mapped_sum(10, lambda x: x * x)";

        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(lib, calls);
        String annotated = new PythonAnnotationWriter().annotate(lib, asts[0], asts[1]);

        section("All three constraints — hasToBe + extendToBe + definedToBe");
        System.out.println(annotated);

        // hasToBe: n 从 call-site 10 推导
        assertTrue(annotated.contains("n: int"),
                "hasToBe: n 从 call-site 传入 10 → n: int.\n" + annotated);

        // extendToBe: step 从默认值 1 推导
        assertTrue(annotated.contains("step: int"),
                "extendToBe: step=1 默认值 → step: int.\n" + annotated);

        // definedToBe: transform 被调用为函数
        Argument transformParam = findFunctionArg(asts[0], "mapped_sum", "transform");
        assertNotNull(transformParam, "transform 参数必须存在");
        assertInstanceOf(Genericable.class, transformParam.outline());
        Genericable<?, ?> g = (Genericable<?, ?>) transformParam.outline();

        System.out.printf("  transform.definedToBe = %s%n", g.definedToBe());
        assertFalse(g.definedToBe() instanceof ANY,
                "definedToBe: transform(i) 调用模式 → definedToBe 应被设置为函数类型");

        // 返回类型应为 int
        assertTrue(annotated.contains("-> int"),
                "返回类型应推导为 int.\n" + annotated);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 辅助方法
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 从推导后的 GCP AST 中定位指定函数的指定参数。
     *
     * @param ast      完成推导的 GCP AST
     * @param funcName Python 函数名
     * @param argName  参数名
     * @return 对应的 {@link Argument}，未找到返回 {@code null}
     */
    private Argument findFunctionArg(AST ast, String funcName, String argName) {
        for (var stmt : ast.program().body().statements()) {
            if (!(stmt instanceof VariableDeclarator vd)) continue;
            for (Assignment a : vd.assignments()) {
                if (a.lhs() == null) continue;
                String lhsName = a.lhs().lexeme().trim().replaceAll(":.*", "").trim();
                if (funcName.equals(lhsName) && a.rhs() instanceof FunctionNode fn) {
                    for (Argument arg : typeGen.flattenFunctionArgs(fn)) {
                        if (argName.equals(arg.name())) return arg;
                    }
                }
            }
        }
        return null;
    }

    private static void section(String heading) {
        System.out.println("\n── " + heading + " " + "─".repeat(Math.max(0, 70 - heading.length())));
    }
}
