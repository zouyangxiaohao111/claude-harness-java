# Superpowers 编排模式研究（企业软件设计技能链适配）

## 目录

- [官方工作流的原始事实](#一官方工作流的原始事实)
- [可迁移到企业软件设计技能链的推断](#二可迁移到企业软件设计技能链的推断)
- [建议继承的强制门](#三建议继承的强制门)
- [不应机械照搬的部分](#四superpowers-中不应机械照搬的部分)
- [研究结论摘要](#五面向后续技能设计的研究结论摘要)

研究日期：2026-07-21  
研究范围：仅限官方 `obra/superpowers` 仓库的 README，以及 `using-superpowers`、`brainstorming`、`writing-plans`、`executing-plans`、`subagent-driven-development`、`requesting-code-review`、`receiving-code-review`、`verification-before-completion`。  
用途：为“从建设方案到可实施设计”的技能链提供编排研究依据；本文不定义最终技能。

## 一、官方工作流的原始事实

以下各项均为对官方仓库的事实归纳，不代表本项目已经采用。

### F1. Superpowers 是“入口约束 + 可组合技能”，不是一个包办全部工作的巨型技能

官方 README 将其定义为由可组合技能和一组确保技能被使用的初始指令组成的完整开发方法；基本流程由 brainstorming、writing-plans、执行、测试、评审、收尾等技能串接而成，而不是由一个技能同时完成所有阶段。  
来源：[README — How it works / Basic Workflow](https://github.com/obra/superpowers/blob/main/README.md)

### F2. 总入口先路由“过程技能”，再进入领域或实现技能

`using-superpowers` 要求在响应、澄清、探索文件之前先判断技能是否适用，并规定多技能同时适用时，过程技能优先于实现技能。它还明确：如果当前代理已经是被派发来完成具体任务的子代理，则忽略该入口技能，避免递归路由。  
来源：[using-superpowers/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/using-superpowers/SKILL.md)

### F3. 设计批准是实现前的硬门

`brainstorming` 禁止在设计被用户批准前写代码、搭脚手架或调用实现技能；流程依次包含：了解项目上下文、逐步澄清、提出 2–3 个方案、分段展示设计并确认、写设计文档、自审、用户复核，然后才进入 `writing-plans`。  
来源：[brainstorming/SKILL.md — Checklist / Process Flow](https://github.com/obra/superpowers/blob/main/skills/brainstorming/SKILL.md)

### F4. 大范围需求先拆分为可独立交付的子项目

`brainstorming` 要求在深入问题前先判断范围；若请求包含多个相对独立的子系统，应先拆分，并让每个子项目分别完成 spec → plan → implementation 循环。`writing-plans` 也要求多子系统规格拆成多个能独立产生可测试软件的计划。  
来源：[brainstorming/SKILL.md — Understanding the idea](https://github.com/obra/superpowers/blob/main/skills/brainstorming/SKILL.md)、[writing-plans/SKILL.md — Scope Check](https://github.com/obra/superpowers/blob/main/skills/writing-plans/SKILL.md)

### F5. 阶段交接依靠持久化产物，而不是仅依赖对话记忆

`brainstorming` 将批准后的设计保存为规格文档；`writing-plans` 将实现计划保存为计划文档。计划必须包含目标、架构、技术栈、全局约束、准确文件路径、前后任务接口、测试步骤和预期结果。  
来源：[brainstorming/SKILL.md — After the Design](https://github.com/obra/superpowers/blob/main/skills/brainstorming/SKILL.md)、[writing-plans/SKILL.md — Plan Document Header / Task Structure](https://github.com/obra/superpowers/blob/main/skills/writing-plans/SKILL.md)

### F6. 交接产物禁止占位符和模糊动作

`writing-plans` 明确禁止 `TBD`、`TODO`、“适当处理错误”、“为上述内容编写测试”、未定义的类型或函数等；计划完成后还需进行规格覆盖、占位符、类型一致性自审。  
来源：[writing-plans/SKILL.md — No Placeholders / Self-Review](https://github.com/obra/superpowers/blob/main/skills/writing-plans/SKILL.md)

### F7. 执行前必须重新批判性审查计划

`executing-plans` 的第一步不是直接执行，而是加载并批判性审查计划；若存在关键疑问或缺口，应先向用户提出。`subagent-driven-development` 也要求在任务 1 前扫描任务间冲突和全局约束冲突，并将发现合并为一次决策请求。  
来源：[executing-plans/SKILL.md — Load and Review Plan](https://github.com/obra/superpowers/blob/main/skills/executing-plans/SKILL.md)、[subagent-driven-development/SKILL.md — Pre-Flight Plan Review](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md)

### F8. 只有相对独立的任务才适合按子代理并行或隔离执行

`subagent-driven-development` 的使用判断包含“是否已有计划”和“任务是否大多独立”；紧耦合任务应手工执行或回到设计。每个任务使用新鲜、有限上下文的实现代理，避免把整个会话历史复制进去。  
来源：[subagent-driven-development/SKILL.md — When to Use / Constructing Reviewer Prompts](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md)

### F9. 每个执行单元后都有“符合规格 + 质量”门，末尾还有全局评审

`subagent-driven-development` 的任务循环是：实现、自测、自审 → 任务级评审 → 修复 Critical/Important → 复审 → 标记完成；全部任务完成后再进行整个分支的宽范围评审。  
来源：[subagent-driven-development/SKILL.md — The Process](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md)

### F10. 评审必须拿到明确的“应当是什么”与“实际变化”，并保持只读

`requesting-code-review` 要求评审上下文包含实现说明、计划/需求、起止版本范围；评审模板要求检查计划一致性、代码质量、架构、测试、生产就绪度，并按 Critical、Important、Minor 分级，给出明确可合并结论。评审 checkout 被要求保持只读。  
来源：[requesting-code-review/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/requesting-code-review/SKILL.md)、[code-reviewer.md](https://github.com/obra/superpowers/blob/main/skills/requesting-code-review/code-reviewer.md)

### F11. 评审意见不能盲从，必须先验证其在当前项目中的正确性

`receiving-code-review` 的顺序是读完、理解、针对代码库验证、评价技术正确性、回应、逐项实现并测试。意见不清时先停止；外部意见若与现有兼容性、用户决策或真实调用冲突，应提出有证据的异议。  
来源：[receiving-code-review/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/receiving-code-review/SKILL.md)

### F12. “完成”必须有刚刚取得的证据

`verification-before-completion` 要求在任何完成/成功声明前，识别能证明声明的命令，完整运行，阅读输出与退出码，再根据证据陈述状态。它特别区分了“测试通过”与“需求逐项满足”：测试通过不能替代需求覆盖核对。  
来源：[verification-before-completion/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/verification-before-completion/SKILL.md)

### F13. 阻塞状态是一等输出，不允许猜测穿透

`executing-plans` 遇到缺依赖、测试反复失败、指令不清或关键计划缺口时要求停止并询问；`subagent-driven-development` 区分 DONE、DONE_WITH_CONCERNS、NEEDS_CONTEXT、BLOCKED，并针对上下文不足、推理能力不足、任务过大、计划错误采取不同动作。  
来源：[executing-plans/SKILL.md — When to Stop and Ask for Help](https://github.com/obra/superpowers/blob/main/skills/executing-plans/SKILL.md)、[subagent-driven-development/SKILL.md — Handling Implementer Status](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md)

## 二、可迁移到企业软件设计技能链的推断

以下内容是基于上述官方事实的适配推断，不是 Superpowers 官方原文或官方承诺。

### I1. 使用“薄总入口 + 阶段技能”比巨型万能技能更合适

推断：`using-software-design-chain` 应主要完成输入识别、阶段定位、门禁判断、下一技能路由和状态记录；需求、场景、领域、数据、后端、前端、测试、一致性审查应由独立技能承担。这样能复用 F1/F2 的组合式方法，并避免一个技能同时携带全部方法论导致触发不准和上下文膨胀。  
依据：[README](https://github.com/obra/superpowers/blob/main/README.md)、[using-superpowers/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/using-superpowers/SKILL.md)

### I2. 企业设计链需要“产物准入门”，而非仅有顺序说明

推断：每一阶段都应定义最低输入、必备输出、未知项、冲突项、验证方式和进入下一阶段的条件。例如，不能只因“需求文档存在”就进入数据建模；应先确认业务场景、状态、权限和关键规则达到相应准入标准。该推断扩展了 F3/F5/F7。  
依据：[brainstorming/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/brainstorming/SKILL.md)、[executing-plans/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/executing-plans/SKILL.md)

### I3. 每个阶段应有稳定、可追踪的交接契约

推断：阶段产物需要统一 ID 和映射，而不只是自然语言章节。例如需求 ID → 场景/规则/状态/权限 → 领域能力/边界 → 实体/表 → API/用例 → 页面/操作 → 测试/验收。此举把 F5/F6/F10 的“明确规格和计划”扩展为跨产物追踪链。  
依据：[writing-plans/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/writing-plans/SKILL.md)、[code-reviewer.md](https://github.com/obra/superpowers/blob/main/skills/requesting-code-review/code-reviewer.md)

### I4. 阶段内可以迭代，阶段间默认单向推进但允许显式回退

推断：总链不是瀑布式“一次冻结”。若数据模型暴露规则缺失，应带着具体冲突回退到场景/规则阶段；若前端暴露用例缺失，应回退到后端契约或需求阶段，并更新受影响产物。回退必须记录原因和影响范围，不能静默修改上游。该模式来自 F3 中的设计复核循环和 F7 的执行前重审。  
依据：[brainstorming/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/brainstorming/SKILL.md)、[subagent-driven-development/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md)

### I5. “跨产物一致性审查”既要逐阶段做，也要在全链末尾做

推断：每个阶段完成时，先审查它与直接上游是否一致；全部设计完成后，再进行一次全链宽范围审查。这对应 F9 的任务级门和最终全局评审。只在最后审查会让错误级联，只做局部审查又会漏掉跨三层以上的断链。  
依据：[subagent-driven-development/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md)

### I6. 评审者应使用“最小充分上下文”，但跨产物审查者要拿到追踪矩阵

推断：领域边界评审不必读取全部前端细节，表设计评审不必继承整个对话；应提供当前产物、直接上游、绑定约束和待判定事项。全链审查则应读取追踪矩阵及各产物的已确认版本，而不是对话摘要。该推断迁移 F8/F10 的上下文隔离原则。  
依据：[requesting-code-review/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/requesting-code-review/SKILL.md)、[subagent-driven-development/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md)

### I7. 设计建议也要像评审意见一样验证，不能因“DDD 最佳实践”而自动采纳

推断：DDD 可用于识别语言、边界、不变量、聚合与一致性要求；实现映射必须检查当前代码结构、团队能力和技术约束。若现有三层架构把领域行为放入 service，只要边界、规则和事务语义可追踪，就不应强制改为四层目录。这个推断直接应用 F11 的“针对当前代码库验证”。  
依据：[receiving-code-review/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/receiving-code-review/SKILL.md)

### I8. 完成声明要分别证明“文档完整、跨产物一致、可实现、可验收”

推断：设计链的验证不能只有 Markdown 文件存在或 schema 校验通过。至少要提供：必需章节/字段检查、未决事项检查、ID 引用完整性、名称/状态/权限/字段/API/页面/测试映射检查，以及必要的人工决策记录。该推断扩展 F12 对“证据与声明匹配”的要求。  
依据：[verification-before-completion/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/verification-before-completion/SKILL.md)

### I9. 并行只用于无直接产物依赖的研究或审查，不用于主链相邻阶段

推断：需求精炼 → 场景规则 → 领域边界 → 数据/后端/前端的主链存在强依赖，不应因为有子代理就全部并行。可以并行的通常是同一阶段中互不影响的模块研究、独立来源核验、或在稳定接口下的数据与前端细化。总入口需要显式判断共享写入、输入依赖和决策耦合。  
依据：[subagent-driven-development/SKILL.md — When to Use](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md)

### I10. 状态值应成为跨技能协议

推断：各阶段至少需要统一表达 `READY`、`READY_WITH_ASSUMPTIONS`、`NEEDS_CONTEXT`、`BLOCKED`、`REVISE_UPSTREAM`，并携带证据、缺口和下一动作。这样可迁移 F13 的结构化状态，而不是让下游从长篇文字中猜测能否继续。  
依据：[subagent-driven-development/SKILL.md — Handling Implementer Status](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md)

## 三、建议继承的强制门

以下为适配建议（推断），来源见每项末尾。

1. **入口门**：先判断当前任务属于哪个阶段，是否已有可用且已确认的上游产物；被总入口派发的阶段技能不得重新进入总入口。依据 F2：[using-superpowers](https://github.com/obra/superpowers/blob/main/skills/using-superpowers/SKILL.md)。
2. **范围门**：发现多个独立业务域时，先定义系统全景和切分，再选择首个纵向切片，避免一次生成整套细节。依据 F4：[brainstorming](https://github.com/obra/superpowers/blob/main/skills/brainstorming/SKILL.md)。
3. **事实/假设门**：所有未被资料或用户确认的业务规则必须标注为假设、问题或建议，不得写成既定事实。依据 F7/F11：[executing-plans](https://github.com/obra/superpowers/blob/main/skills/executing-plans/SKILL.md)、[receiving-code-review](https://github.com/obra/superpowers/blob/main/skills/receiving-code-review/SKILL.md)。
4. **用户决策门**：涉及范围、关键业务语义、不可逆数据策略、权限边界、核心架构路线的分歧，必须形成候选方案和推荐理由，等待用户或授权责任人确认。依据 F3：[brainstorming](https://github.com/obra/superpowers/blob/main/skills/brainstorming/SKILL.md)。
5. **阶段交接门**：下游开始前，当前产物必须通过必需项、占位符、冲突、歧义、追踪关系检查。依据 F6：[writing-plans](https://github.com/obra/superpowers/blob/main/skills/writing-plans/SKILL.md)。
6. **变更回退门**：下游发现上游根本错误时停止扩散，生成影响分析并回退；不得在下游静默重定义上游术语或规则。依据 F7/F13：[executing-plans](https://github.com/obra/superpowers/blob/main/skills/executing-plans/SKILL.md)、[subagent-driven-development](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md)。
7. **评审门**：每个阶段至少审查“符合上游”与“本阶段质量”，Critical/Important 未闭环不得继续；计划要求本身有问题时升级给用户裁决。依据 F9/F10：[subagent-driven-development](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md)、[code-reviewer](https://github.com/obra/superpowers/blob/main/skills/requesting-code-review/code-reviewer.md)。
8. **完成证据门**：只有在新鲜验证证据覆盖声明范围后，才可称阶段或全链完成。依据 F12：[verification-before-completion](https://github.com/obra/superpowers/blob/main/skills/verification-before-completion/SKILL.md)。

## 四、Superpowers 中不应机械照搬的部分

以下均为适配判断（推断），不是对官方技能正确性的否定；Superpowers 的目标主要是代码实现工作流，本项目目标是企业软件设计产物链。

### N1. 不照搬“任何项目都必须完整 brainstorming”到每个阶段

官方规则适用于创意/实现前设计；若用户已经提供经过确认的上游产物，阶段技能应先验证其准入条件，而不是重复从零头脑风暴。只有出现关键歧义或新设计决策时才启动相应澄清循环。  
对照来源：[brainstorming/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/brainstorming/SKILL.md)

### N2. 不照搬“brainstorming 后唯一允许 writing-plans”的二阶段模型

企业系统需要多个设计阶段，需求规格之后不能直接跳到代码计划；应将“唯一下一步”替换为由总入口根据产物状态路由到场景建模、领域边界、数据、后端、前端、测试或一致性审查。  
对照来源：[brainstorming/SKILL.md — terminal state](https://github.com/obra/superpowers/blob/main/skills/brainstorming/SKILL.md)

### N3. 不把 2–5 分钟、完整代码、频繁提交作为设计技能输出标准

这些是 `writing-plans` 的实现计划粒度。需求和架构阶段的合理单元应是可评审的业务决策或产物片段，例如一个业务场景、一组状态转换、一个限界上下文、一个表簇或一个页面任务；它们的验收证据也不一定是测试命令。  
对照来源：[writing-plans/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/writing-plans/SKILL.md)

### N4. 不把 TDD、worktree、commit 当作所有设计阶段的强制条件

这些对代码实现很有价值，但需求、建模和页面设计更适合使用追踪检查、schema 校验、示例场景走查、决策记录和人工批准。代码实现阶段仍可调用 Superpowers 已有实现技能。  
对照来源：[README — Basic Workflow](https://github.com/obra/superpowers/blob/main/README.md)

### N5. 不把“一个问题一条消息”绝对化到大型企业文档澄清

逐问有助于降低认知负担，但几十个文档冲突逐条往返会阻塞项目。可将相互独立、同一责任人的问题组织为小批量决策包；会改变后续问题路径的关键决策仍应单独询问。  
对照来源：[brainstorming/SKILL.md — Ask clarifying questions](https://github.com/obra/superpowers/blob/main/skills/brainstorming/SKILL.md)

### N6. 不默认所有阶段都使用新子代理

领域、数据、后端和前端设计之间高度耦合。若没有稳定交接契约，新鲜上下文会丢失关键语义；此时应由总入口持有追踪台账，阶段代理只接收最小充分的已确认产物。无子代理环境也必须能顺序执行。  
对照来源：[subagent-driven-development/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md)

### N7. 不把 DDD 战术实现结构当作设计结论的唯一落地

Superpowers 本身强调设计边界和遵循现有代码模式，并未要求 DDD 四层架构。企业设计链可用 DDD 辅助发现边界和不变量，但物理代码结构必须映射到现有三层、四层、模块化单体或其他选定架构，并记录概念到实现的映射。  
相关来源：[brainstorming/SKILL.md — Design for isolation / Working in existing codebases](https://github.com/obra/superpowers/blob/main/skills/brainstorming/SKILL.md)、[writing-plans/SKILL.md — File Structure](https://github.com/obra/superpowers/blob/main/skills/writing-plans/SKILL.md)

### N8. 不把“验证”缩减为命令执行

设计产物的部分结论无法由单一命令证明。应保持“声明必须匹配证据”的原则，但证据可包括机器检查、来源引用、追踪矩阵、场景演练、评审记录和用户确认。  
对照来源：[verification-before-completion/SKILL.md](https://github.com/obra/superpowers/blob/main/skills/verification-before-completion/SKILL.md)

## 五、面向后续技能设计的研究结论摘要

以下为推断性结论，供下一阶段设计使用：

- 总入口应是强制路由器和状态机，而不是第九个业务设计器。
- 主链应以已版本化产物及其准入条件推进，不以“对话看起来已经讨论过”推进。
- 每个阶段都需要：明确输入、工作步骤、固定输出、未知/冲突表示、质量检查、完成状态和下一跳。
- DDD 应作为领域设计方法与边界语言，不能直接规定最终代码目录和分层。
- 相邻阶段默认串行；同阶段独立模块可并行；跨阶段并行必须由稳定契约解耦。
- 局部评审防止错误级联，最终全链审查发现跨产物断链，两者缺一不可。
- Superpowers 现有的代码计划、执行、评审和验证技能可以在“后端契约与代码架构”之后继续复用；前面的企业设计链不应复制一套代码执行机制。

共同依据：[README](https://github.com/obra/superpowers/blob/main/README.md)、[using-superpowers](https://github.com/obra/superpowers/blob/main/skills/using-superpowers/SKILL.md)、[brainstorming](https://github.com/obra/superpowers/blob/main/skills/brainstorming/SKILL.md)、[writing-plans](https://github.com/obra/superpowers/blob/main/skills/writing-plans/SKILL.md)、[subagent-driven-development](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/SKILL.md)、[verification-before-completion](https://github.com/obra/superpowers/blob/main/skills/verification-before-completion/SKILL.md)。
