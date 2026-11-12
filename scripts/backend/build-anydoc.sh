#!/usr/bin/env bash
# =============================================================================
#  build-anydoc.sh — 编译 anydoc 原生库，放进 resources/anydoc/<arch>-<os>/
#
#  为什么需要这个脚本：
#    anydoc 是 Rust 写的（Firecrawl），本仓通过 JNA 调它的 C ABI。它的源码不在
#    本仓里 —— 在 WeKnora 的 third_party/anydoc-go/。而且上游默认只出 staticlib
#    （给 Go 的 cgo 用），**没有动态库**；JNA 加载不了 .a，必须自己加 cdylib。
#    这个脚本把「拉源码 → 打两个补丁 → 加 cdylib → 编译 → 落到正确目录」串成一步。
#
#  用法（在 Git Bash / Linux / macOS 里跑）：
#    bash backend/scripts/build-anydoc.sh                 # 编当前平台
#    bash backend/scripts/build-anydoc.sh --check         # 只体检，不编译
#    bash backend/scripts/build-anydoc.sh --clone         # 没有 WeKnora 源码时自动 clone
#    WEKNORA_DIR=/path/to/WeKnora bash .../build-anydoc.sh
#
#  ⚠️ 不支持交叉编译（见脚本里的 die_cross 说明）：cdylib 必须在目标平台上链接，
#     在 Windows 上编不出 Linux 的 .so、也编不出 mac 的 .dylib。要哪个平台就上哪台机器跑。
#
#  产物落点必须与 Java 侧 AnydocNative.platformDirName() 逐字一致：
#    backend/src/main/resources/anydoc/<arch>-<os>/{anydoc_go.dll | libanydoc_go.so | libanydoc_go.dylib}
# =============================================================================
set -euo pipefail

