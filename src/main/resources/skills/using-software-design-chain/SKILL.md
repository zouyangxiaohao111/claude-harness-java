---
name: using-software-design-chain
description: 编排从建设方案到完整企业软件设计的全过程：需求与场景、子代理探查现有架构/BPM/Infra/System、领域边界、逐表数据、逐用例后端、逐任务前端、逐义务测试及自动增量/最终一致性审查。以“逐项发现→登记→确认”为不可跳过的最高铁律，通过AI基线和工作队列控制粒度；只有确实缺失且会实质改变设计的事实才诚实请求人工。
---

# 软件设计链总入口

把软件设计作为两条事实线汇合、由工作队列驱动的增量链：业务事实来自文档和决策，工程事实来自真实项目探查。总入口只编排，不替代阶段技能。

## 铁律：逐项发现、登记、确认

这是高于阶段便利、批量生成和进度要求的最高执行规则，适用于每一个需求、术语、场景、规则、状态、权限、组件、架构判断、上下文、聚合、表、字段、API、事件、页面、测试、缺陷和变更：

1. **发现**：从来源、代码或上游关系识别单个候选，记录候选名称、范围和原始证据；发现不等于结论。
2. **登记**：把候选真实追加写入 `docs/software-design/00-control/work-item-registry.csv`，并把业务/设计明细追加到对应阶段主清单CSV；至少落盘稳定ID、版本、状态、来源证据、上游、责任和开放问题。未写入CSV行不算登记，未登记不得细化或交给下游。
3. **确认**：AI逐项复核证据、语义、冲突和一致性，将其置为 `CONFIRMED` 或 `BASELINED` 并记录确认依据；未确认不得进入正式DDL、契约、页面、测试或Gate完成统计。

确认默认由AI完成，不等于人工逐项批准。确实需要人工时遵循后文三条件协议。批量扫描只能产生“发现候选”；批量写文档不能替代逐项登记；在回复或Markdown中写“已登记”、只返回 `registered_ids`、CSV仅有表头或事后补写均不算登记。`registered_ids` 必须逐个对应 `work-item-registry.csv` 的真实数据行和阶段CSV数据行；确认必须回写同一行的 `confirmation_status/confirmation_evidence`。文件存在、格式正确或模型自信不能替代逐项确认。任何阶段发现跳步，立即回到缺失步骤，不得事后补登记掩盖过程。

**不断链附则**：逐项表示“同一时刻只处理一项”，不表示“处理一项后结束”。一项确认/验证后必须自动选择下一个已登记未确认项；本阶段清单闭合后必须自动执行阶段收敛审查并路由下一阶段。不得因为完成PLAN、生成一份文档、关闭一个工作项、阶段返回READY或到达步骤5而停止，也不得逐阶段询问“是否继续”。

只允许在以下终止条件停止：用户请求范围已完成；G7通过；确实满足人工介入三条件；工具/验证失败且穷尽安全自修复仍无法继续。每次阶段返回必须提供 `continuation_required,next_work_item_id,next_stage,stop_reason`；只要 `continuation_required=true`，总入口就在同一任务中继续路由。

## 核心规则

1. 在设计前识别项目、纵向切片、来源版本、代码版本、当前Gate、现有产物和工作项。
2. 需求/场景建立业务事实；项目探查建立实现事实。旧代码不能创建业务规则，业务设想也不能伪装成已有组件。
3. 一次只激活一个阶段计划或工作项，但完成后在同一任务中自动路由下一项/下一阶段。第4阶段以后，用户说“完整生成”也先生成候选清单，不批量生成全部细节。
4. 目录先行：AI先自检并基线化存储簇/表、用例、页面、测试义务清单，再逐项研究、基线化和验证；不要求人工逐项批准。
5. 同一阶段最多一个 `RESEARCHING`；工作项未经 `BASELINED/CONSISTENCY_CHECKED` 不得生成正式DDL、OpenAPI、页面合同或可执行验收基线。
6. 每个工作项 `BASELINED` 或变化后自动路由增量一致性审查；可修问题自动回到原阶段技能修复和复核，不暂停等待人工。
7. 所有目录追加和版本化：稳定ID、版本、状态、替代关系和变更理由；不得静默覆盖已基线化产物。
8. DDD用于边界和聚合设计；实现可映射三层、四层、模块化单体或微服务。聚合、表、模块、API、页面和部署服务不是一一对应。
9. 下游发现上游错误时返回问题ID、受影响ID、目标阶段和关闭条件，不得静默改写。

## 启动

读取 [阶段路由](references/stage-router.md)、[产物协议](references/artifact-protocol.md)、[阶段产物合同](references/stage-artifact-contract.md) 和 [质量门](references/quality-gates.md)。

若项目代码、纵向切片或权威来源未知，停在G0。确认后初始化：

```bash
python3 scripts/design_chain.py init <project-root> --project-code <CODE> --slice-id <SLICE>
```

已有工作区先运行结构检查；它不证明任何Gate通过：

