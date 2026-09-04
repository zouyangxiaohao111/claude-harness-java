#!/usr/bin/env python3
"""Check project MySQL DDL against the mandatory table convention."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


TABLE_PATTERN = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`(?P<name>[^`]+)`\s*\((?P<body>.*?)\)\s*(?P<options>ENGINE\s*=.*?);",
    re.IGNORECASE | re.DOTALL,
)
FORBIDDEN = ("AUTO_INCREMENT", "created_at", "updated_at", "created_by", "is_deleted", "create_at", "update_at")
REQUIRED_BODY = {
    "snowflake primary key": r"`id`\s+bigint\s+NOT\s+NULL\b",
    "creator": r"`creator`\s+varchar\(64\)\s+CHARACTER\s+SET\s+utf8mb4\s+COLLATE\s+utf8mb4_unicode_ci\s+DEFAULT\s+''\s+COMMENT\s+'创建者'",
    "create_time": r"`create_time`\s+datetime\s+NOT\s+NULL\s+DEFAULT\s+CURRENT_TIMESTAMP\s+COMMENT\s+'创建时间'",
    "updater": r"`updater`\s+varchar\(64\)\s+CHARACTER\s+SET\s+utf8mb4\s+COLLATE\s+utf8mb4_unicode_ci\s+DEFAULT\s+''\s+COMMENT\s+'更新者'",
    "update_time": r"`update_time`\s+datetime\s+NOT\s+NULL\s+DEFAULT\s+CURRENT_TIMESTAMP\s+ON\s+UPDATE\s+CURRENT_TIMESTAMP\s+COMMENT\s+'更新时间'",
    "deleted": r"`deleted`\s+bit\(1\)\s+NOT\s+NULL\s+DEFAULT\s+b'0'\s+COMMENT\s+'是否删除'",
    "BTREE primary key": r"PRIMARY\s+KEY\s*\(\s*`id`\s*\)\s+USING\s+BTREE",
}
REQUIRED_OPTIONS = {
    "InnoDB": r"ENGINE\s*=\s*InnoDB",
    "utf8mb4": r"DEFAULT\s+CHARSET\s*=\s*utf8mb4",
    "unicode collation": r"COLLATE\s*=\s*utf8mb4_unicode_ci",
    "dynamic row format": r"ROW_FORMAT\s*=\s*DYNAMIC",
    "table comment": r"COMMENT\s*=\s*'[^']+'",
}


def check_file(path: Path) -> list[str]:
    sql = path.read_text(encoding="utf-8")
    sql_without_comments = re.sub(r"/\*.*?\*/", "", sql, flags=re.DOTALL)
    sql_without_comments = re.sub(r"(?m)^\s*(?:--\s|#).*?$", "", sql_without_comments)
    errors: list[str] = []
    for token in FORBIDDEN:
        if re.search(rf"\b{re.escape(token)}\b", sql_without_comments, re.IGNORECASE):
            errors.append(f"{path}: forbidden token {token}")

    create_count = len(re.findall(r"\bCREATE\s+TABLE\b", sql_without_comments, re.IGNORECASE))
    tables = list(TABLE_PATTERN.finditer(sql_without_comments))
    if not tables:
        errors.append(f"{path}: no complete CREATE TABLE statement found")
        return errors
    if len(tables) != create_count:
        errors.append(f"{path}: parsed {len(tables)} of {create_count} CREATE TABLE statements")

    for match in tables:
        name = match.group("name")
        body = match.group("body")
        options = match.group("options")
        for label, pattern in REQUIRED_BODY.items():
            if not re.search(pattern, body, re.IGNORECASE | re.DOTALL):
                errors.append(f"{path}:{name}: missing or invalid {label}")
        for label, pattern in REQUIRED_OPTIONS.items():
            if not re.search(pattern, options, re.IGNORECASE | re.DOTALL):
                errors.append(f"{path}:{name}: missing or invalid {label}")
        if re.search(r"`version`\s+", body, re.IGNORECASE) and not re.search(
            r"`version`\s+int\s+NOT\s+NULL\s+DEFAULT\s+0\s+COMMENT\s+'版本号'",
            body,
            re.IGNORECASE,
        ):
            errors.append(f"{path}:{name}: version must use the optimistic-lock convention")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("sql_files", nargs="+", type=Path)
    args = parser.parse_args()
    errors: list[str] = []
    for path in args.sql_files:
        try:
            errors.extend(check_file(path))
        except (OSError, UnicodeError) as exc:
            errors.append(f"{path}: unreadable: {exc}")
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(f"MySQL DDL convention: OK ({len(args.sql_files)} file(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main())
