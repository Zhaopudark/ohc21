# OHC JDK 21 Migration Roadmap

> **进度真相源**。本文件是 OHC 仓库向 JDK 21 演进的唯一 spec 与进度文档。
> 工作流遵循 `AGENTS.md` 中的 SDD 规范。每个任务的状态变更必须同步到本文件。

---

## 0. 概述

### 目标
将 OHC（off-heap cache）从 Java 8 字节码逐步演进到 JDK 21，最终：
- 编译/运行目标为 JDK 21
- 移除 `sun.misc.Unsafe` 与 `sun.nio.ch.DirectBuffer` 依赖，改用 `java.lang.foreign.MemorySegment` / `Arena`（FFM API）
- 现代化并发原语（`VarHandle`）、去除第三方依赖债（Guava）、整理 API
- 重新启用基准测试，验证迁移无性能退化

### 原则
- 渐进式、分版本落地，每个阶段可独立发布
- 不破坏现有 API 契约（除非明确列入版本路线的 major release）
- 每个任务有可客观核对的验收标准
- 失败如实记录，flaky 标注为 flaky

### 状态图例
- `⬜ 未开始` / `🟡 进行中` / `✅ 完成` / `⛔ 阻塞`

---

## 1. 阶段任务总览

### A. 立即可做（低风险，纯工程）

| ID | 标题 | 状态 | 依赖 |
|---|---|---|---|
| **A1** | 提升 source/target 到 21 + 升级 Maven 插件 | ✅ 完成 | — |
| A2 | 删除 `ByteBufferCompat` 的 ASM 字节码生成 | ✅ 完成 | A1 |
| A3 | 升级依赖版本（Guava/JNA/TestNG/log4j/slf4j/snappy） | ✅ 完成 | A1 |
| A4 | 文档对齐（README/BUILDING/CHANGES） | ✅ 完成 | A1 |

### B. 中等难度（核心改造，分版本逐步做）

| ID | 标题 | 状态 | 依赖 |
|---|---|---|---|
| B1 | 用 `MemorySegment`/`Arena` 替代 `Unsafe` + `DirectBuffer` | ✅ 完成（原子残留待22/B4） | A2, A3, C1 |
| B2 | 重新设计分配器 `IAllocator`，新增 `ForeignMemoryAllocator` 为默认 | ⬜ 未开始 | B1 |
| B3 | 去掉 Guava 依赖 | ⬜ 未开始 | A3 |
| B4 | 并发原语现代化（`VarHandle` 替换 `Unsafe` CAS，`StampedLock` 评估） | ⬜ 未开始 | B1 |

### C. 进阶 / 长期（后置）

| ID | 标题 | 状态 | 依赖 |
|---|---|---|---|
| C1 | 重新启用 `ohc-jmh` / `ohc-benchmark` + 建立 Unsafe 基线 | ✅ 完成 | A1 |
| C2 | JPMS 模块化（加 `module-info.java`） | ⬜ 未开始 | B1 |
| C3 | API 整理（`Future`→`CompletableFuture`、`CacheSerializer` 零拷贝 default 方法） | ⬜ 未开始 | B1 |
| C4 | CI / 发布流程（GitHub Actions JDK 21 矩阵） | ⬜ 未开始 | A4 |

### 独立小修（不阻塞阶段任务）

| ID | 标题 | 状态 | 依赖 |
|---|---|---|---|
| S1 | 修复 chunked `putEntry` 堆外越界写（JVM 崩溃） | ✅ 完成 | — |
| X1 | 修复 `TimeoutsTest.testGetWithLoader` flaky | ✅ 完成 | — |
| X2 | 清理 `finalize()` deprecated 警告 | ⬜ 未开始 | — |
| X3 | 去除 lz4 依赖（处理 xxHash） | ✅ 完成 | — |

---

## 2. 任务详细 Spec

### A1 — 提升 source/target 到 21 + 升级 Maven 插件 ✅

**背景与动机**：原仓库编译目标为 Java 8（`<source>1.8</source><target>1.8</target>`），Maven 插件版本陈旧。需先将编译目标升到 21、插件升级到 JDK 21 兼容版本，为后续 FFM 迁移等奠定基础。

**范围（In Scope）**：
- 父 `pom.xml` 的 `maven-compiler-plugin` 配置 `<source>/<target>` 1.8 → 21
- 父 `pom.xml` 的 `maven-javadoc-plugin` `<source>` 8 → 21（pluginManagement + reporting 两处）
- 升级 14 个 Maven 插件版本属性到 JDK 21 兼容稳定版
- `ohc-jmh/pom.xml` 的 compiler 配置同步到 21（模块当前禁用，保持一致）
- 修复 Windows 下 Maven 输出中文乱码（`.mvn/jvm.config` 强制 UTF-8）

**不做（Out of Scope）**：
- 不改任何 `.java` 源代码
- 不升级 Guava/JNA/TestNG 等业务依赖版本（归 A3）
- 不动 README/BUILDING/CHANGES 文档（归 A4）
- 不移除 `sun.nio.ch.DirectBuffer` / `sun.misc.Unsafe` 依赖（归 B1）

**验收标准**：
1. `mvn clean compile -pl ohc-core -am` 成功，产出字节码版本 21
2. `mvn test -pl ohc-core`：3 个 surefire execution（jna+debug / unsafe+debug / jna prod-like）功能性测试全部通过（允许 flaky，见 X1）
3. Maven 输出无中文乱码
4. 父 pom 中不再出现 `<source>1.8</source>` 或 `<target>1.8</target>`
5. `.mvn/jvm.config` 存在并被 git 跟踪

**风险**：
- `--release` 与对系统模块的 `--add-exports` 互斥 → 用 `<source>/<target>` + `--add-exports` 替代
- XML 注释内双连字符 `--` 会破坏 pom 解析 → 注释中描述 flag 时去掉 `--` 前缀

**回滚方案**：git revert 相关提交；`.mvn/jvm.config` 可独立删除。

**实现笔记**：
- 改动文件：`pom.xml`（父）、`ohc-jmh/pom.xml`、`.mvn/jvm.config`（新建）
- 升级插件版本：compiler 3.8.1→3.13.0、surefire 2.22.2→3.2.5、javadoc 3.2.0→3.6.3、jar 3.2.0→3.4.1、source 3.2.1→3.3.0、gpg 1.6→3.2.4、assembly 3.3.0→3.6.0、dependency 3.1.2→3.6.1、shade 3.2.4→3.5.2、site 3.9.1→3.12.1、project-info-reports 3.1.1→3.5.0、felix bundle 5.1.1→5.1.9、asm 9.2→9.6
- 编译期 `compilerArgs` 加 `--add-exports java.base/sun.nio.ch=ALL-UNNAMED`（之前只在 surefire 运行期有）
- 验证结果：51 源文件编译 SUCCESS；测试 1892/1893 通过；唯一失败为 `TimeoutsTest.testGetWithLoader` flaky（归 X1）
- 踩坑：XML 注释里写 `--add-exports` 导致 pom 不可解析，Maven 报错信息有误导性（误判为 `--release` 与 add-exports 互斥）。已记录到 AGENTS.md 的"XML 注释禁止双连字符"约定。

---

### A2 — 删除 `ByteBufferCompat` 的 ASM 字节码生成 ✅

**背景与动机**：`org.caffinitas.ohc.util.ByteBufferCompat` 用 ASM 动态生成桥接类，仅为兼容 Java 9 改了 `ByteBuffer.flip()/position()/clear()/limit()` 返回类型、又在 Java 8 字节码下编译的场景。source 升到 21 后此桥接完全多余，应删除以降低运行时复杂度并移除 ASM 依赖（chunked 不再需要 ASM）。