# ── 路径 ─────────────────────────────────────────────────────────────────────
# ⚠️ 解析本脚本自身位置要同时兼容 bash 与 POSIX sh（dash）：
#   · `${BASH_SOURCE[0]}` 是 bash 专有 —— **dash 里直接 `Bad substitution` 退出**
#     （实测：Ubuntu 的 /bin/sh 是 dash）；
#   · 纯 `$0` 在 `sh script.sh` 从别的目录调用时是相对路径。
# ⇒ 只用 $0，并在下面用「dirname + 逐级找 backend/pom.xml」兜底修正。
_self="$0"
case "$_self" in
  */*) : ;;                              # 含路径分隔符：直接用
  *)   _self="$PWD/$_self" ;;            # 裸文件名（sh 在 PATH 里找的情况）
esac
SCRIPT_DIR=$(cd "$(dirname "$_self")" && pwd)
BACKEND_DIR=$(cd "$SCRIPT_DIR/.." && pwd)

# 兜底：往上找 pom.xml（最多 4 层）—— 防止 $0 解析偏了一层
if [ ! -f "$BACKEND_DIR/pom.xml" ]; then
  _probe="$SCRIPT_DIR"
  _i=0
  while [ "$_i" -lt 4 ]; do
    if [ -f "$_probe/pom.xml" ]; then BACKEND_DIR="$_probe"; break; fi
    _probe=$(cd "$_probe/.." && pwd)
    _i=$((_i + 1))
  done
fi

[ -f "$BACKEND_DIR/pom.xml" ] || {
  printf 'error: 无法定位 backend 目录（试过 %s 及其上级）\n' "$SCRIPT_DIR" >&2
  printf '       $0=%s\n' "$0" >&2
  printf '       请用绝对路径调用：bash /abs/path/backend/scripts/build-anydoc.sh\n' >&2
  exit 2
}
REPO_DIR=$(cd "$BACKEND_DIR/.." && pwd)
RES_DIR="$BACKEND_DIR/src/main/resources/anydoc"

# ── 参数 ─────────────────────────────────────────────────────────────────────
DO_CLONE=0
CHECK_ONLY=0
TARGET=""
for arg in "$@"; do
  case "$arg" in
    --check)   CHECK_ONLY=1 ;;
    --clone)   DO_CLONE=1 ;;
    --target)  echo "error: 不支持 --target（cdylib 必须在目标平台本机编译）；请在目标机器上直接运行本脚本" >&2; exit 2 ;;
    -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "error: 未知参数 '$arg'（--check / --clone / -h）" >&2; exit 2 ;;
  esac
done

say()  { printf '%s\n' "$*"; }
step() { printf '\n=== %s ===\n' "$*"; }
warn() { printf '⚠️  %s\n' "$*" >&2; }
die()  { printf '❌ %s\n' "$*" >&2; exit 1; }

# ── 1) 工具链 ────────────────────────────────────────────────────────────────
step "1/7 检查工具链"
command -v cargo >/dev/null 2>&1 || die "找不到 cargo。装 Rust：https://rustup.rs（Windows 还需 VS Build Tools 的「使用 C++ 的桌面开发」）"
command -v rustc >/dev/null 2>&1 || die "找不到 rustc（通常与 cargo 一起装）"
command -v git  >/dev/null 2>&1 || warn "没有 git（只有 --clone 模式需要它）"
for t in tar curl sed awk; do
  command -v "$t" >/dev/null 2>&1 || die "缺少基础工具：$t"
done
HOST_TRIPLE=$(rustc -vV | sed -n 's/^host: //p')
say "rustc     $(rustc --version)"
say "host       $HOST_TRIPLE"

# ── 2) 目标平台 → 目录名（必须与 Java 侧逐字一致）────────────────────────────
step "2/7 推导平台目录"

# target triple 里的「架构-系统」两段；与 AnydocNative.resolveArch/resolveOs 同口径。
# ⛔ 这段映射改了必须同步改 AnydocNative.platformDirName()（有单测守着资源路径）。
platform_dir_of() {
  local triple="$1" arch os
  case "$triple" in
    x86_64-*|amd64-*) arch=x64 ;;
    aarch64-*|arm64-*) arch=arm64 ;;
    *) return 1 ;;
  esac
  case "$triple" in
    *windows*)            os=win32 ;;
    *apple-darwin*)       os=darwin ;;
    *linux*)              os=linux ;;
    *) return 1 ;;
  esac
  printf '%s-%s' "$arch" "$os"
}

lib_name_of() {
  case "$1" in
    *-win32)  printf 'anydoc_go.dll' ;;
    *-darwin) printf 'libanydoc_go.dylib' ;;
    *-linux)  printf 'libanydoc_go.so' ;;
    *) return 1 ;;
  esac
}

PLATFORM_DIR=$(platform_dir_of "$HOST_TRIPLE") \
  || die "无法从 $HOST_TRIPLE 推出平台目录（支持 x86_64/aarch64 × windows/darwin/linux）"
LIB_FILE=$(lib_name_of "$PLATFORM_DIR") || die "未知平台：$PLATFORM_DIR"
say "target     $HOST_TRIPLE"
say "平台目录    $PLATFORM_DIR"
say "库文件名    $LIB_FILE"

# ── 3) 定位 WeKnora 的 anydoc-go 源码 ────────────────────────────────────────
step "3/7 定位 anydoc-go 源码"

find_src() {
  local candidate
  for candidate in \
      "${WEKNORA_DIR:-}" \
      "D:/tmp/WeKnora" \
      "$HOME/WeKnora" \
      "/tmp/WeKnora" \
      "$REPO_DIR/../WeKnora" ; do
    [ -n "$candidate" ] || continue
    if [ -f "$candidate/third_party/anydoc-go/Cargo.toml" ]; then
      printf '%s' "$candidate/third_party/anydoc-go"
      return 0
    fi
  done
  return 1
}

SRC=$(find_src || true)
if [ -z "$SRC" ] && [ "$DO_CLONE" = "1" ]; then
  CLONE_DIR="${TMPDIR:-/tmp}/nexusai-weknora-src"
  if [ -d "$CLONE_DIR/.git" ]; then
    say "复用已有克隆 $CLONE_DIR（要强制更新，删掉它再跑）"
  else
    say "克隆 WeKnora（--depth 1，约 270MB，可能要几分钟）→ $CLONE_DIR"
    git clone --depth 1 https://github.com/Tencent/WeKnora.git "$CLONE_DIR" \
      || die "克隆失败（网络/代理）。可手动 clone 后用 WEKNORA_DIR=<路径> 重跑"
  fi
  SRC="$CLONE_DIR/third_party/anydoc-go"
fi

if [ -z "$SRC" ]; then
  die "找不到 anydoc-go 源码。三选一：
   ① 设 WEKNORA_DIR 指向 WeKnora 克隆：WEKNORA_DIR=D:/tmp/WeKnora bash $0
   ② 加 --clone 让脚本自己拉
   ③ 手动 clone：
       git clone --depth 1 https://github.com/Tencent/WeKnora.git D:/tmp/WeKnora"
fi
say "源码       $SRC"
SRC_VERSION=$(sed -n 's/^anydoc = "=\([0-9][^"]*\)".*/\1/p' "$SRC/Cargo.toml" | head -1)
[ -n "$SRC_VERSION" ] || die "读不出 $SRC/Cargo.toml 里的 anydoc 版本（期望 anydoc = \"=X.Y.Z\"）"
say "版本       anydoc $SRC_VERSION"

