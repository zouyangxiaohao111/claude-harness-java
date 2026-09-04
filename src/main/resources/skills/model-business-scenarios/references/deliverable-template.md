# 行为模型模板

## 场景卡

```yaml
id: PRJ-SCN-DOMAIN-001
name: 场景名称
actor_refs: []
trigger: ""
preconditions: []
main_flow: []
alternative_flows: []
exception_flows: []
postconditions: []
business_result: ""
rule_refs: []
state_transition_refs: []
permission_refs: []
data_refs: []
integration_refs: []
audit_evidence: []
acceptance_refs: []
open_issues: []
```

## 规则表

记录 `id,condition,outcome_or_formula,exceptions,error_message,effective_scope,effective_time,source_refs,status`。

## 状态迁移表

记录 `id,state_axis,from_state,event_or_command,guard,actor,permission_ref,to_state,side_effects,failure_handling,audit`。显式测试自环、重复和非法迁移。

## 权限矩阵

记录 `id,subject_role,org_scope,object_relation,resource,action,business_state,workflow_state,field_policy,channel,decision,deny_behavior`。

## 异常目录

至少逐场景判断：撤回、退回、驳回、重提、重复、并发、超时、依赖不可用、部分成功、人工恢复、取消/终止。

## Gate报告

列G2通过/失败、证据、候选规则、阻塞决策及下一允许阶段。