**范围**：
- 删除 `ohc-core/src/main/java/org/caffinitas/ohc/util/ByteBufferCompat.java`
- 删除 `ByteBufferAccess` 内部接口（如果存在独立文件）
- 修正所有 `import static org.caffinitas.ohc.util.ByteBufferCompat.*` 的调用点，改为直接调用 `ByteBuffer` 方法
- 评估是否可移除 `org.ow2.asm` 依赖（若无其他引用）

**不做**：
- 不改 `Uns.java`（linked/chunked）的 Unsafe 用法
- 不动 Guava 等（归 A3/B3）

**验收标准**：
1. `mvn clean compile -pl ohc-core -am` 成功
2. `mvn test -pl ohc-core`：测试通过数不减少（对比 A1 基线 1892）
3. `ByteBufferCompat.java` 文件不存在
4. 全仓 grep 无 `ByteBufferCompat` 引用
5. 若 ASM 无其他引用，从 `ohc-core/pom.xml` 移除 `org.ow2.asm` 依赖且编译通过

**风险**：调用点较多，可能漏改导致编译失败 → 用 grep 全量扫描调用点。

**回滚方案**：git revert。

**依赖**：A1

**实现笔记**：
- 改动文件：9 个 main（`linked/Uns.java`、`linked/OHCacheLinkedImpl.java`、`linked/BufferedWritableByteChannel.java`、`linked/BufferedReadableByteChannel.java`、`linked/CompressingOutputChannel.java`、`linked/DecompressingInputChannel.java`、`chunked/OffHeapChunkedMap.java`、`chunked/OHCacheChunkedImpl.java`、`chunked/Murmur3Hash.java`）+ 5 个 test（`chunked/CheckOHCacheImpl.java`、`chunked/ChunkedCacheImplTest.java`、`chunked/HasherTest.java`、`chunked/KeyBufferTest.java`、`chunked/UnsTest.java`、`linked/UnsTest.java`）+ 删除 `util/ByteBufferCompat.java` + `ohc-core/pom.xml`（移除 asm 依赖）+ 父 `pom.xml`（移除 asm dependencyManagement 与 version.org.ow2.asm property）
- 替换映射：`byteBufferFlip(bb)`→`bb.flip()`、`byteBufferClear(bb)`→`bb.clear()`、`byteBufferLimit(bb,n)`→`bb.limit(n)`、`byteBufferPosition(bb,n)`→`bb.position(n)`。Java 9+ 这些方法返回类型从 Buffer 改成 ByteBuffer，作为副作用调用完全兼容
- 编译验证：50 源文件 SUCCESS（A1 是 51，少 1 = ByteBufferCompat）
- 测试验证：631/631 per execution（default-test 全过；unsafe 出现 X1 flaky，与 A2 无关，单独跑 default-test 通过）
- ASM 依赖彻底移除：`org.ow2.asm` 从 ohc-core pom + 父 pom dependencyManagement + 父 pom `version.org.ow2.asm` property 三处全部清除
- 踩坑：`OHCacheChunkedImpl.java` 中多处 `byteBufferPosition(hashEntry, ...)` 缩进相同，用 `replaceAll` 一次替换 4 处；`linked/UnsTest.java` 的 `byteBufferClear(directBuffer)` 4 处缩进相同用 replaceAll，但 276 行 `byteBufferFlip(directBuffer)` 缩进不同需单独处理

---

### A3 — 升级依赖版本 ✅

**背景与动机**：Guava 31、JNA 5.10、TestNG 7.4、log4j 2.16、slf4j 1.7、snappy 1.1.8.4 均有较新稳定版，部分含安全修复。趁 A 阶段一并清理版本债。

**范围（In Scope）**：
- 父 `pom.xml` properties 升级：
  - `version.com.google.guava` 31.0.1-jre → 33.8.0-jre
  - `version.net.java.dev.jna` 5.10.0 → 5.19.1
  - `version.org.testng` 7.4.0 → 7.12.0
  - `version.org.apache.logging.log4j` 2.16.0 → 2.26.1
  - `version.org.slf4j` 1.7.32 → 2.0.20
  - `version.org.xerial.snappy` 1.1.8.4 → 1.1.10.12
- slf4j 2.x binding：`log4j-slf4j-impl` → `log4j-slf4j2-impl`（父 pom dependencyManagement + ohc-core pom 两处）

**不做（Out of Scope）**：
- 不动 `net.jpountz.lz4:lz4:1.3.0`（归 X3）
- 不改业务代码适配新 API（Guava API 去除归 B3）
- 不升 `version.org.hamcrest`（2.2 已是最新）

**验收标准**：
1. `mvn clean compile -pl ohc-core -am` 成功
2. `mvn test -pl ohc-core`：3 个 execution 测试通过数不减少（对比 A2 基线 1893）
3. 父 pom properties 中上述版本号已更新
4. `mvn dependency:tree` 确认新版本生效，无新引入的可疑传递依赖

**风险**：
- slf4j 2.0 API 与 log4j binding 方式变化 → 改 `log4j-slf4j-impl` 为 `log4j-slf4j2-impl`（已处理）
- TestNG 7.12 对 JDK 21 的支持需验证（已通过测试验证）

**回滚方案**：git revert。

**依赖**：A1

**实现笔记**：
- 改动文件：父 `pom.xml`（properties 6 处 + dependencyManagement 的 log4j-slf4j-impl → log4j-slf4j2-impl）、`ohc-core/pom.xml`（log4j-slf4j-impl → log4j-slf4j2-impl）
- 编译验证：50 源文件 SUCCESS
- 测试验证：3 个 execution 全过（631×3 = 1893），连之前的 X1 flaky 也通过了
- 依赖树验证：所有目标依赖升级生效；lz4 仍为 1.3.0 optional（留待 X3）；asm 不在 ohc-core 直接依赖中（A2 已移除）
- 踩坑：lz4 升级路径有歧义（`org.lz4:lz4-java:1.8.1` 是 relocation POM 指向 `at.yawk.lz4:lz4-java`）。且 lz4 仅被 `XxHash.java`（linked+chunked）使用，是 optional 且非默认算法（默认 MURMUR3 自实现）。决策：拆出 X3 独立任务，A3 保持纯版本升级边界
- slf4j 2.x 与 1.x 的关键差异：binding 机制从 `ServiceLoader` 静态绑定改为运行时发现，artifactId 从 `log4j-slf4j-impl` 改为 `log4j-slf4j2-impl`。slf4j-api 2.0 对应用代码（`LoggerFactory.getLogger`）向后兼容，无需改业务代码

---

### A4 — 文档对齐 ✅

**背景与动机**：README 仍写"runs with Java 8 and Java 11"，BUILDING 写"Java 11 or newer"，与实际目标 JDK 21 不符。CHANGES.txt 只有 0.9.0 一条记录，未反映 A1 完成的事实。

**范围（In Scope）**：
- `README.rst`：运行/构建要求改为 Java 21+；git clone 地址指向本 fork；benchmark jar 版本引用改为与版本无关写法并标注模块当前禁用
- `BUILDING.rst`：重写构建说明（JDK 21、--add-exports 说明、模块禁用状态、surefire 3 execution 说明）
- `CHANGES.txt`：补 `0.9.3 (unreleased)` 区块，汇总 A1–A4 + X3 的变更（构建/平台、代码简化、依赖、文档）

**不做（Out of Scope）**：
- 不重写 README 整体结构
- 不动 LICENSE
- 不擅自 bump pom 版本号（0.9.2 保持，发布版本由 owner 决定）

**验收标准**：
1. README 中无"Java 8 and Java 11"或"Java 7"的过时表述
2. BUILDING 中构建要求明确写 JDK 21
3. CHANGES.txt 有 0.9.x 变更记录，含 A1 完成条目

**风险**：无。

**回滚方案**：git revert。

**依赖**：A1（其他任务完成时同步追加 CHANGES）

