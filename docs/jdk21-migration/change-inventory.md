# 变更清单总览（Change Inventory）

> 把本次 OHC → JDK 21 演进 + 安全修复过程中做过的**全部改动**梳理成一份清单，按类别归类，并标注**是否可发布（无 preview 依赖）**与所在分支。
> 进度/验收细节仍以 `jdk21-migration.md` 为真相源；本文件是"做过哪些改动"的索引。

## 分支现状

| 分支 | 基于 | 内容 | 可发布性 |
|---|---|---|---|
| `master` | — | 原始 fork 0.9.2（Java 8 目标、旧依赖、原 Unsafe 实现） | ✅ 基线 |
| `fix/s1-oob` | master | **仅** S1 安全修复（guard + 回归测试），1 提交 `5901c76` | ✅ 可发布（无 preview，Java 8 目标实测通过） |
| `feature/b1-ffm` | master | A1–A4 / X1 / X3 / C1 / B1a·b·c / S1 | 🟡 含 B1 preview，整支不可常规发布；其中除 B1 外均可发布 |

**关键结论**：S1（漏洞修复）不依赖任何迁移工作，已在 `fix/s1-oob` 作为纯净可发布补丁独立成立。`feature/b1-ffm` 里**除 B1（FFM/preview）外**的文档、pom、依赖、测试类修复，都可单独并入 master。

> **⚠️ 决策更新（2026-11-14，owner）**：定调整个 FFM 计划 `feature/b1-ffm` **合入 master**（不再只挑可发布子集）。即接受 master 变为 `--enable-preview` 制品。原"落地建议（只并可发布子集）"作废。安全修复已在 SDD 三处记录：`jdk21-migration.md`、`change-inventory.md`、**`CHANGES.txt` 的 Security 小节**。

---

## 一、文档类（全部可发布，无 preview 依赖）

| 文件 | 改动 | 关联 |
|---|---|---|
| `AGENTS.md`（新） | SDD 工作流规范 + agent 通用约定 | SDD 体系 |
| `docs/jdk21-migration/jdk21-migration.md`（新） | 路线图/进度/各任务 spec + 实现笔记（真相源） | SDD 体系 |
| `docs/jdk21-migration/baseline-unsafe.md`（新） | Unsafe 性能基线 + **同会话背靠背 A/B 方法论** | C1 |
| `docs/jdk21-migration/baseline-unsafe-ohcbenchmark.txt`（新） | OHCBenchmark 基线原始输出 | C1 |
| `docs/jdk21-migration/baseline-unsafe-allocator.txt`（新） | AllocatorBenchmark 基线原始输出 | C1 |
| `docs/jdk21-migration/change-inventory.md`（本文件，新） | 全量变更梳理清单 | 梳理 |
| `README.rst` | Java 8/11→21；Requirements 如实标注 Unsafe/DirectBuffer；git clone→本 fork；benchmark jar 版本无关写法 + 模块禁用注记 | A4 |
| `BUILDING.rst` | 构建说明全量重写（JDK 21、--add-exports、模块状态、surefire 3 execution） | A4 |
| `CHANGES.txt` | 新增 `0.9.3 (unreleased)` 区块汇总 A1–A4+X1+X3 | A4 |

---

## 二、构建 / POM / 工具链类

### 可发布（无 preview）

| 文件 | 改动 | 关联 |
|---|---|---|
| `pom.xml`（父） | A1：source/target 1.8→21、14 个 Maven 插件升级、移除 asm 依赖管理；A3：6 依赖升级 + slf4j2 binding；X3：lz4 坐标→`at.yawk.lz4:lz4-java`；C1：modules 取消注释 | A1/A3/X3/C1 |
| `ohc-core/pom.xml` | A2：移除 `org.ow2.asm` 依赖；A3：`log4j-slf4j-impl`→`log4j-slf4j2-impl`；C1：javadoc 加 `--add-exports`（chunked 用 DirectBuffer 所需，非 preview） | A2/A3/C1 |
| `ohc-jmh/pom.xml` | C1：JMH 1.26→1.37、ohc-core groupId 修正、lz4/slf4j2 坐标、log4j-api 显式、exec `--add-exports`、去 `commantLineArgs` 拼写错误 | C1 |
| `ohc-benchmark/pom.xml` | C1：⚠️ 依赖从上游 `org.caffinitas.ohc:ohc-core:0.7.4`→本 fork `${project.version}`、slf4j2 binding | C1 |
| `.mvn/jvm.config`（新） | 强制 UTF-8，修 Windows 中文乱码 | A1 |
| `.gitignore` | 加 `dependency-reduced-pom.xml`（shade 产物）；含用户既有 `.vscode` | C1 卫生 |
| `ohc-benchmark/src/config/README.txt`（新） | assembly 所需目录占位 | C1 |

### 不可发布（绑定 B1 preview，勿并 master）

| 文件 | 改动 | 关联 |
|---|---|---|
| `pom.xml`（父） | `--enable-preview`（compilerArgs、surefire.jvmargs）、`--enable-native-access` | B1 |
| `ohc-core/pom.xml` | javadoc `--enable-preview` | B1 |
| `ohc-jmh/pom.xml` | compiler `--enable-preview`、exec 参数 | B1 |

