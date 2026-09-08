-- ===================================================================
-- V69: projects 唯一键 name UNIQUE → path UNIQUE（对齐 CC 项目=目录）
-- 事故：欢迎页「绑定项目」重复选同一目录 → ProjectService.register 无条件 INSERT，
--       撞 projects.name UNIQUE → SQLite 500（SQLITE_CONSTRAINT_UNIQUE）。
-- 拍板（方案 A · nexusai-backend 后端修复）：
--   * CC 中项目身份 = 目录（startup dir / getOriginalCwd），name 仅是展示字段；
--     name 挂 UNIQUE 本身偏了——不同目录可同名（D:/a/proj 与 D:/b/proj），按 name 判重会误伤。
--   * 本迁移：name 保留 NOT NULL 退化为展示字段（去 UNIQUE）；唯一键迁到 path。
--   * 历史 path 反斜杠 → 正斜杠归一（与 register 新存储格式对齐，Windows Path.toString 是反斜杠），
--     保证 path UNIQUE 字面一致生效 + 前端展示/日志友好。
-- SQLite 不支持 DROP CONSTRAINT（一次 ALTER 仅支持单操作），故表重建（同 V20 范式）：
--   CREATE projects_new（8 列 = V1:131-140 原样，仅 name 去 UNIQUE、path 加 UNIQUE）
--   → UPDATE 归一历史 path（先于 INSERT..SELECT，避免反斜杠/正斜杠并存行在 path UNIQUE 上互撞）
--   → INSERT INTO ... SELECT 显式 8 列（列名/顺序逐列对照，防生产数据丢失）
--   → DROP TABLE projects → ALTER TABLE projects_new RENAME TO projects
-- ===================================================================

UPDATE projects SET path = replace(path, '\', '/');

CREATE TABLE projects_new (
  id                TEXT PRIMARY KEY,
  name              TEXT NOT NULL,
  path              TEXT NOT NULL UNIQUE,
  branch            TEXT DEFAULT 'main',
  dirty             INTEGER DEFAULT 0,
  agents            INTEGER DEFAULT 0,
  last_indexed_at   TEXT,
  bound             INTEGER NOT NULL DEFAULT 0
);

INSERT INTO projects_new (id, name, path, branch, dirty, agents, last_indexed_at, bound)
SELECT id, name, path, branch, dirty, agents, last_indexed_at, bound
FROM projects;

DROP TABLE projects;

ALTER TABLE projects_new RENAME TO projects;
