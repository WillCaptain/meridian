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
| `ListComp` / `DictComp` / `SetComp` / `GeneratorExp` | 推导式：Python 最常用的高性能模式 | 待实现 |
| `Lambda` | 匿名函数，HOF 场景（sorted/map/filter） | 待实现 |
| 函数体内 `AnnAssign` | 局部带注解变量类型丢失问题 | 待实现 |
| `For` 循环变量类型 | `for x in lst` 中 `x` 的类型推断 | 待实现 |
| 默认参数推断 | `def f(x=0)` 中 `x: int` | 待实现 |

---

## P2 — 中等优先级

| 节点类型 | 说明 | 状态 |
|----------|------|------|
| `*args` / `**kwargs` 基础类型 | 至少推断为 `list[Any]` / `dict[str, Any]` | 待实现 |
| `NamedExpr` 海象运算符 `:=` | Python 3.8+ | 待实现 |
| `Starred` 展开 | `f(*args)` 调用中的展开参数 | 待实现 |
| `Assert` 类型收窄 | `assert isinstance(x, int)` → GCP 类型收窄 | 待实现 |
| `Try/Except` handler 变量 | `except ValueError as e:` 中 `e: ValueError` | 待实现 |

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