```bash
python3 scripts/design_chain.py check-structure <project-root>
```

脚本相对本技能目录解析，不假设当前目录。

## 阶段技能

| 顺序 | 必用技能 | 核心产物 |
|---|---|---|
| 1 | `refine-software-requirements` | 需求基线、来源/冲突/决策、验收意图 |
| 2 | `model-business-scenarios` | 场景、规则、状态、权限、异常与审计 |
| F | `inspect-existing-project-capabilities` | 工程约定、模块/API/表/扩展点、复用与缺口 |
| 3 | `design-domain-boundaries` | 通用语言、上下文、聚合根、不变式与已有能力映射 |
| 4 | `design-data-model` | 存储簇/表工作清单，逐表设计、DDL与迁移 |
| 5 | `design-backend-architecture` | 用例工作清单，逐用例契约、复用和代码映射 |
| 6 | `design-frontend-experience` | 任务/页面工作清单，逐页合同与API绑定 |
| 7 | `design-tests-acceptance` | 风险/覆盖义务清单，逐项测试和UAT |
| 8 | `review-design-consistency` | 增量审查、影响分析、阶段闭合与G7 |

F可在G1后与场景建模并行；存在代码库时，进入G3/G4详细设计前GF必须覆盖目标切片。确认绿色项目时也执行一次探查并登记“无现有项目”的证据。

项目能力探查需要子代理能力，总入口先创建专用只读探查子代理，并在任务中明确要求该子代理使用 `inspect-existing-project-capabilities`；探查技能可再按项目规模拆分边界清晰的只读证据任务。主代理只负责限定范围、接收/校验证据、登记产物和继续设计链，不在子代理可用时自行吞掉全工程扫描。GF必须给出三层、DDD四层、Clean/Hexagonal、COLA、模块化单体、微服务或混合架构的证据化现状剖面，供后端包结构映射使用。

## 项目探查强制触发（启动子代理）

启动时检查项目根目录及 `pom.xml/build.gradle/settings.gradle/package.json/go.mod/Cargo.toml/*.sln`、源码目录、DDL或迁移目录等代码证据，登记 `codebase_status: EXISTS/GREENFIELD/NOT_PROVIDED`。上传ZIP属于代码库，先只读解压再判断。

当 `codebase_status=EXISTS` 且GF没有同一代码版本的 `PASSED` 记录时，总入口必须显式创建专用只读子代理，并让子代理加载、执行 `inspect-existing-project-capabilities`，写入 `project-foundation/` 后再继续。禁止依赖隐式技能触发；禁止只把“建议使用子代理”写入计划却不创建任务；禁止因已完成需求/场景、用户说“继续”或已有旧架构印象而跳过。GF未通过时不得进入领域实现映射、物理数据、后端包结构或前端组件设计。

### 总入口创建子代理的强制协议

总入口在代码库存在时必须先判断当前运行环境是否提供子代理能力，并把判断写入 Manifest 的 `project_probe.subagent_available`。能力存在时，必须实际创建一个有界、只读的专用子代理；其任务指令必须逐字点名 `Use @inspect-existing-project-capabilities` 或“使用 `inspect-existing-project-capabilities` 技能”，不得期待描述相似度自动触发。任务至少携带：

```yaml
probe_task:
  skill_name: inspect-existing-project-capabilities
  project_root: ABSOLUTE_PROJECT_ROOT
  code_version: COMMIT_OR_ARCHIVE_HASH
  slice_id: SLICE
  allowed_paths: []
  write_policy: READ_ONLY
  evidence_contract:
    - architecture-profile
    - module-component-api-table inventories
    - reuse-gap-decisions
    - evidence-index
  return_contract:
    - status
    - agent_task_ref
    - artifact_refs
    - evidence_refs
    - blockers
```

主代理必须先把探查任务追加到 `work-item-registry.csv`，并把任务明细追加到 `project-foundation/probe-task-register.csv`，再保存创建任务所得的稳定引用到 `project_probe.agent_task_ref`。等待子代理返回后，校验其确实使用指定技能、范围未越界、证据可定位、代码版本一致，追加登记 `project-foundation/` 各项CSV产物，回写原任务行的确认状态/证据，最后把 `project_probe.status` 置为 `PASSED`。只创建任务但未取得合格结果仍是 `IN_PROGRESS/FAILED`，不得通过GF。子代理内部如何分片由探查技能控制；主代理不得把未合并的多份观察直接当成工程事实。

只有运行环境确实没有子代理能力时，才允许主代理按探查技能最小协议降级执行；此时必须记录 `subagent_available=false`、`execution_mode=PARENT_FALLBACK` 和非空 `fallback_reason`。不能创建成功、子代理失败、时间紧或项目较小都不是自动降级理由，应先安全重试或登记失败。技能不可用或代码不可访问则诚实返回 `NEEDS_CONTEXT`。只有明确绿色项目才能登记GF无现有能力基线后继续。

用户显式只要一个阶段时直接使用对应技能并验证上游；不要强制跑完整链。

## 工作队列协议

