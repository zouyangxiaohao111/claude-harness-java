# 构建 anydoc 原生库（`anydoc_go.dll` / `libanydoc_go.so` / `libanydoc_go.dylib`）

`KbAnydocParser` 通过 JNA 调用 anydoc 的 C ABI 把 Office 文档转成结构化 Markdown。
它依赖的先生产物**不是本仓能直接编出来的** —— 源码在 WeKnora 仓库的
`third_party/anydoc-go/`，还需要给上游的 Rust crate 加 `cdylib` 才出动态库。

---

## ⭐ 最简用法（脚本已把全部步骤串好）

脚本位置：**`backend/scripts/build-anydoc.sh`**（+ Windows 双击用的 `build-anydoc.bat`）。

```bash
# 在当前平台的 Git Bash / Linux / macOS 里跑
bash backend/scripts/build-anydoc.sh            # 编当前平台并落到 resources/anydoc/
bash backend/scripts/build-anydoc.sh --check    # 只体检：工具链 / 平台 / 源码是否就位
bash backend/scripts/build-anydoc.sh --clone    # 本机没有 WeKnora 源码时自动 clone
WEKNORA_DIR=D:/tmp/WeKnora bash backend/scripts/build-anydoc.sh
```

Windows 上也可以**直接双击** `backend\scripts\build-anydoc.bat`（它定位 Git for Windows 的
`bash.exe` 后转调 `.sh`；参数原样透传，如 `build-anydoc.bat --check`）。

脚本做的事（7 步，每步都有输出）：

| 步 | 内容 |
|---|---|
| 1 | 检查 `cargo/rustc/tar/curl/sed/awk`，读 host target triple |
| 2 | 由 triple 推出 `<arch>-<os>` 目录名（与 Java 侧 `platformDirName()` **逐字一致**） |
| 3 | 定位 `third_party/anydoc-go`（`WEKNORA_DIR` → `D:/tmp/WeKnora` → `~/WeKnora` → `/tmp/WeKnora` → 可选 `--clone`） |
| 4 | 复制到临时工作目录（⛔ 不改你的 WeKnora 克隆；不用 `tar --exclude`——BSD tar 不认 GNU 写法） |
| 5 | 准备 `patched-anydoc`（上游 `[patch.crates-io]` 指向的目录被 gitignore，仓库里没有）+ 打两个补丁 |
| 6 | 把 `crate-type` 加上 `cdylib` 后 `cargo build --release --locked` |
| 7 | 校验产物（大小 > 1MB + PE/ELF/Mach-O 魔数），拷到 `resources/anydoc/<arch>-<os>/` |

✅ **本机实测**：一次跑通，产物 8,040,448 B，探针加载 + 解析 docx 正常
（与手工编出来的那份相比只差 28 字节，全在 PE `TimeDateStamp`，代码等价）。

### 脚本的两个刻意的拦截

- **`--target` 直接报错退出**：`cdylib` 必须在目标平台链接（见文末「其它平台」），
  接受 `--target` 只会让人以为自己交叉编出了 `.so`。
- **musl 目标直接报错**：Rust 会 `dropping unsupported crate type 'cdylib'`，
  **编完没有产物但退出码是 0** ⇒ 不拦的话用户会拿到「成功」却没有文件（本机实测）。

---

## 产物落点

> **`backend/src/main/resources/anydoc/<arch>-<os>/`**
> 例：Windows x64 → `backend/src/main/resources/anydoc/x64-win32/anydoc_go.dll`。
> 目录命名与运行时推导、与 `resources/rg/` 的 ripgrep 布局**完全一致**（`<arch>-<os>`，
> 即 Node 的 `process.arch-process.platform`）。
>
> 这份资源会被 Maven 打进 jar 的 `BOOT-INF/classes/anydoc/`，运行时 `AnydocNative`
> 从 classpath 提取到 `%TEMP%/nexusai-anydoc/<版本>/<平台>/` 再 `Native.load`
> （fat jar 里的条目是压缩的，不能直接加载，必须先落盘 —— 与 ripgrep 同一机制）。
>
> ⭐ **入库**：该目录**要提交进 git**（这是它与旧方案最大的区别）。旧方案放在
> `front/src-tauri/backend/`，那里被 `.gitignore` 全忽略 ⇒ 干净 worktree / CI 上必然缺库，
> 0.1.68 的发版日志就是「⚠️ 未找到 anydoc 原生库」。

