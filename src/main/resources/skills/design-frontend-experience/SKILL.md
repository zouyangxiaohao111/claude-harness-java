---
name: design-frontend-experience
description: 将已确认的角色、场景、规则、状态、权限、后端契约和现有前端能力基线，分批转换为角色任务、信息架构、页面流、路由、动作权限、字段合同、数据源/API绑定和页面状态。用于先建立任务/页面工作清单，再逐任务逐页面研究、确认和追加设计，适配现有菜单、权限指令、组件库和请求层；不要从数据库表或微服务列表自动生成页面，也不要一次性生成全部前端设计。
---

# 前端任务与页面设计

基本单位是角色任务，不是表、聚合或服务。全局运行只形成任务和页面候选清单；详细设计一次推进一个任务流或页面工作项。

独立执行协议：稳定ID使用 `{PROJECT}-{TYPE}-{DOMAIN}-{SEQUENCE}`，序号不复用；证据状态只用 `SOURCE_FACT/CONFIRMED/INFERRED/PROPOSED/OPEN/REJECTED/RETIRED`。结束时返回 `status, gate: G5, gate_status, run_mode, work_item_id, continuation_required, next_work_item_id, next_stage, stop_reason, discovered_items, registered_ids, confirmed_ids, unconfirmed_ids, artifacts, added_ids, changed_ids, retired_ids, blockers, upstream_revision, next_allowed_stages, evidence`。

## 铁律：逐项发现、登记、确认并继续

每个角色任务、页面/路由、数据源、动作权限、字段合同、交互状态、API绑定和复用组件必须先从场景/契约发现候选，再以稳定ID和版本追加到全局 `00-control/work-item-registry.csv`，并把工作项/明细追加到 `page-design-worklist.csv`、`role-task-matrix.csv`、`page-route-catalog.csv` 及对应动作权限/字段/API绑定/组件复用CSV；最后由AI逐项核对语义、权限、状态和契约后回写同一CSV行，置为 `BASELINED/CONFIRMED`。只在回复/Markdown说“已登记”、只返回 `registered_ids` 或CSV只有表头均不算登记。未登记不得细化页面，未确认不得作为正式前端合同。确认一个任务或页面后自动继续下一项；清单闭合后自动执行G5并路由测试，不因生成页面清单或单个页面设计而停止。

## 准入

至少消费G2行为基线；详细动作、字段和错误恢复需要已基线化用例/API。存在前端工程时消费GF的架构剖面、模块、路由、菜单、权限、组件库、请求层、错误处理和既有页面模式事实。读取 `ACT/SCN/BR/STM/TRN/PERM/FLD/DATA/UC/API/ERROR/NFR/REUSE`。

输入不全时可输出 `PROPOSED` 清单，不自行补造状态、权限、枚举、默认值或后端能力。

## 运行模式与队列

- `PLAN`：建立角色任务矩阵、信息架构候选和 `page-design-worklist.csv`；
- `DESIGN_TASK_FLOW`：一次细化一个端到端角色任务；
- `DESIGN_PAGE`：一次细化一个页面职责、动作、字段、数据源和状态；
- `BIND_CONTRACT`：把一个已AI评审/基线化页面绑定到API、错误、幂等和并发契约；
- `REVIEW_CHANGE`：追加版本、影响和迁移，不静默覆盖。

状态为 `PLANNED -> RESEARCHING -> PROPOSED -> AI_REVIEWED -> BASELINED -> CONSISTENCY_CHECKED -> CONTRACT_BOUND -> VERIFIED`，也可进入 `BLOCKED/RETIRED/SUPERSEDED`。同一时刻最多一个 `RESEARCHING`。用户要求完整前端设计时默认先执行 `PLAN`；AI自检后自动基线化并继续，不要求人工逐页批准。

