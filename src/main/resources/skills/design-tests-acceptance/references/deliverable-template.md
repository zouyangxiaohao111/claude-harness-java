# 增量测试与验收模板

## 风险与覆盖义务

- `risk-catalog.csv`：`id,area,description,likelihood,impact,priority,upstream_refs,mitigation,test_obligation_refs,owner,status,version`。
- `coverage-obligation-catalog.csv`：`id,obligation_type,upstream_refs,risk_refs,required_outcome,negative_boundary,observable_channel,environment_need,data_need,priority,status,version,supersedes_id`。
- `test-design-worklist.csv`：`work_item_id,obligation_ref,candidate_name,test_level,priority,depends_on,upstream_refs,research_refs,assignee,state,ai_review_evidence,consistency_review_ref,human_review_status,human_feedback_refs,decision_refs,version,updated_at`。

义务类型包括 `REQUIREMENT/RULE/STATE/PERMISSION/API/INTEGRATION/MIGRATION/NFR/UAT`。先由AI自检并基线化清单，一次只推进一个义务，不要求人工逐项批准。

## 验收场景

```yaml
id: PRJ-AC-DOMAIN-001
version: 1
upstream_refs: []
given: []
when: ""
then:
  business_result: ""
  state: ""
  ui_or_api: ""
  notifications: []
  audit: []
  downstream: []
negative_or_boundary: []
evidence_required: []
data_refs: []
owner: ""
status: PROPOSED
```

## 测试目录

- `test-case-catalog.csv`：`id,obligation_ref,type,risk,preconditions,data_refs,steps,expected,observable_channel,automation_candidate,environment,upstream_refs,status,version,supersedes_id`。
- `test-data-catalog.csv`：`id,purpose,roles_orgs,object_relations,states,values,source,sensitivity,masking,uniqueness,setup,cleanup,repeatability,owner,status,version`。
- `state-permission-coverage.csv`：`id,state_or_permission_ref,allowed_case_refs,denied_case_refs,boundary_case_refs,channel,coverage_status,version`。

## 覆盖方法

逐项判断决策表、状态迁移、边界值、权限组合、契约、集成故障、迁移对账、性能、安全、可用性和UAT。审计、通知、事件和对账必须注明可观察渠道。

## Append与Gate

上游变化追加新版本和重测义务；结果使用独立ID引用被执行的测试版本，不覆盖旧结果。报告当前工作项、已基线化/检查/可执行/验证/阻塞/剩余数量和未覆盖高风险ID；阶段文档完成后汇总可选人工审查项，清单闭合后才通过G6。
