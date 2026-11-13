#!/usr/bin/env bash
# 发布核验：四方哈希 + 四仓终态 + 密钥零泄漏。
#
# 用法：
#   bash verify-release.sh --repo <路径> [--version <x.y.z>] [--harness <路径>]
#                          [--svn-wc <路径>] [--worktree <构建树>] [--skip-download]
#
# ⛔ SVN 的 rev **必须按具体文件路径查**：`svn log -l 1 <目录>` 给的是目录节点的
#    最后改动 rev（可能很旧），而镜像内容级全等要用**本次提交那个 rev**：
#      svn log -l 1 <wc>/nexusai/backend/pom.xml   ← 取这个
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

REPO=""; VERSION=""; HARNESS=""; SVN_WC=""; WORKTREE=""; SKIP_DL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --repo)    REPO="$2"; shift 2;;
    --version) VERSION="$2"; shift 2;;
    --harness) HARNESS="$2"; shift 2;;
    --svn-wc)  SVN_WC="$2"; shift 2;;
    --worktree) WORKTREE="$2"; shift 2;;
    --skip-download) SKIP_DL=1; shift;;
    *) _die "未知参数：$1";;
  esac
done

REPO=$(detect_repo "$REPO")
HARNESS=$(detect_harness "$HARNESS")
SVN_WC=$(detect_svn_wc "$SVN_WC")
[ -n "$VERSION" ] || VERSION=$(repo_version "$REPO")

# ── 1. 哈希三方一致（本地 / MinIO 回拉 / GitHub 服务端 digest）─────────
_step "verify" "哈希一致性（version=$VERSION）"
LOCAL_EXE="$REPO/front/src-tauri/target/release/bundle/nsis/NexusAI_${VERSION}_x64-setup.exe"
[ -f "$LOCAL_EXE" ] || LOCAL_EXE=$(ls "$REPO/release/NexusAI_${VERSION}_x64-setup.exe" 2>/dev/null | head -1 || true)
if [ -z "$LOCAL_EXE" ] || [ ! -f "$LOCAL_EXE" ]; then
  _die "找不到本地安装包（用 --version 确认，或先 build）"
fi
LH=$(sha256_of "$LOCAL_EXE")
LSIZE=$(file_size "$LOCAL_EXE")
_ok "本地：$LH（$LSIZE 字节）"

if [ "$SKIP_DL" = "0" ]; then
  TMPX="${TMPDIR:-/tmp}/verify-minio-$VERSION.exe"
  if [ -x "/c/Users/WIN/mc/mc.exe" ] || command -v mc >/dev/null 2>&1; then
    MC=$(command -v mc || echo "/c/Users/WIN/mc/mc.exe")
    if "$MC" cp --quiet "nexusai/nexusai/updater/NexusAI_${VERSION}_x64-setup.exe" "$TMPX" 2>/dev/null; then
      MH=$(sha256_of "$TMPX")
      [ "$MH" = "$LH" ] && _ok "MinIO 回拉一致：$MH" || _die "MinIO 回拉**不一致**：$MH ≠ $LH"
      rm -f "$TMPX"
    else
      _warn "MinIO 回拉失败（未登录或对象不存在）"
    fi
  fi
  if command -v gh >/dev/null 2>&1; then
    D="${TMPDIR:-/tmp}/verify-gh-$VERSION"
    rm -rf "$D"; mkdir -p "$D"
    if gh release download "v$VERSION" --repo zouyangxiaohao111/claude-harness-java \
        --pattern "NexusAI_${VERSION}_x64-setup.exe" --dir "$D" >/dev/null 2>&1; then
      GH_H=$(sha256_of "$D/NexusAI_${VERSION}_x64-setup.exe")
      [ "$GH_H" = "$LH" ] && _ok "GitHub 资产一致：$GH_H" || _die "GitHub 资产**不一致**：$GH_H ≠ $LH"
      rm -rf "$D"
    else
      _warn "GitHub 资产回拉失败（国内网络常断流）—— 可改用服务端 digest："
      echo "        gh api repos/zouyangxiaohao111/claude-harness-java/releases/tags/v$VERSION --jq '.assets[].digest'"
    fi
  fi
