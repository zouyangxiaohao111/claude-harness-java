# 增量数据设计交付模板

## 目录

1. DM0候选清单
2. DM1存储簇
3. DM2单表结构
4. DM3物理策略
5. DM4 DDL与验证
6. 版本、迁移与Gate

## DM0：存储簇与工作清单

- `table-cluster-catalog.csv`：`id,name,context_ref,aggregate_refs,purpose,includes,excludes,data_authority,lifecycle,reuse_refs,table_candidate_refs,status,version,supersedes_id`。
- `table-design-worklist.csv`：`work_item_id,cluster_ref,table_ref,candidate_name,table_type,purpose,priority,depends_on,upstream_refs,research_refs,assignee,state,ai_review_evidence,consistency_review_ref,human_review_status,human_feedback_refs,decision_refs,version,updated_at`。

表类型只从 `ROOT/CHILD/RELATION/HISTORY/SNAPSHOT/LEDGER/CONFIG/READ_MODEL/INTEGRATION_STAGING` 选择；附件优先引用既有文件能力，不默认复制文件元数据表。

## DM1：存储簇卡

记录簇使命、业务所有者、写入口、聚合/上下文、包含/排除、强一致范围、跨簇引用、现有组件/表复用、生命周期、删除/归档、并发、历史/快照、迁移来源和开放问题。

## DM2：单表设计卡

每次只填写一张 `tables/<TBL-ID>.md`：

```yaml
table_id: PRJ-TBL-DOMAIN-001
version: 1
cluster_ref: ""
name: ""
purpose: ""
owner_refs: []
upstream_refs: []
reuse_refs: []
write_entries: []
business_key: []
columns: []
relationships: []
constraints: []
application_validations: []
queries: []
indexes: []
json_fields: []
audit_tenant_delete: {}
concurrency: {}
migration: {}
alternatives: []
open_issues: []
ai_baseline: {}
human_review: {}
```

## 中间目录固定列

- `table-catalog.csv`：`id,cluster_ref,physical_name,purpose,table_type,owner_refs,write_entries,business_key,reuse_refs,status,version,supersedes_id`。
- `column-catalog.csv`：`id,table_ref,business_name,physical_name,type,length_precision,nullable,default,comment,data_refs,authority,sensitivity,history,validation,status,version,supersedes_id`。
- `relationship-catalog.csv`：`id,from_table,to_table,cardinality,ownership,reference_mode,on_delete,consistency,source_refs,status,version,supersedes_id`。
- `constraint-catalog.csv`：`id,table_ref,type,columns,rule_refs,database_expression,application_fallback,error_ref,status,version,supersedes_id`。
- `query-pattern-catalog.csv`：`id,consumer_ref,table_refs,filters,sort,join,aggregation,expected_volume,frequency,freshness,permission_refs,status,version`。
- `index-catalog.csv`：`id,table_ref,index_name,columns,unique,query_refs,selectivity,write_cost,status,version,supersedes_id`。
- `json-field-catalog.csv`：`id,column_ref,value_object,why_json,query_exclusions,schema_version,validation,size_limit,sensitivity,migration_trigger,status,version,supersedes_id`。
- `reuse-impact-catalog.csv`：`id,table_or_cluster_ref,reuse_decision_ref,existing_component_or_table,usage_mode,adapter,ownership,compatibility,risk,status,version`。
- `data-model-change-log.csv`：`id,changed_ref,from_version,to_version,change_type,reason,source_refs,decision_refs,migration_ref,rollback_ref,changed_at`。

## DM3：评审检查

逐表确认所有权、主键/业务键、空值、时间/金额、枚举、关系、唯一/检查约束、真实查询与索引、JSON资格、审计、租户、软删除、乐观锁、敏感、保留、迁移、回滚和对账。

## DM4：DDL与验证

只对 `BASELINED` 且增量一致性检查通过的表生成 `ddl/<TBL-ID>.sql`。记录静态检查、目标库版本、执行结果、警告、例外决策和验证证据；通过后把工作项置为 `VERIFIED`。

## Append与迁移

首次基线化且检查通过的DDL可追加到 `schema.sql`。之后的列/索引/约束变化写入独立迁移文件，并在变更日志记录前后版本、回滚和数据对账；不得删除旧目录行或覆盖原DDL来隐藏变化。

## Gate报告

分别列DM0—DM4结果、当前工作项、已基线化/一致性检查/已验证/阻塞/剩余数量、跨表问题、复用冲突和下一允许动作。阶段文档完成后汇总可选人工审查项；全局G4数据组件只在本期清单闭合后通过。
