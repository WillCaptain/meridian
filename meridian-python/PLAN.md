# Meridian Python → GCP 类型推导优化计划

## 目标
将无类型注解的 Python 代码通过 GCP 类型推导系统转换为完整类型注解，再经 mypyc 编译为原生 C 扩展，实现大幅运行时加速（目标 10-14x）。

## 架构

```
Python source (无注解)
    ↓  PythonAstBridge  (py_ast_dump.py 子进程)
Python JSON AST
    ↓  PythonGCPConverter (注册表 + PyConverter 体系)
GCP AST
    ↓  ASF.infer()
Typed GCP AST
    ↓  PythonAnnotationWriter / TypeAnnotationGenerator / FunctionSpecializer
Python source (有注解) / .pyi stub
    ↓  MypycRunner
.so / .pyd  (原生 C 扩展)
```

## Converter 设计模式

采用与 outline 模块一致的 **注册表分发器模式**：
- `PyConverter`：抽象基类，持有共享注册表 `Map<String, PyConverter>`，提供 dispatch/helper 方法
- 每种 Python AST 节点类型对应一个独立的 `PyConverter` 子类
- `PythonGCPConverter`：纯注册表，将节点类型名映射到对应 Converter，无业务逻辑
- 扩展方式：只需新增一个 Converter 类并在注册表中 `put` 即可

---

## P0 — 最高优先级（直接影响类型推断精度）

| 节点类型 | 说明 | 状态 |
|----------|------|------|
| `AugAssign` | `x += 1` → `x = x + 1`，修复循环累积变量类型推断 | ✅ 已完成 |
| `Subscript` | `lst[i]` → `ArrayAccessor`，解锁集合元素类型 | ✅ 已完成 |
| `IfExp` | `a if cond else b` → 真正的 `TernaryExpression`（之前只返回 body 分支） | ✅ 已完成 |
| Tuple 解包赋值 | `a, b = f()` → `TupleUnpackNode`，支持多返回值 | ✅ 已完成 |

---

## P1 — 高优先级（扩大可推断代码范围）

| 节点类型 | 说明 | 状态 |
|----------|------|------|
| `ListComp` / `DictComp` / `SetComp` / `GeneratorExp` | 推导式：Python 最常用的高性能模式 | ✅ 已完成 |
| `Lambda` | 匿名函数，HOF 场景（sorted/map/filter） | ✅ 已完成 |
| 函数体内 `AnnAssign` | 局部带注解变量类型丢失问题 | ✅ 已完成 |
| `For` 循环变量类型 | `for x in lst` 中 `x` 的类型推断 | ✅ 已完成 |
| 默认参数推断 | `def f(x=0)` 中 `x: int`，无需 call context | ✅ 已完成 |

---

## P2 — 中等优先级

| 节点类型 | 说明 | 状态 |
|----------|------|------|
| `NamedExpr` 海象运算符 `:=` | `n := expr` → `VariableDeclarator` + 返回标识符；`Compare`/`BinOp` 传播 parent | ✅ 已完成 |
| `Starred` 展开赋值 | `a, *rest = lst` → rest 获得 `list` 类型；独立 `VariableDeclarator` 声明 | ✅ 已完成 |
| `*args` / `**kwargs` 签名类型 | `rewriteArgs` 剥离 `*`/`**` 前缀后匹配类型，保留前缀输出 | ✅ 已完成 |
| `Assert` 类型收窄 | `assert isinstance(x, int)` → GCP 类型收窄 | 待实现 |
| `Try/Except` handler 变量 | `except ValueError as e:` 中 `e: ValueError` | 待实现 |

---

## P3 — 进阶模式（常用 Python 习惯用法覆盖）

| 节点类型 | 说明 | 状态 |
|----------|------|------|
| `enumerate` / `zip` in `For` | `for i, x in enumerate(range(n))` → `i: int, x: int`；`for a, b in zip(l1, l2)` → `a: T1, b: T2`；range 嵌套直接返回 int | ✅ 已完成 |
| `Assert isinstance` 类型收窄 | `assert isinstance(x, int)` → 声明 `x: int`；与 call-site inference 协同 | ✅ 已完成 |
| `Try/Except` handler 变量 | `except ValueError as e:` → 声明 `e: ValueError`；orelse/finalbody 均 dispatch | ✅ 已完成 |
| `match/case` 模式匹配 | Python 3.10+：`MatchConverter` + `MatchAs`/`MatchStar`/`MatchSequence`/`MatchMapping` 绑定变量 | ✅ 已完成 |

---

---

## P4 — 鲁棒性 + 内置函数推断

### P4-A：安全网（不崩溃保障）

