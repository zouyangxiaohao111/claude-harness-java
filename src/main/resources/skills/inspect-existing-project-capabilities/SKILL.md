---
name: inspect-existing-project-capabilities
description: 使用边界清晰的只读子代理探查已有软件项目、基础平台和数据库，由主代理汇总模块、组件、公开API、现有表、工程约定、扩展点、复用决策、能力缺口及架构剖面。用于存量系统改造、在设计表/API/包结构前确认BPM、Infra、System等能力，识别三层、DDD四层、Clean/Hexagonal、COLA、模块化单体、微服务或混合架构；只记录工程事实，不让旧代码替代业务规则或领域边界。
---

# 现有项目能力探查

建立“实现事实基线”，供领域、数据、后端、前端和测试设计消费。业务事实仍以需求、场景、规则和决策为准。

独立执行协议：稳定ID使用 `{PROJECT}-{TYPE}-{DOMAIN}-{SEQUENCE}`，序号不复用；证据状态只用 `SOURCE_FACT/CONFIRMED/INFERRED/PROPOSED/OPEN/REJECTED/RETIRED`。结束时返回 `status, gate: GF, gate_status, continuation_required, next_work_item_id, next_stage, stop_reason, discovered_items, registered_ids, confirmed_ids, unconfirmed_ids, artifacts, added_ids, changed_ids, retired_ids, blockers, upstream_revision, next_allowed_stages, evidence`；被总入口调用时保留项目、切片和基线版本。

## 铁律：逐项发现、登记、确认并继续

每个模块、架构判断、依赖方向、组件、API、表、扩展点、工程约定、复用决策和缺口都必须：先由主/子代理发现候选及原始路径；再以稳定ID追加到全局 `00-control/work-item-registry.csv`，把探查任务追加到 `probe-task-register.csv`，把事实明细追加到 `project-module-catalog.csv`、`project-capability-catalog.csv`、`existing-api-catalog.csv`、`existing-schema-catalog.csv` 等对应CSV；最后由主代理交叉验证并回写同一CSV行，置为 `CONFIRMED` 或明确保持 `PROPOSED/UNKNOWN`。只在回复/Markdown说“已登记”、只返回 `registered_ids` 或CSV只有表头均不算登记。未登记的搜索命中不得形成结论，未确认项不得供下游使用。

确认一项后自动处理下一个探查任务，直到目标切片的探查清单闭合并完成GF；不得在识别一个架构样例、一个组件或一个子代理返回后停止。总入口因 `codebase_status=EXISTS` 显式调用时必须执行，不得等待隐式触发或以已有印象跳过。

## 准入

确认项目根目录、代码版本或提交、目标切片和允许探查的系统。若已知存在代码但不可访问，返回 `NEEDS_CONTEXT`；确认是全新项目时登记绿色项目事实，GF可以通过但复用目录为空。

至少读取G1需求能力目录；可在G2行为建模期间并行探查。不得因现有模块名称或旧表字段而新增业务需求、状态、权限或聚合。

## 探查顺序

1. 识别构建系统、模块拓扑、语言/框架、启动模块、API/实现拆分、前端工程和部署单元。
2. 优先查公共契约和依赖关系，再查实现：公开API、Client、Service接口、事件、扩展点、配置和调用样例。
3. 定向盘点基础能力：BPM/审批、用户/组织/权限/租户、字典、文件/附件/预览、导入导出/模板、通知、日志审计、任务调度、ID生成、异常、幂等、消息和外部集成。
4. 盘点现有表、DDL、迁移脚本、基础DO、类型处理器、软删除/审计/乐观锁和JSON映射约定。
5. 对每项能力至少记录两个证据维度：声明位置与真实调用/持久化/配置样例；只有声明没有消费者时降低置信度。
6. 将业务需要与现有能力对照，逐项作出 `REUSE/EXTEND/ADAPT/REPLACE/NEW/UNKNOWN` 候选决策。
7. 记录扩展位置、兼容限制、数据所有权、调用责任、失败语义和验证方式；不直接修改项目。
8. 识别实际架构和依赖方向，形成后续包/模块映射所需的架构剖面。
9. 执行GF并登记实现事实基线版本。

大项目先按目录、依赖和符号建立候选清单，再按目标切片定向深入；禁止无边界地阅读全部文件。搜索结论必须附具体路径、符号/表名和代码版本，不以少量正则命中概括整个项目。

## 子代理探查协议

存在子代理能力且项目为多模块、前后端一体或中大型代码库时，默认使用子代理减少主代理注意力稀疏。小型单模块项目可由主代理直接完成，但说明未拆分原因。

主代理先建立模块候选和探查任务，再把互不重叠的只读任务分派给子代理。建议按实际范围选择：

- 构建、模块拓扑、依赖方向与架构形态；
- BPM/工作流、用户组织权限、字典、文件、通知、审计等基础组件和公开API；
- DDL、迁移、基础DO、Mapper、JSON/类型处理器、ID和审计规范；
- 前端路由/菜单/权限/请求/组件库，以及测试、任务、消息和集成基础设施。

