# AGENTS.md

本仓库内所有 AI agent（包括人在环）协作的统一约定。包含两部分：**SDD 工作流规范** 和 **agent 通用约定**。

---

## 一、SDD（Spec-Driven Development）工作流

### 核心原则

> **任何非平凡改动，先有 spec，再有代码。**

- "非平凡"判定：涉及多文件、跨模块、有 API/行为变更、或可能影响现有测试的改动。纯文档修正、单行 typo 不强制走 SDD。
- spec 是 living document：实施过程中如果发现 spec 不准确，**先改 spec，再改代码**，保证 spec 与实现一致。

### 工作流（每个任务）

```
1. PROPOSE  — 写 spec：目标、范围、验收标准、风险、回滚方案
2. REVIEW   — spec 经过确认（人/owner）
3. IMPLEMENT— 按 spec 实施，期间如偏离则回头改 spec
4. VERIFY   — 按 spec 的验收标准逐条核对（编译、测试、文档）
5. CLOSE    — 在 roadmap 文档勾选状态，补"实现笔记"
```

### Spec 必须包含的字段

| 字段 | 说明 |
|---|---|
| ID | 形如 `A1`、`B2`，与 roadmap 对应 |
| 标题 | 一句话目标 |
| 背景与动机 | 为什么要做 |
| 范围（In Scope） | 明确要做的事 |
| 不做（Out of Scope） | 明确不做的事，避免范围蔓延 |
| 验收标准 | 可客观核对的条目（编译通过、测试通过、文件清单、行为契约） |
| 风险 | 已知风险与缓解 |
| 回滚方案 | 出问题如何退回 |
| 依赖 | 前置任务 ID |

### 验收标准写作要求

- **可客观核对**：避免"性能更好"这类主观描述；用"编译产出字节码版本 21"、"测试 N/0/0 通过"等可断言形式。
- **明确测试范围**：写清跑哪些测试（`mvn test -pl ohc-core` / 全量 / 某个测试类）。
- **明确文档同步项**：如果任务涉及 README/CHANGES/构建文档，必须在验收标准里列出。

### Roadmap 文档维护

- 唯一的进度真相源：`docs/jdk21-migration/jdk21-migration.md`
- 每个任务有状态：`⬜ 未开始` / `🟡 进行中` / `✅ 完成` / `⛔ 阻塞`
- 任务完成后必须更新 roadmap：勾选状态 + 补"实现笔记"（关键改动、踩过的坑、偏离 spec 的地方）
- 不在对话里维护进度，避免漂移。

---

## 二、Agent 通用约定

### 仓库与代码风格

- 保持与现有代码风格一致：4 空格缩进、`final class` 默认、包私有优先、`org.caffinitas.ohc.*` 包名结构保留。
- **不擅自重构**：碰到不熟悉的代码或疑似用户改动，先调查上下文再动，不确定时先问。
- 修改 `.java` 时关注是否触发 deprecation 警告链（如 `finalize()`、`sun.misc.Unsafe`），能顺手清理的归入对应阶段任务，不越阶段。
- 改 pom/构建配置时，XML 注释内**禁止出现双连字符 `--`**（会破坏 XML 解析）。需要描述 flag 时用单字符替代（如 `add-exports` 而非 `--add-exports`）。

### 构建与测试

- 编译目标：JDK 21（`<source>21</source><target>21</target>`）。
- 验证命令优先级：
  1. `mvn clean compile -pl ohc-core -am`（快速验证编译）
  2. `mvn test -pl ohc-core`（功能验证，含 3 个 surefire execution）
  3. `mvn clean install -DskipJmh`（全模块，含打包；`-DskipJmh` 避免 install 时自动跑全套 JMH）
- 平台编码已通过 `.mvn/jvm.config` 强制 UTF-8，agent 跑 Maven 命令无需额外加 `-Dfile.encoding`。
- ohc-benchmark / ohc-jmh 模块已恢复（C1）。`mvn install` 会触发 ohc-core 的 javadoc 生成，与编译期一样需要 `--add-exports java.base/sun.nio.ch=ALL-UNNAMED`（已配在 javadoc plugin）；不要在只跑 compile/test 时误以为 install 也安全。
- JMH 基线 uberjar：`ohc-jmh/target/ohc-jmh-<version>-benchmarks.jar`；运行需 `--add-exports java.base/sun.nio.ch=ALL-UNNAMED`（chunked 路径）。性能护栏见 `docs/jdk21-migration/baseline-unsafe.md`。

### 版本控制与提交

- 提交信息前缀：`[A1]`、`[B2]` 等，与 spec ID 对齐。
- 单次提交尽量对应单个 spec 任务或其子步骤；跨任务的改动拆成多个提交。
- 不在主线直接做大改动，复杂任务用 feature 分支或 worktree。

### 系统模块访问（过渡期约束）

- 代码仍依赖 `sun.nio.ch.DirectBuffer`（chunked 实现）和 `sun.misc.Unsafe`（linked + chunked 实现，B1 迁移中）。
- 编译期与 surefire 运行期都需要 `--add-exports java.base/sun.nio.ch=ALL-UNNAMED`，已在 pom 配置好，**不要移除**，直到 chunked（B1）完成。
- **B 方案（JDK 21 + preview）**：FFM（`java.lang.foreign`）在 21 是 preview（JEP 442；22 才 final），故 compiler/javadoc/surefire/JMH 均须带 `--enable-preview`。preview 与 `--release` 互斥 → **必须用 `<source>/<target>21`，不能用 `<release>`**（B1 完成后亦然，直到放弃 preview 即迁到 22）。
- 后果（已知并接受）：产出的字节码锁定 JDK 21 特性版本、所有消费方需 `--enable-preview`、不可按常规库发 Maven Central。

### 已知问题与处理边界

| 问题 | 归属 | 处理时机 |
|---|---|---|
| `TimeoutsTest.testGetWithLoader` flaky | X1 | ✅ 已修：放宽时序窗口 + 用确定性 `f.get(超时)` 替代 `f.get(0)`（时序敏感测试的通用修法） |
| `sun.misc.Unsafe` deprecation 警告 | B1 | FFM 迁移时统一消除 |
| `finalize()` deprecated for removal 警告 | 清理项 | 后续（B4 或独立任务） |
| `ohc-benchmark` / `ohc-jmh` 模块禁用 | C1 | B1 完成后重新启用并重跑基线 |

### 沟通

- 不确定的事先问，不要替 owner 做决策（尤其涉及 API 变更、依赖增删、版本号调整）。
- 给出选项时附推荐项 + 理由，但最终由 owner 拍板。
- 失败要如实报告：编译/测试失败时贴完整错误，不省略；flaky 要标注为 flaky，不掩盖。