| 节点类型 | 说明 | 状态 |
|----------|------|------|
| `Raise` | 异常抛出，直接 NoOp | ✅ 已完成 |
| `AsyncFor` | 异步 for 循环，NoOp | ✅ 已完成 |
| `AsyncWith` | 异步 with 块，NoOp | ✅ 已完成 |
| `Await` | await 表达式，NoOp | ✅ 已完成 |
| `Yield` | yield 表达式，P11 已升级为完整 `YieldConverter`（`Iterator[T]` 类型推导） | ✅ 已完成 |
| `YieldFrom` | yield from，P11 已升级为完整 `YieldFromConverter` | ✅ 已完成 |
| `JoinedStr` | f-string → `LiteralNode("")`，推断为 `str` | ✅ 已完成 |
| `FormattedValue` | f-string 内的插值节点，NoOp | ✅ 已完成 |
| `Slice` | `a[1:n]` 中的 slice 节点，NoOp（SubscriptConverter 已返回 array 类型） | ✅ 已完成 |

### P4-B：内置函数返回类型推断

| 内置函数 | 返回类型策略 | 状态 |
|----------|-------------|------|
| `len(x)` / `ord(c)` / `hash(x)` / `id(x)` | → `int` (`LiteralNode<Long>`) | ✅ 已完成 |
| `int(x)` | → `int` | ✅ 已完成 |
| `float(x)` | → `float` (`LiteralNode<Double>`) | ✅ 已完成 |
| `str(x)` / `chr(i)` / `repr(x)` / `hex` / `bin` / `oct` | → `str` (`LiteralNode<String>`) | ✅ 已完成 |
| `abs(x)` | → 与参数同类型（dispatch 第一个 arg） | ✅ 已完成 |
| `range(n)` as value | → `list[int]` (`ArrayNode([intLit])`) | ✅ 已完成 |
| `sum(iterable)` | → 迭代器元素类型（`ArrayAccessor(iter, 0)` / `intLit` for range） | ✅ 已完成 |
| `min(a, b)` / `max(a, b)` | 单参数 → 元素类型；多参数 → 第一个参数类型 | ✅ 已完成 |
| `sorted(x)` / `list(x)` / `reversed(x)` / `tuple(x)` | → 与输入集合同类型 | ✅ 已完成 |

---

## P5 — 内置类型方法返回类型推断

### P5-A：方法调用拦截（`CallConverter.tryMethodCall`）

GCP 的 `MemberAccessorInference` 对 Python 原始类型（`str`/`int`/`float`）的成员访问会报 `FIELD_NOT_FOUND`，
P5-A 在 `CallConverter` 中提前拦截，将方法调用替换为有类型的 GCP 节点，完全绕过 GCP 的方法查找。

| 方法 | 返回类型 | 状态 |
|------|---------|------|
| `count()` / `index()` / `find()` / `rfind()` / `rindex()` / `bit_length()` | `int` | ✅ 已完成 |
| `startswith()` / `endswith()` / `is*()` / `issubset()` 等 | `bool` | ✅ 已完成 |
| `upper()` / `lower()` / `strip()` / `replace()` / `join()` / `format()` 等 | `str` | ✅ 已完成 |
| `split()` / `rsplit()` / `splitlines()` 等 | `list[str]` | ✅ 已完成 |
| `pop()` | 接收者元素类型（`ArrayAccessor(recv, 0)`） | ✅ 已完成 |
| `append()` / `extend()` / `sort()` / `clear()` 等变更方法 | `None` | ✅ 已完成 |

**关键价值**：`str.count()/find()` 等方法返回 `int`，使后续整型累积链 `total += c` 能够推断出 `total: int` 和函数返回类型 `-> int`，进而让 mypyc 生成全局优化的原生代码。

---

## P6 — 跨文件/跨模块类型推断 🔴 最高优先级

**问题**：`ImportConverter` 和 `ImportFromConverter` 目前是 NoOp，`from utils import helper` 后调用 `helper(x)` 全部返回 `UNKNOWN`。真实项目 90% 的代码都是多文件的。

| 任务 | 说明 | 状态 |
|------|------|------|
| `ModuleLoader` 接口 + `ModuleLoaderAdapter` | 将 ModuleLoader 注入共享 converters map（key `__module_loader__`），零侵入所有现有 converter 构造函数 | ✅ 已完成 |
| `ImportFromConverter` 自动触发模块加载 | 处理 `from X import Y` 时调用 loader，将 X 的 GCP AST 合并入同一 ASF | ✅ 已完成 |
| `PythonInferencer` 多文件支持 | `registerModule(name, src)`、`inferFile` 文件系统 loader、`inferWithContext` 集成 | ✅ 已完成 |
| E2E 测试 | `cross_module_inference_gives_speedup`：`from utils import fast_sum` → wrapper 函数推断 `n: int`，断言 ≥ 3× 加速 | ✅ 已完成 |

