---
name: design-backend-architecture
description: 将已确认的场景、领域边界、聚合、数据模型和现有项目能力基线，分批转换为后端用例、API/事件契约、权限、事务、并发、幂等、错误、审计、集成及代码映射。用于先建立用例工作清单，再逐用例研究和追加设计；适配既有BPM/Infra/System等组件及三层、四层、Clean、模块化单体或微服务。不要因DDD设计强制建立domain层或微服务，也不要一次性生成整个后端架构。
---

# 后端契约与代码架构

先设计业务契约，再映射到真实项目。全局运行只建立用例清单；详细契约一次推进一个用例或一个不可拆分的契约组。

独立执行协议：稳定ID使用 `{PROJECT}-{TYPE}-{DOMAIN}-{SEQUENCE}`，序号不复用；证据状态只用 `SOURCE_FACT/CONFIRMED/INFERRED/PROPOSED/OPEN/REJECTED/RETIRED`。结束时返回 `status, gate: G4, gate_component: BACKEND, gate_status, run_mode, work_item_id, continuation_required, next_work_item_id, next_stage, stop_reason, discovered_items, registered_ids, confirmed_ids, unconfirmed_ids, artifacts, added_ids, changed_ids, retired_ids, blockers, upstream_revision, next_allowed_stages, evidence`。

## 铁律：逐项发现、登记、确认并继续

每个用例、API/事件/任务契约、事务、授权、幂等/并发、错误、集成、复用和代码映射必须先发现候选及上游/架构证据，再以稳定ID和版本追加到全局 `00-control/work-item-registry.csv`，并把工作项/明细追加到 `use-case-design-worklist.csv`、`use-case-catalog.csv`、`api-contract-catalog.csv` 及对应事务/权限/并发/错误/集成/代码映射CSV；最后由AI审查、验证并回写同一CSV行，置为 `BASELINED/CONFIRMED`。只在回复/Markdown说“已登记”、只返回 `registered_ids` 或CSV只有表头均不算登记。未登记不得生成契约，未确认不得并入OpenAPI或正式代码映射。确认一个用例后自动继续下一工作项；清单闭合后自动完成G4后端收敛并路由前端，不因完成“步骤5”、单个契约或目录设计而停止。

## 准入

正式设计要求G3通过；持久化映射消费已基线化的数据工作项。存在代码库时要求GF覆盖目标切片，读取 `MOD/COMP/XAPI/XTBL/EXT/REUSE/GAP`、`architecture-profile.md` 和 `architecture-layer-mapping.csv`，并定向验证真实项目的模块/API边界、实际架构、依赖方向、框架、事务、数据访问、异常、权限、审计、日志和测试风格。

同时读取 `SCN/CMD/BR/STM/PERM/CTX/AGG/EVT/DATA/TBL/INT/NFR`。探查事实必须附代码版本、路径和符号/调用样例；不得以现有Controller/Service机械生成用例。

## 运行模式与队列

- `PLAN`：建立 `use-case-design-worklist.csv` 和用例候选，不批量生成OpenAPI或目录；
- `DESIGN_USE_CASE`：一次细化一个命令、查询或后台任务；
- `DESIGN_CONTRACT`：为一个已AI评审用例生成API/事件/任务契约；
- `MAP_IMPLEMENTATION`：把一个已基线化契约映射到现有架构、组件、模块和代码位置；
- `REVIEW_CHANGE`：增加版本、影响、兼容和迁移，不静默覆盖。

工作项状态：`PLANNED -> RESEARCHING -> PROPOSED -> AI_REVIEWED -> BASELINED -> CONSISTENCY_CHECKED -> CONTRACT_VALIDATED -> MAPPED -> VERIFIED`，也可进入 `BLOCKED/RETIRED/SUPERSEDED`。同一时刻最多一个 `RESEARCHING`。用户要求完整后端设计时默认先执行 `PLAN`；AI自检后可自动基线化并继续，不要求人工逐用例批准。

`BASELINED` 是AI当前基线，人工阶段审查另记 `human_review_status`。AI必须先穷尽文档、代码、架构剖面、既有决策和相邻契约；可用显式可逆假设继续时就继续。只有缺失事实会实质改变业务规则、数据权威、安全合规、公开兼容或高影响架构选择且无法可靠裁决时，才诚实请求人工，并列缺失事实、不能决定的原因、选项、推荐、影响项和延后默认值。

