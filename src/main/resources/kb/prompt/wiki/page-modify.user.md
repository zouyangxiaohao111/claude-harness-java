<!-- 源侧 internal/agent/prompts_wiki.go:345 WikiPageModifyUserPrompt -->
<shared_source_contexts>
{{SHARED_SOURCE_CONTEXTS}}
</shared_source_contexts>

<page_metadata>
slug: {{PAGE_SLUG}}
title: {{PAGE_TITLE}}
type: {{PAGE_TYPE}}
aliases: {{PAGE_ALIASES}}
</page_metadata>

<existing_page_content>
{{EXISTING_CONTENT}}
</existing_page_content>

<new_information>
{{NEW_INFORMATION}}
</new_information>

{{DELETION_BLOCK}}
<valid_wiki_links>
{{VALID_WIKI_LINKS}}
</valid_wiki_links>

<instructions>
**第一步：先做冲突检查。**
核实 <new_information> 是否**真的**在讲「{{PAGE_TITLE}}」。
如果新信息明显属于**另一个**相关事物
（例如页面是「混元模型」但新信息在讲「Qwen3」；
或页面是「居民身份证」但新信息在讲「工作居住证」），
⇒ **必须拒绝这部分新信息，不要把它加进页面。**
只有当新信息确实在讲本页面主题时，才继续。

**第二步：合并。**
把新信息中被支撑的事实整理进页面。遵守系统提示词的全部规则：
不编造、不推断、不润色、不写 chunk 编号、只做轻度重排。

输出首行 `SUMMARY: {...}`，然后是完整页面正文（不是增量补丁）。
</instructions>
