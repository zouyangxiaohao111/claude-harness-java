# 数据模型与后端架构研究（企业软件设计技能链）

## 目录

- [来源与证据等级](#一来源与证据等级)
- [可核验的外部事实](#二可核验的外部事实)
- [适配到技能链的核心推断](#三适配到技能链的核心推断)
- [数据模型技能结论](#四design-data-model-应采用的研究结论)
- [后端架构技能结论](#五design-backend-architecture-应采用的研究结论)
- [反模式与示例](#六研究识别出的反模式)
- [最终摘要](#八供最终技能采用的摘要)
研究日期：2026-07-21  
研究范围：概念/逻辑/物理数据模型、主数据与快照、审计、多租户、约束和索引、CQRS、API 契约、事务、幂等、Transactional Outbox 与集成对账。  
用途：为 `design-data-model` 与 `design-backend-architecture` 提供研究依据；本文不是最终技能说明。

## 一、来源与证据等级
- **事实（F）**：来自标准、数据库/架构官方文档或用户提供的设计书。
- **推断（I）**：将上述事实适配为企业软件设计链的方法；不是来源原文或产品承诺。
- **建议（R）**：技能应执行的默认动作，可被已确认的项目约束覆盖。
- 用户材料：《中台架构与实现：基于DDD和微服务》，重点参考第8、10、11、14、15、17、19、23、24章；仅归纳观点，不复制原文。

## 二、可核验的外部事实
### F1. 逻辑模型与物理模型不是同一个产物
Oracle Data Modeler 将逻辑模型描述为与实现无关的企业信息视图；物理模型则以具体数据库对象表达，并允许一个关系模型映射多个物理模型。  
来源：[Oracle Data Modeler Concepts and Usage](https://docs.oracle.com/en/database/oracle/sql-developer-data-modeler/18.3/dmdug/data-modeler-concepts-usage.html)

### F2. 约束是数据完整性机制，不只是文档注释
PostgreSQL 官方文档明确：列/表约束会拒绝违反规则的数据；主键、唯一、外键、检查约束具有不同语义。唯一约束和主键会自动建立相应唯一索引。  
来源：[PostgreSQL Constraints](https://www.postgresql.org/docs/current/ddl-constraints.html)、[CREATE TABLE](https://www.postgresql.org/docs/current/sql-createtable.html)

### F3. 索引是查询性能结构，也会增加代价
PostgreSQL 将索引用于增强访问性能，同时提醒不恰当的索引会降低性能；支持多列、表达式、部分索引等不同形式。  
来源：[PostgreSQL CREATE INDEX](https://www.postgresql.org/docs/current/sql-createindex.html)

### F4. 行级安全可以在数据库层约束可见和可修改行
PostgreSQL Row-Level Security 可按用户、角色、命令与布尔策略限制查询和写入；启用后无匹配策略时默认拒绝，但表所有者通常可绕过，`TRUNCATE` 和引用完整性检查也不受其约束。  
来源：[PostgreSQL Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)

### F5. 多租户数据没有单一正确隔离方式
Azure Architecture Center 将共享资源、租户专属资源及混合方式视为需根据隔离、规模、成本、合规和运维权衡的选择，而不是默认“一租户一库”或“全部共享”。  
来源：[Storage and Data in Multitenant Solutions](https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/approaches/storage-data)、[Tenancy Models](https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/considerations/tenancy-models)

### F6. 应用审计不同于基础设施访问日志
OWASP 指出应用事件日志能补充服务器和数据库日志；高价值业务操作应具有防篡改/防删除的审计轨迹，日志应统一、可关联且避免泄漏敏感信息。  
来源：[OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)、[OWASP A09:2021](https://owasp.org/Top10/2021/A09_2021-Security_Logging_and_Monitoring_Failures/)

### F7. CQRS 的核心是分离读写模型或接口，不等于必须上微服务或事件溯源
Microsoft 将 CQRS 定义为把读取与更新操作分离；简化实现可以仍在一个服务和一个数据库中使用不同模型，事件溯源只是可组合方式之一。  
来源：[CQRS Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/cqrs)、[Simplified CQRS and DDD](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/microservice-ddd-cqrs-patterns/apply-simplified-microservice-cqrs-ddd-patterns)

### F8. OpenAPI 是语言无关的 HTTP API 契约
OpenAPI 3.2 定义路径、操作、请求、响应、模式和安全等对象；`operationId` 在一个 API 描述内必须唯一，响应至少应描述成功和已知错误。  
来源：[OpenAPI Specification 3.2.0](https://spec.openapis.org/oas/v3.2.0.html)

### F9. HTTP 幂等语义不自动覆盖所有业务命令
RFC 9110 定义 PUT、DELETE 和安全方法为幂等；POST 并无默认幂等保证。`If-Match` 可用强 ETag 防止并发覆盖和 lost update，前置条件失败通常返回 412。  
来源：[RFC 9110 §9.2.2、§13.1.1](https://www.rfc-editor.org/rfc/rfc9110.html)

### F10. HTTP API 可采用标准机器可读错误格式
RFC 9457 定义 Problem Details，避免每个 API 自创一种错误载荷。  
来源：[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html)

### F11. Transactional Outbox 解决“业务提交与消息发布”双写缺口，但消费者仍要处理重复
Microsoft 的实现将待发布事件与业务数据置于同一事务存储，再异步发布；重处理可能产生重复，因此示例使用应用控制的 MessageId 做去重。  
来源：[Transactional Outbox Pattern](https://learn.microsoft.com/en-us/azure/architecture/databases/guide/transactional-out-box-cosmos)

### F12. Saga 是分布式一致性选项，不是本地事务的替代品
Azure 将 Saga 描述为多个本地事务及补偿/重试的协调；它适合无法使用单一原子事务的跨服务流程，但会增加调试、补偿与测试复杂度。  
来源：[Saga Design Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/saga)

### F13. 用户提供的设计书本身支持“设计与实现解耦”
书中说明 DDD 没有规定唯一代码模型；三层可演进到 DDD 分层，限界上下文映射为微服务时仍需结合部署、团队、伸缩、安全等非业务因素；复杂查询可使用面向查询的数据视图。  
来源：用户提供的 EPUB，第10、11、14、17、23章。

## 三、适配到技能链的核心推断
### I1. 数据设计必须保留三层模型，而不是直接从表单字段生成表
推断：概念模型回答“企业在管理什么及其业务关系”；逻辑模型回答“实体、属性、基数、键与历史语义”；物理模型才回答“表、列类型、约束、索引、分区与数据库特性”。  
原因：直接从页面字段到表会把显示结构、业务事实和存储结构错误绑定；F1 表明逻辑与物理本就承担不同职责。

### I2. 聚合、表、模块、微服务不能画等号
推断：聚合用于表达一致性和行为边界；一个聚合可落到多表，多个简单聚合可同属一个模块/服务；复杂查询可跨表构建只读模型。部署单元另由非功能要求决定。  
依据：F7、F13 与用户要求的架构中立性。

### I3. DDD 设计可映射到三层架构而不丢失边界
推断：若现有三层项目没有独立 domain 层，可把实体规则、状态转换和事务编排落实在业务 service 内，但仍需：按业务边界分包、禁止跨边界任意写表、保留规则 ID、声明事务边界、隔离 DTO/PO。  
这不是“domain 全塞进 service 就天然等价”，而是通过可追踪约束保留设计语义。

### I4. CQRS 应按读写矛盾触发，不应默认采用
推断：只有当写模型不适合报表/搜索、读写负载差异明显、权限投影复杂或跨边界组合频繁时，才建立读模型；简单 CRUD 优先同库独立查询 DTO。  
依据：F7、F13；CQRS 的收益必须大于数据延迟、重建和运维成本。

### I5. “实时”必须转为可测试的数据新鲜度契约
推断：外部系统每日同步的数据只能标注为“截至某次同步”，不能称实时；每个读模型/集成数据需定义权威源、同步模式、最大延迟、失败状态和对账方式。

## 四、`design-data-model` 应采用的研究结论
### 4.1 准入输入
- 已确认的业务场景、规则、状态机、权限矩阵和领域/能力边界。
- 现有表建议只能作为候选输入，不能拥有高于业务规则的权威级别。
- 已知数据源、数据所有者、保留期限、容量、查询和批处理要求。
- 缺少状态终态、唯一性、历史要求或租户定义时，状态应为 `NEEDS_CONTEXT`。

### 4.2 固定步骤
1. 建立概念模型：业务对象、术语定义、关系、生命周期和所有者。
2. 建立数据分类：主数据、事务数据、配置/字典、快照、派生数据、附件、审计、集成暂存、读模型。
3. 建立逻辑模型：实体、属性、标识、基数、可空性、唯一性、有效期与历史策略。
4. 将业务不变量映射为“应用校验 / 数据库约束 / 两者”，不得只写自然语言。
5. 建立物理表簇：表、列、类型、PK/FK/UQ/CK、索引、分区候选、审计列、租户列。
6. 以真实查询清单设计索引；每个索引关联查询/排序/唯一性理由，不凭字段直觉全量加索引。
7. 设计写入所有权和跨边界引用：本边界可写表、外部只读 ID、快照字段、同步方式。
8. 设计迁移、种子字典、归档、删除与恢复策略。
9. 用样例数据演练创建、修改、状态转换、并发更新、删除、历史查询和越权访问。

### 4.3 主数据、快照和历史默认规则
- 主数据记录当前权威事实；引用方不应通过复制字段反向修改主数据。
- 需要保持“交易发生时事实”的字段使用业务快照，并记录来源 ID、来源版本/同步时间。
- 快照不是无控制冗余；必须说明为何不能只读当前主数据。
- 状态历史使用显式转换记录：from、to、trigger、actor、time、reason、workflow instance。
- `created_at/updated_at` 不能代替业务审计；软删除也不能代替归档和法定保留策略。
- 金额明确币种、精度和舍入；时间明确时区语义；枚举关联已确认字典 ID。

### 4.4 多租户/多单位决策门
- 先定义 tenant 是法人、集团成员企业、部门还是客户，禁止从组织树直接猜测。
- 对每类数据确定：共享、按 tenant 行隔离、按 schema 隔离、按库隔离或混合。
- 行隔离时，唯一约束和常用索引通常需包含 `tenant_id`；跨租户汇总需单独授权路径。
- 数据库 RLS 可作为纵深防御，但不能替代应用鉴权和自动化越权测试（F4）。
- 选择结果记录隔离等级、成本、迁移路径、备份恢复粒度和监管依据。

### 4.5 数据阶段必交付物
- `conceptual-data-model.md`、`logical-data-model.md`、`physical-data-model.md`。
- ER 图与数据字典；表簇按业务边界分组。
- 约束矩阵、索引依据表、数据所有权/权威源矩阵。
- 主数据/快照/历史/审计策略，多租户隔离决策记录。
- DDL 草案和迁移计划；DDL 是设计产物之一，不取代前三类模型。
- `RULE -> ENTITY -> TABLE/COLUMN/CONSTRAINT` 追踪矩阵及未决问题。

### 4.6 数据阶段退出门
- 每个业务对象有且仅有明确写入所有者。
- 每个关键不变量有执行位置；不能全部依赖前端校验。
- 每个状态转换能落到数据结构和并发策略。
- 外键缺失、复制字段、软删除、JSON 大字段和无约束字典均有书面理由。
- 关键查询有样例 SQL/伪查询与索引依据；用执行计划验证属于实现阶段任务。

## 五、`design-backend-architecture` 应采用的研究结论
### 5.1 后端设计先定义契约，再映射代码目录
每个用例先产出：用例 ID、触发者、权限、输入、前置条件、规则、事务边界、状态变化、输出、错误、审计、事件/集成副作用。  
然后才决定 controller/application/domain/service/repository 的具体落点，防止目录结构替代业务设计。

### 5.2 API 契约最低字段
- `operationId` 绑定用例 ID；资源路径与命令型端点命名理由。
- 方法、认证、角色与数据范围；请求 schema、必填/可空/枚举/格式。
- 成功响应、已知错误、RFC 9457 `type` 与稳定业务错误码。
- 分页、排序、过滤、字段选择；时间、金额、精度、时区。
- 并发控制（版本号或 ETag/If-Match）；幂等策略；速率/超时约束。
- 弃用、兼容性和版本策略；示例不得成为唯一契约。

### 5.3 事务与一致性决策表
| 场景 | 默认选择 | 升级条件 |
|---|---|---|
| 同一数据库、同一业务原子操作 | 本地 ACID 事务 | 锁竞争或长事务需拆分流程 |
| 同模块多实体且必须共同成功 | 同一本地事务 | 先验证是否真为一个不变量 |
| 跨模块但同库 | 优先同步应用编排或本地事务 | 独立部署/所有权要求再事件化 |
| 跨服务/跨库通知 | Outbox + 至少一次投递 + 幂等消费 | 严格顺序需分区键/序列策略 |
| 跨服务长流程 | 状态机/Saga + 补偿与人工兜底 | 不可补偿步骤需明确 pivot |
| 报表/搜索 | 直接只读查询或 CQRS 投影 | 负载、模型、权限矛盾达到阈值 |

### 5.4 幂等不是一句“支持重试”
- 为非天然幂等命令定义幂等键作用域：tenant + operation + caller + key。
- 存储请求指纹、处理状态、结果摘要、创建/过期时间；同键不同请求应冲突。
- 消费者按 event/message ID 去重；业务副作用也必须可重复执行或检测已执行。
- 明确幂等记录保留期和重放窗口；不能无限保存也不能早于上游重试期删除。
- HTTP 方法幂等只描述预期效果，不代替数据库唯一约束和业务去重（F9）。

### 5.5 Outbox 最低设计
- 与业务写入同一事务保存：event_id、aggregate/business key、type、version、payload、occurred_at。
- 发布状态、尝试次数、next_retry_at、published_at、trace/correlation ID。
- 发布器支持并发领取、失败退避、死信/人工处理；消费者必须幂等。
- 定义同一业务键顺序要求、事件 schema 兼容规则、清理/归档和监控指标。
- 不允许“先提交数据库再直接发消息”却声称原子可靠。

### 5.6 外部集成与对账合同
每个 OA、财务、工商信息或移动端集成必须定义：

- 权威源、业务键/外部键、方向、触发方式、频率与最大可接受延迟。
- 全量/增量边界、水位线、时区、删除语义、重跑和重复输入规则。
- 字段映射、码表转换、版本兼容、数据质量校验与隔离区。
- 请求/批次 ID、发送数、接受数、拒绝数、缺失数、金额/数量控制总计。
- 差异明细、自动重试、人工修复、回放、关闭责任人和 SLA。
- 页面显示 `source_system`、`source_updated_at`、`sync_status`，避免把批量数据伪装成实时。

### 5.7 架构中立映射
| 设计概念 | 三层架构 | DDD 四层 | 模块化单体 | 微服务 |
|---|---|---|---|---|
| 用例编排 | service 方法 | application service | 模块 application | 服务 application |
| 核心规则 | service 内受控规则对象/方法 | domain entity/service | 模块 domain 或 service | 服务内部 domain/service |
| 持久化端口 | DAO/repository 接口 | domain repository 接口 | 模块私有 repository | 服务私有 repository |
| 查询模型 | mapper/query service | query application | 模块 query API | 服务 API/投影 |
| 边界调用 | service facade | application port | 模块公开接口 | HTTP/message contract |
| 事务 | service transaction | unit of work | 模块内本地事务 | 本地事务 + 协调机制 |

R：映射允许目录不同，但不允许写入所有权、规则、权限和事务语义消失。  
R：从模块化单体拆成微服务应主要改变边界通信和部署，不应重新发明业务词汇与规则。

### 5.8 后端阶段必交付物
- 用例/命令/查询目录与事务矩阵。
- OpenAPI 契约、错误目录、权限与数据范围映射。
- 模块依赖图、写入所有权、同步/异步交互和事件目录。
- 选定架构的概念到代码映射、包/项目骨架和依赖规则。
- 幂等、并发、Outbox、对账、可观测性和失败处理设计。
- `USE_CASE -> API -> SERVICE -> TABLE/EVENT -> ERROR/AUDIT` 追踪矩阵。

### 5.9 后端阶段退出门
- API 能完整驱动已确认场景和状态转换，不存在“页面直接改状态字段”。
- 每个写用例有事务、并发、权限、审计和失败语义。
- 跨边界写入通过公开契约，禁止任意跨模块 DAO/跨服务写库。
- 所有异步链路能回答：丢失、重复、乱序、毒消息、积压、回放怎么办。
- 架构选择有非功能依据；不得因使用 DDD 建模就自动选择微服务或四层代码。

## 六、研究识别出的反模式
- 一个页面/表单对应一张表；一个聚合对应一个微服务；一个实体对应一个 CRUD API。
- 先按现有表生成代码，再反向把字段解释成业务规则。
- 把外部主数据复制到业务表却不记录来源、版本或业务快照理由。
- 仅有 `status` 字段，没有允许转换、触发者、审批态和历史。
- 把 `tenant_id` 加到表就宣称多租户安全，却没有唯一约束、查询过滤与越权测试。
- 为“以后可能查询”给所有列加索引，或只因外键存在就假设查询已经高效。
- 为了“先进”默认 CQRS、事件溯源、微服务或 Saga。
- OpenAPI 只写 200 示例，没有已知错误、权限、并发和幂等语义。
- 同步失败只记录日志，没有批次、水位、差异、重跑和业务责任人。

## 七、对科研管理系统的示例性推断（非已确认需求）
- “科研项目”当前主档与“申报/立项时单位名称、负责人、预算口径”可能同时存在：前者是主数据引用，后者是否做快照需由业务审计要求确认。
- 财务每日同步宜进入集成批次/明细暂存并对账，再更新项目经费读模型；页面标注截至时间，不宣称实时。
- 项目业务状态与 OA 审批状态宜分离；API 暴露“提交、通过、退回、终止”等命令，不开放任意修改状态。
- 集团下属企业是否构成 tenant 尚不能从组织字段推断，必须先确定隔离、集团汇总与管理员跨单位权限。

## 八、供最终技能采用的摘要
- 数据阶段固定执行概念 → 逻辑 → 物理模型，并保留规则到约束的追踪。
- DDD 负责发现语义和一致性边界；代码架构按现状映射，三层与四层都是可选实现。
- 表设计必须覆盖所有权、历史、快照、审计、多租户、约束、索引、迁移与查询。
- 后端契约先于目录；每个写用例必须有权限、事务、并发、幂等、错误和审计语义。
- CQRS、Outbox、Saga 和微服务均由具体矛盾触发，不是默认成熟度标志。
- 外部集成完成的标准不是“接口调用成功”，而是可追踪、可重跑、可对账、可关闭差异。
- 两阶段之间用稳定 ID 连接；下游发现上游规则缺失时返回 `REVISE_UPSTREAM`，不得静默补造。
