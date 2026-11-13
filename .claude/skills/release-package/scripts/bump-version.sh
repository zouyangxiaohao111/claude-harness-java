#!/usr/bin/env bash
# 版本递增：六处版本号 + 两处 CHANGELOG。
#
# 用法：
#   bash bump-version.sh --repo <路径> --version <x.y.z> [--from <x.y.z>] [--note "<一句话>"]
#   bash bump-version.sh --repo <路径> --next          # 自动取「当前 +1 补丁」
#
# ⛔ 只改文件、**不提交**——提交由 agent 判断内容后自行决定。
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

REPO=""; VERSION=""; FROM=""; NOTE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --repo)    REPO="$2"; shift 2;;
    --version) VERSION="$2"; shift 2;;
    --from)    FROM="$2"; shift 2;;
    --next)    VERSION="__NEXT__"; shift;;
    --note)    NOTE="$2"; shift 2;;
    *) _die "未知参数：$1";;
  esac
done

REPO=$(detect_repo "$REPO")
CUR=$(repo_version "$REPO")

if [ "$VERSION" = "__NEXT__" ] || [ -z "$VERSION" ]; then
  VERSION=$(next_patch "$CUR")
fi
[ -n "$FROM" ] && FROM="$FROM" || FROM="$CUR"

_step "bump" "$FROM → $VERSION （仓库 $REPO）"
if [ "$FROM" = "$VERSION" ]; then
  _die "目标版本与当前相同（$VERSION）——发版必须递增（复用同号 ⇒ 已装该号的客户端收不到更新）"
fi

python - "$REPO" "$FROM" "$VERSION" "$NOTE" <<'PY'
import io, os, re, sys

repo, frm, to, note = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]


def read(rel):
    return io.open(os.path.join(repo, rel), encoding="utf-8", newline="").read()


def write(rel, s):
    io.open(os.path.join(repo, rel), "w", encoding="utf-8", newline="").write(s)


def sub(rel, old, new, count=1):
    s = read(rel)
    n = s.count(old)
    if n != count:
        print("FAIL %s: 期望 %d 处 %r，实际 %d" % (rel, count, old[:48], n))
        sys.exit(1)
    write(rel, s.replace(old, new))
    print("  OK %-36s %s" % (rel, old[:40].replace("\n", "\\n")))


# ── 六处版本号 ────────────────────────────────────────────────────
sub("backend/pom.xml", "<version>%s</version>" % frm, "<version>%s</version>" % to)
sub("front/package.json", '"version": "%s"' % frm, '"version": "%s"' % to)
sub("front/package-lock.json", '"version": "%s"' % frm, '"version": "%s"' % to, count=2)
sub("front/src-tauri/tauri.conf.json", '"version": "%s"' % frm, '"version": "%s"' % to)
sub("front/src-tauri/Cargo.toml", 'version = "%s"' % frm, 'version = "%s"' % to)
# ⚠️ Cargo.lock 是 CRLF ⇒ 含 \n 的字面串匹配不到（跨行尾必失败）⇒ 该条改用正则
_cl = read("front/src-tauri/Cargo.lock")
_pat = r'(name = "nexusai-client"[ \t\r\n]+version = ")%s(")' % re.escape(frm)
_n = len(re.findall(_pat, _cl))
if _n != 1:
    print("FAIL front/src-tauri/Cargo.lock: 期望 1 处 nexusai-client %s，实际 %d" % (frm, _n))
    sys.exit(1)
write("front/src-tauri/Cargo.lock", re.sub(_pat, lambda m: m.group(1) + to + m.group(2), _cl))
print("  OK %-36s nexusai-client %s → %s" % ("front/src-tauri/Cargo.lock", frm, to))

# ── front/CHANGELOG.md：新增版本段（放在最前一个版本段之前）────────
rel = "front/CHANGELOG.md"
s = read(rel)
m = re.search(r"^## \[(\d+\.\d+\.\d+)\]", s, re.M)
if not m:
    print("FAIL front/CHANGELOG.md: 找不到版本段标题")
    sys.exit(1)
anchor = "## [%s]" % m.group(1)
head = "### Added\n\n- %s\n" % (note if note else "（本次发布内容待补）")
import datetime
_today = datetime.date.today().isoformat()
section = "## [%s] - %s\n\n%s\n" % (to, _today, head)
# 段内若有 ### Fixed 块，新段在它后面更合顺序；简单起见放最前
s = s.replace(anchor, section + anchor, 1)
write(rel, s)
print("  OK front/CHANGELOG.md  新增 [%s] 段" % to)

# ── backend/CHANGELOG.md：表格追加一行 ───────────────────────────
rel = "backend/CHANGELOG.md"
s = read(rel)
lines = s.split("\n")
idx = next((i for i, l in enumerate(lines) if l.startswith("|------|---------|--------|")), None)
if idx is None:
    print("FAIL backend/CHANGELOG.md: 找不到表头分隔行")
    sys.exit(1)
row = "| %s | %s | **待补** |" % ("2026-01-01", to)
lines[idx + 1:idx + 1] = [row]
write(rel, "\n".join(lines))
print("  OK backend/CHANGELOG.md 追加 1 行 %s（内容待补）" % to)
PY

_ok "版本已递增到 $VERSION —— **请补 CHANGELOG 内容**（脚本只写了占位）"
_step "bump" "复核"
for f in backend/pom.xml front/package.json front/package-lock.json \
         front/src-tauri/tauri.conf.json front/src-tauri/Cargo.toml front/src-tauri/Cargo.lock; do
  v=$(grep -o '0\.1\.[0-9]\+' "$REPO/$f" | head -1)
  printf '    %-34s %s\n' "$f" "$v"
done
echo "    ⛔ 提交由你（agent）决定：git add -- <显式路径>，⛔ 禁用 git add -A/."
