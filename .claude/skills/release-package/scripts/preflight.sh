#!/usr/bin/env bash
# 发版预检：把「踩过的坑」变成硬门禁，任一 FAIL 即中止。
#
# 用法：
#   bash preflight.sh [--repo <路径>] [--version <x.y.z>] [--target public|scene|internal]
#                     [--harness <路径>] [--svn-wc <路径>] [--scene-wt <路径>]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

REPO=""; VERSION=""; TARGET="public"; HARNESS=""; SVN_WC=""; SCENE_WT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --repo)     REPO="$2"; shift 2;;
    --version)  VERSION="$2"; shift 2;;
    --target)   TARGET="$2"; shift 2;;
    --harness)  HARNESS="$2"; shift 2;;
    --svn-wc)   SVN_WC="$2"; shift 2;;
    --scene-wt) SCENE_WT="$2"; shift 2;;
    *) _die "未知参数：$1";;
  esac
done

REPO=$(detect_repo "$REPO")
HARNESS=$(detect_harness "$HARNESS")
SVN_WC=$(detect_svn_wc "$SVN_WC")
SCENE_WT=$(detect_scene_wt "$SCENE_WT")

_step "preflight" "仓库=$REPO  目标=$TARGET"
_ok "仓库：$REPO"
[ -n "$HARNESS" ] && _ok "harness：$HARNESS" || _warn "未探测到 harness 仓（同步 GitHub 镜像那步需要）"
[ -n "$SVN_WC" ] && _ok "SVN 工作副本：$SVN_WC" || _warn "未探测到 SVN 工作副本"
[ -n "$SCENE_WT" ] && _ok "场景 worktree：$SCENE_WT" || _warn "未探测到场景 worktree"

# ── 1. 版本号：不能复用已发布版本 ─────────────────────────────────
_step "preflight" "版本号"
CUR=$(repo_version "$REPO")
PUB=$(published_version "$REPO/release")
echo "    仓库当前版本：$CUR"
echo "    MinIO 已发布  ：${PUB:-（无）}"
if [ -n "$PUB" ]; then
  if ver_gt "$CUR" "$PUB"; then
    _ok "$CUR > 已发布 $PUB（可直接发）"
  elif [ "$CUR" = "$PUB" ]; then
    _die "版本 $CUR **已发布过** ——复用同号会让已装该号的客户端永远收不到更新（updater 判据是严格大于）。请先递增。"
  else
    _die "仓库版本 $CUR **低于**已发布 $PUB ——先查清版本号为何回退。"
  fi
fi
if [ -n "$VERSION" ] && [ "$VERSION" != "$CUR" ]; then
  _warn "目标版本 $VERSION ≠ 仓库当前 $CUR（构建前必须先递增到 $VERSION）"
fi

# ── 2. 密钥隔离：构建树里三处密钥文件都不能在 ──────────────────────
_step "preflight" "密钥隔离（公网包口径）"
if [ "$TARGET" = "public" ]; then
  # 主工作区里**应该有**内网秘钥（本地开发用），但构建用 detached worktree ⇒ 那边不会有
  for f in backend/src/main/resources/factory/factory-preset.local.json \
           front/src-tauri/error-report.local.json \
           front/src-tauri/.error-report-internal ; do
    if [ -e "$REPO/$f" ]; then
      _ok "主工作区存在 $f（gitignored，构建树里不会有 ⇒ 包内无密钥）"
    else
      _ok "主工作区无 $f"
    fi
  done
  if [ -e "$REPO/backend/src/main/resources/factory/factory-preset.local.json" ]; then
    _warn "⚠️ 主工作区有内网出厂秘钥：**绝不要**在主工作区直接构建（会把 key 打进包）——必须用 detached worktree（build.sh 已如此实现）"
  fi
else
  _warn "target=$TARGET：非公网口径，密钥检查按对应口径另行确认"
fi

# ── 3. 主工作区洁净度：构建走 detached worktree，但仍要知道工作区状态 ─
_step "preflight" "工作区状态"
DIRTY=$(git -C "$REPO" status --porcelain | grep -vc '^??' || true)
UNTRACKED=$(git -C "$REPO" status --porcelain | grep -c '^??' || true)
echo "    已跟踪改动：$DIRTY 项    未跟踪：$UNTRACKED 项"
if [ "$DIRTY" != "0" ]; then
  _warn "工作区有 $DIRTY 项已跟踪改动 —— 提交哪些由你（agent）判断；构建只取**已提交状态**"
fi

