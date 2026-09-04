# 领域设计模板

## 通用语言

记录 `TERM id,term,definition,context,synonyms,forbidden_terms,source_refs,status`。

## 限界上下文卡

记录 `CTX id,mission,includes,excludes,language,data_authority,capabilities,upstream,downstream,contracts,change_reason,open_issues`。

## 聚合卡

```yaml
id: PRJ-AGG-DOMAIN-001
name: 聚合名称
root: 根名称
root_identity: ""
upstream_refs: []
commands: []
invariants:
  - id: PRJ-BR-DOMAIN-001
    statement: ""
    violation: ""
included_entities: []
included_value_objects: []
explicitly_excluded: []
state_transitions: []
domain_events: []
transaction_boundary: ""
concurrency_strategy: ""
failure_recovery: ""
external_references: []
persistence_candidate: ""
confidence: medium
alternatives: []
open_issues: []
```

## 聚合压力测试

分别验证主流程、退回/撤回、重复、并发写、部分失败、依赖不可用、历史快照、批量和复杂查询。查询压力不应反向扩大写聚合；必要时提出读模型。

## 设计到实现候选映射

若架构已确认，只记录 `MAP id,design_item,selected_architecture,mapping,reuse_decision_refs,adapter_boundary,decision_basis,risk`。架构未定且用户需要比较时，才增加 `three_layer/four_layer/modular_monolith/microservice` 候选列。已有模块/表/API只能作为映射约束，不能作为上下文或聚合的生成依据。仅作为下一阶段输入。

## Gate报告

列G3每项、证据、失败、替代方案、阻塞决策和下一允许阶段。