# Java 侧缓存目录带版本号 —— 版本变了要提醒同步，否则旧缓存不会被判失效。
JAVA_VERSION=$(sed -n 's/.*ANYDOC_VERSION = "\([0-9.]*\)".*/\1/p' \
  "$BACKEND_DIR/src/main/java/com/nexusai/kb/app/parser/AnydocNative.java" | head -1)
if [ -n "$JAVA_VERSION" ] && [ "$JAVA_VERSION" != "$SRC_VERSION" ]; then
  warn "AnydocNative.ANYDOC_VERSION=$JAVA_VERSION 但源码是 $SRC_VERSION —— 升级库时请同步改它（它只是缓存目录的版本隔离，不改也能跑，但旧缓存不会自动作废）"
fi

if [ "$CHECK_ONLY" = "1" ]; then
  step "仅体检（--check），到此为止"
  say "工具链 / 平台推导 / 源码定位 都正常。去掉 --check 即可真正编译。"
  exit 0
fi

# ── 4) 复制到临时工作目录（⛔ 不改用户的 WeKnora 克隆）────────────────────────
step "4/7 准备构建工作目录"
WORK="${TMPDIR:-/tmp}/nexusai-anydoc-build/anydoc-go"
rm -rf "$WORK"
mkdir -p "$WORK"
# ⚠️ 不用 `tar --exclude`：macOS 自带的是 BSD tar，`--exclude=./target` 这类 GNU 写法
#    在 BSD tar 上语法不同（会报 unrecognized option）。手工逐项拷，跨平台一致。
#    排除 target/（可能几 GB 的编译缓存）与 lib/（Go 侧的静态库产物，Rust 用不到）。
( cd "$SRC" && for entry in * .cargo .gitignore; do
    [ -e "$entry" ] || continue
    case "$entry" in
      target|lib|patched-anydoc) continue ;;   # patched-anydoc 下面会重建
    esac
    cp -R "$entry" "$WORK/"
  done )
say "工作目录    $WORK"

# crates.io 被墙 ⇒ 必须配国内镜像（写在工作副本里，不动源目录）。
# ⚠️ 若用户源目录已有 .cargo/config.toml（含镜像配置），上面 tar 已经带过来了；
#    只有缺失时才补一份，避免覆盖公司内网镜像。
if [ ! -f "$WORK/.cargo/config.toml" ]; then
  mkdir -p "$WORK/.cargo"
  cat > "$WORK/.cargo/config.toml" <<'EOF'
[source.crates-io]
replace-with = 'rsproxy-sparse'

[source.rsproxy-sparse]
registry = "sparse+https://rsproxy.cn/index/"
EOF
  say "crates 镜像 已写入 $WORK/.cargo/config.toml（rsproxy）"
else
  say "crates 镜像 沿用源码里已有的 .cargo/config.toml"
fi

# ── 5) patched-anydoc ────────────────────────────────────────────────────────
# 上游 [patch.crates-io] anydoc = { path = "patched-anydoc" } 指向一个**被 gitignore**
# 的目录（仓库里没有）⇒ 不先准备就必然 `cargo build` 失败。
# 两个补丁（与 WeKnora scripts/build-anydoc-lib.sh 逐条一致）：
#   ① document_to_markdown 由 crate 私有改成 pub use（我们只出 markdown，不需要 document 模型）
#   ② zip 8.6.0 → 9（RUSTSEC-2026-0367/0368/0369）
step "5/7 准备 patched-anydoc"
PATCH_ID="${SRC_VERSION}-zip9-v1"
DEST="$WORK/patched-anydoc"

