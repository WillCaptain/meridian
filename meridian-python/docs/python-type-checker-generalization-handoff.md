# Meridian Python 类型检查通用化：评估与交接报告

## 1. 交接目标

下一会话的目标不是继续优化 TypeEvalPy 分数，而是把 Meridian 建设为可用于真实
Python 项目的通用类型推断与注解工具：

1. GCP 继续作为语言无关的通用类型推断引擎。
2. Python converter 负责把 Python AST 正确降级为 GCP AST。
3. Python semantic refiner 负责表达 GCP 与 Python 之间无法一一映射的语义。
4. `infer`、`stub`、`sites` 必须共享同一套 Python 推断结果。
5. TypeEvalPy 只用于验证能力，不得决定推断规则或生产架构。

核心判断标准：

> 一条规则只有在不看 benchmark 文件名、模板名、ground truth、固定变量名和固定样例值时，
> 仍能由 Python 语义或可追踪的数据流证明，才可以进入生产推断路径。

## 2. 当前仓库基线

- 仓库：`/Users/imac/Documents/code/github/meridian`
- 分支：`main`
- 交接提交：`cde23e5`
- 提交标题：`Restore Python harness and clear TypeEvalPy micro 513/513.`
- 当前 TypeEvalPy 记录：`513/513 EXACT`
- 当前 exporter：约 4100 行
- 构建命令：

```bash
mvn -q -B -f pom.xml -pl meridian-python -am package -DskipTests
```

- exporter 测试：

```bash
mvn -B -pl meridian-python test -Dtest=TypeEvalPySiteExporterTest
```

- TypeEvalPy micro 重跑：

```bash
python3 scripts/run-typeevalpy-micro-progress.py
```

注意：513/513 的实际度量对象是：

```text
Python source
  → PythonGCPConverter
  → GCP inference
  → TypeEvalPySiteExporter.enrichFromPythonAst
  → soft locator matching
  → manifest comparison
```

它不是生产 `infer`/`stub` 路径的端到端得分。

## 3. 当前架构与关键断层

### 3.1 生产注解路径

`MeridianCli.writeAnnotated` 与 `writeStub` 使用：

```text
PythonInferencer.inferFile
  → PythonGCPConverter.convert
  → asf.infer
  → PythonAnnotationWriter / TypeAnnotationGenerator
```

它们不调用 `TypeEvalPySiteExporter`，因此 exporter 中约 3000 行 Python refinement
当前不会改善生产注解。

### 3.2 benchmark sites 路径

`MeridianCli.writeSites` 使用：

```text
PythonInferencer.inferFileDetailed
  → TypeEvalPySiteExporter.collect(ast, fileName, pyAst, sourcePath)
  → enrichFromPythonAst
```

这意味着当前代码把三类责任混在一个类里：

- Python 语义 refinement；
- TypeEvalPy FR/FP/LV 站点生成；
- TypeEvalPy locator/命名兼容。

### 3.3 目标架构

建议拆成：

```text
Python source
  → PythonAstBridge
  → PythonGCPConverter
  → GCP inference
  → PythonSemanticRefiner
  → PythonInferenceResult
       ├─ PythonAnnotationWriter
       ├─ TypeAnnotationGenerator
       └─ TypeEvalPySiteExporter
```

建议的新边界：

- `PythonSemanticRefiner`
  - 输入：GCP AST、Python AST、模块解析上下文；
  - 输出：统一的符号、作用域、类型、容器元素、调用结果和导入摘要；
  - 不知道 TypeEvalPy、FR/FP/LV、manifest 或 expected type。
- `TypeEvalPySiteExporter`
  - 只把 `PythonInferenceResult` 映射为 FR/FP/LV JSON；
  - 只处理 1-based column、site 名称和 benchmark 文件格式。
- `PythonAnnotationWriter`
  - 读取与 exporter 相同的 `PythonInferenceResult`；
  - 不再只依赖原始 GCP outline。

## 4. Converter 层评估

### 4.1 总体结论

`converter/`、`PythonGCPConverter` 和 `PythonInferencer` 总体是通用的
Python → GCP lowering，未发现按 benchmark 文件路径、模板名或 `func1`/`func2`
直接分支的代码。