## 逐项工作流

1. 建用例候选：触发者、权限、输入、前置、规则、状态变化、业务结果、错误、审计、事件/集成副作用和上游ID。
2. 选取一个工作项，重新读取相关场景/规则/状态/权限/聚合/表、项目能力与架构剖面、既有调用样例和前序基线契约。
3. 先做能力复用判断：对BPM、文件、组织权限、字典、通知、导出、审计等选择 `REUSE/EXTEND/ADAPT`；`NEW/REPLACE` 必须有可追踪决策和证据，只有无法由证据裁决的高影响选择才请求人工。
4. 设计用例：命令、查询和后台任务分离；确定雪花ID、授权、事务、状态守卫、并发、幂等、审计、软删除和失败恢复。
5. 定义契约：operationId、输入/输出、枚举/空值、错误、分页/排序、版本/ETag、幂等键、超时、追踪ID和敏感字段。
6. 为副作用选择本地事务、同步调用、进程内事件、Outbox/消息或Saga，并说明重复、顺序、对账、补偿和人工恢复。
7. AI自检后标记 `AI_REVIEWED/BASELINED`，自动执行增量一致性审查；可修问题路由回本技能修复，无阻塞后标记 `CONSISTENCY_CHECKED`。
8. 契约验证通过后，按已探明的三层、DDD四层、Clean/Hexagonal、COLA或混合架构映射Controller/Service/Application/Domain/DAO/Mapper/Adapter/Client/Listener等位置；不得仅因偏好改造全项目。三层项目可由Service承载领域规则，但职责必须可定位和测试。
9. 建 `UC -> API -> SVC -> AGG/TBL/EVT -> ERROR/AUDIT -> PAGE/TC` 追踪；完成该工作项后才推进下一个。

## 现有组件优先规则

- 先找公开API、扩展点和实际消费者，再考虑直接依赖实现类；
- 业务状态与BPM实例/任务状态分轴，业务表保存必要关联ID，不复制流程引擎模型；
- 文件、模板、预览、导出、用户、组织、字典、权限和通知优先调用基础模块；
- 跨模块只能通过已基线化的公开契约或适配器，不直写其他模块私有表；
- 现有组件不满足时记录适配缺口、兼容责任和错误翻译，不能仅因分层偏好重建。

## Append协议

所有目录行记录稳定ID、版本、状态、`supersedes_id/change_reason/upstream_refs/reuse_refs/decision_refs`。每个用例保留 `use-cases/<UC-ID>.md`，契约片段保留 `contracts/<UC-ID>.*`；只有验证通过的片段才合并到正式 `openapi.yaml`。已发布契约变化必须记录兼容策略和消费者影响。

## 必交付物

写入 `docs/software-design/05-backend-architecture/`：

- `backend-architecture.md`、`use-case-design-worklist.csv`、`use-case-catalog.csv`；
- `api-contract-catalog.csv`、`transaction-boundary-catalog.csv`；
- `authorization-catalog.csv`、`idempotency-concurrency-catalog.csv`；
- `error-catalog.csv`、`integration-catalog.csv`、`event-publication-catalog.csv`；
- `component-reuse-mapping.csv`、`code-mapping.csv`、`architecture-decisions.md`；
- `use-cases/`、`contracts/`及验证通过后的 `openapi.yaml`；
- 更新问题、决策、产物和追踪账本。

使用 [增量后端设计模板](references/deliverable-template.md)。

## 禁止

- 一次性生成全量用例、OpenAPI和代码目录；
- 先画目录再补用例，或一个实体一个CRUD API；
- 未探查已有组件就新建流程、文件、权限等基础服务；
- API只有200，写接口无权限/事务/并发/幂等/错误/审计；
- Controller或SQL承载核心规则；跨模块直写表；
- 三层架构因没有domain目录就判为不合格；
- 为“先进”默认微服务、CQRS、事件溯源或Saga。
- 为每个用例请求人工确认，或用“请确认架构”代替对现有依赖方向的探查。

## Gate G4（后端）

单项验证只提升该工作项。只有本期用例清单全部达到 `VERIFIED`、现有组件复用/适配决策闭合、契约组合验证通过且无跨项冲突，后端组件才 `PASSED`。否则返回当前项、剩余项及下一允许动作。
