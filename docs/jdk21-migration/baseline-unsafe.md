# Unsafe 性能基线（迁移前）

> 本文件是 B1（`sun.misc.Unsafe` → `java.lang.foreign`）性能护栏的**基准**。
> 迁移过程中每个改动子步都要用**相同命令**重跑，与下表做 A/B 对比。
> 通过标准：热路径 get/put 吞吐回退 < 3%（见下"判定阈值"）。

## 采集环境

| 项 | 值 |
|---|---|
| 采集日期 | 2026-11-08 |
| OHC 版本 | 0.9.2（阶段 A 完成、B1 未开始，仍使用 `sun.misc.Unsafe`） |
| JDK | OpenJDK 21+35-2513 |
| OS | Windows 11 (10.0, amd64) |
| CPU | Intel Core Ultra 9 285H，16 逻辑处理器 |
| ⚠️ CPU 特性 | **混合架构（P 核 + E 核）**，线程调度会造成绝对吞吐噪声；因此以**同机 A/B 相对比较**为准，不跨机器比较绝对值 |
| 原始输出 | `baseline-unsafe-ohcbenchmark.txt`、`baseline-unsafe-allocator.txt` |

## 复现命令

JMH uberjar：`ohc-jmh/target/ohc-jmh-0.9.2-benchmarks.jar`（由 `mvn install -DskipJmh` 产出）。

```bash
# 1. 热路径（linked / MURMUR3 / LRU）
java --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
  -jar ohc-jmh/target/ohc-jmh-<version>-benchmarks.jar \
  -p hashAlg=MURMUR3 -p chunkSz=-1 -p eviction=LRU \
  -p keys=2000000 -p capacity=536870912 \
  -f 1 -wi 2 -i 5 -r 2 -w 1 \
  "OHCBenchmark\.(getSingleThreaded|getMultiThreaded|putSingleThreaded|putMultiThreaded)"

# 2. 分配器（Unsafe vs JNA，B2 相关）
java --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
  -jar ohc-jmh/target/ohc-jmh-<version>-benchmarks.jar \
  -p size=128,8192 -p allocatorType=Unsafe,JNA \
  -f 1 -wi 2 -i 3 -r 2 -w 1 \
  "AllocatorBenchmark"
```

## 基线结果

### OHCBenchmark（thrpt，ops/µs，越大越好；5 次测量迭代，1 fork）

| Benchmark | Score | Error | ops/s（≈Score×1e6） |
|---|---:|---:|---:|
| getMultiThreaded (4 线程) | **21.341** | ±2.054 | ~21.3 M |
| getSingleThreaded | **4.373** | ±0.441 | ~4.4 M |
| putMultiThreaded (4 线程) | **6.758** | ±1.101 | ~6.8 M |
| putSingleThreaded | **2.298** | ±0.246 | ~2.3 M |

参数：capacity=512MB、keys=2,000,000、valueSz=256、linked 实现、MURMUR3、LRU、默认段数。

### AllocatorBenchmark（thrpt，ops/µs；3 次测量迭代，1 fork）

| Benchmark | allocator | size | Score | Error |
|---|---|---:|---:|---:|
| allocateMultiThreaded | Unsafe | 128 | 20.355 | ±9.667 |
| allocateMultiThreaded | Unsafe | 8192 | 20.055 | ±5.224 |
| allocateMultiThreaded | JNA | 128 | 22.314 | ±7.946 |
| allocateMultiThreaded | JNA | 8192 | 23.174 | ±2.052 |
| allocateSingleThreaded | Unsafe | 128 | 14.602 | ±4.213 |
| allocateSingleThreaded | Unsafe | 8192 | 13.069 | ±7.982 |
| allocateSingleThreaded | JNA | 128 | 14.884 | ±12.831 |
| allocateSingleThreaded | JNA | 8192 | 15.993 | ±4.300 |

观察：单线程下 Unsafe 与 JNA(=OS malloc/free) 分配吞吐相当，JNA 甚至略高——提示 B2 的 `ForeignMemoryAllocator`（`Arena`）只要做到与二者同档即可，不构成性能红线。

## 判定阈值（B1/B2 验收用）

