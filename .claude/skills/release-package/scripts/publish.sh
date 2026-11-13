#!/usr/bin/env bash
# 双源发布：MinIO（自更新源）+ GitHub Release。
#
# 用法：
#   bash publish.sh --worktree <构建用的 worktree 目录> --version <x.y.z>
#                    [--target public|scene|internal] [--gh-repo <owner/name>] [--skip-minio] [--skip-gh]
#
# ⛔ ⛔ **禁止给 release-publish.mjs 传 --exe**：
#   --exe 分支会跳过「复制到 <repo>/release/」，而内嵌的 release-update.mjs 只认
#   `<repo>/release/NexusAI_{version}_x64-setup.exe` ⇒ 「未找到安装包」⇒ latest.json
#   生不出来 ⇒ 整个发布中止（MinIO/GitHub 都没推）。
#   ⇒ 正确姿势：把产物放到 <worktree>/front/src-tauri/target/release/bundle/nsis/，
#     然后**不带 --exe** 跑 release-publish.mjs（它自己会复制 + 生成 latest.json）。
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

WORKTREE=""; VERSION=""; TARGET="public"; GH_REPO="zouyangxiaohao111/claude-harness-java"; SKIP_MINIO=0; SKIP_GH=0
while [ $# -gt 0 ]; do
  case "$1" in
    --worktree) WORKTREE="$2"; shift 2;;
    --version)  VERSION="$2"; shift 2;;
    --target)   TARGET="$2"; shift 2;;
    --gh-repo)  GH_REPO="$2"; shift 2;;
    --skip-minio) SKIP_MINIO=1; shift;;
    --skip-gh)  SKIP_GH=1; shift;;
    *) _die "未知参数：$1";;
  esac
done

[ -n "$WORKTREE" ] || _die "必须给 --worktree（发布脚本要在构建树里跑：凭据与 release/ 都在那儿）"
[ -d "$WORKTREE/front/scripts" ] || _die "worktree 不完整：$WORKTREE/front/scripts 不存在"

# 场景版与内网版：一律**跳过 GitHub**（铁律：场景版 exe 只上 MinIO）
case "$TARGET" in
  scene|internal) SKIP_GH=1; _ok "target=$TARGET ⇒ 跳过 GitHub Release（只上 MinIO）";;
  public) : ;;
  *) _die "未知 target：$TARGET（public|scene|internal）";;
esac
[ "$SKIP_GH" = "1" ] && SKIP_GH=1

# ── 1. 定位产物（必须让脚本自己找到它 ⇒ 不传 --exe）────────────────
_step "publish" "定位产物"
BUNDLE="$WORKTREE/front/src-tauri/target/release/bundle/nsis"
[ -n "$VERSION" ] || VERSION=$(repo_version "$WORKTREE")
EXE="$BUNDLE/NexusAI_${VERSION}_x64-setup.exe"
if [ ! -f "$EXE" ]; then
  # 兜底：从 cargo 共享 target 目录找同名产物并拷进来（⛔ 仍然不带 --exe）
  for c in "$WORKTREE/../../../front/src-tauri/target/release/bundle/nsis/NexusAI_${VERSION}_x64-setup.exe" \
           "$WORKTREE/../front/src-tauri/target/release/bundle/nsis/NexusAI_${VERSION}_x64-setup.exe"; do
    if [ -f "$c" ]; then
      mkdir -p "$BUNDLE"; cp "$c" "$EXE"; _ok "从共享 target 目录拷入产物"; break
    fi
  done
fi
[ -f "$EXE" ] || _die "未找到产物：$EXE"
_ok "产物就位：$EXE ($(file_size "$EXE") 字节)"

# ── 2. 发布前密钥门禁（最后一道）──────────────────────────────────
_step "publish" "发布前密钥门禁"
JAR=$(ls "$WORKTREE/front/src-tauri/backend/"*.jar 2>/dev/null | head -1 || true)
[ -n "$JAR" ] && assert_jar_no_secret "$JAR"
APPEXE=$(ls "$WORKTREE"/front/src-tauri/target/release/nexusai-client.exe 2>/dev/null | head -1 || true)
if [ -z "$APPEXE" ]; then
  APPEXE=$(ls "$WORKTREE"/../front/src-tauri/target/release/nexusai-client.exe 2>/dev/null | head -1 || true)
fi
[ -n "$APPEXE" ] && [ -f "$APPEXE" ] && \
  assert_exe_no_secret "$APPEXE" "$WORKTREE/front/src-tauri/error-report.local.json"
_ok "无密钥泄漏"

# ── 3. 发布 ───────────────────────────────────────────────────────
_step "publish" "上传双源"
cd "$WORKTREE/front"
ARGS=""
[ "$SKIP_MINIO" = "1" ] && ARGS="$ARGS --skip-minio"
[ "$SKIP_GH" = "1" ] && ARGS="$ARGS --skip-gh"
# ⚠️ 故意**不传 --exe**（见文件头说明）
# shellcheck disable=SC2086
node scripts/release-publish.mjs $ARGS
_ok "发布完成"

# ── 4. 回写主仓的 release/（否则主仓 release/ 永远停在旧版本）──────
_step "publish" "回写主仓 release/"
REPO_DIR=$(cd "$WORKTREE" && git rev-parse --show-toplevel 2>/dev/null || echo "")
if [ -n "$REPO_DIR" ] && [ -d "$WORKTREE/release" ]; then
  mkdir -p "$REPO_DIR/release"
  cp "$WORKTREE/release/"* "$REPO_DIR/release/" 2>/dev/null || true
  _ok "已回写 $REPO_DIR/release/"
  ls -la "$REPO_DIR/release/" 2>/dev/null | tail -4 | sed 's/^/      /'
else
  _warn "未能判定主仓路径 —— 请手动把产物与 latest.json 拷回 <repo>/release/"
fi

_step "publish" "产物位置"
echo "    release/ ：$WORKTREE/release/"
ls -la "$WORKTREE/release/" 2>/dev/null | sed 's/^/      /'