# ── 4. 占用 backend/target 的实例（会让 mvn clean 失败）────────────────
_step "preflight" "占用进程"
if command -v jps >/dev/null 2>&1; then
  JPSOUT=$(jps -l 2>/dev/null | grep -i "nexusai" || true)
  if [ -n "$JPSOUT" ]; then
    echo "$JPSOUT" | sed 's/^/    /'
    _warn "有后端实例在跑 —— 构建前需按 PID 精确停（⛔ 别按端口杀），否则 mvn clean 会失败"
  else
    _ok "无运行中的后端实例"
  fi
fi

# ── 5. 工具链 ─────────────────────────────────────────────────────
_step "preflight" "工具链"
for c in git svn node npm curl; do
  command -v "$c" >/dev/null 2>&1 && _ok "$c 可用" || _die "缺少 $c"
done
if [ -n "${JAVA_HOME:-}" ]; then
  _ok "JAVA_HOME=$JAVA_HOME"
else
  _warn "JAVA_HOME 未设（构建需完整 JDK 25：jlink/jdeps/jar 齐全）"
fi

# ── 6. 迁移号段（撞号会让 jar 起不来）────────────────────────────────
_step "preflight" "Flyway 迁移号段"
MAIN_MAX=$(ls "$REPO/backend/src/main/resources/db/migration/" 2>/dev/null | grep -oE '^V[0-9]+' | sed 's/^V//' | sort -n | tail -1)
if [ -n "$SCENE_WT" ] && [ -d "$SCENE_WT/backend/src/main/resources/db/migration" ]; then
  SCENE_MAX=$(ls "$SCENE_WT/backend/src/main/resources/db/migration/" | grep -oE '^V[0-9]+' | sed 's/^V//' | sort -n | tail -1)
  echo "    主线最大 V$MAIN_MAX    场景最大 V$SCENE_MAX"
fi

# ── 迁移唯一性：查**待构建的那棵树内部**（这才是 jar 里会出现的）────
# ⛔ 不要比「两条线各自」：主线有 V78__add_settings_stream_idle_timeout_ms、场景有
#    V78__create_oa_review_notes（场景曾把主线那个改名成 V100，commit 99d30727）⇒
#    两边同号不同名是**历史设计**，但它们**永不同时进同一个 jar**（场景 merge 不会复活主线那个）。
#    真正会炸的是**同一棵树里**出现两个同号迁移 ⇒ Flyway validate 失败 ⇒ 后端起不来。
_step "preflight" "迁移唯一性（构建树内）"
CONFLICT=$(python - "$REPO" <<'PY'
import re, subprocess, sys
root = sys.argv[1]
p = subprocess.run(["git", "-C", root, "ls-files", "backend/src/main/resources/db/migration/"],
                   capture_output=True, text=True, encoding="utf-8", errors="replace")
by = {}
for line in p.stdout.split("\n"):
    m = re.match(r".*/(V\d+)__(.+)\.sql$", line.strip())
    if m:
        by.setdefault(m.group(1), []).append(m.group(2))
for v in sorted(by, key=lambda x: int(x[1:])):
    if len(by[v]) > 1:
        print("  %s 出现 %d 次: %s" % (v, len(by[v]), ", ".join(sorted(by[v]))))
PY
)
if [ -n "$CONFLICT" ]; then
  _die "构建树内存在同号迁移（jar 启动即失败 ⇒ Flyway validate 报错）："$'\n'"$CONFLICT"
fi
_ok "构建树内迁移版本号唯一"

# 场景线另查一遍（它要单独出包）
if [ -n "$SCENE_WT" ] && [ -d "$SCENE_WT/backend/src/main/resources/db/migration" ]; then
  SCONF=$(python - "$SCENE_WT" <<'PY'
import re, subprocess, sys
p = subprocess.run(["git", "-C", sys.argv[1], "ls-files", "backend/src/main/resources/db/migration/"],
                   capture_output=True, text=True, encoding="utf-8", errors="replace")
by = {}
for line in p.stdout.split("\n"):
    m = re.match(r".*/(V\d+)__(.+)\.sql$", line.strip())
    if m:
        by.setdefault(m.group(1), []).append(m.group(2))
for v in sorted(by, key=lambda x: int(x[1:])):
    if len(by[v]) > 1:
        print("  %s 出现 %d 次: %s" % (v, len(by[v]), ", ".join(sorted(by[v]))))
PY
)
  if [ -n "$SCONF" ]; then
    _die "**场景树**内存在同号迁移（场景单独出包会失败）："$'\n'"$SCONF"
  fi
  _ok "场景树内迁移版本号唯一"
fi

_step "preflight" "结论：可以继续"
echo "    仓库版本 $CUR → 目标版本 ${VERSION:-（下一步 bump）}"
