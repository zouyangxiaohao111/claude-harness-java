# 统一产物与工作项协议

## 最高铁律

所有产物项必须按 `DISCOVERED -> REGISTERED -> CONFIRMED/BASELINED` 推进。发现项保留原始证据；登记必须把候选追加为 `00-control/work-item-registry.csv` 的真实数据行，并同步追加到阶段主清单CSV；确认必须回写同一登记行的确认状态和证据。未写CSV、CSV只有表头、只写Markdown/回复或只返回 `registered_ids` 都不算登记。未登记项不得细化，未确认项不得作为正式下游输入。人工确认不是默认要求。

确认一项后必须继续下一项；确认一个阶段后必须继续下一阶段。阶段结果固定包含 `continuation_required,next_work_item_id,next_stage,stop_reason`。允许的 `stop_reason` 只有 `REQUESTED_SCOPE_COMPLETE/G7_PASSED/NEEDS_HUMAN_INPUT/UNRECOVERABLE_VALIDATION_FAILURE`。

## 工作区

```text
docs/software-design/
├── 00-control/
│   └── project-foundation/
├── 01-requirements/
├── 02-business-scenarios/
├── 03-domain-boundaries/
├── 04-data-model/
├── 05-backend-architecture/
├── 06-frontend-experience/
├── 07-tests-acceptance/
└── 08-consistency-review/
```

`00-control`保存 `design-chain-manifest.json`、`artifact-registry.csv`、`traceability-ledger.csv`、`work-item-registry.csv`、`issues.csv`、`decisions.csv`、`human-input-requests.csv`、`human-review-feedback.csv`。项目探查产物放入 `project-foundation/`。

01–08及GF必须遵循 [阶段产物合同](stage-artifact-contract.md)：阶段CSV是可计算事实和逐项登记账本，Markdown是面向人的基线说明，二者缺一不可。阶段准备通过时，必需CSV不能只有表头；确无适用项时写一条带原因和证据的 `NOT_APPLICABLE` 数据行，不得以空文件表示。

Manifest记录 `codebase_status`、`code_version` 和 `project_probe`。`EXISTS` 时必须存在同版本GF记录和项目探查产物；子代理可用时 `project_probe` 必须证明总入口创建了专用只读任务、任务显式使用 `inspect-existing-project-capabilities` 且合格返回。只有环境无子代理能力时才允许记录 `PARENT_FALLBACK` 和原因。`GREENFIELD` 必须有明确判定证据；`NOT_PROVIDED` 不得被自动当作绿色项目。

`project_probe` 固定记录：`subagent_available,execution_mode,skill_name,agent_task_ref,status,fallback_reason,code_version,artifact_refs`。`execution_mode` 仅允许 `SUBAGENT/PARENT_FALLBACK`；子代理模式要求非空任务引用，降级模式要求非空原因，`PASSED` 要求代码版本和产物引用完整。该记录是执行证据，不得由事后推断或隐式技能触发代替。

`current_gate`表示正在评估的Gate；同时记录 `gate_status: NOT_CHECKED | IN_PROGRESS | PASSED | FAILED | WAIVED`。每次Gate运行保存输入版本、检查结果、证据和执行验证的主体。兼容字段 `approved_by` 可以记录 `AI:<skill-or-agent>`，不表示人工签字；人工审查单独记录在 `human_review` 和反馈台账。

## 稳定ID

格式：`{PROJECT}-{TYPE}-{DOMAIN}-{SEQUENCE}`。序号永不复用，不包含状态、年份、版本、阶段号、章节号或文件路径。

业务/设计类型：`SRC GOAL SCOPE TERM ACT CAP REQ SCN BR STM TRN PERM FLD DATA CTX AGG CMD EVT TBL COLUMN CONSTRAINT INDEX QUERY UC API SVC ERROR AUDIT PAGE ACTION TC AC RESULT DEFECT NFR INT DEC ISSUE CONFLICT ASM MAP WORK CLUSTER`。