可保留的主干包括：

- module、name、constant、assignment、return；
- function、lambda、class 基础 lowering；
- import/module loader；
- if/while/for、布尔和算术表达式；
- tuple/list/dict 字面量；
- `range` 元素为 `int`；
- `enumerate`、`zip` 的 binder witness；
- 默认参数字面量推断；
- `PyConverter.listOf` / `strOf` 的 null-safe 修改。

### 4.2 必须先修的明确 benchmark 特化

`BinOpConverter.java:29-34` 把所有 `BitOr` 无条件转成：

```java
left.merge(right)
```

代码注释明确说明 micro benchmark 依赖该行为，并假设不会出现整数 bitwise-or。
这不符合通用 Python：

- `int | int` 是按位或；
- `set | set` 是集合 union；
- `dict | dict` 是字典 merge；
- 用户对象可以实现 `__or__`；
- `|=` 当前又走另一条 `BITWISE_OR` 路径，前后不一致。

建议：

1. 不再在 converter 中根据 benchmark 假定 operand 类型。
2. 为 GCP 增加 overload/operator dispatch，或根据已知 receiver outline 分派。
3. `|` 和 `|=` 共用同一个 Python operator resolution。

### 4.3 重要通用能力缺口

这些不是 benchmark gaming，但会影响真实 Python：

- `dict[K, V]` 注解被擦成裸 `Dict`；
- `Union[A, B, C]` 只处理前两个成员；
- `Set` 被按 list/array 建模；
- comprehension 只处理第一个 generator，忽略 `if` 和嵌套 generator；
- slice 被当作 element accessor；
- `with cm as x` 没有绑定 `x`；
- `isinstance(x, (A, B))` 只取第一个类型；
- sequence/mapping pattern binder 得到整个 subject 类型，而非元素类型；
- `In`、`NotIn`、`Is`、`IsNot` 缺少比较 lowering；
- `yield` 被近似为 return；
- f-string 没有 dispatch 插值表达式；
- builtin/method witness 表缺少 receiver-sensitive overload。

### 4.4 TypeEvalPy location 泄漏

以下逻辑只影响位置元数据，但不应留在 converter：

- `FunctionDefConverter` 为 TypeEvalPy 把 location 移到函数名；
- `PyConverter.functionNameCol` 直接引用 TypeEvalPy GT；
- class 名列偏移也按 site 需求计算。

建议将这类坐标映射移入 `TypeEvalPySiteExporter`，converter 保留 Python AST 原始位置。

## 5. Harness / exporter 层评估

### 5.1 可以通用化并保留的规则

以下规则有 Python AST 或数据流证据，应迁移到 `PythonSemanticRefiner`：

- 字面量、容器和 nominal constructor 类型；
- 调用点实参 → 参数类型约束；
- `int|float` 返回与具体数值实参的窄化；
- tuple/list/starred unpack binder 对齐；
- `for x in range(...)` → `x: int`；
- dict/list 字面量的常量 key/index 元素类型；
- `self.attr = rhs` 属性绑定；
- `self.attr = self.method` 的委托绑定；
- receiver 类型已知时的 `Class.method` 解析；
- 明确 import alias 下的模块/类限定；
- `return other_func` / returned callable 的有向数据流；
- 只有 GCP 类型弱于新证据时才更新类型。

保留条件：

- 必须按作用域、调用点和程序顺序索引；
- 不允许扫描全模块后取第一个同名/同后缀 symbol；
- 不允许从任意 sibling method 猜返回类型；
- 不允许无数据流证据地 `forceUpsert`。

### 5.2 P0：必须删除或重写

#### 固定默认 key `'a'`

`specializeCallReturn` 和 second-chance pass 会把零参多态调用映射到
`d['a']()`，并在 FR 包含 `str` 时收窄为 `str`。

问题：

- 没有读取函数签名中的真实默认参数；
- `'a'` 来自具体 micro 样例；
- 会扫描模块内无关 dict element 的 `callReturns`。

通用替代：

1. 读取被调用函数参数的真实 default AST；
2. 解析该参数在函数体内索引的具体 dict；
3. 只沿该 callee 的数据流解析对应 key；
4. 无证据时保持 union/unknown。

