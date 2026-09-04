# 增量后端设计模板

## 用例工作清单

`use-case-design-worklist.csv`：`work_item_id,use_case_ref,candidate_name,kind,priority,depends_on,upstream_refs,research_refs,reuse_refs,architecture_refs,assignee,state,ai_review_evidence,consistency_review_ref,human_review_status,human_feedback_refs,decision_refs,version,updated_at`。

用例类型为 `COMMAND/QUERY/JOB/CONSUMER`。先由AI自检并基线化清单；一次只推进一个工作项，不要求人工逐项批准。

## 用例卡

每个 `use-cases/<UC-ID>.md` 记录：名称、触发者、权限、输入、前置、规则、状态变化、业务结果、事务、并发、幂等、错误、审计、事件、集成、复用组件、架构/包映射、持久化、失败恢复、替代方案、开放问题、AI基线和可选人工审查记录。

## 固定目录列

- `api-contract-catalog.csv`：`id,use_case_ref,operation_id,protocol,method_path,input_schema,output_schema,auth,error_refs,idempotency,concurrency,compatibility,status,version,supersedes_id`。
- `transaction-boundary-catalog.csv`：`id,use_case_ref,resources,isolation,locks,commit_point,side_effects,rollback,recovery,status,version`。
- `authorization-catalog.csv`：`id,use_case_ref,permission_refs,function_check,data_scope,object_check,field_policy,deny_semantics,audit,status,version`。
- `idempotency-concurrency-catalog.csv`：`id,use_case_ref,key_scope,request_hash,in_progress,success_replay,failure_replay,retention,version_check,conflict,retry_owner,status,version`。
- `integration-catalog.csv`：`id,use_case_ref,authority,direction,trigger,schema,auth,idempotency,timeout,retry,reconcile,compensate,manual_recovery,status,version`。
- `event-publication-catalog.csv`：`id,use_case_ref,event_ref,publication_mode,commit_relation,outbox,ordering,deduplication,consumers,recovery,status,version`。
- `component-reuse-mapping.csv`：`id,use_case_ref,need,reuse_decision_ref,component_ref,mode,public_contract,adapter,error_translation,data_owner,risk,status,version`。

## API最低契约

定义唯一operationId、认证授权、请求/响应、枚举/空值、分页/排序、字段校验、规则拒绝、401/403/404/409/412/429、依赖失败、幂等、版本/ETag、超时、追踪ID、敏感字段和前端恢复动作。遵循项目现有统一错误格式；无既有格式时可采用RFC 9457。

## 幂等与并发

先按调用者/用例/对象作用域读取幂等记录并校验请求哈希，再判断当前版本和业务状态。同键同请求重放原确定性结果；结果未知使用原键；确定冲突刷新版本并由用户重新确认时使用新键。幂等不替代乐观锁。

## 实现映射

三层可记录 `UC -> Controller -> BusinessService -> Rule/Policy -> DAO/Mapper -> Table`，并列公开模块API、Client/Gateway、Listener、事件发布和测试位置。已有BPM/Infra/System能力必须引用其公开契约或适配器。

## Append与Gate

候选契约按用例保存在 `contracts/`；AI基线化并通过增量一致性和契约验证后才组合为正式OpenAPI。变更记录版本、替代关系、兼容窗口和消费者影响。报告当前工作项、已基线化/检查/验证/阻塞/剩余数量；阶段文档完成后汇总可选人工审查项，清单闭合才通过G4后端组件。
