---
name: design-tests-acceptance
description: 从需求、场景、规则、状态、权限、数据模型、API、代码架构、前端设计及现有测试能力基线，分批生成风险、覆盖义务、验收场景、测试数据、契约/集成/迁移/NFR和UAT设计。用于先建立测试工作清单，再逐场景、规则或风险切片研究、确认和追加；不要一次性生成整套测试，也不要只覆盖主流程。
---

# 测试与验收设计

测试设计的基本单位是可追踪的覆盖义务，不是文档章节。全局运行只建立风险和工作清单；详细用例一次推进一个场景/规则/契约切片。

独立执行协议：稳定ID使用 `{PROJECT}-{TYPE}-{DOMAIN}-{SEQUENCE}`，序号不复用；证据状态只用 `SOURCE_FACT/CONFIRMED/INFERRED/PROPOSED/OPEN/REJECTED/RETIRED`。结束时返回 `status, gate: G6, gate_status, run_mode, work_item_id, continuation_required, next_work_item_id, next_stage, stop_reason, discovered_items, registered_ids, confirmed_ids, unconfirmed_ids, artifacts, added_ids, changed_ids, retired_ids, blockers, upstream_revision, next_allowed_stages, evidence`。

## 铁律：逐项发现、登记、确认并继续

每个风险、覆盖义务、验收场景、测试用例、测试数据、观察面和UAT任务必须先从已基线化上游发现候选，再以稳定ID和版本追加到全局 `00-control/work-item-registry.csv`，并把工作项/明细追加到 `test-design-worklist.csv`、`risk-catalog.csv`、`coverage-obligation-catalog.csv` 及对应验收场景/测试用例/测试数据/追踪CSV；最后由AI逐项核对预期、边界、环境和可执行性后回写同一CSV行，置为 `BASELINED/CONFIRMED`。只在回复/Markdown说“已登记”、只返回 `registered_ids` 或CSV只有表头均不算登记。未登记不得写测试步骤，未确认不得进入可执行验收基线。确认/验证一个义务后自动继续下一项；清单闭合后自动执行G6和最终一致性审查，不因完成测试文档或单个用例而停止。

## 准入

消费同一版本的 `REQ/NFR/SCN/BR/STM/TRN/PERM/DATA/TBL/UC/API/PAGE/ACTION/ERROR/INT`，以及GF中的测试框架、基础组件、调用/状态观察面和测试数据限制。设计仍有阻塞冲突时只登记待测义务，不宣称可执行或G6通过。

## 运行模式与队列

- `PLAN`：建立风险、覆盖义务和 `test-design-worklist.csv`；
- `DESIGN_OBLIGATION`：一次细化一个场景/规则/状态/权限/契约/NFR义务；
- `DESIGN_TEST`：为一个已AI评审/基线化义务生成测试和数据合同；
- `ASSEMBLE_UAT`：只把已基线化验收任务组合成UAT方案；
- `REVIEW_CHANGE`：追加版本与重测影响，不覆盖旧结果。

状态：`PLANNED -> RESEARCHING -> PROPOSED -> AI_REVIEWED -> BASELINED -> CONSISTENCY_CHECKED -> EXECUTABLE -> VERIFIED`，也可进入 `BLOCKED/RETIRED/SUPERSEDED`。同一时刻最多一个 `RESEARCHING`。用户要求完整测试方案时默认先执行 `PLAN`；AI自检后自动基线化并继续，不要求人工逐用例批准。

`BASELINED` 是AI测试设计基线，不等于人工签字。人工通常在测试/UAT文档完成后审查，另记 `human_review_status`。AI先穷尽上游产物、代码观察面、测试框架和决策；能以可逆假设继续时记录后继续。只有缺失的业务期望、合规阈值、验收口径、生产等价环境或责任归属会实质改变结论且无法可靠裁决时，才诚实请求人工，并列缺失事实、不能决定的原因、选项、推荐、影响项和延后默认值。

