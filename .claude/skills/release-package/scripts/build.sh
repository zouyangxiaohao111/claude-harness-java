#!/usr/bin/env bash
# 在**独立 detached worktree** 上构建发布包（公网口径：不含任何内网密钥）。
#
# 用法：
#   bash build.sh [--repo <路径>] [--version <x.y.z>] [--out <worktree 目录>]
#                 [--target public|scene] [--cargo-target <目录>] [--skip-anydoc-check]
#
# ⛔ 为什么必须 detached worktree：
#   主工作区常被别的会话实时改动（半成品代码 / 在制品），在其中构建会把
#   未提交内容与内网密钥一起打进包。detached worktree 只含**已提交状态**，
#   且天然没有 gitignored 的密钥文件。
#
# ⛔ node_modules 必须用 **junction** 复用主工作区的，不要 npm ci：
#   npm ci 会重解依赖树，把 @univerjs/core 装成两份（顶层 1.0.2 + presets 内嵌 0.25.1）
#   ⇒ tsc 报 TS2322（ExcelPreview.tsx）而本地却"能编过"。
#
# ⛔ 收尾必须先拆 junction 再 git worktree remove：
#   否则 remove 会顺着 junction 删掉**主工作区的 node_modules**。
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

REPO=""; VERSION=""; OUT=""; TARGET="public"; CARGO_TARGET=""; SKIP_ANYDOC=0
while [ $# -gt 0 ]; do
  case "$1" in
    --repo)       REPO="$2"; shift 2;;
    --version)    VERSION="$2"; shift 2;;
    --out)        OUT="$2"; shift 2;;
    --target)     TARGET="$2"; shift 2;;
    --cargo-target) CARGO_TARGET="$2"; shift 2;;
    --skip-anydoc-check) SKIP_ANYDOC=1; shift;;
    *) _die "未知参数：$1";;
  esac
done

REPO=$(detect_repo "$REPO")
[ -n "$VERSION" ] || VERSION=$(repo_version "$REPO")
[ -n "$OUT" ] || OUT="${TMPDIR:-/tmp}/nexusai-release-$VERSION"
[ -n "$CARGO_TARGET" ] || CARGO_TARGET="$REPO/front/src-tauri/target"

_step "build" "repo=$REPO  version=$VERSION  target=$TARGET"
echo "    worktree：$OUT"
echo "    cargo 缓存：$CARGO_TARGET"

# ── 1. 建 detached worktree ───────────────────────────────────────
_step "build" "准备 detached worktree"
if [ -e "$OUT/.git" ]; then
  _warn "已存在，先移除：$OUT"
  git -C "$REPO" worktree remove --force "$OUT" 2>/dev/null || rm -rf "$OUT"
  git -C "$REPO" worktree prune
fi
git -C "$REPO" worktree add --detach "$OUT" HEAD
_ok "worktree @ $(git -C "$OUT" rev-parse --short HEAD)"

# ── 2. 密钥隔离门禁 ───────────────────────────────────────────────
_step "build" "密钥隔离检查"
assert_no_secrets_in_tree "$OUT"
_ok "构建树内三处密钥文件均不存在"

# ── 3. node_modules junction（⛔ 不要 npm ci）───────────────────────
_step "build" "复用主工作区 node_modules"
rm -rf "$OUT/front/node_modules"
if [ -d "$REPO/front/node_modules" ]; then
  WIN_OUT=$(cygpath -w "$OUT/front/node_modules" 2>/dev/null || echo "$OUT/front/node_modules")
  WIN_SRC=$(cygpath -w "$REPO/front/node_modules" 2>/dev/null || echo "$REPO/front/node_modules")
  cmd //c "mklink /J \"$WIN_OUT\" \"$WIN_SRC\"" >/dev/null 2>&1 || \
    cmd //c "mklink /J $WIN_OUT $WIN_SRC" >/dev/null 2>&1 || \
    { _warn "junction 创建失败，退回拷贝（慢但可用）"; cp -r "$REPO/front/node_modules" "$OUT/front/node_modules"; }
  _ok "node_modules 已挂 junction（⛔ 收尾要先 rmdir 它再 worktree remove）"
