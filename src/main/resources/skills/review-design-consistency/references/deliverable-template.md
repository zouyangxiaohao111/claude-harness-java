# 增量一致性审查模板

## 审查工作清单

`consistency-check-worklist.csv`：`work_item_id,review_mode,trigger_ref,trigger_version,change_type,direct_scope,transitive_scope,input_baseline,assignee,state,finding_refs,auto_fix_skill,owner_fix_refs,human_input_required,human_question_refs,recheck_version,decision_refs,updated_at`。

## 产物一致性矩阵

`artifact-consistency-matrix.csv`：`id,source_ref,source_version,target_ref,target_version,relationship,rule_family,expected,actual,check_status,evidence,checked_at`。

矩阵至少覆盖业务链与工程复用链：

- `REQ/SCN/BR/STM/PERM -> CTX/AGG -> DATA/TBL -> UC/API -> PAGE/ACTION -> TC/AC`；
- `MOD/COMP/XAPI/XTBL/EXT -> REUSE/GAP -> TBL/UC/API/PAGE/TC`。

## 发现台账

`finding-ledger.csv`：`id,version,severity,rule_family,title,evidence,affected_ids,root_stage,owner,recommendation,close_condition,status,waiver_decision,opened_at,rechecked_at,supersedes_id`。

发现状态使用 `OPEN/ACKNOWLEDGED/AUTO_FIX_ROUTED/FIXING/FIXED_PENDING_RECHECK/NEEDS_HUMAN_INPUT/CLOSED/WAIVED/REOPENED`。原产物修订后才可复核；审查者不能直接标记业务修复完成。

## 影响分析

`impact-analysis.csv`：`changed_id,changed_version,change_type,direct_impacts,transitive_impacts,reopen_work_items,retest_ids,unaffected_ids,unaffected_reason,decision_refs`。

## 审查模式输出

- 增量：触发项、局部矩阵、发现、自动修复路由、确需人工的问题和下一动作；
- 阶段收敛：工作清单闭合度、跨项冲突、Gate建议；
- 变更影响：重开/重测队列和无影响证明；
- 最终G7：冻结基线、规则族统计、严重度、豁免、证据索引和限定完成声明。

## Append与G7

保留所有历史报告、输入版本、发现状态变化、自动修复和复核证据。当前矩阵可重建，但不得覆盖历史发现。阶段文档后的人工反馈追加为新问题并重开影响项。只有所有检查工作项闭合且全局重算通过，才能输出G7通过。
