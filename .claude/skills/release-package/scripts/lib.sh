#!/usr/bin/env bash
# release-package 公共库：路径探测 / 日志 / 门禁
# ⛔ 本文件被其它脚本 source，不单独执行。
set -euo pipefail

# ── 日志 ────────────────────────────────────────────────────────────
_step() { printf '\n\033[1;36m[%s]\033[0m %s\n' "$1" "$2"; }
_ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
_warn() { printf '  \033[33m⚠\033[0m %s\n' "$1"; }
_die()  { printf '  \033[31m⛔ %s\033[0m\n' "$1" >&2; exit 1; }

# ── 路径探测（都可用参数覆盖）────────────────────────────────────────
# 仓库根：含 backend/pom.xml + front/package.json 者
detect_repo() {
  local cand="$1"
  if [ -n "$cand" ]; then printf '%s' "$cand"; return; fi
  local d
  d=$(pwd)
  while [ "$d" != "/" ] && [ -n "$d" ]; do
    if [ -f "$d/backend/pom.xml" ] && [ -f "$d/front/package.json" ]; then
      printf '%s' "$d"; return
    fi
    d=$(dirname "$d")
  done
  printf '%s' "$PWD"
}

# harness 仓：含 frontend/ + pom.xml（GitHub claude-harness-java 的镜像）
detect_harness() {
  local cand="$1"
  if [ -n "$cand" ]; then printf '%s' "$cand"; return; fi
  local base
  base=$(dirname "$(detect_repo "")")
  for p in "$base/claude-harness-java" "$base/ai-agent-client-svn"; do
    if [ -d "$p/frontend" ] && [ -f "$p/pom.xml" ]; then printf '%s' "$p"; return; fi
  done
  # 兜底：家目录同级
  for p in "$HOME/claude-harness-java"; do
    if [ -d "$p/frontend" ]; then printf '%s' "$p"; return; fi
  done
  printf ''
}

# SVN 工作副本根：含 .svn 且有 nexusai/ 或 nexusai-scene/
detect_svn_wc() {
  local cand="$1"
  if [ -n "$cand" ]; then printf '%s' "$cand"; return; fi
  local base
  base=$(dirname "$(detect_repo "")")
  for p in "$base/ai-agent-client-svn"; do
    if [ -d "$p/.svn" ]; then printf '%s' "$p"; return; fi
  done
  for p in "$HOME/ai-agent-client-svn"; do
    if [ -d "$p/.svn" ]; then printf '%s' "$p"; return; fi
  done
  printf ''
}

# 场景 worktree：含 .git 文件且分支是 feat/scene-fusion
detect_scene_wt() {
  local cand="$1"
  if [ -n "$cand" ]; then printf '%s' "$cand"; return; fi
  local repo base
  repo=$(detect_repo "")
  base=$(dirname "$repo")
  for p in "$repo/.nexusai/worktrees/scene-fusion" "$base/.nexusai/worktrees/scene-fusion"; do
    if [ -e "$p/.git" ]; then printf '%s' "$p"; return; fi
  done
  printf ''
}

# ── 工具函数 ────────────────────────────────────────────────────────
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else python -c "import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())" "$1"; fi
}

file_size() { wc -c < "$1" | tr -d ' '; }

# 当前版本（读 front/package.json —— 发布脚本读的就是它）
# ⚠️ 用 argv 传路径：MSYS 路径（/d/...）与 Windows 路径（D:\...）在 Python 里不等价，
#    拼进 r'' 字面量会 FileNotFoundError。
repo_version() {
  python -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['version'])"     "$(cygpath -w "$1/front/package.json" 2>/dev/null || echo "$1/front/package.json")"
}

# 已发布的最高版本（MinIO latest.json + GitHub Release tag）
published_version() {
  local v=""
  local f="$1/latest.json"
  if [ -f "$f" ]; then
    v=$(python -c "
import json,sys
try: print(json.load(open(sys.argv[1],encoding='utf-8')).get('version',''))
except Exception: print('')" "$(cygpath -w "$f" 2>/dev/null || echo "$f")" 2>/dev/null || true)
  fi
  printf '%s' "$v"
}

# 版本号比较：a > b ⇒ 返回 0
ver_gt() {
  python - "$1" "$2" <<'PY'
import sys
def key(v):
    return [int(x) for x in v.split(".") if x.isdigit()]
a, b = key(sys.argv[1]), key(sys.argv[2])
sys.exit(0 if a > b else 1)
PY
}

# 下一个补丁版本
next_patch() {
  python - "$1" <<'PY'
import sys
p = [int(x) for x in sys.argv[1].split(".")]
p[-1] += 1
print(".".join(str(x) for x in p))
PY
}

# ── 门禁：确认「公网包不含内网密钥」的三处文件都不在构建树里 ──────────
assert_no_secrets_in_tree() {
  local root="$1"
  local bad=0
  for f in \
    "backend/src/main/resources/factory/factory-preset.local.json" \
    "front/src-tauri/error-report.local.json" \
    "front/src-tauri/.error-report-internal" ; do
    if [ -e "$root/$f" ]; then
      printf '  \033[31m⛔ 构建树里存在密钥文件：%s\033[0m\n' "$f" >&2
      bad=1
    fi
  done
  [ "$bad" = "0" ] || exit 1
}

# 门禁：jar 内不得含内网出厂秘钥
assert_jar_no_secret() {
  local jar="$1"
  if command -v unzip >/dev/null 2>&1; then
    if unzip -l "$jar" 2>/dev/null | grep -q "factory-preset\.local\.json"; then
      printf '  \033[31m⛔ jar 内含 factory-preset.local.json（内网秘钥）——拒绝发布\033[0m\n' >&2
      exit 1
    fi
  fi
}

# 门禁：app exe 内不得含错误上报只写 key
assert_exe_no_secret() {
  local exe="$1" keyfile="$2"
  [ -f "$exe" ] || return 0
  [ -f "$keyfile" ] || return 0
  python - "$exe" "$keyfile" <<'PY' || exit 1
import json, sys
exe, keyfile = sys.argv[1], sys.argv[2]
try:
    key = json.load(open(keyfile, encoding="utf-8")).get("secretKey", "")
except Exception:
    key = ""
if not key:
    sys.exit(0)
data = open(exe, "rb").read()
if key.encode() in data:
    print("  \033[31m⛔ app exe 内含错误上报只写 key ——拒绝发布\033[0m")
    sys.exit(1)
PY
}

# 门禁：GitHub 场景远端必须没被推（场景线只进 SVN）
assert_scene_not_on_github() {
  local repo="$1"
  local remote
  remote=$(git -C "$repo" ls-remote origin refs/heads/feat/scene-fusion 2>/dev/null | cut -c1-12)
  if [ -z "$remote" ]; then
    _ok "GitHub 上无 feat/scene-fusion 分支（场景线未外泄）"
  else
    _warn "GitHub 上存在 feat/scene-fusion = $remote ——确认这是**有意**的（场景线原则上只进 SVN）"
  fi
}