工程事实类型：`MOD COMP XAPI XTBL EXT REUSE GAP`。`CAP`只表示业务能力；现有组件能力使用 `COMP`，避免混淆。

典型业务链：`SRC/CAP/REQ -> SCN -> BR/STM/PERM -> CTX/AGG/CMD -> DATA/TBL -> UC/API/SVC/EVT -> PAGE/ACTION -> TC/AC`。

工程事实链：`MOD/COMP/XAPI/XTBL/EXT -> REUSE/GAP -> CTX/AGG/TBL/UC/API/PAGE/TC`。

关系至少包括：`derives_from realizes persists_as exposes renders verifies reuses adapts extends replaces conflicts_with supersedes`。

## 证据与状态

证据只使用 `SOURCE_FACT CONFIRMED INFERRED PROPOSED OPEN REJECTED RETIRED`。下游不得自行提升证据等级。每项至少记录 `id,type,title,status,version,owner,source_refs,confidence,upstream_refs,decision_refs,verification_refs,supersedes_id`。

阶段状态：`NEEDS_CONTEXT/BLOCKED/READY_WITH_ASSUMPTIONS/REVISE_UPSTREAM/FAILED_VALIDATION/READY`。候选文件存在不能提升Gate。

## 工作项

全局 `work-item-registry.csv` 固定列：

`work_item_id,stage,item_type,candidate_key,item_ref,state,discovery_evidence,registered_artifact,confirmation_status,confirmation_evidence,priority,depends_on,baseline_version,owner,source_worklist,updated_at`

每次“登记”至少追加一个非空数据行；`work_item_id` 唯一，`discovery_evidence` 可定位原始来源，`registered_artifact` 和 `source_worklist` 指向真实阶段CSV，`confirmation_status` 初始明确为未确认状态。AI确认后更新同一行的 `confirmation_status` 和非空 `confirmation_evidence`，不得另写总结冒充回写。阶段返回的每个 `registered_ids` 都必须可在该CSV中查到，并可沿 `registered_artifact/source_worklist` 查到阶段CSV数据行。

阶段工作清单保存详细研究、AI评审、基线、一致性检查和可选人工反馈。一个阶段最多一个 `RESEARCHING`；总入口一次只路由一个工作项。不得从 `PLANNED` 直接跳到正式产物。

每次阶段返回必须分别报告 `discovered_items/registered_ids/confirmed_ids/unconfirmed_ids`。`registered_ids` 必须能在产物目录找到；`confirmed_ids` 必须是 `registered_ids` 子集并附确认依据。发现后被否决的候选也要登记为 `REJECTED` 或进入候选日志，不能无痕消失。

详细阶段状态统一使用 `PLANNED/RESEARCHING/PROPOSED/AI_REVIEWED/BASELINED/CONSISTENCY_CHECKED/.../VERIFIED`。`human_review_status: NOT_REVIEWED/HUMAN_COMMENTED/HUMAN_CONFIRMED` 是旁路元数据，不是工作项状态或Gate。

`human-input-requests.csv` 固定列：`id,stage,work_item_id,missing_fact,why_ai_cannot_decide,options,recommendation,impact_ids,default_if_deferred,scope_blocked,status,answered_by,updated_at`。只有无法由证据解决且会实质改变设计的问题才能登记。

`human-review-feedback.csv` 固定列：`id,stage,artifact_refs,feedback,status,affected_ids,issue_refs,recorded_at`。阶段文档后的反馈追加记录，通过影响分析重开工作项。

## Append与变更

- 同一语义修订保留ID并增加版本；拆分/合并创建新ID和替代关系；
- 废弃对象保留并标记 `RETIRED/SUPERSEDED`，不物理删除、不复用编号；
- 目录和事实历史只追加；当前视图可重建，但不得覆盖历史；
- 已基线化表通过迁移脚本变更；已发布契约记录兼容窗口；已基线化页面/测试追加新版本；
- 每次运行报告新增、修改、废弃、冲突、断链和工作项状态变化。