---

## P7 — 用户自定义类方法推断 🟡 第二优先级

**问题**：P5-A 只处理内置类型方法（硬编码方法名→返回类型）。用户自定义类 `class Foo:` 的方法调用 `foo.bar()` 仍然返回 `UNKNOWN`。

| 任务 | 说明 | 状态 |
|------|------|------|
| `ClassDefConverter` 补充方法注册 | 无需改动——已将 class body 的方法 dispatch 为顶层函数，GCP 符号表可见 | ✅ 已完成 |
| `CallConverter.tryMethodCall` 扩展 | P7 反糖化：`obj.method(args)` → `method(obj, args)`，将接收者作为 `self` 传递，GCP 可完整推断返回类型 | ✅ 已完成 |
| E2E 测试 | `class_method_inference_gives_speedup`：`h.fast_sum(n)` → `n: int`，断言 ≥ 3× 加速 | ✅ 已完成 |

---

## P8 — 真实项目基准测试 🟡 第三优先级

**问题**：目前所有 E2E 测试都是人工设计的"最优"场景（纯整数循环）。真实项目的代码混合了字符串处理、IO、自定义类、多文件依赖，实际加速可能只有 2-5×。

| 任务 | 说明 | 状态 |
|------|------|------|
| 选取开源 Python 纯计算库 | 如 sympy 核心算法、networkx 路径算法或自定义矩阵库 | ⬜ 待实现 |
| 运行管道统计覆盖率 | 有多少函数推断出参数类型/返回类型 | ⬜ 待实现 |
| 测量整体项目加速比 | 对比 3× 目标 | ⬜ 待实现 |
| 识别瓶颈，指导 P6/P7 | | ⬜ 待实现 |

---

## P9 — dict 类型感知 ✅ 已完成

**问题**：`dict.get(key)` 返回 `value | None`，`dict[key]` subscript 返回 value 类型，目前都返回 `UNKNOWN`。

| 任务 | 说明 | 状态 |
|------|------|------|
| `DictConverter` 提取 key/value 类型 | `DictNode.addNode()` 修复 + `DictNodeInference` 已有实现 | ✅ 完成 |
| P5-A 扩展 `dict.get(key)` | `CallConverter` 转为 `ArrayAccessor`，通过 `ArrayAccessorInference.inferDict` 返回 value 类型 | ✅ 完成 |
| `dict.keys()/values()/items()` 返回类型 | `MemberAccessorInference` 通过 `Dict.loadBuiltInMethods()` 处理 | ✅ 完成 |
| 类型传播到参数注解 | `PythonAnnotationWriter.propagateCallSiteTypes` + `TypeAnnotationGenerator` dict 支持 | ✅ 完成 |
| E2E 对比测试 | `ConverterE2ETest.dict_type_inference_gives_speedup`：GCP 加速 2.36×，GCP/bare = 2.42× | ✅ 完成 |

---

## P11 — `yield` / Generator 类型推导 ✅ 已完成

**问题**：Generator 函数（包含 `yield` 语句）的返回类型为 `Iterator[T]`，但之前 `Yield`/`YieldFrom` 均被映射为 NoOp，GCP 无法识别生成器语义，mypyc 因缺少类型注解而无法优化消费者循环。

| 任务 | 说明 | 状态 |
|------|------|------|
| `GeneratorYieldNode` 标记类 | 继承 `ArrayNode`，零侵入 GCP 推断逻辑，可通过 `instanceof` 检测 | ✅ 完成 |
| `YieldConverter` | `yield expr` → `ReturnStatement(GeneratorYieldNode([expr]))`，提取产出类型 | ✅ 完成 |
| `YieldFromConverter` | `yield from iter` → `ReturnStatement(GeneratorYieldNode([iter[0]]))`，传播迭代器元素类型 | ✅ 完成 |
| `ExprStatementConverter` 透传 parent | 将 parent 传递给 `dispatch`，使 Yield/YieldFrom 可直接操作函数体 | ✅ 完成 |
| `TypeAnnotationGenerator` 生成器检测 | `isGeneratorFunction()` 扫描函数体的 `GeneratorYieldNode`；`functionReturnType()` 输出 `Iterator[T]` 而非 `list[T]` | ✅ 完成 |
| `PyConverter.buildTypeNode` 扩展 | `Iterator[T]`/`Iterable[T]`/`Generator[T,...]` 注解解析为 `ArrayTypeNode` | ✅ 完成 |
| `PythonAnnotationWriter` 注入 `Iterator` import | `rewrite()` 检测 `Iterator[` 注解时自动 prepend `from typing import Iterator` | ✅ 完成 |
| `TypeAnnotationGenerator` stub 添加 `Iterator` import | 生成 `.pyi` 时若检测到 `Iterator[` 则添加 `from typing import Iterator` | ✅ 完成 |
| 单元测试 | 4 个 yield 推导测试（`yield i` from range → `Iterator[int]`，直接参数 yield → `Iterator[float]`，源文件注解注入，`yield from` 生成器识别） | ✅ 完成 |
| E2E 对比测试 | `yield_generator_gives_speedup`：GCP `Iterator[int]` 注解 → mypyc 编译 → **3.6×–4.1× 加速**（vs CPython），GCP/bare = **3.2–3.8×** | ✅ 完成 |