prepare_patched() {
  if [ -f "$DEST/.nexusai-patched" ] \
     && [ "$(cat "$DEST/.nexusai-patched")" = "$PATCH_ID" ] \
     && [ -f "$DEST/Cargo.toml" ]; then
    say "已就绪（$PATCH_ID），跳过"
    return
  fi

  # 优先用 cargo registry 里已解包的源码，省一次下载
  local src=""
  src=$(find "${CARGO_HOME:-$HOME/.cargo}/registry/src" -maxdepth 2 -type d \
          -name "anydoc-$SRC_VERSION" 2>/dev/null | head -1 || true)

  if [ -z "$src" ]; then
    local tarball="$WORK/.anydoc-$SRC_VERSION.crate"
    say "下载 anydoc $SRC_VERSION 源码（rsproxy → crates.io 兜底）"
    if ! curl -fsSL "https://rsproxy.cn/api/v1/crates/anydoc/$SRC_VERSION/download" -o "$tarball"; then
      curl -fsSL "https://static.crates.io/crates/anydoc/anydoc-$SRC_VERSION.crate" -o "$tarball" \
        || die "下载 anydoc-$SRC_VERSION.crate 失败（网络/代理）"
    fi
    local unpack="$WORK/.anydoc-unpack"
    rm -rf "$unpack"; mkdir -p "$unpack"
    tar -xzf "$tarball" -C "$unpack" || die "解包 $tarball 失败"
    src=$(find "$unpack" -maxdepth 1 -type d -name "anydoc-$SRC_VERSION" | head -1)
    [ -n "$src" ] || die "解包里没有 anydoc-$SRC_VERSION 目录"
  fi

  rm -rf "$DEST"
  cp -R "$src" "$DEST"

  # 补丁①：document_to_markdown 变成公开
  grep -q '^use render::markdown::document_to_markdown;$' "$DEST/src/lib.rs" \
    || die "anydoc $SRC_VERSION 的 lib.rs 里没有预期的 document_to_markdown 导入（上游改结构了，需重新核对补丁）"
  sed 's/^use render::markdown::document_to_markdown;$/pub use render::markdown::document_to_markdown;/' \
      "$DEST/src/lib.rs" > "$DEST/src/lib.rs.new"
  mv "$DEST/src/lib.rs.new" "$DEST/src/lib.rs"

  # 补丁②：zip 8.6.0 → 9
  if ! awk '
    /^\[/ { in_zip = ($0 == "[dependencies.zip]") }
    in_zip && /^version = / {
      if ($0 != "version = \"8.6.0\"") exit 1
      $0 = "version = \"9\""
      patched++
    }
    { print }
    END { if (patched != 1) exit 1 }
  ' "$DEST/Cargo.toml" > "$DEST/Cargo.toml.new"; then
    rm -f "$DEST/Cargo.toml.new"
    die "anydoc $SRC_VERSION 的 zip 依赖不再是 8.6.0（上游已修 CVE），请去掉本补丁后重试"
  fi
  mv "$DEST/Cargo.toml.new" "$DEST/Cargo.toml"

  printf '%s\n' "$PATCH_ID" > "$DEST/.nexusai-patched"
  rm -rf "$WORK/.anydoc-unpack" "$WORK/.anydoc-$SRC_VERSION.crate"
  say "已打好补丁（$PATCH_ID）"
}
prepare_patched

# ── 6) 加 cdylib 并编译 ──────────────────────────────────────────────────────
# 上游 crate-type 只有 "staticlib"（给 Go 的 cgo 用）⇒ 不产动态库，JNA 无从加载。
# 加 "cdylib" 不影响 Go 侧：cargo 会同时产出 .lib/.a 与 .dll/.so/.dylib。
step "6/7 编译"
if grep -q 'crate-type = \["staticlib", "cdylib"\]' "$WORK/Cargo.toml"; then
  say "crate-type 已是 [staticlib, cdylib]"
else
  if ! grep -q '^crate-type = \["staticlib"\]$' "$WORK/Cargo.toml"; then
    die "$WORK/Cargo.toml 的 [lib] crate-type 不是预期的 [\"staticlib\"]（上游格式变了，请人工核对）"
  fi
  sed 's/^crate-type = \["staticlib"\]$/crate-type = ["staticlib", "cdylib"]/' \
      "$WORK/Cargo.toml" > "$WORK/Cargo.toml.new"
  mv "$WORK/Cargo.toml.new" "$WORK/Cargo.toml"
  say "已把 crate-type 改为 [staticlib, cdylib]"
fi

# musl 目标会被 Rust 直接丢弃 cdylib（实测：dropping unsupported crate type 'cdylib'），
# 编完没有产物、退出码却是 0 ⇒ 必须提前拦，否则用户拿到「成功」但没有文件。
case "$HOST_TRIPLE" in
  *musl*) die "musl 目标不支持 cdylib（Rust 会静默丢弃该 crate-type，只出 .a 静态库）。
   Linux 请用 gnu 版工具链（rustup default stable 默认就是 gnu）。" ;;
