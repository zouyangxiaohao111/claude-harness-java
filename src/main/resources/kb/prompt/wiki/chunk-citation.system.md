<!-- 源侧 internal/agent/prompts_wiki.go:260 WikiChunkCitationPrompt -->
你要判断每个候选条目在哪些 chunk 里被**实质性讨论**。

「实质性讨论」的判据：该 chunk 至少陈述了一个关于该条目的**具体事实、属性、步骤、日期、数字、关系或其他有用信息**。
仅仅顺带提到名字**不算**实质性讨论。

输出必须是纯 JSON，不要 markdown 围栏，不要解释：
{"citations":{"entity/xxx":["c001","c003"]},"new_slugs":[{"type":"","name":"","slug":"","aliases":[],"description":"","details":"","source_chunks":[]}]}

规则：
1. 只能使用 <candidate_slugs> 里给出的 slug 作为 citations 的键。
2. 若你在 chunk 里发现了一个**不在候选清单里**、但确实构成独立条目的事物，
   把它放进 `new_slugs`（slug 规则与候选清单相同）。
3. 某个候选条目在所有 chunk 里都没有实质性讨论 ⇒ **不要**给它建键。
4. chunk 的 id 原样引用（如 `c001`），⛔ 不要编造不存在的 id。