⚠️ **不编也能出包**：`prepare-backend.mjs` 发现 jar 内没有 anydoc 资源时只打印警告并继续。
缺库时 `AnydocNative.isAvailable()` 返回 false ⇒ `KbAnydocParser.supports()` 恒 false
⇒ Office 类文档回落「内置 Office 解析」（Apache Tika），应用本身照常可用。

---

## 手工步骤（想理解脚本在做什么，或脚本失败时对照）

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
third_party/anydoc-go/target/x86_64-pc-windows-msvc/release/anydoc_go.dll
  →  backend/src/main/resources/anydoc/x64-win32/anydoc_go.dll
```

约 8 MB。拷完重新 `mvn package`，然后：

```bash
# ① jar 里真的有它（判据来源，不是「我拷过去了」）
"C:/Program Files/Java/jdk-25.0.3/bin/jar.exe" --list \
  --file backend/target/nexusai-backend.jar | grep BOOT-INF/classes/anydoc/
# 期望：BOOT-INF/classes/anydoc/x64-win32/anydoc_go.dll

# ② 打包脚本认同
node front/scripts/prepare-backend.mjs
# 期望：anydoc 原生库：已随 jar 内置（平台 x64-win32，1 个文件）
```

⚠️ **改资源后必须清 `target/classes`**：Maven 的 `resources:resources` **不会删除**
已移走的旧资源 ⇒ 只移文件不清理时，`target/classes/anydoc/` 里还留着上一次的副本，
测试会**假绿**（实测踩过）。

---

## 验证是否真的能调

```bash
# 符号级自查（⛔ 别用 strings —— .lib 是 MSVC COFF 归档，strings 会假红）
python -c "
d=open(r'backend/src/main/resources/anydoc/x64-win32/anydoc_go.dll','rb').read()
print(len(d), d.count(b'anydoc_to_markdown'))   # 期望 ~8000000 与 >0
"
```

跑起来看后端日志，加载成功的两条（按优先级）：

```
AnydocNative: 内置原生库已提取 → C:\Users\<you>\AppData\Local\Temp\nexusai-anydoc\0.2.4\x64-win32\anydoc_go.dll（来源 classpath /anydoc/x64-win32/anydoc_go.dll）
AnydocNative: 已加载内置原生库 …（平台 x64-win32）
```

失败时打印 `AnydocNative: <原因>`，两种口径要分清：

| 日志 | 含义 |
|---|---|
| `当前平台（arm64-darwin）未随包内置 libanydoc_go.dylib …` | 该平台**本来就没做**（设计现状），不是故障 |
| `未找到可加载的 anydoc_go.dll（os=…）；已尝试 —— 内置 …（当前平台未随包）；按库名 …；…` | 平台有但文件缺失/放错目录 |

加载顺序（`AnydocNative.loadInternal`）：**内置资源（classpath → 临时目录）→ 按库名 → 外部绝对路径**。
后两级是给「不想重新打包、临时替换库」的场景留的兜底。

### 真机探针（不启 Spring）

探针类：`backend/src/test/java/com/nexusai/kb/app/parser/AnydocProbe.java`（手动跑，不是 JUnit ——
理由见该类 javadoc：断言「必须可用/不可用」在 CI 与打包机上会互相打架）。

```bash
cd backend
mvn -o -q test-compile
mvn -o -q dependency:build-classpath -Dmdep.outputFile=D:/tmp/cp-anydoc.txt

