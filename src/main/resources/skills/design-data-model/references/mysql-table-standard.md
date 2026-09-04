# MySQL物理表规范

本规范是默认强制规则。现有项目若有冲突，记录 `DEC-*` 后采用项目已确认标准；不要静默混用。

## 主键

- 所有新表：`id bigint NOT NULL COMMENT '<业务对象>编号'`。
- ID由雪花算法在应用或统一ID基础设施生成。
- 禁止 `AUTO_INCREMENT`、`AUTO_INCREMENT=<value>` 和数据库自增与雪花混用。
- 关联ID使用 `bigint`，命名为 `<object>_id`。

## 固定审计字段

```sql
`creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
`updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
`deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除'
```

不要改成 `created_at`、`updated_at`、`created_by`、`is_deleted`。若表不允许逻辑删除，必须通过决策说明，而不是自行删字段。

为兼容公司现有表规范，`creator/updater` 保持示例中的 `DEFAULT ''`，不额外强制数据库 `NOT NULL`；后端写入责任仍应保证正常业务记录使用实际操作者，不得把空串或NULL当作已完成审计。

## 乐观锁

仅当存在并发编辑、状态竞争或防止丢失更新的实际需求时添加：

```sql
`version` int NOT NULL DEFAULT 0 COMMENT '版本号'
```

后端更新必须使用 `WHERE id = ? AND version = ?` 并原子递增；只有字段没有更新协议不算完成。

## 表选项

```sql
ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_unicode_ci
ROW_FORMAT=DYNAMIC
COMMENT='<中文表说明>';
```

主键：`PRIMARY KEY (id) USING BTREE`。普通索引使用 `idx_<table_abbr>_<columns>`，唯一索引使用 `uk_<table_abbr>_<columns>`；命名应符合现有项目规范。

## 字段

- 表名、字段名使用小写snake_case和稳定业务语义。
- 每个字段必须有中文COMMENT。
- varchar/text显式使用utf8mb4与utf8mb4_unicode_ci，除非项目确认表级继承即可。
- 金额使用decimal并说明单位/精度；禁止float/double表示货币。
- 时间使用datetime并说明业务时区；只存日期使用date。
- 状态/类型字段说明字典或枚举来源，不在COMMENT里复制完整业务规则。
- `NOT NULL`、默认值和空值语义必须来自业务，而非模板习惯。
- 敏感字段记录加密、脱敏、查询和审计策略。

## JSON值对象与冗余

满足以下条件时，可把值对象或展示型冗余保存为MySQL `json` 字段：

- 无独立身份和生命周期，随所属记录整体创建、替换和删除；
- 主要整体读取/写入，不经常按内部属性筛选、排序、分组或关联；
- 不承担业务唯一性、状态迁移、金额汇总、权限边界或强关系完整性；
- 接受应用层进行schema校验和兼容迁移。

设计必须记录：JSON字段的业务名称与COMMENT、结构schema及版本、必填/空值、大小上限、敏感字段、变更兼容、历史/审计、查询频率和迁移策略。需要少量内部属性查询时，可评估生成列/函数索引，但必须有真实查询依据；查询成为常态、需要唯一/FK/CHECK约束或独立更新时应拆为普通列或子表。

示例：

```sql
`contact_snapshot` json DEFAULT NULL COMMENT '联系人快照，结构版本由应用维护'
```

JSON是值对象持久化和受控冗余方案，不是省略逻辑模型、字段字典或业务约束的理由。

## 约束与软删除唯一性

- 所有业务唯一性都必须记录；逻辑删除条件下说明唯一键是否允许重建。
- 物理FK是否启用由项目规范决定；即使不建FK，也要在逻辑模型和测试中保留关系完整性。
- 跨字段复杂规则若无法用CHECK稳定表达，映射到Service校验与测试用例。
- 使用 `CHECK` 前确认MySQL版本及实际启用行为；版本不支持或项目禁用时，将规则落实到Service校验、条件更新、唯一约束和测试，不得保留一个不会生效的装饰性约束。

## 基础模板

```sql
CREATE TABLE `example_record` (
  `id` bigint NOT NULL COMMENT '记录编号',
  `business_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务编码',
  `name` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '名称',
  `version` int NOT NULL DEFAULT 0 COMMENT '版本号',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_example_record_business_code` (`business_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='示例记录表';
```

若无乐观锁需求，删除 `version`；其他固定审计字段保留。
