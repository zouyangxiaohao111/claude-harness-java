---
name: design-data-model
description: 从已确认的业务字段、规则、状态、权限、领域边界、聚合及现有项目能力基线，分批设计概念/逻辑模型、聚合存储簇、物理表、字段、关系、约束、索引、JSON值对象、迁移和DDL。用于先建立表候选清单，再按工作队列逐簇逐表研究、确认和追加；也用于评审字段建议、现有表兼容性或MySQL建表语句。默认遵循雪花bigint主键及creator/create_time/updater/update_time/deleted规范；不要从表单直接生成表，也不要一次性生成全部数据库。
---

# 数据模型与数据库表设计

数据库表在本阶段正式生成，但必须先规划、后逐项细化。全局运行只建立候选清单；物理设计和DDL一次只推进一个AI基线化工作项，不要求人工逐表批准。

独立执行协议：稳定ID使用 `{PROJECT}-{TYPE}-{DOMAIN}-{SEQUENCE}`，序号不复用；证据状态只用 `SOURCE_FACT/CONFIRMED/INFERRED/PROPOSED/OPEN/REJECTED/RETIRED`。结束时返回 `status, gate: G4, gate_component: DATA, gate_status, run_mode, work_item_id, continuation_required, next_work_item_id, next_stage, stop_reason, discovered_items, registered_ids, confirmed_ids, unconfirmed_ids, artifacts, added_ids, changed_ids, retired_ids, blockers, upstream_revision, next_allowed_stages, evidence`；被总入口调用时保留项目、切片和基线版本。

## 铁律：逐项发现、登记、确认并继续

每个存储簇、表、列、关系、约束、查询、索引、JSON字段和迁移都必须先发现候选及上游/现有工程证据，再以稳定ID和版本追加到全局 `00-control/work-item-registry.csv`，并把工作项/明细追加到 `table-design-worklist.csv`、`table-catalog.csv`、`column-catalog.csv` 及对应关系/约束/索引/JSON/迁移CSV；最后由AI逐项审查并回写同一CSV行，置为 `BASELINED/CONFIRMED`。只在回复/Markdown说“已登记”、只返回 `registered_ids` 或CSV只有表头均不算登记。未登记不得细化，未确认不得生成DDL或供后端使用。确认/验证一张表后自动选择工作队列下一项；清单闭合后自动完成G4数据收敛并路由后端，不因某张表或某次PLAN完成而停止。

## 准入

至少需要G2行为基线；正式物理模型要求G3通过。存在代码库时要求GF通过适用范围，并读取 `MOD/COMP/XAPI/XTBL/EXT/REUSE/GAP`。同时读取 `FLD/DATA/BR/STM/PERM/CTX/AGG/CMD/EVT/NFR/INT`、现有数据库规范和已基线化的数据模型工作项。

生成可执行DDL前确认：数据库产品/版本、命名与字段规范、雪花ID生成位置、数据量、典型查询、保留/删除、迁移、物理外键和租户策略。缺失时只生成候选模型和阻塞项。

旧表和基础组件是实现约束，不是目标模型。已有BPM、文件、用户组织、字典、权限、通知等能力优先引用/扩展/适配；提出新通用表前必须引用 `REUSE-*` 或批准的 `DEC-*`。

## 运行模式

只使用以下模式，每次调用明确一个模式：

- `PLAN`：建立概念模型、存储簇和表候选清单，不生成完整列或DDL；
- `DESIGN_CLUSTER`：一次研究一个聚合存储簇，确认表职责、所有权、生命周期和关系边界；
- `DESIGN_TABLE`：一次只细化一张表的列、键、关系、约束、索引、JSON、审计和迁移；
- `GENERATE_DDL`：只为已 `BASELINED` 且增量一致性检查无阻塞的一张表追加DDL并验证；
- `REVIEW_CHANGE`：评估一项变更，追加新版本和迁移，不静默覆盖已基线化设计。

用户要求“生成全部数据模型”时默认先执行 `PLAN` 并返回工作清单；AI完成清单自检后可自动基线化并逐项继续，但不得跳过清单直接批量生成DDL。

`BASELINED` 表示AI依据上游证据、项目事实和阶段自检形成当前设计基线，不等于人工逐项签字。人工审查通常在阶段文档完成后进行，另记 `human_review_status: NOT_REVIEWED/HUMAN_COMMENTED/HUMAN_CONFIRMED`；没有人工确认不阻止AI继续。人工反馈进入问题/变更日志，只重开受影响工作项。

## 工作队列

`table-design-worklist.csv` 状态固定为：

`PLANNED -> RESEARCHING -> PROPOSED -> AI_REVIEWED -> BASELINED -> CONSISTENCY_CHECKED -> DDL_GENERATED -> VERIFIED`

也可进入 `BLOCKED/RETIRED/SUPERSEDED`。同一时刻最多一个工作项为 `RESEARCHING`。工作项优先按聚合存储簇排列；簇内再按根表、强一致子表、关系/历史/快照/读模型顺序推进。

开始工作项前必须重新读取其 `upstream_refs`、相关文档定位、既有决策、相邻已基线化表、现有表/API/组件、查询与统计场景、数据量、权限、保留、迁移和失败恢复。没有充分证据就不得把候选提升为基线。

AI必须先穷尽可用文档、代码、既有决策和相邻产物。能用显式、可逆、范围受限的假设继续时，记录假设并继续；只有缺失事实会实质改变业务规则、数据权威、合规/安全、不可逆迁移或高影响结构选择且无法从证据可靠裁决时，才返回 `NEEDS_CONTEXT/BLOCKED` 请求人工。请求必须列出缺失事实、无法自行决定的原因、选项、推荐、影响工作项和延后默认值，不能只写“请确认”。

