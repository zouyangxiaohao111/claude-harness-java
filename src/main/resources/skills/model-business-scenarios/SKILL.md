---
name: model-business-scenarios
description: 把已精炼的软件需求转换为可执行的业务场景、主流程与异常流程、业务规则、状态机、权限矩阵、审计和业务结果。用于梳理审批、撤回、退回、驳回、重提、终止、超时、重复提交、外部系统失败等行为，或评审需求能否进入领域边界与聚合设计。不要设计数据库表、代码目录或视觉稿。
---

# 场景、规则、状态与权限

本阶段回答“系统必须怎样行动”，不回答“代码和表放在哪里”。

独立执行协议：稳定ID使用 `{PROJECT}-{TYPE}-{DOMAIN}-{SEQUENCE}`，序号不复用；证据状态只用 `SOURCE_FACT/CONFIRMED/INFERRED/PROPOSED/OPEN/REJECTED/RETIRED`。结束时返回 `status, gate, gate_status, continuation_required, next_work_item_id, next_stage, stop_reason, discovered_items, registered_ids, confirmed_ids, unconfirmed_ids, artifacts, added_ids, changed_ids, retired_ids, blockers, upstream_revision, next_allowed_stages, evidence`；被总入口调用时保留调用方的项目、切片和基线版本。

## 铁律：逐项发现、登记、确认并继续

每个场景、规则、状态轴/迁移、权限决策、命令和异常路径必须先从需求发现候选，再以稳定ID追加到全局 `00-control/work-item-registry.csv`，并把明细追加到本阶段场景主清单、`business-rules.csv`、`state-transitions.csv`、`permission-matrix.csv` 等对应CSV，最后由AI核对来源、主/异常闭环和互相引用后回写同一CSV行确认。只在回复/Markdown说“已登记”、只返回 `registered_ids` 或CSV只有表头均不算登记。未登记不得展开流程，未确认不得进入领域设计。确认一项后自动选择下一登记项；清单闭合后自动进入G2收敛和下一阶段，不因完成一个场景或一份文档停止。

## 准入

消费G1通过的需求基线；若仅有候选需求，必须保留证据等级。读取 `REQ/CAP/ACT/FLD/INT/NFR/AC/ISSUE/DEC`。

## 工作流

1. 按参与者目标建立端到端场景目录，不按菜单拆场景。
2. 对每个场景建立：触发、前置、主流程、备选、异常、后置、业务结果、审计证据和外部依赖。
3. 将动作改写为命令候选；查询与命令分开记录。
4. 提取业务规则：条件、结果/计算、例外、错误提示、生效范围/时间、来源。
5. 建立业务状态机：状态、触发事件、守卫、执行者、状态变化、副作用、失败处理。
6. 将业务状态、工作流状态、健康度、资格有效性分成不同状态轴，除非业务确认合并。
7. 建立权限决策：主体角色/岗位、组织范围、对象归属、资源、动作、业务状态、工作流状态、字段、渠道和拒绝结果。
8. 覆盖撤回、退回、驳回、重提、重复、并发、超时、外部不可用、部分失败和人工恢复等适用路径。
9. 把新增推断写入 `PROPOSED/OPEN`，不冒充来源事实。
10. 用决策表、状态迁移表和权限矩阵检查闭合，执行Gate G2。

## 稳定ID

主要使用：`SCN BR STM TRN PERM CMD AC ISSUE DEC ASM`。每项引用上游 `REQ/CAP/ACT/INT/NFR`。

## 必交付物

写入 `docs/software-design/02-business-scenarios/`：

- `business-scenario-model.md`；
- `business-rules.csv`；
- `state-transitions.csv`；
- `permission-matrix.csv`；
- 更新问题、决策和追踪账本。

使用 [交付模板](references/deliverable-template.md)。

## 禁止

- 只写快乐路径；
- 把审批实例状态塞进项目业务状态枚举；
- 把“某角色可编辑”当完整权限；
- 用前端隐藏按钮替代服务端授权；
- 把外部接口HTTP成功等同于业务成功；
- 直接提出表名、Controller或微服务。

## Gate G2

返回 `READY` 前确认：

- 每个核心能力至少一个端到端场景；
- 适用主/异常/补偿路径闭合；
- 每条规则有条件、结果、例外、来源和错误表现；
- 每个状态迁移有事件、守卫、权限、副作用和失败处理；
- 权限覆盖功能、数据、对象、字段、状态和渠道；
- 所有推断保持候选，重大语义已决策或阻塞。