else
  _die "主工作区没有 front/node_modules —— 先在主仓跑 npm install"
fi

# ── 4. 发布凭据（gitignored，仅发布期用）────────────────────────────
_step "build" "准备发布凭据"
if [ -f "$REPO/front/scripts/.minio-credentials.json" ]; then
  mkdir -p "$OUT/front/scripts"
  cp "$REPO/front/scripts/.minio-credentials.json" "$OUT/front/scripts/"
  _ok "已拷入 .minio-credentials.json（gitignored，不入库）"
else
  _warn "没有 .minio-credentials.json ⇒ MinIO 上传会跳过（须有 MINIO_API/别名登录）"
fi

# ── 5. 构建 ───────────────────────────────────────────────────────
_step "build" "tauri build（beforeBuildCommand 会跑 tsc+vite、mvn clean package、jlink）"
cd "$OUT/front"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-25.0.3}"
# ⚠️ 必须转成 Windows 路径：cargo / Tauri CLI 是**原生 Windows 程序**，
#    不认 MSYS 的 /d/... ⇒ 会当相对路径解析，产物落到
#    front/src-tauri/front/src-tauri/target/ 这种嵌套目录里。
export CARGO_TARGET_DIR="$(cygpath -w "$CARGO_TARGET" 2>/dev/null || echo "$CARGO_TARGET")"
set +e
npx tauri build --bundles nsis > "${TMPDIR:-/tmp}/tauri-build-$VERSION.log" 2>&1
EXIT=$?
set -e
if [ "$EXIT" != "0" ]; then
  _die "构建失败（exit $EXIT）——日志：${TMPDIR:-/tmp}/tauri-build-$VERSION.log"
fi
_ok "构建成功"

# 产物可能落在 CARGO_TARGET_DIR，也可能落在 Tauri 的默认 target（cwd 下 front/src-tauri/target）
EXE=""
for cand in "$CARGO_TARGET/release/bundle/nsis/NexusAI_${VERSION}_x64-setup.exe"             "$OUT/front/src-tauri/front/src-tauri/target/release/bundle/nsis/NexusAI_${VERSION}_x64-setup.exe"             "$OUT/front/src-tauri/target/release/bundle/nsis/NexusAI_${VERSION}_x64-setup.exe"; do
  if [ -f "$cand" ]; then EXE="$cand"; break; fi
done
[ -n "$EXE" ] || _die "未找到安装包（查过 CARGO_TARGET_DIR 与两个默认 target 位置）"
_ok "安装包：$EXE ($(file_size "$EXE") 字节)"

# ── 6. 产物内容门禁 ───────────────────────────────────────────────
_step "build" "产物内容检查"
JAR=$(ls "$OUT/front/src-tauri/backend/"*.jar 2>/dev/null | head -1 || true)
if [ -n "$JAR" ]; then
  assert_jar_no_secret "$JAR"
  _ok "jar 内无 factory-preset.local.json"
fi
APPEXE="$CARGO_TARGET/release/nexusai-client.exe"
[ -f "$APPEXE" ] && assert_exe_no_secret "$APPEXE" "$REPO/front/src-tauri/error-report.local.json" && _ok "app exe 内无错误上报 key"
if [ "$SKIP_ANYDOC" = "0" ]; then
  if [ -n "$JAR" ] && (unzip -l "$JAR" 2>/dev/null | grep -q "anydoc"); then
    _ok "anydoc 原生库已随 jar 内置"
  else
    _warn "jar 内未见 anydoc 原生库（若该功能启用，需确认是否随包）"
  fi
fi

_step "build" "产物路径（供后续 publish 用）"
echo "    EXE=$EXE"
echo "    ⛔ 收尾：rmdir '$OUT/front/node_modules' → git worktree remove --force '$OUT'"
