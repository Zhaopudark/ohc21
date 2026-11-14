# 发布 Runbook：推送到 Maven Central

> 本仓库**无 CI**，发布是**本地手动** `mvn` 操作。发布通道 = **Sonatype Central Portal（新），经 `central-publishing-maven-plugin`**。老 OSSRH staging 与 atlassian jgitflow 均已弃用、不再使用。

## 0. 发布物坐标
- groupId：`io.github.zhaopudark.ohc21`
- artifactId：`ohc-core`（主库）、`ohc-benchmark`、`ohc-jmh`（后两者一般也一并 deploy；若只想发主库可 `-pl ohc-core -am`）
- 当前版本：**0.9.3**（pom 手动设定）
- ⚠️ **必须是无 preview 的制品**：master 这条线不含 `--enable-preview`。preview 字节码锁 JDK 特性版本、消费方需 `--enable-preview`，不可作为常规库发 Central。带 FFM/preview 的是 `feature/b1-ffm`，**不要从那条线发布**。

## 1. 前置条件
1. **JDK 21+**、**Maven 3.9+**（构建 source/target 21；无需 22，因为 master 不用 FFM）。
2. **GPG 密钥**：pom `deploy` profile 里 keyname = `0802D482D22790A1651BA231ADB95FF70953D71D`。本机需有该私钥。
   - 口令来源（任选）：`settings.xml` 的 server `gpg-passphrase`，或环境变量 `GPG_PASSPHRASE`，或 `-Dgpg.passphrase=...`。
   - 首次可先 `mvn clean install -DskipJmh -Dgpg.skip=true` 不带签名本地验证；正式发布必须签名。
3. **`~/.m2/settings.xml`** 配置 Central Portal token（server id 必须为 **`central`**）：
   ```xml
   <servers>
     <server>
       <id>central</id>
       <username>CENTRAL_TOKEN_USER</username>
       <password>CENTRAL_TOKEN_PASSWORD</password>
     </server>
   </servers>
   ```
   token 在 https://central.sonatype.com → 生成 User Token（形如 `abc123$def456`，`$` 前为 username、后为 password）。
4. 代码已就绪：`mvn clean install -DskipJmh` 全模块 SUCCESS、`mvn clean test -pl ohc-core` 633×3 绿。

## 2. 发布步骤
```powershell
# 在仓库根，pwsh 7 语法
# 1) 确认版本号（如需改：编辑 4 个 pom 的 0.9.2/0.9.3 等）
# 2) 干净构建 + 签名 + 产 javadoc/sources（deploy profile 触发）
mvn clean -Pdeploy

# 3) 上传到 Central Portal（central-publishing-maven-plugin 在 deploy 阶段执行）
mvn deploy -Pdeploy
#    只发主库：mvn -pl ohc-core -am deploy -Pdeploy

# 4) autoPublish=false：上传后到 Portal 手动发布
#    打开 https://central.sonatype.com → Deployments → 找到本次 bundle → Validate → Publish
```

### 产物齐全性（Central 强制要求）
每个发布模块必须含：主 jar、`-sources.jar`（根 `build/plugins` 常驻 source-plugin）、`-javadoc.jar`（deploy profile 的 javadoc-plugin）、GPG `.asc` 签名（gpg-plugin 在 verify 阶段）、`pom`。缺 javadoc/sources/签名任一 → 校验失败。

## 3. 验证
- Portal：Deployment 状态从 `VALIDATING` → `PUBLISHED`。
- 约 15 分钟到数小时后镜像到 Maven Central，可查：
  - https://repo1.maven.org/maven2/io/github/zhaopudark/ohc21/ohc-core/0.9.3/
  - 或 `search.maven.org`。

## 4. 常见坑
- **401/Unauthorized**：`settings.xml` server id 不是 `central`，或 token 过期 → 重新生成。
- **javadoc 失败**：因 chunked `sun.nio.ch.DirectBuffer`，javadoc 已配 `--add-exports`；若仍报 preview，说明误从 `feature/b1-ffm` 线发布。
- **gpg 挂起**：headless 环境需 `GPG_TTY` 或 `batch` 模式 + `-Dgpg.passphrase`/`pinentry-loopback`。
- **误发 preview**：绝不可从含 `--enable-preview` 的构建发布（锁版本 + 不可常规用）。

## 5. 与历史工具的差异（C 现代化）
- 已**移除** `external.atlassian.jgitflow`（2014 停更、新环境不稳）；不再做 gitflow release 分支流转。
- 已**移除**指向退役 OSSRH（`oss.sonatype.org` staging）的 `<distributionManagement>`。
- 版本改为**手动**在 pom 设定；发布即 `mvn -Pdeploy clean deploy`，无需 release-start/finish。
- （可选）若想自动管 SNAPSHOT→release→下一快照 + tag，可后续引入 `maven-release-plugin`，当前不依赖。

## 6. 回滚
Central Portal 已发布的版本**不可删除/覆盖**。发布错误版本的唯一处理：发布更高版本修正。故**步骤 2/4 的 Validate 前务必核对版本与坐标**。
