#!/usr/bin/env bash
# 四仓同步：GitHub master / GitHub harness / SVN 主 / SVN 场景。
#
# 用法：
#   bash sync-repos.sh --repo <路径> [--harness <路径>] [--svn-wc <路径>] [--scene-wt <路径>]
#                       [--base <commit>]        # 增量镜像的起点（默认 = 上次同步点）
#                       [--skip-harness] [--skip-svn-main] [--skip-svn-scene] [--push]
#
# ⛔ 本脚本只做**机械同步**（拷文件 / svn add / commit）。
#    冲突解决、提交内容取舍、CHANGELOG 措辞 —— 一律由 agent 判断。
#    ⛔ 仓库根禁用 `git add -A` / `git add .`（曾一次卷进 6000+ 文件）—— 一律显式路径。
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

REPO=""; HARNESS=""; SVN_WC=""; SCENE_WT=""; BASE=""; PUSH=0
SKIP_H=0; SKIP_MAIN=0; SKIP_SCENE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --repo)     REPO="$2"; shift 2;;
    --harness)  HARNESS="$2"; shift 2;;
    --svn-wc)   SVN_WC="$2"; shift 2;;
    --scene-wt) SCENE_WT="$2"; shift 2;;
    --base)     BASE="$2"; shift 2;;
    --push)     PUSH=1; shift;;
    --skip-harness)  SKIP_H=1; shift;;
    --skip-svn-main) SKIP_MAIN=1; shift;;
    --skip-svn-scene) SKIP_SCENE=1; shift;;
    *) _die "未知参数：$1";;
  esac
done

REPO=$(detect_repo "$REPO")
HARNESS=$(detect_harness "$HARNESS")
SVN_WC=$(detect_svn_wc "$SVN_WC")
SCENE_WT=$(detect_scene_wt "$SCENE_WT")
[ -n "$BASE" ] || BASE=$(git -C "$REPO" rev-parse HEAD)

# ── 0. 场景线绝不进 GitHub ─────────────────────────────────────────
_step "sync" "前置：场景线外泄检查"
assert_scene_not_on_github "$REPO"

# ── 1. GitHub master ──────────────────────────────────────────────
_step "sync" "GitHub master"
if [ "$PUSH" = "1" ]; then
  git -C "$REPO" push origin master && _ok "已推送 master"
else
  _warn "未推送（--push 才推）"
fi

# ── 2. GitHub harness（镜像 master 的映射子集）─────────────────────
_step "sync" "GitHub harness 镜像"
if [ "$SKIP_H" = "1" ]; then
  _warn "跳过"
elif [ -z "$HARNESS" ]; then
  _warn "未探测到 harness 仓 —— 跳过（用 --harness 指定）"