# ⚠️ 必须用 Windows 绝对路径（MSYS 的 target/classes 相对路径 java.exe 认不出）
# ⚠️ -Duser.dir 指向没有 dll 的目录 —— 排除「靠外部文件兜底成功」的假绿
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Duser.dir=D:/tmp/probe-cwd \
  -cp "D:/code/ai_project/nexusai/backend/target/test-classes;D:/code/ai_project/nexusai/backend/target/classes;$(cat D:/tmp/cp-anydoc.txt)" \
  com.nexusai.kb.app.parser.AnydocProbe D:/tmp/_t.docx
```

期望：`[1] resourceName = /anydoc/x64-win32/anydoc_go.dll`、`[2] isAvailable = true`、
`[3]` 缓存目录里出现 8,040,448 B 的 dll、`[4] 转换成功`。

---

## 其它平台（mac / Linux）

`build-anydoc-lib.sh` 的 `case "$target"` 里有 7 个目标：
`x86_64/aarch64-apple-darwin`、`x86_64/aarch64-unknown-linux-{gnu,musl}`、`x86_64-pc-windows-msvc`。
但要产出 `.dylib` / `.so` 同样需要第 4 步的 `cdylib`。

**代码侧无需再改** —— `AnydocNative` 已经按平台推导资源路径
（`libraryFileNameFor()` / `platformDirName()` / `resourceName()`），
只要对应目录放上文件就自动生效：

| 平台 | 目录 | 文件名 |
|---|---|---|
| Windows x64 | `anydoc/x64-win32/` | `anydoc_go.dll` ✅ 已随包 |
| Windows arm64 | `anydoc/arm64-win32/` | `anydoc_go.dll` |
| macOS Intel / Apple Silicon | `anydoc/x64-darwin/` · `anydoc/arm64-darwin/` | `libanydoc_go.dylib` |
| Linux x64 / arm64 | `anydoc/x64-linux/` · `anydoc/arm64-linux/` | `libanydoc_go.so` |

### ⚠️ 本机（Windows）为什么编不出其它平台的 `.so` / `.dylib`（实测结论）

`cdylib` 是**动态库**，必须在目标平台的工具链下最终链接，交叉编译要额外 sysroot：

| 尝试 | 结果 |
|---|---|
| `--target x86_64-unknown-linux-gnu` | ❌ 缺 glibc sysroot：`rust-lld: unable to find library -lc / -lm / -lpthread` |
| `--target x86_64-unknown-linux-musl` | ⚠️ **编译成功但 Rust 直接丢弃 cdylib**：`dropping unsupported crate type 'cdylib' for target ...musl` ⇒ 只出 `.a` 静态库，没有 `.so` |
| `--target x86_64-apple-darwin` | 需要 Apple SDK（本机没有），且 macOS 对 dylib 有签名/公证要求 |

⇒ 正确做法是在 **Linux 机（或 ubuntu CI）** 上编 `.so`、**Mac（或 macos CI）** 上编 `.dylib`，
然后把产物放到上表对应目录，重新打包即可。不需要改任何 Java 代码。

未随包的平台上 `AnydocNative.isAvailable()` 返回 false，`unavailableReason` 会明说
「当前平台（x64-darwin）未随包内置 …」并给出放置路径，Office 文档走内置 Office 解析。

---

## 已知边界

| 项 | 说明 |
|---|---|
| PDF | ⛔ anydoc 没有 PDF document model，错误码 `ERR_PDF_NO_MODEL=7`。`KbAnydocParser.supports()` 排除 `pdf`，PDF 走 `KbPdfParser`（逐页判文本层 + 扫描页视觉补强）。 |
| 扫描件 PDF | anydoc 不 OCR，会报 `ERR_NEEDS_OCR=10`。 |
| 图片 / 音频 | 不在 anydoc 范围。图片走 `KbImageParser`（视觉模型），音频尚未接入。 |
| `csv` / `md` / `txt` / `json` | anydoc 能转 csv，但本仓按源站「简单格式不接管」语义留给 `KbTextParser`（它的 CSV→Markdown 表格转义与源站逐字对齐）。 |