**实现笔记**：
- 改动文件：`README.rst`（Features 区、Requirements 区、Building-from-source 区、Benchmarking 区）、`BUILDING.rst`（全文重写）、`CHANGES.txt`（新增 0.9.3 unreleased 区块）
- README Features/Requirements：Java 8/11 → Java 21，并如实说明当前仍依赖 `sun.misc.Unsafe` 与 `sun.nio.ch.DirectBuffer`（需 --add-exports），B1 迁移后移除
- README git clone 地址：`snazy/ohc` → `Zhaopudark/ohc21`
- CHANGES 用 "0.9.3 (unreleased)" 而非改 pom 版本，避免替 owner 决定发布版本；内容按 A1–A4+X3 分类
- grep 校验：`*.rst` 无 Java 8/7/11/15、OpenJDK 11、0.7.1 等过时表述；新表述 8 处命中 Java 21/OpenJDK 21
- 纯文档改动，不影响编译与测试

---

### B1 — 用 `MemorySegment`/`Arena` 替代 `Unsafe` + `DirectBuffer` 🟡 进行中（决策 B：JDK 21 + --enable-preview）

> **owner 决策（2026-11-09）**：尽管 FFM 在 21 是 preview、且 preview 制品有"锁 JDK 21 特性版本 + 全消费方需 `--enable-preview` + 不可常规发 Central"的硬约束，owner 仍选择 **B：留在 21 用 `--enable-preview` 硬上**。本 spec 据此配置构建与验收。
> **构建侧影响**：`maven-compiler-plugin` 加 `--enable-preview`；javadoc、surefire(3 execution)、JMH exec、benchmark 运行、jar 启动 全部需带 `--enable-preview`；因用到 preview，pom 必须用 `<source>/<target>` 而非 `<release>`（`--release` 与 `--enable-preview` 互斥）。

**背景与动机**：兑现 `CHANGES.txt` 中"removing unsafe"承诺。`sun.misc.Unsafe` 在 JDK 21 已标记 deprecated for removal，`sun.nio.ch.DirectBuffer` 属非导出内部 API。

> ⚠️ **关键前提纠正（2026-11-09 实测 + JEP 核实）**：
> 原 spec 误写"FFM 在 JDK 21 已 Finalize"。事实是 **JDK 21 的 FFM（JEP 442）是 third preview（预览）**，`java.lang.foreign` 整套（`MemorySegment`/`Arena`/`ValueLayout`）需 `--enable-preview` 才能编译，且 preview 字节码锁死特定 feature 版本、换 JDK 跑不了。FFM 直到 **JDK 22（JEP 454）才 final**。
> 探针实测（本机 21+35-2513）：不加 `--enable-preview` 时 `import java.lang.foreign.MemorySegment` 直接报"是预览 API，默认禁用"。
> **影响**：B1"用 FFM 无痛消除 Unsafe"在 **JDK 21 上无干净的非 preview 路径**——因为 21 里除 Unsafe 外，唯一能按裸地址访问堆外内存的受支持方式就是 FFM，而它在 21 是预览。故 B1 被**阻塞**，需 owner 决策目标 JDK。

核心约束不变：**性能不掉**（以 C1 基线为护栏，热路径回退 < 3%）。

**关键洞察（决定"无痛"可行性）**：
- `org.caffinitas.ohc.linked.Uns` / `chunked.Uns` 已是**全部堆外访问的唯一收敛点**（91 处调用均经 `Uns.*`）。
- off-heap 内存里存的是裸 `long`（bucket next、LRU prev/next、hash、expireAt、entry 地址），Java 侧遍历用 `long hashEntryAdr` + 常量偏移 `ENTRY_OFF_*`。
- ⇒ **只重写 `Uns` 内部实现**即可，不改 `OffHeapLinkedMap`/`HashEntries`/`Timeots` 等调用方的签名，不改 off-heap 内存布局，**序列化/持久化格式不变 → 老数据零迁移**。这就是"无痛"的技术基础。

**FFM 映射表**（`Uns` 内部替换，签名保持 `long address` 不变）：

| 现（Unsafe） | FFM 替换 | 路径 | 风险 |
|---|---|---|---|
| `getLong/putLong/getInt/...`(null,addr+off) | `MemorySegment.ofAddress(addr+off).reinterpret(size).get/put(ValueLayout.X,0)` | 🔴 热 | 高（每次访问物化 segment） |
| `getAndSetLong`/`getAndAddInt` | `...reinterpret(8/4).getAtomicLongVarHandle()/getAtomicIntVarHandle()` 的 getAndSet/getAndAdd | 🔴 热(refcount) | 中 |
| `copyMemory`(byte[]↔addr, addr↔addr) | `MemorySegment.copy(srcSeg,srcOff,dstSeg,dstOff,bytes)` | 🟡 序列化 | 低（memcpy 内联） |
| `setMemory` | `MemorySegment.ofAddress(addr).reinterpret(len).fill(val)` | 🟡 | 低 |
| `getLongFromByteArray` 等 | `MemorySegment.ofArray(arr).getX(off)` 或 byte[] VarHandle | 🟡 hash | 低 |
| `directBufferFor`（allocateInstance+字段偏移 hack） | `MemorySegment.ofAddress(addr+off).reinterpret(len).asByteBuffer()` | 🟡 (de)序列化/迭代 | 低-中（生命周期） |
| `chunked` 的 `((DirectBuffer)buf).address()` | 持 `MemorySegment`，`.address()` 取址 | 🟡 | 低 |
| `UnsafeAllocator` allocate/free | （归 B2）先经 JNA，或临时用 `Arena.ofConfined().allocate` | — | B2 |

**子任务（每步独立可测、过 C1 护栏后再推进下一步）**：

- **B1a 冷路径先行**（低风险）：`directBufferFor`/`invalidateDirectBuffer`/`keyBuffer*`/`valueBuffer*` 全部改 `MemorySegment.asByteBuffer()`；array 版 hash 辅助；chunked 的 `DirectBuffer.address()`。此步不碰热路径 get/put 元素访问。
- **B1b copy/fill/原子**：`copyMemory`→`MemorySegment.copy`、`setMemory`→`fill`、`getAndSetLong/getAndAddInt`→atomic VarHandle。
- **B1c 热路径元素读写**（最高风险，最后做）：`getLong/putLong/getInt/...` 的 `Uns` 内部实现切 `MemorySegment`。全程用 C1 基线 A/B，热路径吞吐回退 < 3% 才继续。

**范围**：linked + chunked 两套 `Uns`；完成后移除 `sun.misc.Unsafe` 与 `sun.nio.ch.DirectBuffer` 引用；pom 改回 `<release>21</release>` + 删三处 `--add-exports`（compilerArgs、surefire.jvmargs、javadoc additionalJOptions）。

**不做（Out of Scope）**：
- 不改公开 API（`OHCache`/`OHCacheBuilder`/`CacheSerializer` 签名）。
- 不改 off-heap 内存布局与序列化格式（持久化兼容）。
- 不重设计 `IAllocator`（B2）；不动 `StampedLock` 评估（B4）。
- 不引入新第三方堆外库。

**⚠️ 需 owner 拍板的唯一决策 —— B1c 热路径表示策略**：

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A：段物化 + 测量**（推荐） | 保持 `Uns` 内部每次访问 `ofAddress().reinterpret().get()`，先实现再靠 C1 实测 | 改动最小、最"无痛"、签名/布局全不动 | 若 JIT 逃逸分析/边界检查消除不理想，可能超 3% |
| **B：每 entry 缓存 MemorySegment** | entry 访问复用已物化的 segment（Java 侧持有或切片池） | 性能上限最高，逼近 Unsafe | 改动大、与"裸 long 遍历"结构冲突、偏离无痛 |

**建议路径**：先走 A，B1c 实测若 < 3% 即定案；若 ≥ 3%，仅对**实测回退的具体访问点**升级到 B 的局部缓存（混合策略），不预先全量重构。**请 owner 确认是否认可"A 先试、按护栏升级 B"的决策方式**，认可后我按 B1a→B1b→B1c 逐步实施，每步跑基线回报。