else
  git -C "$REPO" diff --name-status "$BASE"..HEAD > /tmp/harness-diff.txt 2>/dev/null || true
  copied=0; skipped=0; unmapped=0
  while IFS=$'\t' read -r st path; do
    [ -n "$path" ] || continue
    case "$path" in
      backend/src/*)      tgt="src/${path#backend/src/}";;
      backend/pom.xml)    tgt="pom.xml";;
      backend/scripts/*)  tgt="scripts/backend/${path#backend/scripts/}";;
      backend/anydoc_go.dll) tgt="anydoc_go.dll";;
      front/src/*)        tgt="frontend/${path#front/}";;
      front/src-tauri/*)  tgt="frontend/${path#front/}";;
      front/public/*)     tgt="frontend/${path#front/}";;
      front/scripts/*)    tgt="frontend/${path#front/}";;
      front/package.json|front/package-lock.json|front/CHANGELOG.md|front/.gitignore) tgt="frontend/${path#front/}";;
      backend/CHANGELOG.md|.gitattributes|marketplace/*|docs/*|CLAUDE.md|AGENTS.md|.claude/*|.gitignore) skipped=$((skipped+1)); continue;;
      *) echo "  UNMAPPED: $st $path"; unmapped=$((unmapped+1)); continue;;
    esac
    if [ "$st" = "D" ]; then
      rm -f "$HARNESS/$tgt"
    else
      mkdir -p "$HARNESS/$(dirname "$tgt")"
      cp "$REPO/$path" "$HARNESS/$tgt"
      copied=$((copied+1))
    fi
  done < /tmp/harness-diff.txt
  _ok "镜像完成：copied=$copied skipped=$skipped unmapped=$unmapped"
  # 显式路径暂存（⛔ 不用 git add -A）
  git -C "$HARNESS" add -- $(cd "$HARNESS" && git diff --name-only --diff-filter=ACMR | tr '\n' ' ') 2>/dev/null || true
  git -C "$HARNESS" diff --cached --name-only | sed 's/^/    staged: /' | head -30
  _warn "⛔ 提交与推送由你（agent）决定；核验用：git -C '$HARNESS' diff --cached --name-only"
fi

# ── 3. SVN 主 ──────────────────────────────────────────────────────
_step "sync" "SVN 主"
if [ "$SKIP_MAIN" = "1" ]; then
  _warn "跳过"
elif [ -z "$SVN_WC" ]; then
  _warn "未探测到 SVN 工作副本 —— 跳过（用 --svn-wc 指定）"
else
  SCRIPT="$SVN_WC/sync-to-svn.sh"
  if [ -f "$SCRIPT" ]; then
    (cd "$SVN_WC" && bash sync-to-svn.sh "$REPO" 2>&1 | tail -12)
    _ok "SVN 主已同步"
  else
    _warn "没有 $SCRIPT —— 手动镜像：git archive HEAD 解包覆盖 + svn add + commit"
  fi
fi

# ── 3b. 场景合并后的**依赖自检**（场景专属依赖易被 master 覆盖）──────
#   坑：场景线的 backend/pom.xml 携带**场景专属依赖**（langgraph4j-core / milvus-sdk-java
#   及其版本属性）。合并 master 时若整份 pom 取 master ⇒ 这两个依赖消失 ⇒
#   场景后端编译报「找不到 org.bsc.langgraph4j / io.milvus」⇒ 场景线直接构建不出来。
#   ⇒ 合并后必须跑一次场景后端编译；缺依赖时按场景原 pom 的块并回。
_step "sync" "场景依赖自检"
if [ -n "$SCENE_WT" ] && [ -d "$SCENE_WT/backend" ]; then
  MISSING=""
  for pat in "langgraph4j-core" "milvus-sdk-java"; do
    grep -q "$pat" "$SCENE_WT/backend/pom.xml" || MISSING="$MISSING $pat"
  done
  if [ -n "$MISSING" ]; then
    _warn "场景 pom 缺少场景专属依赖:$MISSING ⇒ 场景后端编译会失败（取 master 的 pom 覆盖所致）"
    _warn "  修法：从场景合并前的 pom（git show <合并前 commit>:backend/pom.xml）取回该 <dependency> 块与版本属性"
  else
    _ok "场景专属依赖在位"
  fi
fi

# ── 4. SVN 场景（只进 SVN）─────────────────────────────────────────
_step "sync" "SVN 场景"
if [ "$SKIP_SCENE" = "1" ]; then
  _warn "跳过"
elif [ -z "$SVN_WC" ] || [ -z "$SCENE_WT" ]; then
  _warn "缺 SVN 工作副本或场景 worktree —— 跳过"
else
  SCRIPT="$SVN_WC/sync-scene-to-svn.sh"
  if [ -f "$SCRIPT" ]; then
    (cd "$SVN_WC" && bash sync-scene-to-svn.sh "$SCENE_WT" 2>&1 | tail -10)
    _ok "SVN 场景已同步（⛔ 绝不推 feat/scene-fusion 到 GitHub）"
  else
    _warn "没有 $SCRIPT —— 手动镜像"
  fi
fi

_step "sync" "完成"
echo "    提醒：核验 SVN 用**具体文件路径**查 rev（目录节点 rev 不可靠）："
echo "      svn log -l 1 <svn-wc>/nexusai/backend/pom.xml"
