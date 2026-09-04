# 增量前端设计模板

## 工作清单

`page-design-worklist.csv`：`work_item_id,task_ref,page_ref,candidate_name,page_type,priority,depends_on,upstream_refs,research_refs,api_refs,reuse_refs,assignee,state,ai_review_evidence,consistency_review_ref,human_review_status,human_feedback_refs,decision_refs,version,updated_at`。

先由AI自检并基线化角色任务和页面候选；一次只推进一个任务流或页面，不要求人工逐项批准。

## 角色任务卡

记录 `task_id,actor,trigger,goal,preconditions,main_flow,alternatives,exceptions,result,audit,channel,scenario_refs,page_refs,status,version`。

## 页面卡

记录页面职责、路由、任务/角色、业务与流程状态轴、数据源、动作、字段、权限、所有交互状态、API/错误绑定、现有组件复用、PC/移动差异、返回恢复、开放问题、AI基线和可选人工审查记录。

## 固定目录列

- `page-route-catalog.csv`：`id,name,route,page_type,task_refs,role_refs,menu_parent,entry_conditions,channel,status,version,supersedes_id`。
- `page-data-source-catalog.csv`：`id,page_ref,purpose,authority,api_ref,query_params,freshness,permission_refs,partial_failure,empty_semantics,status,version`。
- `page-action-permission.csv`：`id,page_ref,action,role,org_scope,object_relation,business_state,workflow_state,channel,rule_refs,api_ref,deny_behavior,status,version`。
- `form-field-contract.csv`：`id,page_ref,field_ref,label,control,data_type,required_when,default,authority,format,range,dependencies,editable_states,field_permission,error_message,api_path,status,version`。
- `interaction-state-catalog.csv`：`id,page_ref,state,trigger,user_message,available_actions,recovery,api_error_ref,telemetry,status,version`。
- `api-binding-catalog.csv`：`id,page_ref,action_or_data_ref,api_ref,request_mapping,response_mapping,error_mapping,idempotency,version_handling,status,version`。
- `frontend-component-reuse.csv`：`id,page_ref,need,reuse_decision_ref,existing_component,mode,adapter,limitations,status,version`。

## 页面状态

至少逐页判断：`initial/loading/partial/success/empty-first/empty-filter/saving/saved/validation-error/unauthorized/forbidden/not-found/conflict/rate-limited/dependency-timeout/unavailable/stale-data`。不适用必须给理由；适用状态必须给用户下一步。

写请求结果未知时复用原幂等键；确定版本/状态冲突后获取最新对象、重新计算动作资格，用户重新确认后使用最新版本和新幂等键。

## Append与Gate

已基线化任务/页面发生变化时增加版本和替代关系，记录受影响API、权限、测试和相邻页面。报告当前工作项、已基线化/检查/绑定/验证/阻塞/剩余数量；阶段文档完成后汇总可选人工审查项，清单闭合后才通过G5。