每个子代理必须收到：项目根目录、代码版本、允许路径、目标问题、禁止写入、证据格式和返回上限。返回只包含候选事实：`claim,evidence_paths,symbol_or_table,consumer_example,confidence,unknowns`；不得直接决定领域边界、目标表或新建组件。

主代理负责去重、交叉验证、解决矛盾和最终复用判断。单一子代理结论不能直接成为 `CONFIRMED`；关键能力至少需要声明/实现与真实消费者、配置或持久化证据中的两个维度。子代理不可用时按相同任务边界由主代理分批探查，不得降低证据要求。

## 架构识别

不要只看包名自称。分别检查：模块/部署拓扑、层职责、允许依赖方向、调用入口、领域对象/规则位置、应用编排、基础设施适配、跨模块公开契约、事务边界和实际违规调用。

架构候选至少包括 `THREE_LAYER/DDD_FOUR_LAYER/CLEAN_HEXAGONAL/COLA/MODULAR_MONOLITH/MICROSERVICES/HYBRID/UNKNOWN`。架构风格、部署形态和领域建模成熟度分开记录；例如“模块化单体＋三层实现＋DDD边界设计”是有效组合。

识别COLA时检查Adapter/Application/Domain/Infrastructure等职责和依赖是否真实成立；识别DDD四层、Clean或Hexagonal时同样以职责和依赖为证据。发现Controller直连Mapper、跨模块直写表或领域规则集中在Service时如实登记，不因目录名称美化结论。

输出目标架构映射前，先形成现状 `as-is` 剖面：每层/模块职责、代表路径、依赖方向、公开边界、基础类型、例外和置信度。后端技能只能在此基础上决定新代码包结构。

## 复用决策

- `REUSE`：现有契约直接满足，记录标准调用路径；
- `EXTEND`：在有代码/配置证据的扩展点增加能力，不改变现有语义；
- `ADAPT`：通过适配/防腐转换契约、模型或错误；
- `REPLACE`：明确替换范围、兼容迁移、回滚和可追踪决策；
- `NEW`：确无可复用能力，记录排除过的候选和新建边界；
- `UNKNOWN`：证据不足，列责任人和验证动作。

`REPLACE/NEW` 必须引用需求或NFR及 `DEC-*`；不得仅因命名、分层或编码风格不同而重建。`UNKNOWN` 不得被下游当成不存在。

先让子代理/主代理穷尽代码、DDL、配置、调用样例和项目文档。普通不确定性用置信度与验证动作表达，不请求人工逐项确认；只有高影响 `UNKNOWN/REPLACE/NEW` 涉及无法从工程证据判断的所有权、兼容承诺、安全合规或不可逆替换，且不同答案会实质改变设计时，才诚实请求人工，并说明缺失事实、无法决定原因、选项、推荐和影响范围。

## 必交付物

写入 `docs/software-design/00-control/project-foundation/`：

- `project-convention-profile.md`；
- `architecture-profile.md`；
- `architecture-layer-mapping.csv`；
- `probe-task-register.csv`；
- `project-module-catalog.csv`；
- `project-capability-catalog.csv`；
- `existing-api-catalog.csv`；
- `existing-schema-catalog.csv`；
- `extension-point-catalog.csv`；
- `reuse-decision-catalog.csv`；
- `component-gap-catalog.csv`；
- 更新问题、决策、产物和追踪账本。

使用 [探查交付模板](references/deliverable-template.md)。目录采用追加和版本化更新：事实修订保留稳定ID并增加版本；语义替换使用 `supersedes_id`；旧项标记 `RETIRED`，不得静默删除。

## 禁止

- 把旧表直接当目标数据模型；
- 把已有Service边界直接当限界上下文或聚合；
- 只列模块名，不验证公开契约与实际消费者；
- 看到BPM仍另建通用流程引擎，或看到文件能力仍另建通用文件服务；
- 用“项目应该有”代替路径、符号、表和版本证据；
- 仅凭目录名宣称采用DDD、COLA、Clean或微服务；
- 让多个子代理无边界重复扫描，或把未经主代理复核的结论直接合并；
- 在探查阶段修改代码、表或业务设计。

## Gate GF

- 项目/版本/切片可复核；
- 探查任务有范围、证据和主代理汇总结论；
- 发现候选均已登记，供下游消费项均已逐项确认，探查清单无无痕遗漏；
- 架构风格、部署形态、依赖方向、包结构和例外有证据化剖面；
- 与切片有关的基础模块、API、表、扩展点和工程约定已登记；
- 每项关键能力有证据、所有者、置信度和验证状态；
- `REUSE/EXTEND/ADAPT/REPLACE/NEW/UNKNOWN` 决策可追踪；
- BPM、权限、组织、字典、文件、通知、审计和ID等适用能力无未解释遗漏；
- `NEW/REPLACE` 有业务依据、排除证据和可追踪决策；确需人工的项目有具体结构化问题。

否则返回 `READY_WITH_ASSUMPTIONS`、`NEEDS_CONTEXT`、`BLOCKED` 或 `FAILED_VALIDATION`，并限定下游可继续的范围。
