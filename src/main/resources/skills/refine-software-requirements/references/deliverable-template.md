# 需求基线模板

## 1 元数据

项目、切片、版本、日期、所有者、状态、输入来源、批准记录。

### 来源登记schema

`source_id,file_name,declared_version,body_version,metadata_modified_at,owner,approver,authority,applicable_scope,visual_status,content_status,conflicts,status`。

来源定位优先使用可复核组合：`文件名/版本 + 章节标题 + 表名/行键或段落摘记 + 页码（仅在稳定渲染时）`。页码不稳定时不得单独作为定位。

结构化正文/表格可提取但因字体等原因视觉验证失败时，记录 `content_status=READABLE`、`visual_status=FAILED`，继续事实提取但请求来源方提供已验证Word或PDF；不得宣称视觉核验通过。

## 2 目标与范围

| ID | 类型 | 内容 | 优先级/批次 | 来源 | 状态 |
|---|---|---|---|---|---|

明确列出本期、后期、不做和外部依赖。

## 3 术语与参与者

| ID | 名称 | 定义/职责 | 数据范围 | 同义/禁用词 | 来源 | 状态 |
|---|---|---|---|---|---|---|

## 4 能力与原子需求

| ID | 陈述 | 参与者 | 触发/结果 | 优先级 | 来源 | 验收意图 | 开放问题 |
|---|---|---|---|---|---|---|---|

`requirement-catalog.csv` 固定列：`id,capability_id,statement,actor,trigger,result,priority,batch,evidence_status,source_refs,acceptance_intent,owner,version,open_issue_ids`。

## 5 数据/字段种子

记录 `id,business_name,canonical_key,cardinality,required_when,source_mode,authority,edit_policy,sensitivity,history_intent,source_refs`。

“项目中获取”必须转为实时引用/提交快照的开放决策；“外部同步可改”必须定义本地覆盖、回写和冲突。

## 6 集成与NFR

集成记录方向、触发/频率、认证、幂等、超时、重试、对账、补偿和延迟。NFR记录环境、工作负载、指标、阈值和测量方法。

## 7 冲突与决策

| ID | 类型/严重度 | 证据双方 | 影响 | 选项 | 推荐 | 责任人 | 状态 |
|---|---|---|---|---|---|---|---|

## 8 验收意图与追踪

每个Must需求至少有一个可观察结果。更新 `SRC/CAP/REQ -> AC`，不要在此阶段伪造完整测试用例。

## 9 Gate报告

列G0/G1每项的通过/失败、证据、阻塞ID和下一允许阶段。
