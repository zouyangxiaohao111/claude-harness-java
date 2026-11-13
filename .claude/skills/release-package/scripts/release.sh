#!/usr/bin/env bash
# 一键编排：预检 → 版本递增 → 提交(agent 负责) → 构建 → 发布 → 同步 → 核验
#
# 用法（所有路径/版本/目标由调用方指定）：
#   bash release.sh --repo <路径> --version <x.y.z> --target public|scene|internal \
#                   [--harness <路径>] [--svn-wc <路径>] [--scene-wt <路径>] \
#                   [--worktree <构建树>] [--steps preflight,bump,build,publish,sync,verify] \
#                   [--dry-run]
#
# ⚠️ **本脚本不提交代码**：版本递增后 git 状态由你（agent）判断并提交，
#    因为「哪些改动该进这次发布」是判断题不是机械题。
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

REPO=""; VERSION=""; TARGET="public"; HARNESS=""; SVN_WC=""; SCENE_WT=""; WORKTREE=""
STEPS="preflight,bump,build,publish,sync,verify"; DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --repo)     REPO="$2"; shift 2;;
    --version)  VERSION="$2"; shift 2;;
    --target)   TARGET="$2"; shift 2;;
    --harness)  HARNESS="$2"; shift 2;;
    --svn-wc)   SVN_WC="$2"; shift 2;;
    --scene-wt) SCENE_WT="$2"; shift 2;;
    --worktree) WORKTREE="$2"; shift 2;;
    --steps)    STEPS="$2"; shift 2;;
    --dry-run)  DRY=1; shift;;
    *) _die "未知参数：$1";;
  esac
done

REPO=$(detect_repo "$REPO")
HARNESS=$(detect_harness "$HARNESS")
SVN_WC=$(detect_svn_wc "$SVN_WC")
SCENE_WT=$(detect_scene_wt "$SCENE_WT")
[ -n "$VERSION" ] || VERSION=$(repo_version "$REPO")
[ -n "$WORKTREE" ] || WORKTREE="${TMPDIR:-/tmp}/nexusai-release-$VERSION"

has() { case ",$STEPS," in *",$1,"*) return 0;; *) return 1;; esac; }

_step "release" "repo=$REPO version=$VERSION target=$TARGET"
echo "    steps: $STEPS"

if has preflight; then
  bash "$HERE/preflight.sh" --repo "$REPO" --version "$VERSION" --target "$TARGET" \
       --harness "$HARNESS" --svn-wc "$SVN_WC" --scene-wt "$SCENE_WT"
fi

if has bump; then
  bash "$HERE/bump-version.sh" --repo "$REPO" --version "$VERSION" \
       --note "$( [ -n "$NOTE" ] && echo "$NOTE" || echo '本次发布内容' )"
  echo "  ⛔ 现在停下：补 CHANGELOG 内容 → 提交（显式路径）→ 再继续 build"
  if [ "$DRY" = "1" ]; then exit 0; fi
  _warn "（未自动提交 —— 请你提交后重跑 release.sh --steps build,publish,sync,verify）"
  exit 0
fi

if has build; then
  bash "$HERE/build.sh" --repo "$REPO" --version "$VERSION" --out "$WORKTREE" --target "$TARGET"
fi

if has publish; then
  bash "$HERE/publish.sh" --worktree "$WORKTREE" --version "$VERSION" --target "$TARGET"
fi

if has sync; then
  bash "$HERE/sync-repos.sh" --repo "$REPO" --harness "$HARNESS" --svn-wc "$SVN_WC" --scene-wt "$SCENE_WT"
fi

if has verify; then
  bash "$HERE/verify-release.sh" --repo "$REPO" --version "$VERSION" --harness "$HARNESS" \
       --svn-wc "$SVN_WC" --worktree "$WORKTREE"
fi

_step "release" "完成"
echo "    ⛔ 收尾（顺序不能反）："
echo "        cmd //c \"rmdir <worktree>/front/node_modules\"   # 先拆 junction"
echo "        git -C '$REPO' worktree remove --force '$WORKTREE'"