第4阶段以后按以下循环：

```text
PLAN候选清单
→ AI自检并BASELINED
→ 选一个工作项RESEARCHING
→ 读取上游、文档定位、既有决策和项目能力证据
→ PROPOSED/AI_REVIEWED/BASELINED
→ 自动INCREMENTAL一致性审查
→ 自动路由修复并复核
→ 生成并验证正式产物
→ 关闭或修复后选择下一项
```

数据设计优先以“聚合存储簇”为规划单位、以“一张表”为细化/DDL单位。后端以一个用例，前端以一个角色任务或页面，测试以一个覆盖义务为工作项。候选列表可以全局生成，细化不得全局并发。

总入口在 `00-control/work-item-registry.csv` 逐行登记跨阶段工作项；阶段技能在自己的主清单CSV逐行登记详细项。两处必须以稳定ID互相引用：总表保存状态和阶段CSV路径，阶段CSV保存详细证据。总表没有数据行、阶段CSV没有对应数据行，或二者ID无法关联时，均视为未登记；Markdown正文只是说明材料，不是登记账本。

## 路由输入输出

提供：

```yaml
invoked_by: using-software-design-chain
project_code: CODE
slice_id: SLICE
baseline_version: VERSION
code_version: VERSION_OR_NONE
input_artifacts: []
current_gate: G0
gate_status: NOT_CHECKED
run_mode: PLAN
work_item_id: null
allowed_assumptions: []
open_blockers: []
project_probe:
  subagent_available: null
  execution_mode: null
  skill_name: inspect-existing-project-capabilities
  agent_task_ref: null
  status: NOT_STARTED
  fallback_reason: null
  code_version: null
  artifact_refs: []
```

阶段返回：

```yaml
stage_result:
  status: IN_PROGRESS | READY | READY_WITH_ASSUMPTIONS | NEEDS_CONTEXT | BLOCKED | REVISE_UPSTREAM | FAILED_VALIDATION
  gate: G0
  gate_component: null
  gate_status: NOT_CHECKED | IN_PROGRESS | PASSED | FAILED | WAIVED
  run_mode: PLAN
  work_item_id: null
  continuation_required: true
  next_work_item_id: null
  next_stage: null
  stop_reason: null
  discovered_items: []
  registered_ids: []
  confirmed_ids: []
  unconfirmed_ids: []
  artifacts: []
  added_ids: []
  changed_ids: []
  retired_ids: []
  blockers: []
  upstream_revision: null
  next_allowed_stages: []
  evidence: []
```

## AI基线与人工介入

`BASELINED` 是AI依据当前证据建立的可继续工作基线，不等于人工签字。阶段文档完成后可将整份文档交给人工审查；人工指出不准确项后，登记反馈、计算影响、重开相关工作项、自动修复并复核。未进行人工审查不阻止AI继续完整设计链。

AI必须先穷尽权威文档、代码/数据库、项目能力目录、既有决策和相邻产物。能通过明确、可逆、范围受限的假设继续时，登记 `ASM-*` 并继续；不得把普通技术选择、格式、自检或可从证据判断的问题推给人工。

只有同时满足以下条件才暂停请求人工：

1. 缺失或冲突事实无法从现有证据可靠裁决；
2. 不同答案会实质改变范围、业务规则、状态/权限、数据权威、合规安全、删除迁移、公开兼容或不可逆高影响架构；
3. 选择一个默认值可能明显偏离用户意图，且没有安全可逆的局部路径。

请求必须写入 `human-input-requests.csv`，说明 `missing_fact,why_ai_cannot_decide,options,recommendation,impact_ids,default_if_deferred,scope_blocked`。问题要少而具体；即使请求人工，也继续所有不依赖该答案的工作。若只是置信度一般但有明显推荐，给出AI基线和风险，不强制人工批准。

## 回退与停止

- 来源阅读、术语/NFR提取和项目探查可在阶段内并行，由单一所有者合并。
- 第4阶段以后只允许候选清单并行发现；同一工作项的物理表、API、页面和测试按短周期纵向收敛。
- 阶段文档完成时登记可选人工审查状态 `NOT_REVIEWED/HUMAN_COMMENTED/HUMAN_CONFIRMED`；它不替代Gate，也不要求逐项停顿。
- 同一问题自动回退最多一次；第二次无新证据时转 `NEEDS_CONTEXT/BLOCKED`。
- 连续两轮开放问题、工作项和产物无实质变化时停止，禁止无限循环。

## 与实施技能协作

DOCX、PDF和表格由适用文件技能读取；它们不拥有业务决策。若存在Superpowers或其他设计/计划/TDD/执行技能，只有被实施的纵向切片通过对应Gate和增量审查后才交接；不把未安装技能作为硬依赖。

仅在更新本技能链、审计依据或发生方法冲突时读取 [研究与综合索引](references/research/INDEX.md)。

## 完成条件

只有所有本期工作清单闭合、阶段组合审查通过，并由 `review-design-consistency` 对冻结基线输出G7通过，才称“设计链完成”。