## 逐项工作流

1. `PLAN`：盘点业务数据和现有表，分类主数据、事务、配置、快照、历史、派生、附件引用、审计、集成暂存和读模型。
2. 建概念模型与 `table-cluster-catalog.csv`，明确每个存储簇的聚合/上下文、权威、生命周期、包含/排除和复用组件。
3. 建 `table-design-worklist.csv`，只列候选表目的、类型、优先级、依赖和上游证据；执行DM0清单确认。
4. `DESIGN_CLUSTER`：压力测试所有权、写入口、强一致范围、跨簇引用、历史/快照、删除和并发；执行DM1。
5. `DESIGN_TABLE`：逐列映射业务含义、来源、权威、空值、精度、枚举、敏感、历史和权限；再设计PK/FK/UQ/CK、应用校验和写入口；执行DM2。
6. 根据真实筛选、排序、关联、统计和批量场景设计索引/读模型；依据整体读写和低频查询决定JSON；设计审计、租户、软删除、乐观锁和迁移；执行DM3。
7. AI完成规则、关系、查询、规范和证据自检后标记 `AI_REVIEWED/BASELINED`；证据不足内容保持 `PROPOSED/OPEN`，不为等待人工而阻塞无关工作。
8. 自动触发该表的增量一致性审查；无阻塞问题后标记 `CONSISTENCY_CHECKED`。可修问题自动路由回本技能修复并复核；确实需要人的问题进入阶段文档人工审查清单。
9. `GENERATE_DDL`：为该表生成独立DDL，追加到基线，运行静态检查及可用的目标库验证；执行DM4并更新追踪。
10. 变更已基线化表时增加版本和 `supersedes_id`，生成迁移脚本、回滚与对账方案；旧行标记 `SUPERSEDED/RETIRED`，不得删除或改写历史。

## Append协议

- 所有目录使用稳定ID、`version/status/supersedes_id/change_reason/source_refs/decision_refs`；
- 新事实追加新行；同一语义修订保留ID并升版本；拆分/合并使用新ID及替代关系；
- `schema.sql` 只追加首次基线化且检查通过表的DDL；后续结构修改写入 `migrations/`，不得伪装成初始设计；
- 每张表保留 `tables/<TBL-ID>.md` 设计卡和 `ddl/<TBL-ID>.sql`；
- 目录中的当前视图可以重建，但事实历史和变更日志不得覆盖。

## 默认MySQL规范

必须读取 [MySQL表规范](references/mysql-table-standard.md)。核心约束：

- 主键 `bigint NOT NULL`，由应用/基础设施生成雪花ID，不使用 `AUTO_INCREMENT`；
- 固定字段为 `creator/create_time/updater/update_time/deleted`，不得改成 `created_at/updated_at/is_deleted`；
- 仅并发敏感表增加 `version`，并说明CAS更新与冲突处理；
- 默认InnoDB、utf8mb4、utf8mb4_unicode_ci、ROW_FORMAT=DYNAMIC，表/字段有中文COMMENT；
- 无独立生命周期、整体读写且不常用于筛选/排序/关联的值对象可用MySQL `json`；必须登记schema版本、校验、迁移、大小和敏感策略；
- 高频查询、唯一/关联键、状态、金额、权限和关键统计字段不得藏入JSON。

## 必交付物

写入 `docs/software-design/04-data-model/`：

- `data-model.md`、`table-cluster-catalog.csv`、`table-design-worklist.csv`；
- `table-catalog.csv`、`column-catalog.csv`、`relationship-catalog.csv`；
- `constraint-catalog.csv`、`index-catalog.csv`、`query-pattern-catalog.csv`；
- `json-field-catalog.csv`、`reuse-impact-catalog.csv`；
- `migration-mapping.csv`、`data-model-change-log.csv`、`data-traceability.csv`；
- `tables/`、`ddl/`、`migrations/`，以及条件满足时的 `schema.sql`；
- 更新问题、决策、产物和追踪账本。

使用 [增量数据设计模板](references/deliverable-template.md)。生成MySQL DDL后运行 `python3 scripts/check_mysql_ddl.py <ddl-file>`；静态检查不能替代目标数据库验证。

## 禁止

- 一次性生成全部表、列和DDL；
- 一张表单/页面/聚合机械对应一张表；
- 未查现有BPM/Infra/System能力就新建通用流程、文件、组织、权限或字典表；
- 雪花ID同时使用AUTO_INCREMENT；
- 用审计字段代替业务历史；只加 `tenant_id` 就宣称多租户安全；
- 所有字符串用varchar(255)、金额用float、索引无查询依据；
- 工作项未基线化/检查就登记为正式表，或静默覆盖已基线化版本；
- 为每张表请求人工确认，或把“请确认”当作AI未完成研究的替代品。

## Gate G4（数据）

子门：DM0清单、DM1存储簇、DM2列/键/关系、DM3约束/索引/JSON/迁移、DM4 DDL验证。单项通过只提升该工作项，不代表全局G4通过。

只有工作清单中本期所有工作项达到 `VERIFIED`（不需要DDL的逻辑项达到 `BASELINED` 且一致性检查通过）、无阻塞依赖、跨表关系和复用决策一致，才返回数据组件 `PASSED`。人工阶段审查可随后提出修订，但不是逐项Gate。否则精确返回当前工作项、下一工作项和剩余清单。