**性能结果**：
- `sum_via_gen(1000)`: CPython 34971 ns → mypyc bare 29938 ns (1.17×) → mypyc GCP **9628 ns (3.63×)**
- `sum_squares_via_gen(1000)`: CPython 42237 ns → mypyc bare 39490 ns (1.07×) → mypyc GCP **10341 ns (4.08×)**

**限制**：间接生成器元素类型（`for v in list_param: yield v`）因 Genericable 未直接关联参数，无法推导 `[T]` 类型参数（输出 `Iterator` 而非 `Iterator[int]`）；直接参数 yield（`yield param`）可正确推导。

---

## P10 — `PythonAnnotationWriter` 支持 `list[T]` 参数注解 🟢 低优先级

**问题**：参数注解只写简单类型（`n: int`），无法写 `data: list[int]`。导致 mypyc 看不到集合参数的元素类型，无法优化 `data[i]` 操作。

| 任务 | 说明 | 状态 |
|------|------|------|
| `TypeAnnotationGenerator` 支持 `Array<T>` → `list[T]` | `outlineToTypeStr` 新增对 `Genericable.min()` 的解析，当 min 已解析为具体类型时委托递归 | ✅ 已完成 |
| 参数注解写入时支持泛型集合 | `augmentFromCallSites` 路径通过 call-site 轮廓传播 `Array<int>` → `list[int]` | ✅ 已完成 |
| 验证 `subscript` 测试提升 | 新增 `list_parameter_annotation_gives_speedup` E2E 测试，断言 ≥ 5× 加速 | ✅ 已完成 |

---

## 忽略的特性（过于动态，暂不考虑）

| 特性 | 原因 |
|------|------|
| `eval()` / `exec()` | 运行时任意代码，静态分析无意义 |
| `getattr(obj, varname)` 动态字段 | 字段名是变量，无法静态解析 |
| `type()` 动态创建类 | 运行时元类型 |
| `__dict__` 直接操作 | 绕过类型系统 |
| `**kwargs` 多态分发 | 无法静态确定参数结构 |
| `globals()` / `locals()` 操作 | 动态符号表 |
| `importlib` 动态导入 | 运行时模块加载 |
| 改变函数签名的 `@decorator` | 类型变换不可预测 |
| `__getattr__` / `__missing__` 重写 | 隐式动态分发 |
| `typing.cast()` | 用户断言，不携带推断信息 |
| `Protocol` 结构子类型 | 需要完整协议相容性检查 |
| `dataclasses.fields()` / `namedtuple` 动态 | 运行时反射构造 |
| 递归类型（前向引用字符串形式）| GCP Lazy 机制与 Python 格式不同 |

---

## GCP 需扩展的语法特性（为适配更多 Python/JS）

| 特性 | 说明 | 优先级 |
|------|------|--------|
| `Subscript` 索引操作 | `arr[i]` → `ArrayAccessor`（已有节点，需完善推断） | P0 |
| `for-in` 迭代器协议 | 从 `Array<T>` 提取元素类型 `T` | P1 |
| 异构 Tuple 类型 | `Tuple[int, str]` → 位置类型而非统一元素类型 | P1 |
| `async/await` 返回类型包装 | `async def` 返回 `Promise<T>` | P1 |
| Generator 类型 | `yield` → `Generator[T, None, None]` | P2 |
| `isinstance` 类型守卫 | 类型收窄传播 | P2 |
| `TypedDict` 支持 | dict with typed keys → GCP `Entity` | P2 |
| `Literal` 类型 | `Literal['GET', 'POST']` → GCP `LiteralUnion` | P2 |
| `Final` 常量 | `x: Final[int] = 42` → `CONST` VariableKind | P2 |
| 字符串方法类型感知 | `s.split()` → `list[str]` | P2 |
| 函数重载 `@overload` | → GCP `Poly` 类型 | P3 |
| 模式匹配 `match/case` | Python 3.10+ → GCP `MatchExpression` | P3 |
| Walrus `:=` 作用域穿透 | 绑定穿透语义 | P3 |
