---
name: review-design-consistency
description: 对需求、场景、规则、状态、权限、项目基础能力、领域边界、聚合、数据表、API、代码、页面和测试执行只读跨产物一致性审查。用于每个工作项AI基线化后的自动增量检查、修复路由、变更影响分析、阶段收敛和最终G7；逐项登记断链、多写入者、重复建设、状态/权限漂移及孤儿产物。不要要求人工逐项批准，也不要直接静默修改原产物。
---

# 跨产物一致性审查

审查者只报告问题、影响和补丁建议；总入口自动把问题路由给原阶段技能修订后复核。增量审查是默认自动步骤，不等待人工逐项批准；最终G7是已审增量结果的全局重算，不是第一次检查。

独立执行协议：稳定ID使用 `{PROJECT}-{TYPE}-{DOMAIN}-{SEQUENCE}`，序号不复用；证据状态只用 `SOURCE_FACT/CONFIRMED/INFERRED/PROPOSED/OPEN/REJECTED/RETIRED`。结束时返回 `status, gate: G7, gate_status, review_mode, work_item_id, continuation_required, next_work_item_id, next_stage, stop_reason, discovered_items, registered_ids, confirmed_ids, unconfirmed_ids, artifacts, added_ids, changed_ids, retired_ids, blockers, upstream_revision, next_allowed_stages, evidence`。

## 铁律：逐项发现、登记、确认并继续

每个检查义务、矩阵关系、发现、影响和豁免必须先发现候选与证据，再以稳定ID/版本追加到全局 `00-control/work-item-registry.csv`，并把工作项/明细追加到 `consistency-check-worklist.csv`、`artifact-consistency-matrix.csv`、`finding-ledger.csv`、`impact-analysis.csv`；最后经复核回写同一CSV行，置为 `CONFIRMED/CLOSED` 或明确保持开放。只在回复/Markdown说“已登记”、只返回 `registered_ids` 或CSV只有表头均不算登记。未登记的问题不得只写在总结里，未复核的问题不得关闭。审查还必须检查所有上游阶段是否遵守“发现→登记→确认”且两级CSV存在对应数据行。关闭一个审查项后自动继续下一项；增量清单闭合后执行阶段收敛，所有阶段闭合后自动执行FINAL_G7，不因产出一份审查报告而停止。

## 准入

取得同一项目、切片和基线的产物注册表、追踪账本、问题/决策台账、工作清单和待审版本。混合版本先阻塞。

自然语言候选或单一产物只能执行候选审查，不得给G7通过。每个新 `BASELINED` 或变更的表、用例/API、页面、测试项都自动创建增量审查工作项。

## 审查模式与队列

- `INCREMENTAL`：审查一个新增/变更产物及其上游和直接下游；
- `STAGE_CLOSURE`：审查一个阶段清单组合、跨项关系和Gate资格；
- `CHANGE_IMPACT`：计算直接/传递影响、重开和重测范围；
- `FINAL_G7`：按冻结基线全链重算。

`consistency-check-worklist.csv` 状态：`PLANNED -> CHECKING -> FINDINGS_RECORDED -> AUTO_FIX_ROUTED -> RECHECKED -> CLOSED`，也可进入 `NEEDS_HUMAN_INPUT/BLOCKED/WAIVED/SUPERSEDED`。同一时刻最多一个 `CHECKING`；豁免不等于修复。

默认不请求人工。能由上游证据、架构/组件事实、公司规范或跨产物关系裁决的问题，必须给出结论并自动路由修复。只有缺失事实会实质改变业务含义、数据权威、权限、合规、安全、删除/迁移或不可逆高影响决策，且AI穷尽现有证据仍无法可靠选择时，才进入 `NEEDS_HUMAN_INPUT`。此时必须诚实列出缺失事实、为什么AI不能决定、选项、推荐、影响项和延后默认值；不得泛化为“请确认设计”。

阶段文档完成后允许人工整体审查。人工指出不准确项时，将反馈登记为问题/变更引用，计算影响并自动重开相应工作项；人工审查不是每个工作项的前置Gate。

## 工作流