`BASELINED` 不等于人工签字。人工通常在阶段文档完成后审查，另记 `human_review_status`。AI必须先穷尽文档、代码、既有页面、契约和决策；能用可逆假设继续时记录后继续。只有缺失事实会实质改变角色任务、权限、字段含义、渠道能力或公开契约且无法可靠裁决时，才诚实请求人工，并说明缺失事实、不能决定的原因、选项、推荐、影响工作项和延后默认值。

## 逐项工作流

1. 建角色×任务目录：触发入口、目标、前置、主/异常路径、结束状态、审计、渠道和场景引用。
2. 设计信息架构和页面候选，建立工作清单；按任务聚合，不按表或微服务分菜单。
3. 选取一个任务流，重新读取其场景、规则、状态、权限、数据权威、用例/API、现有前端模式和已基线化相邻页面。
4. 建任务→页面流及返回/恢复；一个任务可跨页，一个页面可组合多个边界的只读数据。
5. 一次细化一个页面：职责、路由、数据源、动作、字段、权限、加载/空/错误/冲突/超时/旧数据状态和可执行恢复。
6. 对外部同步数据显示来源、数据时间和同步状态；BPM操作绑定已有流程API，业务状态与流程状态分轴展示。
7. 绑定API：请求/响应、枚举/空值、分页、错误、幂等键、版本冲突、字段权限和审计；前端隐藏不替代后端授权。
8. PC/移动按渠道能力设计附件/拍照、弱网、离线草稿、通知和返回恢复，不把窄屏缩放当移动设计。
9. AI自检后标记 `AI_REVIEWED/BASELINED` 并自动执行增量一致性审查；可修问题路由回本技能修复，无阻塞后标记 `CONSISTENCY_CHECKED`。
10. 建 `SCN/PERM/STM -> PAGE/ACTION/FIELD -> API/ERROR -> TC` 追踪，完成该工作项后再推进下一个。

## 现有前端复用

先确认路由/菜单注册、权限指令、字典组件、上传/预览、流程组件、表格/表单、错误处理和请求封装；采用 `REUSE/EXTEND/ADAPT` 并引用项目证据。只有基础能力不存在或不满足已基线化NFR时才提出新公共组件。

## Append协议

目录行保留稳定ID、版本、状态、`supersedes_id/change_reason/upstream_refs/api_refs/reuse_refs/decision_refs`。任务卡保存到 `tasks/<TASK-ID>.md`，页面卡保存到 `pages/<PAGE-ID>.md`。变更已基线化页面时追加新版本和影响，不删除旧动作/字段来隐藏契约变化。

## 必交付物

写入 `docs/software-design/06-frontend-experience/`：

- `frontend-design.md`、`role-task-matrix.csv`、`page-design-worklist.csv`；
- `page-route-catalog.csv`、`page-data-source-catalog.csv`；
- `page-action-permission.csv`、`form-field-contract.csv`；
- `interaction-state-catalog.csv`、`api-binding-catalog.csv`；
- `frontend-component-reuse.csv`、`tasks/`、`pages/`；
- 更新问题、决策、产物和追踪账本。

使用 [增量前端设计模板](references/deliverable-template.md)。需要视觉稿时再调用适用的可视化能力；业务页面合同先于视觉装饰。

## 禁止

- 一次性生成全部菜单、页面和字段；
- 从表结构生成CRUD页面并当成角色任务；
- 未探查现有组件就新建上传、流程、字典或权限公共组件；
- 页面直接写业务状态，或只用隐藏按钮实现权限；
- 只画成功态，错误统一显示“系统异常”；
- 前端私自增加状态、枚举、默认或计算规则；
- 把PC页面压窄当移动端设计。
- 为每个页面请求人工确认，或用“请确认交互”替代对场景、契约和既有页面的研究。

## Gate G5

单页验证只提升该工作项。只有本期高优先级任务和页面清单全部达到 `VERIFIED`、页面/API/权限/字段/错误契约组合一致且无孤儿项，才返回 `PASSED`；否则返回当前项和剩余清单。