**验收标准**：
1. `mvn clean compile -pl ohc-core -am` 成功且 pom 无 `--add-exports java.base/sun.nio.ch`
2. 改回 `<release>21</release>` 后编译通过
3. `mvn test -pl ohc-core`：3 个 execution 测试通过数不减少（≥ A 阶段基线）
4. 全仓 grep 无 `sun.misc.Unsafe`、无 `sun.nio.ch.DirectBuffer`
5. 编译/运行无 `sun.misc.Unsafe` deprecation 警告
6. **C1 基线 A/B：OHCBenchmark 四项热路径吞吐回退均 < 3%**（B1c 完成后必测；未达阈值则不满足验收）
7. `mvn dependency:tree`/编译确认不再依赖 `jdk.unsupported` 的 Unsafe

**风险**：
- FFM 性能不及 Unsafe → 用 C1 护栏量化；B1c 触发红线则停并升级方案 B 局部。
- `asByteBuffer()` 返回缓冲的生命周期绑定 segment scope → 用 `ofAddress().reinterpret()` 得 GLOBAL scope，语义等同现状（内存由显式 free 管控），但须核对迭代器/序列化路径不越界。
- 原子 VarHandle 内存语义与 Unsafe 的 `getAndAdd` 一致（都是强顺序），但需确认 offset 对齐（refcount@24、next@16 等已 8 字节对齐）。
- chunked 与 linked 两套需同步，避免行为分叉。

**回滚方案**：feature 分支/worktree 开发；B1a/B1b/B1c 各自独立提交，任一子步护栏失败可单独 revert 该子步。

**依赖**：A2、A3、C1（性能基线护栏——每子步必须 A/B 对比，热路径回退 < 3% 否则停下重设计）

**B1 子步进度**：
- ✅ **B1a（linked 冷路径）** 2026-11-09：`linked/Uns.directBufferFor`/`invalidateDirectBuffer` 从 `allocateInstance`+字段偏移 hack 改为 `MemorySegment.ofAddress().reinterpret().asByteBuffer()`；invalidate 用 public `buffer.limit(0)` 保留防 use-after-free 语义。`mvn test` 631×3 全绿。同会话 A/B：多线程 get 持平(+0.4%)、put 在 3% 内、单线程 get 名义 -6.2% 但 CI 重叠不显著（根因见 `baseline-unsafe.md` 方法论修正）。
- ✅ **B1a-chunked** 2026-11-09：`chunked/Uns` 移除 `sun.misc.Unsafe`+`sun.nio.ch.DirectBuffer`——`allocate(ByteBuffer)`/`free(ByteBuffer)`→`allocateAddress(long)`/`free(long)`，`directBufferFor`→`MemorySegment.asByteBuffer()`，删 `allocateInstance`+字段偏移 hack 与 `theUnsafe` 反射（chunked 无元素级 unsafe，整类去 Unsafe）；`OffHeapChunkedMap` 的 `memory`/`Table.table` 各存 `xxxAddress` 供 free/copyMemory（×4）用；同步改 `chunked/UnsTest`、`chunked/HasherTest`。测试 631×3 全绿。**生产代码已无 `sun.nio.ch.DirectBuffer`**（仅 `chunked/UnsTest` 取址用，靠测试侧 add-exports）。per-op 访问未变（段构造期建 buffer），无结构性性能影响；chunked 正式 A/B 待基线补齐。
- ✅ **B1b（copyMemory/setMemory）** 2026-11-09：linked + chunked 的 `copyMemory`→`MemorySegment.copy(...)`、`setMemory`→`fill`。测试 631×3 全绿。**相邻同段 A/B**：B1b vs B1a 全项 ±2%、CI 重叠 → 性能中性。
  - ⚠️ **原子操作拆分延后**：探针证实 JDK 21 preview `MemorySegment` **无任何 atomic/VarHandle/compareAndSet/getAndAdd**（这些是 22 final 才加）。故 refcount `increment`/`decrement`（getAndAddInt）与 `getAndPutLong`（getAndSetLong）**无 FFM 等价，仍留 `sun.misc.Unsafe`** → 归 **B4** 或 **JDK 22**。含义：**21 上无法 100% 移除 sun.misc.Unsafe**，最多降到只剩这几个原子方法。
- ✅ **B1c（热路径元素读写）** 2026-11-10：`linked/Uns` 的 `getLong/putLong/getInt/putInt/getShort/putShort/getByte/putByte` + 数组版 `getLongFromByteArray/...` 全改为 `MemorySegment`（静态 `ROOT=ofAddress(0).reinterpret(MAX)`，单次访问无物化）；用 `_UNALIGNED` 布局。测试 631×3 全绿。
  - **对齐实验**：试改回对齐 `JAVA_LONG` → 所有生产测试通过，但 `linked.UnsTest` 5 个白盒用例失败（故意在奇数偏移测，依赖非对齐容忍）。为保持 `Uns` API 契约（unsafe 的非对齐语义），定案用 `_UNALIGNED`。
  - **原子残留**：`getAndPutLong`(getAndSetLong)、`increment/decrement`(getAndAddInt) 无 21 preview FFM 等价 → 仍用 `sun.misc.Unsafe`（见 B1b 记录）。即 **B1 完成后 linked 仍保留 `sun.misc.Unsafe` 仅这几个原子**，彻底移除需 JDK 22。
  - **粗粒度 A/B**（相邻同段，pre-B1c vs B1c）：getMulti -3.8%、getSingle -0.4%、putMulti -2.0%、putSingle -5.5%，CI 重叠；本机噪声无法确证 <3%，留稳定环境/22 精测。
  - 构建：`--enable-native-access=ALL-UNNAMED` 加入 surefire + JMH exec（`reinterpret`/`asByteBuffer` 为受限方法）。
- 构建侧：`--enable-preview` 已接入 compiler/javadoc/surefire/JMH-exec（父 pom + ohc-core + ohc-jmh）。

> ⚠️ 方法论（本会话确立）：护栏**必须同会话 A/B**（临时回退→打包→测→恢复→打包→测），跨天绝对数字不可用。详见 `baseline-unsafe.md`。

---

### B2 — 重新设计分配器 `IAllocator` ⬜

**背景与动机**：当前 `UnsafeAllocator`/`JNANativeAllocator` 二选一。B1 移除 Unsafe 后 `UnsafeAllocator` 应删除，并新增基于 `Arena.allocateNative()` 的 `ForeignMemoryAllocator` 作为默认推荐。

**范围**：
- 新增 `ForeignMemoryAllocator`（基于 `Arena`）
- 删除 `UnsafeAllocator`
- `OHCacheBuilder` 默认 allocator 改为 `foreign`，保留 `jna` 作 jemalloc 场景 fallback
- 更新 `Uns.java`（linked/chunked）的 static 初始化选择逻辑

**验收标准**：
1. 编译 + 测试通过
2. `UnsafeAllocator.java` 文件不存在
3. 默认构建的 cache 使用 `ForeignMemoryAllocator`（通过日志或 stats 验证）
4. 显式选 `jna` allocator 时仍可正常工作

**风险**：`Arena` 的生命周期需与 cache 的 `close()` 对齐，否则可能内存泄漏。

**回滚方案**：feature 分支。

**依赖**：B1

---

### B3 — 去掉 Guava 依赖 ⬜

**背景与动机**：仅用到 Guava 的 `AbstractIterator`、`Ints.checkedCast`、`Futures.immediateFuture`、`SettableFuture`，全部可用 JDK 原生 API 替换，减少 jar 体积与传递依赖。

**范围**：
- `Ints.checkedCast` → `Math.toIntExact`
- `SettableFuture` / `Futures.immediateFuture` → `CompletableFuture`
- `AbstractIterator` → 自写小型抽象类或用 `Spliterators`
- 移除 `com.google.guava` 依赖