1. 识别触发产物、版本、变更类型、工作项和AI基线证据；建立受影响ID集合。
2. 从上游向下游检查：`REQ -> SCN -> BR/STM/PERM -> CTX/AGG -> DATA/TBL -> UC/API/SVC/EVT -> PAGE/ACTION -> TC/AC`。
3. 同时检查工程事实线：`MOD/COMP/XAPI/XTBL/EXT -> REUSE/GAP -> AGG/TBL/UC/API/PAGE/TC`，发现已有能力却重复新建时登记问题。
4. 反向检查每个表、字段、API、页面、事件和测试是否有有效业务依据及可追踪的复用/新建决策。
5. 检查术语、范围、状态、权限、数据、数据库规范、JSON、边界、组件复用、API、前端、集成、NFR和测试规则族。
6. 每个问题记录稳定ID、严重度、证据、受影响ID、根因阶段、修复技能、建议和关闭条件；审查技能不直接改业务产物。
7. 可由AI修复的问题标记 `AUTO_FIX_ROUTED`，总入口调用根因阶段技能修改并自动复核；确需人的问题才登记 `NEEDS_HUMAN_INPUT`。
8. 生成直接和传递影响，只重开从变化ID可达的工作项；给无影响项记录理由。
9. 原产物修订后使用同一检查工作项复核；旧报告不能证明新版本。
10. 阶段清单闭合时执行组合审查；最终冻结输入版本后执行全链G7。

## 关键检查

- 工程复用：已有BPM/文件/权限/组织/字典/通知等能力却新建通用组件/表，或跨模块绕过公开API直写表；
- 数据：表工作项未基线化/检查就生成DDL，目录版本相互矛盾，索引无查询，JSON藏入高频/唯一/关联/状态/金额/权限字段；
- 数据库规范：非bigint雪花主键、AUTO_INCREMENT、固定五审计字段缺失/改名、乐观锁只有字段没有CAS协议；
- 状态/权限：业务与流程状态混轴，API/页面动作绕过守卫，前端隐藏替代后端授权；
- 契约：用例、API、错误、幂等、并发、事件、页面绑定和测试版本漂移；
- 工作队列：跳过研究/AI评审/基线/一致性检查，多个项目同时RESEARCHING，旧版本被覆盖，清单未闭合却宣称Gate通过。
- 铁律：批量生成后补登记、发现项无痕消失、未登记即细化、未确认即进入下游，或有下一项却无允许stop_reason而中断。

## 严重度

- `BLOCKER`：需业务/架构裁决，继续会扩散错误；
- `CRITICAL`：语义、权限、状态、数据所有权、重复基础建设或实现矛盾；
- `MAJOR`：追踪、覆盖、失败处理、复用适配或版本缺口；
- `MINOR`：不改变语义的表达、命名或格式问题。

## Append协议

审查结果追加到 `finding-ledger.csv`，保留发现、复核、关闭和重开历史。发现内容修订保留ID并升版本；新的根因使用新ID。矩阵可重建当前视图，但历史报告、输入版本、豁免和证据不得覆盖。

## 必交付物

写入 `docs/software-design/08-consistency-review/`：

- `consistency-check-worklist.csv`、`artifact-consistency-matrix.csv`；
- `finding-ledger.csv`、`consistency-review.md`；
- `impact-analysis.csv`、`stage-closure-reports/`；
- `gate-g7-report.md`；
- 更新问题、决策、产物和追踪账本。

使用 [增量审查模板](references/deliverable-template.md)。

## 禁止

- 等到全部设计完成才首次审查；
- 审查者直接重写业务规则、状态、权限、表或契约；
- 用“看起来一致”代替ID、版本和证据；
- 自动修复业务语义、删除策略或架构决策；
- 把可以根据证据修复的问题推给人工，或为每个工作项请求人工批准；
- 用旧报告证明新版本，或用大量无理由N/A清空检查；
- BLOCKER/CRITICAL未闭环却宣称完成。

没有可追踪 `DEC-*` 依据而违反公司主键/审计字段规范或重复建设已有基础能力，至少记为 `CRITICAL`，阻止相关Gate和G7。

## Gate G7

只有冻结范围内工作清单全部闭合、BLOCKER/CRITICAL为零、MAJOR关闭或有效豁免、无孤儿/重复建设/版本漂移、AI基线和验证证据可复核时通过。人工整体审查不是G7的必需前置；其后反馈会生成新版本并重开影响项。完成声明必须限定项目、切片、基线和审查时间。
