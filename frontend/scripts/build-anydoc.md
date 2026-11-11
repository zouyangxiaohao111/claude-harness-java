# 构建 anydoc 原生库（`anydoc_go.dll`）

`KbAnydocParser` 通过 JNA 调用 anydoc 的 C ABI 把 Office 文档转成结构化 Markdown。
它依赖的先生产物 `anydoc_go.dll` **不是本仓能直接编出来的** —— 源码在 WeKnora 仓库的
`third_party/anydoc-go/`，需要 Rust 工具链单独构建。本文件说明怎么自己编。

> 产物落点：`front/src-tauri/backend/anydoc_go.dll`（约 8 MB）。
> 该目录同时装 `nexusai-backend.jar` 与 `jre/`，都是构建产物，已被 `.gitignore` 忽略
> （`front/src-tauri/backend/*`），**不入库**。Tauri `bundle.resources` 里有 `"backend"`
> 条目 ⇒ 整个目录随安装包分发，运行时后端进程从 jar 同级目录加载它。

⚠️ **不编也能出包**：`prepare-backend.mjs` 发现 DLL 缺失时只打印警告并继续。
缺 DLL 时 `AnydocNative.isAvailable()` 返回 false ⇒ `KbAnydocParser.supports()` 恒 false
⇒ Office 类文档回落「内置 Office 解析」（Apache Tika），应用本身照常可用。

---

## 步骤

### 1. 装 Rust

<https://rustup.rs> 装 stable 工具链。Windows 需要 MSVC 生成工具（Visual Studio Build Tools
的「使用 C++ 的桌面开发」），因为目标是 `x86_64-pc-windows-msvc`。

```bash
rustc --version     # 本机验证时是 1.96.0
```

### 2. 拿源码

从 WeKnora 仓库取这两样（本机在 `D:/tmp/WeKnora/`）：

- `third_party/anydoc-go/`
- `scripts/build-anydoc-lib.sh`

### 3. 配国内镜像（crates.io 被墙）

写 `third_party/anydoc-go/.cargo/config.toml`：

```toml
[source.crates-io]
replace-with = 'rsproxy-sparse'

[source.rsproxy-sparse]
registry = "sparse+https://rsproxy.cn/index/"
```

不配这一步，`cargo build` 会在拉依赖时超时。

### 4. `Cargo.toml` 加 `cdylib`

原文件只有 `staticlib`（Go 的 cgo 用它，产出 `.lib`）—— **只有 `staticlib` 就没有 DLL**，
JNA 的 `Native.load` 根本无从加载。改成：

```toml
[lib]
crate-type = ["staticlib", "cdylib"]
```

两者互不影响：Go 侧继续用 `.lib`，Java 侧用新产出的 `.dll`。

### 5. 跑构建脚本

```bash
TARGET=x86_64-pc-windows-msvc bash scripts/build-anydoc-lib.sh
```

⚠️ **必须先跑这个脚本**：`third_party/anydoc-go` 的 `Cargo.toml` 里有
`[patch.crates-io] anydoc = { path = "patched-anydoc" }`，而 `patched-anydoc/` 目录
**被 gitignore、仓库里没有** ⇒ 直接 `cargo build` 会立刻失败。脚本会从 registry 拉
`anydoc-0.2.4.crate`、解包到 `patched-anydoc/`，并打两个补丁：

1. `document_to_markdown` 改成 `pub use`（上游是 crate 私有，不改就编不过 Go 侧调用）；
2. `zip` 8.6.0 → 9（修 RUSTSEC-2026-0367 / 0368 / 0369）。

### 6. 拷产物

```
third_party/anydoc-go/target/x86_64-pc-windows-msvc/release/anydoc_go.dll   →   front/src-tauri/backend/
```

约 8 MB。拷完重新跑 `node scripts/prepare-backend.mjs`，会打印
`anydoc 原生库：已就位 …（随包分发）`。

---

## 验证是否真的能调

```bash
# 符号级自查（⛔ 别用 strings —— .lib 是 MSVC COFF 归档，strings 会假红）
python -c "
d=open(r'front/src-tauri/backend/anydoc_go.dll','rb').read()
print(len(d), d.count(b'anydoc_to_markdown'))   # 期望 ~8000000 与 >0
"
```

跑起来看后端日志：启动后第一次上传 Office 文档会打印
`AnydocNative: 已按库名加载 anydoc_go` 或 `已按绝对路径加载 …`；
若打印 `未找到可加载的 anydoc_go.dll；已尝试 —— …` 则说明 DLL 没落在搜索路径里
（`AnydocNative` 依次找：库名/PATH → jar 所在目录 → `user.dir` → `user.dir/backend`）。

---

## 其它平台（mac / Linux）

`build-anydoc-lib.sh` 的 `case "$target"` 里有 6 个目标：
`x86_64/aarch64-apple-darwin`、`x86_64/aarch64-unknown-linux-{gnu,musl}`、`x86_64-pc-windows-msvc`。
但要产出 `.dylib` / `.so` 同样需要第 4 步的 `cdylib`。

**当前状态：只有 Windows 的 `.dll` 随包分发，mac / Linux 均未集成。**
这两个平台上 `AnydocNative.isAvailable()` 直接返回 false（`unavailableReason` 写明
「当前平台未分发 anydoc 原生库」），Office 文档走内置 Office 解析。
若要支持，需要两步：① 按平台编出 `.dylib` / `.so` 放进 `front/src-tauri/backend/`；
② 让 `AnydocNative` 的候选文件名按 `os.name` 决定（目前常量硬编码 `anydoc_go.dll`；
`Native.load(基名)` 那一级本身是跨平台的，若它在 PATH/工作目录里能命中则无需改代码）。

---

## 已知边界

| 项 | 说明 |
|---|---|
| PDF | ⛔ anydoc 没有 PDF document model，错误码 `ERR_PDF_NO_MODEL=7`。`KbAnydocParser.supports()` 排除 `pdf`，PDF 走 `KbPdfParser`（逐页判文本层 + 扫描页视觉补强）。 |
| 扫描件 PDF | anydoc 不 OCR，会报 `ERR_NEEDS_OCR=10`。 |
| 图片 / 音频 | 不在 anydoc 范围。图片走 `KbImageParser`（视觉模型），音频尚未接入。 |
| `csv` / `md` / `txt` / `json` | anydoc 能转 csv，但本仓按源站「简单格式不接管」语义留给 `KbTextParser`（它的 CSV→Markdown 表格转义与源站逐字对齐）。 |