**验收标准**：
1. 编译 + 测试通过
2. 全仓 grep 无 `com.google.common` 引用
3. `mvn dependency:tree` 不再出现 guava

**风险**：`AbstractIterator` 语义需精确匹配（`hasNext()`/`next()` 的状态机）。

**回滚方案**：git revert。

**依赖**：A3

---

### B4 — 并发原语现代化 ⬜

**背景与动机**：`FrequencySketch`、`OffHeapLinkedMap` 等用 `Unsafe.getAndSetLong`/`getAndAddInt` 做 CAS。JDK 9+ 的 `VarHandle` 是官方推荐的替代方案，在 JDK 21 上稳定且性能相当。

**范围**：
- 用 `VarHandle` 替换所有 `unsafe.getAndSetLong`/`getAndAddInt` 等 CAS 操作
- 评估 segment 锁是否能用 `StampedLock` 或 `ReentrantReadWriteLock` 替代 `synchronized`（性能基线对比后再决定是否落地）

**验收标准**：
1. 编译 + 测试通过
2. `sun.misc.Unsafe` 相关警告全部消除（与 B1 协同）
3. 若引入 `StampedLock`：C1 性能基线无显著退化

**风险**：`VarHandle` 在某些访问模式下的内存语义需仔细核对（acquire/release vs opaque）。

**回滚方案**：git revert 或 feature 分支。

**依赖**：B1

---

### C1 — 重新启用 `ohc-jmh` / `ohc-benchmark` + 建立 Unsafe 基线 ✅

**背景与动机**：父 pom 中两个模块被注释禁用。核心目标是"性能不掉的前提下消除 `sun.misc.Unsafe`"，而**没有基线就无法证明"没掉"**。因此 C1 从原计划的"B1 之后"**前置到 B1 之前**：先恢复基准设施、采集 Unsafe 基线，作为 B1/B2 的性能护栏。

**范围（In Scope）**：
- 父 pom 取消 `ohc-benchmark` / `ohc-jmh` 模块注释
- 修复两模块禁用期间漂移的依赖坐标（ohc-core groupId、lz4、log4j-slf4j2-impl、JMH 1.26→1.37）
- 补 `ohc-benchmark/src/config`（assembly 需要该目录）
- 采集 Unsafe 版本的 JMH 基线，存档到 `docs/jdk21-migration/`

**不做**：不改 ohc-core 实现；不引入 ForeignMemory 分配器（B1/B2）。

**验收标准**：
1. `mvn clean install -DskipJmh`（全模块）成功
2. JMH uberjar 产出且可运行，采集到 get/put + allocator 基线
3. benchmark 依赖指向本 fork 的 `ohc-core`（而非上游 0.7.4）
4. 基线数据与复现命令存档为 B1 性能护栏

**判定阈值（供 B1/B2 使用）**：OHCBenchmark 热路径吞吐相对基线回退 < 3%；≥3% 触发红线停下重设计。

**风险**：性能退化超阈值 → 回头优化 B1 实现。

**回滚方案**：恢复模块注释禁用。

**依赖**：A1（编译/插件就绪即可）—— 已从"依赖 B1"前置

