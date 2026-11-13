---
name: release-package
description: Use when building or publishing a NexusAI desktop release (Tauri 安装包 / 双源发布 / MinIO updater / GitHub Release), syncing the four repos (GitHub nexusai master、GitHub claude-harness-java、SVN 主、SVN 场景), bumping the version for a release, or diagnosing release-build failures involving Tauri version mismatch, JDK native access warnings, logback archive deletion, anydoc_go.dll not bundled, or tray icon 退出不生效.
---

# NexusAI 打包发版

## Overview

发版 = **在干净 worktree 里构建 → 双源上传 → 四仓同步 → 哈希核验**。
四条铁律各有代价，必须靠脚本门禁兜住：

1. **构建只在 detached worktree 里做** —— 主工作区常被别的会话实时改动，其中构建会把半成品与内网密钥一起打进包。
2. **node_modules 用 junction 复用主工作区，绝不 `npm ci`** —— `npm ci` 会重解依赖树，`@univerjs/core` 变成两份 ⇒ tsc 报 TS2322。
3. **密钥隔离是硬门禁** —— 内网出厂秘钥（`factory-preset.local.json`）与错误上报只写 key 绝不能进公网包。
4. **场景线只进 SVN** —— `feat/scene-fusion` 绝不推 GitHub。

## When to Use

- 用户说"打包 / 发版 / 发布到双源 / 出包 / 发外网 / 同步四仓"
- 版本要递增（0.1.x → 0.1.y）
- 打包报 `Found version mismatched Tauri packages` / `TS2322` / `Failed to delete file *.log.zip` / `未找到 anydoc 原生库`
- 托盘右键"退出"点了没反应

**不适用**：只改代码不发布；只跑单元测试；只同步单个仓。

## 快速开始

**所有路径、版本号、发布目标都由你（agent）依据用户诉求动态指定**，脚本一律接受参数：

```bash
S=.claude/skills/release-package/scripts

# ① 预检（路径可省略，自动探测）
bash $S/preflight.sh --repo <仓库> --version 0.1.73 --target public \
                      --harness <harness仓> --svn-wc <svn工作副本> --scene-wt <场景worktree>

# ② 版本递增（只改文件，不提交）
bash $S/bump-version.sh --repo <仓库> --version 0.1.73 --note "一句话说明本次内容"

# ③ 你自己提交（⛔ 显式路径；⛔ 禁用 git add -A/.）
git -C <仓库> add -- <显式路径...>
git -C <仓库> commit -m "..."

# ④ 构建（独立 worktree）
bash $S/build.sh --repo <仓库> --version 0.1.73 --out <构建树目录>

# ⑤ 发布双源
bash $S/publish.sh --worktree <构建树目录> --version 0.1.73 --target public

# ⑥ 同步四仓
bash $S/sync-repos.sh --repo <仓库> --harness <harness仓> --svn-wc <svn工作副本> \
                       --scene-wt <场景worktree> --push

# ⑦ 核验（哈希 + 四仓终态 + 密钥零泄漏）
bash $S/verify-release.sh --repo <仓库> --version 0.1.73 --svn-wc <svn工作副本> \
                          --worktree <构建树目录>
```

一键：`bash $S/release.sh --repo <仓库> --version 0.1.73 --target public --steps preflight,bump`
（`bump` 后会停下等你提交 —— 提交内容是判断题，脚本不代劳）

### 参数速查

| 参数 | 含义 | 默认 |
|---|---|---|
| `--repo` | 仓库根 | 自动探测（含 `backend/pom.xml`） |
| `--version` | 目标版本 | 仓库当前版本 |
| `--target` | `public` / `scene` / `internal` | `public` |
| `--harness` / `--svn-wc` / `--scene-wt` | 另三个仓 | 自动探测 |
| `--out` / `--worktree` | 构建树目录 | `$TMPDIR/nexusai-release-<ver>` |
| `--steps` | 跑哪几步 | 全跑 |

`--target scene|internal` ⇒ **自动跳过 GitHub Release**（场景版 exe 只上 MinIO `updater-scene/`）。

## 脚本分工