#### 返回 dict/list 时发明元素

当前逻辑包括：

- list 返回时固定生成 `[0]`、`[1]`，类型为 `int`；
- dict 无法投影时固定生成 `['a']: str`；
- slice 上界未知时曾使用魔法长度；
- 某些 comprehension source 被默认视为 `int` 元素。

通用替代：

- 只有 callee 返回字面量或 container outline 含元素类型时才投影；
- 长度未知时不发明固定 index；
- key 未知时使用 `Dict[K,V]` 的 V，而不是固定 key；
- 元素未知时输出 container 类型，不伪造 element site。

#### 模块级 first-match

当前多处通过以下方式猜类型：

- `endsWith("." + methodName)` 后取第一个 FR；
- 同 class prefix 找第一个 non-callable sibling；
- 找不到时扫描全模块第一个 concrete FR；
- foreign modules 中按 tail 名称 first-hit；
- 变量同名时取 sites 中第一个绑定。

这会在真实项目的同名方法、继承、重绑定和多模块环境中系统性误报。

通用替代：

- 作用域 + symbol id；
- SSA/程序点可见绑定；
- receiver nominal type + MRO；
- import alias → 精确模块；
- returned callable 的显式 data-flow edge。

#### 无依据强制类型

必须移除：

- `AugAssign` 未知类型默认 `int`；
- `.pop()` 默认 `str`；
- 未知 assignment 默认 `callable`；
- 未知 walrus 默认 `str`；
- “所有参数是 int”因此返回 int；
- 未知 BinOp operand 因此结果为 int；
- 未注解 lambda 参数/返回默认 int；
- returned lambda 固定 `int|float`。

正确行为：

- 有证据则约束；
- 无证据则 `Unknown`/`Any`/未解类型变量；
- 不为提升 benchmark EXACT 而选择具体类型。

### 5.3 P1：需要作用域和数据流重写

- method lookup 必须结合 receiver 类型；
- import lookup 必须结合 import alias；
- call-site 类型必须绑定到具体 call node，而不是函数名全局合并；
- `forceUpsertLv` 只能用于更强的、有来源的新证据；
- structural guess 不应覆盖注解或 GCP 已有具体类型；
- sibling module summary 应复用正式 `ModuleLoader`，不要自己扫描目录重建一套解析器。

### 5.4 只属于 exporter 的逻辑

以下可保留，但必须与类型推断分离：

- TypeEvalPy 的 1-based column；
- FR / FP / LV JSON schema；
- `Class.method` 的展示名称；
- benchmark 对 `__init__` 的别名要求；
- nested element site 的 locator 选择；
- TypeEvalPy vocabulary（`Nonetype`、`callable`、泛型擦除）。

这类代码只能改变输出表示，不能产生或覆盖类型。

## 6. 评分器与 513/513 的可信边界

### 6.1 Ground truth 不进入 GCP

manifest 没有传给 `PythonInferencer` 或 exporter，因此 GT 不直接修改 GCP AST。

### 6.2 Ground truth 会影响 site 配对

`scripts/run-typeevalpy-micro-progress.py` 在同 locator 多候选时，会用
`expected_types` 选择类型匹配的候选 site。

这不会改写 `site["type"]`，但属于 oracle-assisted pairing，会抬高 EXACT。

后续应同时报告两种分数：

- `strict`: 只按确定性 `(file,line,col,kind,symbol)` 配对；
- `compat`: 允许 TypeEvalPy locator compatibility，但不得按 expected type 选 site。

禁止将 oracle-assisted 分数称为生产 Python 类型推断 SOTA。

### 6.3 当前声明不一致

评分脚本生成的 JSON claim 曾声明：

```text
Scoring never uses ground-truth types to pick sites.
```

但实际实现会使用 expected type 消歧。下一会话应首先修正文档与 JSON，
避免错误 claim。

## 7. 建议实施顺序

### 阶段 A：建立诚实基线

