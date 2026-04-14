# Python 语法特性覆盖总结

本文回答两个问题：

1. 已经转换了哪些 Python 语法特性（当前代码里真实存在的能力）
2. 这些特性的验证依据是什么（主要测试入口）

## 一、已支持特性（已实现转换）

以下来自 `PythonGCPConverter` 的节点注册与对应 converter 实现：

### 1) 声明与赋值

- `FunctionDef` / `AsyncFunctionDef`
- `ClassDef`
- `AnnAssign`
- `Assign`
- `AugAssign`（如 `x += 1`）

### 2) 控制流与语句

- `If`
- `For`
- `While`
- `With`
- `Try` / `TryStar`
- `Assert`（含 `isinstance` 收窄路径）
- `Match`（Python 3.10+）

### 3) 表达式

- `Name` / `Constant`
- `BinOp` / `BoolOp` / `UnaryOp` / `Compare`
- `Call` / `Attribute`
- `IfExp`（三元表达式）
- `Subscript`（如下标访问 `x[i]`）
- `NamedExpr`（海象运算符 `:=`）

### 4) 集合与推导式

- `List` / `Tuple` / `Dict` / `Set`（`Set` 走数组语义）
- `ListComp` / `SetComp` / `GeneratorExp`
- `DictComp`

### 5) 生成器与字符串

- `Yield`
- `YieldFrom`
- `JoinedStr`（f-string）

### 6) 导入与跨文件推断

- `Import`
- `ImportFrom`
- `ModuleLoader` 机制（用于 `from x import y` 自动加载模块并合并同一 ASF 推断）

## 二、降级支持（NoOp 安全兜底）

以下节点目前采用 NoOp，以保证流水线不崩溃：

- `Pass` / `Delete` / `Global` / `Nonlocal` / `Break` / `Continue`
- `Raise` / `AsyncFor` / `AsyncWith` / `Await`
- `FormattedValue`
- `Slice`

说明：这些语法在当前阶段以“可通过、不崩溃”为主，后续按收益再升级为强语义转换。

## 三、验证依据（测试侧）

主要测试集中在以下文件：

- `src/test/java/.../ConverterE2ETest.java`
  - 针对 AugAssign / Subscript / IfExp / 推导式 / Lambda / NamedExpr / Match / Assert / Yield 等做端到端性能与正确性验证
- `src/test/java/.../PythonInferencerTest.java`
  - 推断入口、跨文件推断、注解生成联动验证
- `src/test/java/.../AnnotationWriterTest.java`
  - 注解写入与类型传播验证

## 四、当前边界

- 该覆盖清单以 `PythonGCPConverter.registerAll()` 的真实注册为准。
- `PLAN` 中的长期项不等于已落地能力；是否已落地以 converter + 测试双证据为准。