| 脚本 | 做什么 | **不**做什么 |
|---|---|---|
| `preflight.sh` | 版本号/密钥/占用进程/工具链/迁移号段 | 不改任何文件 |
| `bump-version.sh` | 六处版本号 + 两处 CHANGELOG 占位 | **不提交** |
| `build.sh` | detached worktree + junction + tauri build + 产物门禁 | 不推任何东西 |
| `publish.sh` | MinIO + GitHub Release | 不传 `--exe`（见下） |
| `sync-repos.sh` | harness 镜像 / SVN 主 / SVN 场景 | 不解冲突、不提交 harness |
| `verify-release.sh` | 四方哈希 + 四仓终态 + 密钥检查 | 不改任何文件 |

**冲突解决、提交取舍、CHANGELOG 措辞 —— 全部由你判断**，脚本只做机械步骤。

## 必须记住的坑（脚本已内建门禁，但你也别手动绕过）

### ① `npm ci` 会毁掉 node_modules
新 worktree 里 `npm ci` ⇒ `@univerjs/core` 装成两份（顶层 1.0.2 + presets 内嵌 0.25.1）⇒ `ExcelPreview.tsx` TS2322，而主工作区却"能编过"。
⇒ **用 junction** 复用主工作区的 node_modules。

### ② 收尾必须先拆 junction
`git worktree remove --force` 遇到 node_modules **junction** 可能顺着删掉**主工作区的 node_modules**。
⇒ 顺序：`cmd //c "rmdir <tree>/front/node_modules"` → 再 `worktree remove`。

### ③ `release-publish.mjs --exe` 会打断发布
`--exe` 跳过"复制到 `<repo>/release/`"，而内嵌 `release-update.mjs` 只认那个路径 ⇒ 「未找到安装包」⇒ **latest.json 生不出来 ⇒ 整个发布中止**。
⇒ **不放 `--exe`**；把产物放进 `<tree>/front/src-tauri/target/release/bundle/nsis/` 让脚本自己找。

### ④ 版本号不能复用已发布版本
updater 判据是**严格大于**。复用同号 ⇒ 已装该号的客户端永远收不到更新。
`preflight.sh` 会拦（版本 ≤ 已发布 ⇒ 中止）。

### ⑤ SVN 核验要用**具体文件路径**查 rev
`svn log -l 1 <目录>` 给的是**目录节点**的最后改动 rev（可能很旧）⇒ 会误判"镜像不完整"。
⇒ `svn log -l 1 <wc>/nexusai/backend/pom.xml` 取本次的 rev。

### ⑥ 内网密钥三处
`backend/src/main/resources/factory/factory-preset.local.json`（出厂 provider key）·
`front/src-tauri/error-report.local.json`（上报只写 key）· `front/src-tauri/.error-report-internal`（内网构建标记）。
⇒ 公网包必须三者都不在**构建树**里；`build.sh`/`publish.sh`/`verify-release.sh` 都会查 jar 与 app exe。

### ⑦ 场景线绝不进 GitHub
`feat/scene-fusion` 只同步 SVN；`sync-repos.sh` 会检查 GitHub 上是否已存在该分支并告警。

### ⑧ 迁移号段
主线走 **V250+**，场景占 **V201–V249**。两段交叉 ⇒ jar 内两个同号迁移 ⇒ 启动即失败。
`preflight.sh` 会查。

## 常见报错 → 处置

| 报错 | 根因 | 处置 |
|---|---|---|
| `Found version mismatched Tauri packages` | npm caret 装到新 minor，Cargo.lock 钉旧 minor | 把 npm 侧钉到同 minor（`~x.y.z`），Rust 侧不动 |
| `TS2322` @univerjs | 依赖树双份 | 用 junction，⛔ 别 npm ci |
| `Failed to delete file *.log.zip` | 过期归档只在滚动时清，杀软扫描占用 | `cleanHistoryOnStart=true`（启动即清） |
| `未找到 anydoc 原生库` | 原生库位置与打包脚本约定不一致 | 确认它在 jar 内（`unzip -l *.jar \| grep anydoc`） |
| `A restricted method in java.lang.System has been called` | JDK 24+ native access 警告 | JVM 加 `--enable-native-access=ALL-UNNAMED`（须在 `-jar` 前，且仅 JDK≥24） |
| 托盘"退出"点了没反应 | 托盘常驻 ⇒ 关窗不结束进程 | 退出项显式 `app.exit(0)` + 退出前回收后端进程树 |

## 收尾清单

```bash
cmd //c "rmdir <worktree>/front/node_modules"   # 先拆 junction
git -C <repo> worktree remove --force <worktree>
git -C <repo> worktree prune
```