1. 增加 strict scorer，完全禁止 expected-type pairing。
2. 保存当前 compat 结果，不删除历史。
3. 增加真实 Python regression corpus：
   - 多类同名方法；
   - 不同模块同名函数；
   - `int/set/dict/custom __or__`；
   - `list[str]`、异构 list、未知长度 list；
   - 任意 dict key；
   - lambda 返回 str/bool/object；
   - `pop()` 在 list/dict/custom class；
   - `AugAssign` 在 int/str/list；
   - 重绑定、shadowing、nested scopes；
   - `with`、slice、match、comprehension、generators。

### 阶段 B：拆分语义层

1. 新建 `PythonInferenceResult`：
   - scoped symbol table；
   - type constraints；
   - call results；
   - container element types；
   - attributes/methods；
   - import/module identities；
   - source locations。
2. 新建 `PythonSemanticRefiner`。
3. 从 exporter 迁移所有有 Python 语义依据的规则。
4. `infer`、`stub`、`sites` 全部消费同一结果。
5. exporter 中留下纯序列化和 locator compatibility。

### 阶段 C：消除 P0/P1 启发式

优先级：

1. `BitOr` operator dispatch；
2. 固定 `'a'`、固定 `[0]/[1]`、固定 `int/str`；
3. first-match method/foreign lookup；
4. returned callable 的显式数据流；
5. receiver-sensitive builtin/method typing；
6. 正式 module loader/stub/type environment；
7. n-ary union 与 container generics。

### 阶段 D：生产注解验证

每项能力同时验证：

1. GCP/refiner 的结构化结果；
2. `meridian infer` 的注解；
3. `meridian stub`；
4. `meridian sites` strict score；
5. 对未注解真实项目运行 mypy/pyright compatibility；
6. mypyc 性能与编译成功率。

## 8. 下一会话的第一批任务

建议下一会话不要直接修改 4100 行 exporter，而按以下小步开始：

1. 新增 strict scorer 并记录当前 strict 分数。
2. 为 `BitOr` 写四组 regression tests，然后修 converter。
3. 为以下错误猜测各加负例测试：
   - list 返回 `str` 元素；
   - dict key 不是 `'a'`；
   - lambda 返回 `str`；
   - `AugAssign` 操作 str/list；
   - 两个 class 有同名 method；
   - 两个 module 有同名 function。
4. 提取最小 `PythonSemanticRefiner` 接口。
5. 首先迁移“调用点参数约束”和“容器元素投影”两组通用规则。
6. 让 `PythonAnnotationWriter` 消费迁移后的结果。

每次迁移都必须满足：

- 生产路径行为改善；
- strict score 不靠 expected-type pairing；
- 新增真实 Python 正例和负例；
- 无硬编码 benchmark symbol/value；
- 原 exporter 规则迁移后删除，避免双重推理。

## 9. 不可违反的原则

1. 不以保持 513/513 为重构验收条件。
2. 不读取 manifest 或 expected type 决定推理。
3. 不使用固定变量名、函数名、key、index 或模板结构。
4. 不用“第一个匹配 symbol”代替作用域和数据流。
5. 不把 Unknown 强制变成具体类型。
6. 不让 exporter 成为第二个类型检查器。
7. 不让 TypeEvalPy location 约定污染 converter。
8. 每条 refinement 都需要 Python 语义依据和负例测试。

## 10. 风险与保护措施

当前 exporter 是从历史会话 transcript 重放约 214 次编辑恢复的。应避免再次整体覆盖：

- 重构前创建专用 feature branch；
- 先提交当前基线；
- 小提交迁移，每次可构建、可测试；
- 不用整文件 rewrite；
- 保留 `513/513` compat 结果作为历史 artifact；
- strict score 与生产注解测试作为新的主要 gate。

当前提交已推送至 `origin/main`，但本报告本身尚未提交。

## 11. 交接完成定义

通用化工作完成至少应满足：

- `TypeEvalPySiteExporter` 不再包含类型猜测；
- 所有 Python refinement 由共享 semantic layer 实现；
- `infer`、`stub`、`sites` 对同一 symbol 给出一致类型；
- scorer 不用 expected type 选择预测 site；
- `BitOr`、container、lambda、scope、imports 有真实负例覆盖；
- Unknown 保持 Unknown；
- strict benchmark 分数可重复；
- 生产 Python corpus 的注解正确率和 mypyc 编译率有独立报告。