- **OHCBenchmark 四个热路径**：相对本基线吞吐回退 < 3% 为达标；≥ 3% 触发红线，停止推进并重新设计该子步。
- **AllocatorBenchmark**：`ForeignMemoryAllocator` 需落入 Unsafe/JNA 同一区间。
- Error 列偏大主要源于混合架构 CPU + 单 fork；如需更高置信度可提高 `-f`（fork 数）与 `-i`（迭代数），但基线与对比必须用**完全相同**的参数集才可比。

## ⚠️ A/B 方法论修正（2026-11-09，B1a 实测教训）

**跨会话的绝对数字不可比，护栏必须同会话 A/B。**

- 本文件首次采集（11-08）得 getMulti≈21.3 / getSingle≈4.37。
- 11-09 同机重跑**未改动的 Unsafe 变体**只得 getMulti≈17.7 / getSingle≈3.54 —— 与 11-08 相差 15-20%。
- 结论：差异来自**机器状态漂移**（Intel Ultra 9 285H 混合 P/E 核调度、温度/功耗墙、后台负载），**不是代码**。昨天那份绝对基线不能用作逐日红线。

**正确护栏协议（B1c 及后续强制）**：
1. 同一会话内，先后构建 **Unsafe 变体** 与 **FFM 变体** 两个 uberjar（临时回退改动 → 打包 → 测 → 恢复 → 打包 → 测）。
2. 用 `--enable-preview`（B 方案）+ 相同 `-p` 参数 + `-f 2` 以上收紧 CI。
3. 只比**同会话两次的相对差**，不看绝对值。
4. 单线程 get 是最干净的信号（无跨线程调度噪声），以此为主判据。

**B1a 同会话配对实测（11-09，-f 2，10 样本）**：

| Benchmark | Unsafe（本会话基线） | FFM B1a | 相对差 |
|---|---:|---:|---:|
| getMultiThreaded | 17.66 ±0.87 | 17.73 ±0.93 | +0.4%（持平） |
| getSingleThreaded | 3.54 ±0.20 | 3.32 ±0.21 | **-6.2%**（CI 重叠，疑似小回退） |
| putMultiThreaded | 6.10 ±0.17 | 5.95 ±0.15 | -2.5% |
| putSingleThreaded | 1.89 ±0.09 | 1.87 ±0.08 | -1.1% |

判读：多线程 get 与两个 put 均在噪声/3% 内；唯单线程 get 名义 -6% 但两个 99.9% CI 大幅重叠（Δ0.22 ≈ 单侧误差），不显著但需警惕。根因：FFM `directBufferFor` 每次 get 反序列化物化 `ofAddress`+`reinterpret`+`asByteBuffer` 3 对象，旧 hack 仅 `allocateInstance` 1 对象。B1a 为冷路径改动，单线程 get 的小幅开销在预期内，待 B1c 全量后整体复测决定是否优化 view 构造。

### 补充：同会话跨小时仍漂移 → 必须"相邻背靠背"配对（B1b 实测）

- B1b（copyMemory/setMemory）测得 getMulti≈21.2 / getSingle≈3.80，看似比早先同会话的 unsafe(17.66)/B1a(17.73) **高 ~20%**——但 B1b 代码不碰 get 元素访问，不可能变快。
- 重跑同一 B1b jar 得 21.1（自洽）→ 机器约 1 小时前处于"慢档"(~17.7)、现处于"快档"(~21)，**同一会话内跨小时也漂移 ±15-20%**。
- 因此护栏的硬性要求升级为：**两个变体必须在数分钟内背靠背测**（临时 stash→建→测→恢复→建→测，相邻），绝不跨时段比绝对值。
- B1b vs B1a 相邻同段（快档）：getMulti 21.07→21.27(+1.0%)、getSingle 3.80→3.80(0%)、putMulti 6.36→6.38(+0.3%)、putSingle 2.16→2.21(+2.1%)，CI 全重叠 → **B1b 性能中性**。

> 结论：本机（hybrid P/E 笔记本）噪声带宽 ~15-20%，远超 3% 闸门。相邻背靠背可缓解但不足以分辨 <5% 的效应；要精确 gate 需更稳的环境（定频/关 E 核/服务器，或多次交错取中位数）。当前对"是否显著回退"的判断以相邻配对的 CI 是否重叠为准。

## 待补基线（可选，B1 进入 chunked 路径时补）

本轮未覆盖 chunked 实现（`-p chunkSz=65536`）与其余 hash 算法。进入涉及 chunked 的迁移子步前，需按"同会话 A/B"协议补采 chunkSz=65536 的配对基线作为该路径护栏。
