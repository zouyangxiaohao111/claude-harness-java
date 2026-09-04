---
name: refine-software-requirements
description: 将项目建设方案、可研报告、招标文件、原始需求、字段要素、会议纪要或现有系统说明精炼为可追踪的软件需求基线。用于范围梳理、需求原子化、术语统一、角色与能力目录、冲突/问题/决策台账、可测试非功能需求和验收意图；也用于评审一份需求是否足以进入业务场景设计。不要用于直接设计聚合、数据库表、API或页面。
---

# 需求精炼

输出结构化需求包，不把建设方案扩写成更长的建设方案。

独立执行协议：稳定ID使用 `{PROJECT}-{TYPE}-{DOMAIN}-{SEQUENCE}`，序号不复用；证据状态只用 `SOURCE_FACT/CONFIRMED/INFERRED/PROPOSED/OPEN/REJECTED/RETIRED`。结束时返回 `status, gate, gate_status, continuation_required, next_work_item_id, next_stage, stop_reason, discovered_items, registered_ids, confirmed_ids, unconfirmed_ids, artifacts, added_ids, changed_ids, retired_ids, blockers, upstream_revision, next_allowed_stages, evidence`；被总入口调用时保留调用方的项目、切片和基线版本。

## 铁律：逐项发现、登记、确认

每个来源、术语、角色、能力、需求、字段种子、NFR、冲突和验收意图都必须逐项执行：先从原文发现候选并保留定位；再以稳定ID追加到全局 `00-control/work-item-registry.csv`，并把明细追加到本阶段 `source-register.csv`、`requirement-catalog.csv` 等对应CSV；最后由AI核对来源、语义、冲突和可测试性后回写同一CSV行并置为 `CONFIRMED` 或明确保持 `PROPOSED/OPEN`。只在回复/Markdown说“已登记”、只返回 `registered_ids` 或CSV只有表头均不算登记。未登记不得展开，未确认不得作为正式场景输入。批量提取只能产生候选，不能一次性写完正文后补登记；人工确认不是默认步骤。确认一项后自动继续下一项，G1清单闭合后自动路由场景建模和项目探查，不因需求文档生成而停止。

## 准入

至少取得一个权威来源及其版本。若来源是DOCX/PDF/表格，使用适用的文件技能读取正文、表格、批注和修订；不要只读目录或摘要。

确认：项目代码、纵向切片、目标、本期/后期/不做、来源所有者。缺失时可生成候选需求，但状态不得为 `READY`。

## 证据标签

每条内容只用：`SOURCE_FACT CONFIRMED INFERRED PROPOSED OPEN REJECTED`。事实带来源定位；推断带验证方式；未知进入 `ISSUE/CONFLICT/ASM/DEC`。

## 工作流

1. 登记来源：名称、版本、日期、所有者、权威级别、适用范围。
2. 扫描污染：复制残留、术语混用、批次冲突、数字口径冲突、正文与表格冲突。
3. 建立目标、范围和能力地图；能力不是服务、模块或菜单。
4. 建立术语表和参与者目录，记录同义词、禁用词、数据范围和代理关系。
5. 原子化需求：每项系统义务只表达一个可验证结果，补齐谁、何时、做什么、结果、失败。
6. 提取字段和数据种子：定义、基数、条件必填、来源、权威系统、编辑、敏感、历史意图；不生成表。
7. 把“实时”“高并发”“安全”等改写为带环境、负载、指标、阈值和测量方法的NFR候选。
8. 建立冲突/问题/决策/假设台账；重大范围和术语问题请求用户或责任人裁决。
9. 为Must需求写可观察的验收意图，并建立双向追踪。
10. 按G0/G1自检，输出结构化阶段结果。

## 稳定ID

使用：`SRC GOAL SCOPE TERM ACT CAP REQ FLD DATA INT NFR AC ISSUE CONFLICT DEC ASM`。格式遵循总入口产物协议。

## 必交付物

写入 `docs/software-design/01-requirements/`：

- `requirements-baseline.md`：目标、范围、术语、角色、能力、原子需求、数据/集成/NFR和验收意图；
- `source-register.csv`；
- `requirement-catalog.csv`；
- 更新控制目录中的问题、决策、产物和追踪账本。

使用 [交付模板](references/deliverable-template.md)。

文件名版本、正文版本、文档属性时间和审批记录冲突时全部登记，审批记录与来源所有者确认优先，技术元数据不得自动当作业务版本。高亮、删除线或人工格式痕迹在含义未确认前标为 `OPEN`；不是正式修订就不得当作接受/删除决定。

## 禁止

- 把要素表当数据库表；
- 把方案中的“支持/实现/智能”原样当可开发需求；
- 用“通常系统会”补造撤回、审批、权限或状态规则；
- 用Word序号作为稳定ID；
- 用 `TBD/TODO/后续完善` 隐藏未知；
- 在需求阶段决定微服务、聚合或代码目录。

## Gate G1

只有以下全部成立才返回 `READY`：

- 本期/后期/不做和实施切片清楚；
- 核心术语、角色、目标和能力可判定；
- 重大范围冲突已有决策或阻塞；
- Must需求和关键NFR有验收意图；
- 无无来源、无所有者、无版本的孤儿需求；
- 开放问题有影响、责任人和下一步。

否则返回 `READY_WITH_ASSUMPTIONS`、`NEEDS_CONTEXT` 或 `BLOCKED`，并列出下阶段允许消费的范围。