> 注：pom 里"可发布子集"与"preview 子集"在父 pom 上交织——真要并 master 需把 `--enable-preview`/`--enable-native-access` 剥离。

---

## 三、代码类

### 可发布（非 preview）

| 文件 | 改动 | 关联 |
|---|---|---|
| `ohc-core/.../util/ByteBufferCompat.java` | **删除**（ASM 字节码桥接，source21 后多余） | A2 |
| 14 个 `.java`（linked/chunked channel、Uns、OHCache 实现等） | `byteBufferFlip(bb)`→`bb.flip()` 等直接调用（Java 9+ 返回类型已兼容） | A2 |
| `ohc-core/.../chunked/OffHeapChunkedMap.java` | `putEntry` 入口加不变量 guard 拒 `entryBytes > chunkDataSize` | S1（已在 fix/s1-oob 独立落地） |

### 不可发布（B1 FFM/preview，勿并 master）

| 文件 | 改动 | 关联 |
|---|---|---|
| `linked/Uns.java` | `directBufferFor`→`asByteBuffer`、`invalidate`→`limit(0)`、元素读写+数组版→ROOT segment+`_UNALIGNED`；原子留 Unsafe | B1a/B1c |
| `chunked/Uns.java` | 整类去 `sun.misc.Unsafe`+`sun.nio.ch`；`allocate/free`→`allocateAddress(long)/free(long)`、`directBufferFor`→`asByteBuffer`；`copyMemory`→`MemorySegment.copy` | B1a-chunked/B1b |
| `chunked/OffHeapChunkedMap.java` | memory/table 记录裸地址；`copyMemory` 用 `MemorySegment.copy` | B1a-chunked/B1b |
| `linked/BufferedReadable/WritableByteChannel`、`Compressing/DecompressingInputChannel`、`OHCacheChunkedImpl`、`OHCacheLinkedImpl` 等 | 随 A2 的直接调用改造（这部分可发布）；FFM 相关不可 | A2(可发布)/B1(不可) |
| `pom.xml` 三处 | `--add-exports` 是否保留（B1 后理论上可去，但测试仍需；见 roadmap） | B1 |

---

## 四、测试类（全部可发布，无 preview）

| 文件 | 改动 | 关联 |
|---|---|---|
| `linked/TimeoutsTest.java` | `testGetWithLoader` 放宽时序窗口 + 确定性 `f.get(超时)`，修 flaky | X1 |
| `chunked/ChunkedOversizedEntryTest.java`（新） | S1 回归：oversized entry 被拒、不越界崩溃（2 用例） | S1 |
| `chunked/UnsTest.java`、`chunked/HasherTest.java` | 适配 chunked `allocateAddress/free(long)`（仅 feature 分支需要；master 的 S1 测试用公共 API，不依赖） | B1a-chunked |
| `linked/UnsTest.java`、`chunked/KeyBufferTest/CheckOHCacheImpl` 等 | A2 后 `byteBufferXxx`→直接调用 | A2 |

---

## 五、依赖版本变更（A3 + X3，可发布）

| 依赖 | 旧→新 |
|---|---|
| slf4j-api | 1.7.32→2.0.20（binding `log4j-slf4j-impl`→`log4j-slf4j2-impl`） |
| log4j2 | 2.16.0→2.26.1 |
| testng | 7.4.0→7.12.0 |
| guava | 31.0.1-jre→33.8.0-jre（顺带缓解 Guava CVE-2026-102554） |
| jna | 5.10.0→5.19.1 |
| snappy-java | 1.1.8.4→1.1.10.12 |
| lz4 | `net.jpountz.lz4:lz4:1.3.0`→`at.yawk.lz4:lz4-java:1.13.0`（去废弃坐标） |
| asm | 9.2→9.6→**移除**（A2 后无引用） |

---

## 落地建议（待 owner 定）

**可发布子集**（并入 master 即可达成"安全 + 工程卫生"目标，且**不碰 preview**）：
- 全部 `fix/s1-oob`（S1 安全修复，已在独立分支）
- 文档类全部
- 构建/POM 类的"可发布"子集（A1 source/target21+插件、A3/X3 依赖、C1 模块恢复+坐标、.mvn、.gitignore）——**需剥离父/子 pom 里的 `--enable-preview`/`--enable-native-access`**
- 代码类的 A2（ByteBufferCompat 删除 + 直接调用）
- 测试类的 X1 + S1

**建议留在 feature/预研、暂不进 master**：
- B1 全部（FFM/preview 代码 + 相关 pom flag）
- 因 preview 不可常规发布；若要彻底去 Unsafe，正解是迁 **JDK 22**（FFM final）后作为 1.0

> 交织点提示：父 `pom.xml`、`ohc-core/pom.xml`、`ohc-jmh/pom.xml`、`linked/Uns.java`、`chunked/OffHeapChunkedMap.java` 同时含"可发布改动"和"B1 preview 改动"。要把可发布子集干净并入 master，需要逐文件剥离 preview 部分并验证 `mvn clean test`（Java 21 目标、无 preview、无 FFM）。