## 逐项工作流

1. 建风险目录：业务价值、合规、金额、权限、状态、集成、迁移、并发、性能和基础组件适配风险。
2. 从上游ID建立覆盖义务；先列清单和依赖，不批量生成测试步骤。
3. 选取一个工作项，重新读取其业务来源、所有相关状态/权限/契约、已基线化表/API/页面、项目观察面和历史决策。
4. 把验收意图改写为可观察Given/When/Then；Then包含业务结果、状态、通知、审计和下游效果。
5. 按义务类型使用决策表、状态迁移、边界值、权限组合、契约、集成、迁移或NFR方法；同时覆盖允许/拒绝、主/异常、重复/并发和人工恢复。
6. 定义测试数据：来源、角色/组织、对象关系、状态、敏感脱敏、唯一性、清理、可重复性和外部替身。
7. 明确观察渠道：业务查询/API、页面、审计时间线、消息/任务探针、对账结果或批准的技术证据；不得只断言数据库行。
8. 选择单元/Service、API、前端、集成、E2E和UAT层级，避免用高成本E2E替代规则测试。
9. AI自检后标记 `AI_REVIEWED/BASELINED` 并自动执行增量一致性审查；可修问题路由回本技能修复，无阻塞后标记 `CONSISTENCY_CHECKED`。
10. 建 `REQ/BR/TRN/PERM/NFR/API/PAGE -> TC/AC -> RESULT/DEFECT` 双向追踪，完成一个工作项后再推进下一个。

## 必查协议

- 幂等：首次成功后携带原版本和同键重放应返回原结果；结果未知复用原键；确定冲突刷新后用新键；
- 数据库：雪花bigint非自增、固定五审计字段、乐观锁更新协议、软删除、JSON schema/兼容/边界；
- BPM/基础组件：验证业务状态与流程状态映射、重复回调、撤销/退回/驳回、文件/权限/通知适配和依赖不可用；
- 权限：功能、组织/数据、对象、字段、直接API、批量和导出；
- 集成：认证、超时、重试、重复、乱序、对账、补偿、旧数据和人工恢复；
- NFR：环境、数据量、并发模型、事务比例、持续时长、P95/P99、成功率、资源和测量方法。

## Append协议

目录保留稳定ID、版本、状态、`supersedes_id/change_reason/upstream_refs/decision_refs`。测试结果不覆盖设计；结果写独立 `RESULT-*` 并引用测试版本。上游变化通过影响分析追加重测项，旧用例和旧结果保留。

## 必交付物

写入 `docs/software-design/07-tests-acceptance/`：

- `test-strategy.md`、`risk-catalog.csv`、`coverage-obligation-catalog.csv`；
- `test-design-worklist.csv`、`acceptance-scenarios.csv`、`test-case-catalog.csv`；
- `state-permission-coverage.csv`、`test-data-catalog.csv`；
- `integration-migration-test.md`、`performance-security-test.md`、`uat-plan.md`；
- `test-traceability.csv`、`tests/`；
- 更新问题、决策、产物和追踪账本。

使用 [增量测试交付模板](references/deliverable-template.md)。

## 禁止

- 一次性生成整套测试用例；
- 只测快乐路径或演示脚本；
- 用菜单不可见代替授权测试，或只看HTTP 200；
- 用数据库断言替代业务可观察结果；
- 把1000在线用户等同1000并发请求；
- 只比较迁移总行数；验收前临时补追踪矩阵；
- 未基线化上游候选却登记为可执行验收标准；
- 为每个测试义务请求人工确认，或用“请确认预期”替代证据研究。

## Gate G6

单项验证只提升该工作项。只有本期所有Must、关键NFR、规则、迁移、状态、权限、API/页面契约和适用基础组件风险都对应可执行测试或已基线化验证方法，工作清单达到 `VERIFIED` 且环境/数据/观察面可获得，才返回 `PASSED`。人工阶段审查可随后提出修订，但不是逐项Gate。