fi

# ── 2. latest.json 声明 ────────────────────────────────────────────
_step "verify" "latest.json 声明"
# ⚠️ latest.json 生成在**构建树**的 release/ 里（发布脚本从那儿跑）⇒ 优先读 worktree 的
_LJ=""
[ -n "$WORKTREE" ] && [ -f "$WORKTREE/release/latest.json" ] && _LJ="$WORKTREE/release/latest.json"
[ -z "$_LJ" ] && [ -f "$REPO/release/latest.json" ] && _LJ="$REPO/release/latest.json"
if [ -n "$_LJ" ]; then
  echo "    清单：$_LJ"
  python - "$_LJ" "$LH" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
want = sys.argv[2]
p = d.get("platforms", {}).get("windows-x86_64", {})
print("    version:", d.get("version"), "| 声明 sha256:", p.get("sha256", "")[:16], "...")
sys.exit(0 if p.get("sha256") == want else 1)
PY
  [ $? = 0 ] && _ok "latest.json 与本地一致" || _die "latest.json 声明的 sha256 与本地不一致"
else
  _warn "找不到 latest.json（构建树与主仓都没有）"
fi

# ── 3. 四仓终态 ───────────────────────────────────────────────────
_step "verify" "四仓终态"
echo "    GitHub master : $(git -C "$REPO" ls-remote origin refs/heads/master 2>/dev/null | cut -c1-12)"
echo "    GitHub tag    : $(git -C "$REPO" ls-remote origin "refs/tags/v$VERSION" 2>/dev/null | cut -c1-12)"
if [ -n "$HARNESS" ]; then
  echo "    harness main  : $(git -C "$HARNESS" ls-remote origin refs/heads/main 2>/dev/null | cut -c1-12)"
fi
assert_scene_not_on_github "$REPO"

if [ -n "$SVN_WC" ]; then
  for sub in nexusai nexusai-scene; do
    [ -d "$SVN_WC/$sub" ] || continue
    REV=$(svn log -l 1 "$SVN_WC/$sub/backend/pom.xml" 2>/dev/null | grep -oE "^r[0-9]+" | head -1 | tr -d 'r')
    if [ -z "$REV" ]; then
      _warn "$sub：查不到 rev"
      continue
    fi
    DIFFOUT=$(svn diff --summarize -r "${REV}:HEAD" "$SVN_WC/$sub" 2>/dev/null | head -5)
    if [ -z "$DIFFOUT" ]; then
      _ok "$sub：r$REV 内容级全等"
    else
      _die "$sub：r$REV 之后仍有差异 ⇒ 镜像不完整：$DIFFOUT"
    fi
  done
  PENDING=$(svn status "$SVN_WC" 2>/dev/null | grep -v '^$' | head -3)
  [ -z "$PENDING" ] && _ok "SVN 工作副本无 pending" || _warn "SVN 有 pending：$PENDING"
fi

# ── 4. 密钥零泄漏 ─────────────────────────────────────────────────
_step "verify" "密钥零泄漏"
JAR=""
[ -n "$WORKTREE" ] && JAR=$(ls "$WORKTREE"/front/src-tauri/backend/*.jar 2>/dev/null | head -1 || true)
[ -z "$JAR" ] && JAR=$(ls "$REPO"/front/src-tauri/backend/*.jar 2>/dev/null | head -1 || true)
[ -n "$JAR" ] && assert_jar_no_secret "$JAR" && _ok "jar 内无 factory-preset.local.json"
APPEXE=""
[ -n "$WORKTREE" ] && APPEXE=$(ls "$WORKTREE"/front/src-tauri/target/release/nexusai-client.exe 2>/dev/null | head -1 || true)
[ -z "$APPEXE" ] && APPEXE=$(ls "$REPO"/front/src-tauri/target/release/nexusai-client.exe 2>/dev/null | head -1 || true)
[ -n "$APPEXE" ] && assert_exe_no_secret "$APPEXE" "$REPO/front/src-tauri/error-report.local.json" && _ok "app exe 内无错误上报 key"

_step "verify" "全部通过"