esac

say "cargo build --release --locked --target $HOST_TRIPLE"
( cd "$WORK" && cargo build --release --locked --target "$HOST_TRIPLE" )

# ── 7) 落到 resources 并校验 ────────────────────────────────────────────────
step "7/7 落地并校验"
BUILT="$WORK/target/$HOST_TRIPLE/release/$LIB_FILE"
[ -f "$BUILT" ] || die "编译结束但没找到产物 $BUILT
   可能原因：crate-type 没生效 / target 目录结构变了。请 ls $WORK/target/$HOST_TRIPLE/release/ 自查。"
BUILT_SIZE=$(wc -c < "$BUILT" | tr -d ' ')
[ "$BUILT_SIZE" -gt 1000000 ] || die "产物只有 $BUILT_SIZE 字节，明显不对（期望数 MB）"

mkdir -p "$RES_DIR/$PLATFORM_DIR"
cp "$BUILT" "$RES_DIR/$PLATFORM_DIR/$LIB_FILE"
DEST_FILE="$RES_DIR/$PLATFORM_DIR/$LIB_FILE"

# 魔数校验：Windows=PE(MZ) / Linux=ELF / macOS=Mach-O
case "$LIB_FILE" in
  *.dll)    HEAD=$(od -An -c -N2 "$DEST_FILE" | tr -d ' ') ; [ "$HEAD" = "MZ" ] || die "产物不是 PE 文件（头=$HEAD）" ;;
  *.so)     HEAD=$(od -An -tx1 -N4 "$DEST_FILE" | tr -d ' ') ; [ "$HEAD" = "7f454c46" ] || die "产物不是 ELF 文件（头=$HEAD）" ;;
  *.dylib)  HEAD=$(od -An -tx1 -N4 "$DEST_FILE" | tr -d ' ') ; case "$HEAD" in cffaedfe|cefaedfe|cafebabe) ;; *) die "产物不是 Mach-O 文件（头=$HEAD）" ;; esac ;;
esac

say ""
say "✅ 完成"
say "   产物   $DEST_FILE"
say "   大小   $BUILT_SIZE 字节（$(awk "BEGIN{printf \"%.1f\", $BUILT_SIZE/1048576}") MB）"
say "   平台   $PLATFORM_DIR（Java 侧 AnydocNative 会按同一目录名从 classpath 提取）"
say ""
say "下一步："
say "   cd backend && mvn -o package -Dmaven.test.skip=true"
say "   # 确认库真的进了 jar："
say "   jar --list --file target/nexusai-backend.jar | grep BOOT-INF/classes/anydoc/"
say "   # 期望 BOOT-INF/classes/anydoc/$PLATFORM_DIR/$LIB_FILE"
say ""
say "其它平台要各自在自己机器上跑本脚本（cdylib 不能交叉编译）：
   Windows → x64-win32 / arm64-win32      （anydoc_go.dll）
   macOS   → x64-darwin / arm64-darwin    （libanydoc_go.dylib）
   Linux   → x64-linux  / arm64-linux     （libanydoc_go.so，须 gnu 工具链）"
