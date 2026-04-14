# Python -> GCP 转换方案与架构

## 一、目标

把“无注解 Python 源码”转成“可推断、可注解、可编译优化”的中间表示，核心链路是：

1. Python 源码解析成 JSON AST
2. JSON AST 转成 GCP AST
3. 在 ASF 上做类型推断
4. 回写 Python 注解（源码或 `.pyi`）
5. 使用 mypyc 编译并做性能验证

## 二、端到端流水线

```text
Python source (untyped)
  -> PythonAstBridge (py_ast_dump.py subprocess)
Python JSON AST
  -> PythonGCPConverter (registry + converters)
GCP AST
  -> ASF.infer()
Typed GCP AST
  -> TypeAnnotationGenerator / PythonAnnotationWriter / FunctionSpecializer
Annotated source or .pyi
  -> MypycRunner
Native extension / benchmark result
```

## 三、核心设计

### 1) 注册表分发架构（可扩展）

- `PythonGCPConverter` 只做“节点类型 -> Converter”注册，不写业务转换逻辑
- 每个 Python AST 节点对应一个 `PyConverter` 子类
- 新增语法支持时，新增 converter + 在注册表 `put` 即可

优点：

- 节点职责清晰
- 改动隔离
- 支持增量演进（先 NoOp，再增强）

### 2) 共享上下文与模块联动

- 多个 converter 共享同一注册表
- `ModuleLoaderAdapter` 以隐藏注册键注入，支持 `ImportFrom` 自动加载并转换其他模块
- 所有模块 AST 挂到同一 `ASF`，做联合推断

### 3) 推断与注解分离

- 推断阶段：`ASF.infer()`
- 注解阶段：
  - `TypeAnnotationGenerator` 负责类型字符串生成
  - `PythonAnnotationWriter` 负责把类型落回源码/参数签名
  - `FunctionSpecializer` 处理函数特化相关策略

这样可以独立演进“推断正确性”和“注解输出风格”。

## 四、关键入口类

- `PythonInferencer`
  - infer string / file
  - register module
  - infer with usage context（调用点类型传播）
- `PythonGCPConverter`
  - 节点注册中心
  - 模块加载器挂载点
- `PythonAstBridge`
  - Python AST 子进程桥接
- `MypycRunner`
  - 编译与运行封装

## 五、测试策略

三层测试：

1. **Converter 级**：节点语义正确（单特性）
2. **Inferencer/Writer 级**：推断与注解联动正确
3. **E2E 性能级**：`CPython vs mypyc(bare) vs mypyc(GCP)` 对比

当前主力回归入口是 `ConverterE2ETest`，用于验证“特性支持是否真的产生推断与性能收益”。
