# 项目能力探查交付模板

## 目录

1. 工程约定
2. 子代理探查任务
3. 架构剖面
4. 模块目录
5. 能力与API
6. 现有数据库
7. 扩展点
8. 复用决策
9. 缺口与GF

## 工程约定

记录项目/版本、构建与运行方式、语言/框架、模块分层、API/实现拆分、基础DO、ID、审计、软删除、事务、异常、权限、测试、DDL和JSON映射约定。每项附证据路径与置信度。

## 子代理探查任务

`probe-task-register.csv`：`task_id,scope,allowed_paths,questions,agent_or_owner,evidence_contract,status,returned_claims,conflicts,merged_by,version`。子代理只读；主代理登记去重、冲突处理和最终合并结果。

## 架构剖面

`architecture-profile.md` 分开记录架构风格、部署形态和领域建模成熟度，列候选、结论、反证、例外和置信度。

`architecture-layer-mapping.csv`：`id,architecture_candidate,module_or_layer,responsibility,allowed_dependencies,forbidden_dependencies,entry_points,domain_logic_location,transaction_owner,public_contract,representative_paths,violations,confidence,status,version,supersedes_id`。

至少比较 `THREE_LAYER/DDD_FOUR_LAYER/CLEAN_HEXAGONAL/COLA/MODULAR_MONOLITH/MICROSERVICES/HYBRID`；没有充分证据时使用 `UNKNOWN`。包名只能作为线索，真实依赖和调用样例才是证据。

## 固定目录列

- `project-module-catalog.csv`：`id,module_name,module_type,responsibility,public_boundary,dependencies,owner,evidence_paths,confidence,status,version,supersedes_id`。
- `project-capability-catalog.csv`：`id,capability_name,category,provider_module,public_contract,consumer_examples,data_owner,limitations,evidence_paths,confidence,status,version,supersedes_id`。
- `existing-api-catalog.csv`：`id,api_name,provider_module,protocol,operations,input_output,auth,error_semantics,consumers,evidence_paths,confidence,status,version,supersedes_id`。
- `existing-schema-catalog.csv`：`id,table_name,owner_module,purpose,key_fields,write_entry,consumers,retention,evidence_paths,confidence,status,version,supersedes_id`。
- `extension-point-catalog.csv`：`id,component_ref,extension_kind,location,contract,constraints,example_consumer,evidence_paths,status,version,supersedes_id`。
- `reuse-decision-catalog.csv`：`id,business_need_refs,candidate_component_refs,decision,fit_gap,adapter_or_extension,compatibility,data_ownership,decision_ref,verification,status,version,supersedes_id`。
- `component-gap-catalog.csv`：`id,business_need_refs,gap,excluded_candidates,risk,proposed_owner,next_action,decision_ref,status,version,supersedes_id`。

## 关键能力检查

逐项判断是否适用并给证据：BPM/审批、组织用户、功能/数据/对象/字段权限、租户、字典、文件/附件/预览、模板/导入导出、通知、审计日志、调度、雪花ID、统一异常、幂等/并发、消息和外部集成。

## GF报告

列检查范围、代码版本、子代理任务及合并结果、架构剖面、各目录版本、未验证项、`NEW/REPLACE/UNKNOWN` 项和可供领域/数据/后端/前端消费的AI基线范围。只有真正无法由证据选择的高影响冲突才列为人工审查项。