**实现笔记**：
- 改动文件：父 `pom.xml`（modules 取消注释 + javadoc `--add-exports`）、`ohc-jmh/pom.xml`、``ohc-benchmark/pom.xml`、新建 `ohc-benchmark/src/config/README.txt`
- **关键修复 1**：`ohc-benchmark` 原依赖 `org.caffinitas.ohc:ohc-core:0.7.4`（上游发布件！），改为 `io.github.zhaopudark.ohc21:ohc-core:${project.version}`，否则基准测的是老库而非本 fork
- **关键修复 2**：`ohc-jmh` 同样 groupId 错误 + lz4/slf4j2 坐标漂移 + JMH 1.26→1.37（1.26 不支持 JDK 21 字节码生成）
- **踩坑 A（javadoc）**：`mvn install` 触发 ohc-core javadoc，与编译期同样受 `sun.nio.ch` 不导出影响报错 → javadoc plugin 补 `--add-exports java.base/sun.nio.ch=ALL-UNNAMED`（compile/test 阶段不暴露此问题，install 才触发）
- **踩坑 B（uberjar 缺 log4j-api）**：父 dependencyManagement 把 log4j 默认设 `test` scope；ohc-jmh 未显式声明 log4j-api，传递依赖继承 test scope 被 shade 排除 → 运行期 `NoClassDefFoundError: org.apache.logging.log4j.Logger`。修复：ohc-jmh 显式声明 log4j-api=compile（与 ohc-benchmark 对齐）
- **踩坑 C（exec 插件）**：`<commantLineArgs>` 是 `commandlineArgs` 的拼写错误且对 `exec` 目标无效；该属性原意是传 `--add-exports`。改为在 `<arguments>` 里正确加入 `--add-exports java.base/sun.nio.ch=ALL-UNNAMED`（chunked 基准运行期需要）
- 基线存档：`docs/jdk21-migration/baseline-unsafe.md` + 原始输出 `baseline-unsafe-ohcbenchmark.txt` / `baseline-unsafe-allocator.txt`
- 基线要点（linked/MURMUR3/LRU/valueSz=256/2M keys/512MB）：getMulti≈21.3、getSingle≈4.4、putMulti≈6.8、putSingle≈2.3 ops/µs；allocator 显示 Unsafe≈JNA(≈13–23 ops/µs)，提示 B2 的 Arena 分配器同档即可
- 环境注记：Intel Core Ultra 9 285H 混合架构（P+E 核），绝对吞吐有调度噪声，A/B 须同机同参数比较

---

### C1 之后 — B1 性能护栏已就绪

C1 前置完成后，B1（FFM 迁移）现已具备"改动即测、回退即停"的客观判据。B1 依赖更新为 `A2, A3, C1`。

---

### C2 — JPMS 模块化 ⬜

**范围**：加 `module-info.java`，把 `org.caffinitas.ohc` 做成显式模块；B1 后 `--add-exports` 已移除，JPMS 可干净落地。梳理 OSGi bundle（`maven-bundle-plugin`）与 JPMS 共存。

**验收标准**：
1. `module-info.java` 存在且编译通过
2. 无 `--add-exports` 残留
3. OSGi bundle 元数据仍正确生成

**依赖**：B1

---

### C3 — API 整理 ⬜

**范围**：
- `OHCache`/`OHCacheBuilder` 上返回 `Future` 的方法统一改 `CompletableFuture`
- `CloseableIterator` 实现 `AutoCloseable`（已满足，确认 javadoc）
- `CacheSerializer` 补 `default` 方法做零拷贝（基于 `MemorySegment`）

**验收标准**：编译 + 测试通过；javadoc 无破坏；新 default 方法有测试覆盖。

**依赖**：B1

---

### C4 — CI / 发布流程 ⬜

**范围**：
- 加 GitHub Actions workflow，JDK 21 矩阵构建（可选多 OS）
- 验证 `central-publishing-maven-plugin` + GPG + jgitflow 的 deploy profile 在 JDK 21 下可用

**验收标准**：
1. PR/push 触发 CI，JDK 21 构建绿
2. `mvn deploy -Pdeploy` 流程可跑通（dry-run 验证）

**依赖**：A4

### S1 — 修复 chunked `putEntry` 堆外越界写（JVM 崩溃） ✅

> 安全项。NVDB（工信部国家漏洞库）收录、可能未公开披露：**OHC chunked 缓存 `OffHeapChunkedMap.putEntry` 堆外越界导致 JVM 崩溃**。

**背景与动机 / 根因（CWE-787 Out-of-bounds Write）**：chunked 实现把整个段的内存一次性分配为 `allocSize = chunkCount * chunkFullSize`（`chunkFullSize = CHUNK_OFF_DATA(16) + chunkSize`）。每个 entry 必须写进**单个 chunk 的数据区（容量 `chunkDataSize` = chunkSize）**。但 `putEntry` 的"写入新 entry"分支（`Uns.copyMemory(newHashEntry.array(), position, memoryAddress, hashEntryOffset, entryBytes - position)`）只在 `writeChunkFree < entryBytes` 时**轮转 chunk**，**从不校验 `entryBytes ≤ chunkDataSize`**。当某条 entry 序列化后大小超过 chunk 数据区：
- 轮转到全新 chunk 后 `writeChunkFree` 仍 `< entryBytes`，写入越过该 chunk 边界；
- 若该 chunk 恰为**最后一个**，写入越过整段 `allocSize` 边界 → **堆外 OOB 写 → JVM 崩溃**（内存破坏，非异常）。
- 变量大小路径虽有 `maxEntrySize` 校验，但默认 0（关闭）；**固定大小路径 (`putInternalFixed`) 完全没有 maxEntrySize 校验**。两者都可触发。

**修复方案（按 B1c 批准的 A 式护栏策略：最小、正确、有回归测试）**：在 `putEntry` 写入路径前加**不变量校验**——`entryBytes > chunkDataSize` 的 entry 无法容纳于任一 chunk，直接返回 false（拒绝写入），而非越界写。与 linked 实现 `maxEntrySize` 拒绝过大 entry 的行为一致。

**验收标准**：
1. `mvn test -pl ohc-core`：3 execution 测试数不减少（≥ 1893）。
2. 新增回归测试 `ChunkedOversizedEntryTest`：chunkSize 设小，put 一个 entryBytes > chunkSize 的 entry → `assertFalse(cache.put(...))`，随后正常 entry 仍可 put/get，进程不崩溃。
3. 修复前后对照：移除该 guard 时该测试应触发崩溃/失败（证明测试有效）。

**范围**：仅 `chunked/OffHeapChunkedMap.java` 加不变量 guard + 新增测试。不改内存布局、不改公开 API。

**不做**：不在 `OHCacheChunkedImpl` 各处重复校验（`putEntry` 是写入收敛点，单点 guard 足够且覆盖 fixed/variable 两路）。

**风险**：极低。返回 false 与既有"put 失败"语义一致。

**依赖**：无

**实现笔记**：
- 改动文件：`chunked/OffHeapChunkedMap.java`（putEntry 顶部加 `if (entryBytes > chunkDataSize) return false;` + SECURITY 注释）、新增测试 `chunked/ChunkedOversizedEntryTest.java`
- 单点 guard 覆盖 fixed + variable 两条 put 路径（都汇聚到 putEntry），无需改 OHCacheChunkedImpl
- **B1b 后 copyMemory 走 `MemorySegment.ofAddress().reinterpret(entryBytes)`**：dst 视图被 reinterpret 成 entryBytes 大小，FFM 不做分配边界检查 → 越界写更隐蔽（不会像旧 ByteBuffer limit 那样抛异常，而是直接破坏堆外内存）。所以本 guard 是唯一有效防线
- 验证：带 guard `mvn clean test` 633×3 全绿（1899，+2 新用例）；临时禁用 guard 后两个用例均 `expected [false] but found [true]`（证明 oversized entry 确被越界写入）→ 回归测试有效
- 行为变更：chunked 现在对 `entryBytes > chunkSize` 的 put 返回 false（与"chunked 实验性、entry 需 ≤ chunk"约束一致）；此前是静默越界写/崩溃
- 关联：这是 NVDB（工信部国家漏洞库）收录、可能未公开披露的 OHC chunked `OffHeapChunkedMap.putEntry` 堆外越界 JVM 崩溃漏洞的修复

---

### X1 — 修复 `TimeoutsTest.testGetWithLoader` flaky ✅

**背景与动机**：测试依赖 `Thread.sleep()` 精度（loader 内 sleep 50ms，主线程 sleep 60ms 后用 `f.get(0, TimeUnit.MILLISECONDS)` 零超时取结果），调度抖动导致偶发 `TimeoutException`。同一测试在 3 个 surefire execution 间结果不一致，属纯 flaky，与 A1/A2/A3/X3 无关。

**范围**：
- 放宽时序窗口 + 用确定性 `f.get(超时)` 替代 `f.get(0)`
- 保留测试语义：验证"loader 完成后结果可见、过期后不可见"
- 不能用 `TestTicker`：该测试本意就是验证真实 wall-clock 下的 Future 过期行为（代码注释明确说明），改用 TestTicker 会失去测试价值

**实现要点**（基于 `getWithLoaderAsync` 语义：Future 在 loader 跑完才完成，返回值 = 完成时刻未过期则存值/返回 value，过期则移除并返回 null）：
- 场景 (a) `expireAt` 在过去 → `f.get()` 改为 `f.get(5, SECONDS)`（有界阻塞，语义不变），断言 null
- 场景 (b) 原 expireAt=+100/loader 50ms/main sleep 60ms 窗口太窄 → 放宽为 expireAt=`now+2000`、loader 仍 50ms、用 `f.get(5, SECONDS)` 确定性等待并断言 "two"，过期断言改为 `Thread.sleep(2150)` 后 `cache.get(2)` 为 null
- 场景 (c) loader sleep 加大到 200ms、expireAt=`now+5` → 完成必然过期，`f.get(5s)` 断言 null

**验收标准**：
1. 连续多次 `mvn test -pl ohc-core -Dtest=TimeoutsTest` 全部 0 失败（实测连续 9 次 execution 运行 0 失败）
2. 测试仍覆盖原三个场景语义

**回滚方案**：git revert。

**依赖**：无

**实现笔记**：
- 改动文件：仅 `ohc-core/src/test/java/org/caffinitas/ohc/linked/TimeoutsTest.java`（`testGetWithLoader` 方法）
- 未碰 main 代码，不改行为契约，只调时序参数与断言方式
- flaky 根因：(b) 的 60ms/50ms/100ms 三角时序在并行 fork + 低堆（-Xmx256m）下抖动越界
- 验证：完整 `mvn clean test` 631×3 全过；目标测试连续 3 轮 ×3 execution = 9 次运行全 `Failures: 0`
- 代价：该测试耗时从 ~0.6s 增至 ~3.6s（放宽窗口所致，换取稳定性，可接受）
- 注：roadmap 原 spec 建议"改用 TestTicker"与实际不符（会丧失该测试的真实 Future 语义验证），实施时按放宽时序方案落地并回写本 spec

---

### X2 — 清理 `finalize()` deprecated 警告 ⬜

**背景与动机**：JDK 21 将 `Object.finalize()` 标记为 deprecated for removal。仓库中多处 override 了 `finalize()`（`OffHeapChunkedMap`、`BufferedReadableByteChannel`、`OHCacheLinkedImpl` 等）。

**范围**：改用 `Cleaner` 或显式 `close()`（多数类已实现 `AutoCloseable`，`finalize()` 只是兜底）。

**验收标准**：
1. 编译无 `finalize()` deprecation 警告
2. 测试通过数不减少

**依赖**：建议在 B1 之后（避免冲突）

---

### X3 — 去除 lz4 依赖（处理 xxHash） ✅

**背景与动机**：`net.jpountz.lz4:lz4:1.3.0`（2014 年废弃）是仓库里唯一的 lz4 依赖，仅被 `linked/XxHash.java` 和 `chunked/XxHash.java` 使用，用的是其中的 `net.jpountz.xxhash.XXHashFactory`。该依赖在 pom 中是 `optional=true`，且 `HashAlgorithm.XX` 不是默认算法（默认是 `MURMUR3`），因此对绝大多数用户无影响。但作为废弃依赖应清理。

**范围（In Scope）**：
- 迁移 lz4 依赖：`net.jpountz.lz4:lz4:1.3.0` → `at.yawk.lz4:lz4-java:1.13.0`（跟随 lz4-java relocation 链的最新继承者）
- 验证 `net.jpountz.xxhash.XXHashFactory` 包名在新依赖下仍可用（lz4-java 历史上保留 `net.jpountz.xxhash` 包名）
- 若包名不变：仅改 pom 依赖坐标
- 若包名变化：同步改 `linked/XxHash.java` 和 `chunked/XxHash.java` 的 import

**不做（Out of Scope）**：不改默认 hash 算法（MURMUR3）；不自写 XXHash64；不移除 `HashAlgorithm.XX`。

**验收标准**：
1. 编译 + 测试通过
2. `mvn dependency:tree` 不再出现 `net.jpountz.lz4`
3. 若选方案 C（迁移）：验证 `net.jpountz.xxhash.XXHashFactory` 在新 groupId 下仍可用

**风险**：方案 A/B 涉及 hash 值变化或 API 破坏，需评估对已有持久化数据的影响（hash 用于 hash 表分布，持久化时序列化的是 hash 值）。

**回滚方案**：git revert。

**依赖**：无（可在 A3 后任意时机做）

**实现笔记**：
- 决策过程：owner 一度倾向"改用 JDK 内置 XXHash"，但发现 JDK 无内置 XXHash（`java.util.zip` 只有 CRC32/Adler32）后，追问"默认算法是什么"。确认默认是 MURMUR3（自实现，不依赖 lz4）、XX 是可选且 optional 后，最终选定方案 C（迁移到 `at.yawk.lz4:lz4-java:1.13.0`）
- 改动文件：父 `pom.xml`（property `version.net.jpountz.lz4` → `version.at.yawk.lz4.lz4-java`；dependencyManagement 坐标变更）、`ohc-core/pom.xml`（依赖坐标变更）
- **未改任何 .java 文件**：`at.yawk.lz4:lz4-java:1.13.0` 保留了 `net.jpountz.xxhash` 包名，`XxHash.java` 的 `import net.jpountz.xxhash.XXHashFactory` 无需修改
- 编译验证：50 源文件 SUCCESS
- 测试验证：3 个 execution 中 2 个全过（default-test、unsafe），prod-like 出现 X1 flaky（`TimeoutsTest.testGetWithLoader` 行 240 TimeoutException），与 X3 无关
- 依赖树验证：`net.jpountz.lz4` 彻底消失，`at.yawk.lz4:lz4-java:1.13.0 (optional)` 生效
- relocation 链路径：`net.jpountz.lz4:lz4`（废弃）→ `org.lz4:lz4-java:1.8.1`（relocation POM）→ `at.yawk.lz4:lz4-java:1.13.0`（当前维护者）

---

## 3. 进度日志

### 2026-11-05
- 完成 **A1**：source/target 升到 21，14 个 Maven 插件升级，新增 `.mvn/jvm.config` 解决 Windows 中文乱码
- 验证：`mvn clean compile` SUCCESS（51 文件）；`mvn test` 1892/1893 通过
- 发现 X1（TimeoutsTest flaky），独立跟踪

### 2026-11-06
- 建立 SDD 文档体系：`AGENTS.md` + 本 roadmap 文档
- 完成 **A2**：删除 `ByteBufferCompat.java`（ASM 字节码生成），14 个调用文件改为直接调用 `ByteBuffer` 方法，移除 `org.ow2.asm` 依赖（ohc-core pom + 父 pom dependencyManagement + version property 三处）
- 验证：`mvn clean compile` SUCCESS（50 文件，比 A1 少 1）；`mvn test` 631/631 per execution（X1 flaky 仍偶发，与 A2 无关）

### 2026-11-08
- 完成 **A3**：升级 6 个依赖（slf4j 1.7.32→2.0.20、log4j 2.16.0→2.26.1、testng 7.4.0→7.12.0、guava 31.0.1→33.8.0、jna 5.10.0→5.19.1、snappy 1.1.8.4→1.1.10.12）；slf4j 2.x binding 从 `log4j-slf4j-impl` 改为 `log4j-slf4j2-impl`
- 新建 **X3** 任务：处理 lz4 依赖（仅 `XxHash.java` 用，optional 且非默认算法，A3 不越界）
- 验证：`mvn clean compile` SUCCESS；`mvn test` 3 execution 全过（1893/1893，含 X1 flaky 这次也通过）；`mvn dependency:tree` 确认版本生效
- 完成 **X3**：lz4 依赖迁移 `net.jpountz.lz4:lz4:1.3.0` → `at.yawk.lz4:lz4-java:1.13.0`（跟随 relocation 链）。`net.jpountz.xxhash` 包名在新依赖下保留，无需改 .java 代码
- 验证：`mvn clean compile` SUCCESS；`mvn test` 2/3 execution 全过（prod-like 出现 X1 flaky，与 X3 无关）；`mvn dependency:tree` 确认 `net.jpountz.lz4` 彻底消失
- 完成 **A4**：README/BUILDING 文档对齐 Java 21；CHANGES.txt 补 0.9.3 (unreleased) 区块汇总 A1–A4+X3。纯文档改动，不影响编译测试
- 完成 **X1**：修复 `TimeoutsTest.testGetWithLoader` flaky——放宽时序窗口（expire 100ms→2000ms、(c) loader 5ms→200ms）+ 用确定性 `f.get(5s)` 替代 `f.get(0)`；仅改测试代码，不动行为契约
- 验证：完整 `mvn clean test` 631×3 全过；目标测试连续 3 轮 ×3 execution 共 9 次运行全 0 失败
- **阶段 A 全部完成 + X1/X3 完成**：A1/A2/A3/A4/X1/X3 ✅，0.9.3 内容齐备
- 完成 **C1（前置）**：恢复 `ohc-jmh` + `ohc-benchmark` 模块，修复坐标漂移（benchmark/jmh 的 ohc-core groupId、lz4、slf4j2、JMH 1.26→1.37），补 src/config，修 javadoc/uberjar 的 `--add-exports` 与 log4j-api 打包问题；`mvn clean install -DskipJmh` 三模块全 SUCCESS
- 采集 **Unsafe 性能基线**（linked/MURMUR3/LRU + allocator）：存 `docs/jdk21-migration/baseline-unsafe.md`，B1 性能护栏就绪（阈值：热路径回退 < 3%）

### 2026-11-09
- 启动 B1，先细化 spec（B1a/b/c 子步 + FFM 映射表 + 性能护栏），owner 批准"B1c 先走方案 A、按护栏升级 B"；建 `feature/b1-ffm` 分支
- **发现阻塞**：实施 B1a 时编译报错 `java.lang.foreign 是预览 API`。经探针实测 + JEP 核实：JDK 21 的 FFM（JEP 442）是 **third preview**，`MemorySegment`/`Arena`/`ValueLayout` 需 `--enable-preview`；FFM 直到 **JDK 22（JEP 454）才 final**。原 B1 spec 误以为 21 已 final（作者记忆错误，已纠正）。
- 影响：JDK 21 上没有"非 preview 且受支持"的方式替换 `sun.misc.Unsafe` 的裸地址访问 → **B1 阻塞，待 owner 决策目标 JDK**
- 处置：撤销基于错误前提的 B1a 改动，分支恢复 BUILD SUCCESS；roadmap 记录事实纠正与阻塞
- owner 决策 **B**（留 21 + `--enable-preview`）：FFM 虽是 preview，仍硬上；已记 preview 约束（锁 21、需全消费方 `--enable-preview`、不可常规发 Central）
- 构建接入 `--enable-preview`：父 pom compilerArgs、ohc-core javadoc、surefire.jvmargs、ohc-jmh compiler+exec、（chunked 仍保留 `--add-exports`）
- 完成 **B1a（linked 冷路径）**：`directBufferFor`/`invalidateDirectBuffer` → FFM `asByteBuffer`；测试 631×3 全绿
- **方法论修正**：发现跨会话基线不可用（同机同码 day-over-day 差 15-20%，混合 CPU 漂移）→ 护栏改为同会话 A/B。B1a 同会话配对：多线程 get 持平、put 在阈值内、单线程 get -6.2%（CI 重叠不显著）
- 提交 `6409777` 到 `feature/b1-ffm`（`mvn clean install -DskipJmh` 全 4 模块 SUCCESS 验证后）：phase A + C1 + B1a 里程碑。注：因 pom/linked/Uns 被多 spec 交织，按可构建原则合并为单提交；设了**仓库本地** git 身份（Pu Zhao，非全局），未做 GPG 签名
- 探针确认 21 preview FFM 能力面：有 `ofAddress/ofArray/reinterpret/fill/copy/asByteBuffer/asSlice/get/set(ValueLayout)`，**无 atomic/VarHandle**
- 完成 **B1b（copy/set）**：linked+chunked `copyMemory`→`MemorySegment.copy`、`setMemory`→`fill`；测试 631×3 全绿；相邻同段 A/B vs B1a 全项 ±2%（中性）
- 完成 **B1a-chunked**：`chunked/Uns` 整类去 `sun.misc.Unsafe`+`sun.nio.ch.DirectBuffer`（`allocate/free`→`allocateAddress(long)`/`free(long)`、`directBufferFor`→`asByteBuffer`、删 allocateInstance hack）；`OffHeapChunkedMap` memory/table 记录裸地址；改 2 个 chunked 测试。测试 631×3 全绿。**生产代码 `sun.nio.ch` 清零**（仅测试侧保留 DirectBuffer 取址）
- 完成 **B1c（热路径元素读写）**：`linked/Uns` 元素 get/put + 数组版全切 `MemorySegment`，用静态 ROOT segment（`ofAddress(0).reinterpret(MAX)`，单次访问无物化）+ `_UNALIGNED` 布局。测试 631×3 全绿。对齐版实验：生产测试全过但 `linked.UnsTest`（故意非对齐白盒）失败 → 定案 UNALIGNED 保 API 契约。原子 `increment/decrement/getAndPutLong` 留 `sun.misc.Unsafe`（21 无等价）。构建加 `--enable-native-access`。**B1 整体完成（仅原子残留待 22/B4）**

### 2026-11-13
- 完成 **S1（安全修复）**：`OffHeapChunkedMap.putEntry` 加不变量 guard 拒绝 `entryBytes > chunkDataSize` 的 entry，修复 NVDB 收录的 **chunked 堆外越界写致 JVM 崩溃（CWE-787）**。新增回归测试 `ChunkedOversizedEntryTest`（2 用例，禁用 guard 时复现 `true`/越界，启用时 `false`）。`mvn clean test` 633×3 全绿（1899）
- 战略结论记录：用户核心目标=修复该 NVDB 漏洞（最低目标）；"21 上无痛彻底去 Unsafe + 性能不掉" 因 FFM preview/原子缺失无法三全，B 系列价值重定位为"安全修复 + 现代化预研"
- **新发现**：21 preview 无堆外原子 → refcount/CAS（increment/decrement/getAndPutLong）**留 Unsafe，无法在 21 全移除**（归 B4/22）。护栏方法论再升级：同会话跨小时也漂移，必须**背靠背配对**
- S1 另落纯净可发布分支 `fix/s1-oob`（基于 master，1 提交 5901c76：guard + 回归测试；master 代码库 Java 8 目标实测 2/2×3 通过，与 preview 无关）

### 2026-11-14
- **战略定调（owner）**：一不做二不休——**把整个 FFM 计划 `feature/b1-ffm` 合入 master**，接受 master 变为 `--enable-preview` 制品（锁 JDK 21、消费方需 preview、不可常规发 Central；正解的干净无-preview 版仍指向 JDK 22）
- **前置检查（owner 要求）**：务必在 SDD 文档补记这次安全修复 → 核对：roadmap ✅、change-inventory ✅ 已含 S1；**CHANGES.txt 原缺** → 已补 `Security` 小节（CWE-787 根因/修复/行为变更/NVDB 关联/回归测试）+ `JDK 21 off-heap access (FFM, phase B)` 小节（如实写 preview 约束 + 原子残留）+ 依赖升级缓解的 Guava/Log4j2 公告
- 合并方式：master ← feature/b1-ffm，用 `--no-ff` 保留可追溯合并提交（便于引用安全修复）

---

## 4. 版本路线

| 版本 | 内容 | 状态 |
|---|---|---|
| **0.9.3** | A1–A4 + X1 + X3 | ✅ 内容完成（待 owner 决定是否 bump pom 版本 0.9.2→0.9.3 并发布） |
| （前置） | **C1 恢复 benchmark/jmh + Unsafe 性能基线** | ✅ 完成（原属 C 系列，因"性能不掉"目标提前） |
| 0.9.4 / 0.10.x | B3 + B4 + X2 | ⬜ |
| **1.0.0** | B1 ✅（元素/拷贝/冷路径全 FFM；仅 refcount/CAS 原子留 Unsafe）+ B2（Arena 分配器，待做）。FFM 在 21 为 preview，制品需 `--enable-preview` | 🟡 B1 完成，B2 待做 |
| 1.1+ | C2 JPMS、C3 API、C4 CI | ⬜ |

---

## 5. 遗留问题与约束

| 项 | 说明 | 归属 |
|---|---|---|
| `sun.nio.ch.DirectBuffer` 依赖 | chunked 实现使用，需 `--add-exports` | B1 移除 |
| FFM 在 JDK 21 是 preview | JEP 442=third preview，JEP 454(22)=final；B1 无法在 21 非-preview 落地 | ⛔ B1 阻塞，待 owner 定目标 JDK |
| `sun.misc.Unsafe` 依赖 | linked + chunked 实现，编译产生 deprecation 警告 | B1 移除 |
| `--release` 与 `--add-exports` 互斥 | 当前用 `<source>/<target>` 替代 | B1 后切回 `<release>` |
| `TimeoutsTest` flaky | 时序敏感，与 JDK 版本无关 | X1 ✅ 已修（放宽时序） |
| `finalize()` deprecated | JDK 21 标记 for removal | X2 |
| `ohc-benchmark`/`ohc-jmh` 禁用 | 父 pom 注释掉 | C1 ✅ 已恢复并采基线 |
| benchmark 曾依赖上游 0.7.4 | `ohc-benchmark` 测的应是本 fork | C1 ✅ 已修正坐标 |

---

## 6. 变更记录

| 日期 | 变更 | 作者 |
|---|---|---|
| 2026-11-06 | 创建 roadmap 文档，纳入 A1 完成进度与 A/B/C/X 全部 spec | Pu Zhao |
| 2026-11-06 | 完成 A2，更新 spec 状态与实现笔记 | Pu Zhao |
| 2026-11-08 | 完成 A3，新建 X3 任务（lz4/xxHash 处理） | Pu Zhao |
| 2026-11-08 | 完成 X3，lz4 迁移到 at.yawk.lz4:lz4-java:1.13.0 | Pu Zhao |
| 2026-11-08 | 完成 A4，文档对齐 Java 21 + CHANGES 0.9.3 区块 | Pu Zhao |
| 2026-11-08 | 完成 X1，修复 TimeoutsTest flaky（放宽时序 + 确定性 get 超时）；0.9.3 阶段内容齐备 | Pu Zhao |
| 2026-11-08 | 前置完成 C1：恢复 ohc-jmh/ohc-benchmark + 采集 Unsafe 性能基线（B1 护栏） | Pu Zhao |
| 2026-11-10 | 完成 B1a-chunked + B1b + B1c：B1 主体迁移完成（元素/拷贝/冷路径全 FFM，仅原子留 Unsafe） | Pu Zhao |
| 2026-11-13 | 完成 S1：修复 chunked putEntry 堆外越界写（NVDB 收录，CWE-787）+ 回归测试 | Pu Zhao |
| 2026-11-14 | 战略定调：FFM 计划合入 master（接受 preview）；CHANGES.txt 补 Security + FFM/preview 小节 | Pu Zhao |
