# 发布 Runbook：推送到 Maven Central

> 发布通道 = **Sonatype Central Portal（新），经 `central-publishing-maven-plugin`**。老 OSSRH staging 与 atlassian jgitflow 均已弃用。
> **主路径 = GitHub Actions**（`.github/workflows/release.yml`，`release` 事件触发；见 §2A）。本地手动 `mvn -Pdeploy deploy` 仍可用作兜底（§2B）。
>
> **两条已核实的事实（2026，Sonatype 官方）**：
> - **Maven Central 发布不支持 OIDC Trusted Publishing** → 认证只能用 **Central Portal User Token**（不存在"免密 GH OIDC 发布"，PyPI/npm 那套 Maven 没有）。
> - **GPG/PGP（`.asc`）签名强制**；**Sigstore（`.sigstore.json`）只是可选附加，不替代 GPG**。所以 GPG 私钥/口令仍必须备好。

## 0. 发布物坐标
- groupId：`io.github.zhaopudark.ohc21`
- artifactId：`ohc-core`（主库）、`ohc-benchmark`、`ohc-jmh`（默认一并 deploy；只发主库用 `-pl ohc-core -am`）
- 当前版本：**0.9.3**（pom 手动设定）
- 命名空间 `io.github.zhaopudark.ohc21` **已验证**（0.9.1/0.9.2 已在 Central 上架 → 命名空间归本账号，无需再注册）
- ⚠️ **必须无 preview**：master 不含 `--enable-preview`。带 FFM/preview 的是 `feature/b1-ffm`，**绝不从那条线发布**（preview 字节码锁 JDK 特性版本、消费方需加 flag、不可常规发 Central）。

## 1. 前置条件
1. **JDK 21+**、**Maven 3.9+**（source/target 21；无需 22，master 不用 FFM）。
2. **GPG 私钥**：pom `deploy` profile 的 keyname = `0802D482D22790A1651BA231ADB95FF70953D71D`。口令走 settings server id **`gpg.passphrase`**（maven-gpg-plugin 默认 passphraseServerId），或 `-Dgpg.passphrase=…`。
   - 本地预检可 `-Dgpg.skip=true` 跳过签名；正式发布必须签名。
3. **Central User Token**：在 https://central.sonatype.com 生成 User Token（形如 `user$tokenPass`），server id 必须为 **`central`**。
4. 代码就绪：`mvn clean install -DskipJmh` 全模块 SUCCESS、`mvn clean test -pl ohc-core` 633×3 绿。

## 2A. 发布步骤（GitHub Actions，主路径）
一次性配置（仓库 GitHub 设置）：
1. **Secrets**（Settings → Secrets and variables → Actions → Repository secrets）：
   - `MAVEN_GPG_PRIVATE_KEY`：`gpg --armor --export-secret-keys 0953D71D` 的文本
   - `GPG_PASSPHRASE`：该私钥口令
   - `CENTRAL_TOKEN_USERNAME` / `CENTRAL_TOKEN_PASSWORD`：Portal token 的 user 与 pass
2. **Environment `production`**（Settings → Environments）：建同名环境，加 **Required reviewers** 作人工审批门（弥补 Maven 无 OIDC——上传前需人放行）。
3. 触发发布：把代码合到 master → 打 tag（裸版本号，如 `0.9.3`）并 push → 在 GitHub **创建 Release**（用该 tag）→ `release: published` 触发 workflow：
   ```powershell
   git tag 0.9.3
   git push origin master 0.9.3
   # 然后在 GitHub Releases 页面 "Draft a release" 选 tag 0.9.3 → Publish release
   ```
4. workflow 会校验 pom 版本 == tag、非 SNAPSHOT，导入 GPG、渲染 settings.xml、`mvn -B -Pdeploy clean deploy`。`autoPublish=true` → 校验通过后**自动上架**（无需再去门户点 Publish）。

## 2B. 本地手动发布（兜底）
```powershell
# 前提：本机有 GPG 私钥 + ~/.m2/settings.xml 配好 central 与 gpg.passphrase
mvn -B -Pdeploy clean deploy
# 只发主库：mvn -B -pl ohc-core -am -Pdeploy clean deploy
```
`autoPublish=true` 时上传校验后即自动发布；设回 false 则需去 central.sonatype.com 的 Deployments 手动 Publish。

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

## 5. 与历史工具的差异 / 生态事实（C 现代化 + 2026 核实）
- 已**移除** `external.atlassian.jgitflow`（2014 停更、新环境不稳）；不再做 gitflow release 分支流转。
- 已**移除**指向退役 OSSRH（`oss.sonatype.org` staging）的 `<distributionManagement>`。
- 版本手动在 pom 设定；发布 = GH Actions 跑 `mvn -B -Pdeploy clean deploy`（或本地同命令）。
- **发布主路径已改为 GitHub Actions**（`on: release`），密钥进 Secrets，笔记本不再需要保管；配 `environment: production` 做人工审批门。
- **认证事实**：Maven Central **不支持 OIDC Trusted Publishing**，只能用 Portal User Token（区别于 PyPI/npm）。别按"GH OIDC 免密发 Maven"去配。
- **签名事实**：**GPG 必留**；`sigstore-maven-plugin` 的 Sigstore 只是**可选附加签名**，Sonatype 明确"无意用 Sigstore 取代 PGP"。若要加 Sigstore 可另配（用 CI OIDC 换短期证书），但不省 GPG。
- 若将来要自动 SNAPSHOT→release→下一快照 + tag 管理，可引入 `maven-release-plugin`，当前不依赖。

## 6. 回滚 / 不可逆性
Central 已发布的版本**不可删除、不可覆盖**（immutability）。发错版本唯一处理：**发更高版本修正**。故发布前务必核对：pom 版本、tag、无 preview、坐标正确。`autoPublish=true` 意味着校验通过即上架——人工安全闸靠 workflow 的 `environment: production` 审批（或临时把 autoPublish 设 false 改回手动 Publish）。
