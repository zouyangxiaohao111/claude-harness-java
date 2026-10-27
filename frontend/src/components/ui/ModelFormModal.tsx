/**
 * ModelFormModal — edit a single ModelInfo entry.
 * Used by ProvidersPanel for the nested models list.
 *
 * Layout: sections are filtered by the selected model type —
 * chat-like (chat/text/vision/multimodal/…) shows 生成参数；
 * embedding shows 向量嵌入；rerank shows 重排序。
 * topP: number | null is reconciled in `handleSave` so an empty input maps to null
 * (provider default).
 */
import { FormModal } from './FormModal'
import type { Model, ModelType } from '@/api/types'
import { tagToClass } from '@/data'

interface ModelFormModalProps {
  initial: Model
  /** The provider's display name (e.g. "OpenAI") for the locked-tag chip */
  providerName?: string
  onSave: (m: Model) => void
  onCancel: () => void
  onDelete?: () => void
}

// Note: the model `tag` (e.g. 'DS' / 'CL' / 'GP' / 'QW') used to be user-editable
// here via a <select>. It is now LOCKED to the parent provider's tag — see the
// `.fm-locked-tag` chip in the 基础 section. The historical DS/CL/GP/QW option
// list is intentionally NOT kept here to avoid drift; if you need to migrate a
// model between providers, do that at the provider level.

const TYPE_OPTIONS: { value: ModelType; label: string }[] = [
  { value: 'chat', label: 'Chat · 文本对话（默认）' },
  { value: 'text', label: 'Text · 文本补全' },
  { value: 'vision', label: 'Vision · 图像理解' },
  { value: 'multimodal', label: 'Multimodal · 多模态' },
  { value: 'image_generation', label: 'Image Generation · 图像生成' },
  { value: 'embedding', label: 'Embedding · 向量嵌入' },
  { value: 'audio', label: 'Audio · 语音识别/合成' },
  { value: 'rerank', label: 'Rerank · 重排序' },
  { value: 'moderation', label: 'Moderation · 审核' },
]

export function ModelFormModal({ initial, providerName, onSave, onCancel, onDelete }: ModelFormModalProps) {
  // 三套配置页：embedding / rerank 各一套，其余（chat/text/vision/multimodal/…）共用生成参数。
  const isEmbedding = (v: Record<string, any>) => v.type === 'embedding'
  const isRerank = (v: Record<string, any>) => v.type === 'rerank'
  const isChatLike = (v: Record<string, any>) => !isEmbedding(v) && !isRerank(v)

  const handleSave = (m: Model) => {
    const emb = m.type === 'embedding'
    const rr = m.type === 'rerank'
    // think 保持原语义（trim）；extraBody 独立通道：trim 空串即「无额外参数」
    onSave({
      ...m,
      think: (m.think ?? '').trim(),
      extraBody: (m.extraBody ?? '').trim(),
      // 类型切换后清掉不属于当前类型的专用参数，避免「页面上看不到却仍落库」
      embeddingDimensions: emb ? m.embeddingDimensions : null,
      embeddingBatchSize: emb ? m.embeddingBatchSize : null,
      rerankTopN: rr ? m.rerankTopN : null,
      rerankInstruct: rr ? m.rerankInstruct : null,
    })
  }

  return (
    <FormModal<Model>
      title="编辑模型"
      subtitle={initial.alias || initial.name || '新模型'}
      initial={initial}
      sections={[
        {
          title: '基础',
          rows: [
            [
              { type: 'text', name: 'name', label: '模型名称', placeholder: 'e.g. deepseek-v3.2' },
              { type: 'text', name: 'alias', label: '别名', placeholder: 'e.g. DS-V3', hint: '显示在选择器中' },
            ],
            [
              {
                type: 'locked',
                label: '提供商标识',
                render: () => (
                  <div className="fm-locked-tag" title="tag 由父级 provider 决定，不能在此切换">
                    <span className={`model-tag ${tagToClass(initial.tag)}`}>{initial.tag}</span>
                    <span className="locked-tag-name">{providerName ?? initial.tag}</span>
                    <span className="locked-tag-hint">· 通过提供商添加</span>
                  </div>
                ),
              },
            ],
          ],
        },
        {
          title: '类型',
          fields: [
            { type: 'select', name: 'type', label: '模型类型', options: TYPE_OPTIONS, hint: '决定模型支持的能力（文本/图像/多模态/嵌入…）' },
          ],
        },
        {
          title: '生成参数',
          visibleWhen: isChatLike,
          rows: [
            [
              { type: 'number', name: 'maxTokens', label: 'max tokens', nullable: true, placeholder: '384000', hint: '单次最大输出 token 数 · 留空=384000' },
              { type: 'number', name: 'maxContextTokens', label: '上下文窗口', nullable: true, placeholder: '1048576', hint: '模型上下文窗口 · 1M=1048576 · 留空=1M' },
            ],
            [
              { type: 'number', name: 'temperature', label: 'temperature', nullable: true, placeholder: '1.0', hint: '0-2 越高越随机 · 留空=1.0' },
              { type: 'number', name: 'topP', label: 'top-p', nullable: true, hint: '核采样 · 留空 = 用 provider 默认' },
            ],
          ],
        },
        {
          // [embedding-provider 2026-10-26] 向量嵌入专用配置（仅 type=embedding 生效）。
          //   维度/批大小落在 models.embedding_dimensions / embedding_batch_size（V200）。
          title: '向量嵌入（仅 Embedding 类型生效）',
          visibleWhen: isEmbedding,
          rows: [
            [
              { type: 'number', name: 'embeddingDimensions', label: '向量维度', nullable: true, placeholder: '1024', hint: '百炼 text-embedding-v4：2048/1536/1024/768/512/256/128/64 · 留空=服务端默认' },
              { type: 'number', name: 'embeddingBatchSize', label: '批大小', nullable: true, placeholder: '10', hint: '单次请求最多几条文本 · 百炼上限 10 · 留空=10' },
            ],
          ],
        },
        {
          // [embedding-provider 2026-10-26] 重排专用配置（仅 type=rerank 生效）。
          //   topN / instruct 落在 models.rerank_top_n / rerank_instruct（V200）；
          //   请求体显式传值优先于这里的默认值。
          title: '重排序（仅 Rerank 类型生效）',
          visibleWhen: isRerank,
          fields: [
            { type: 'number', name: 'rerankTopN', label: '默认 topN', nullable: true, placeholder: '10', hint: '请求未传 topN 时使用 · 超过候选数会自动收敛 · 留空=不发送（服务端默认）' },
            { type: 'text', name: 'rerankInstruct', label: '默认指令 instruct', placeholder: 'Given a web search query, retrieve relevant passages that answer the query.', hint: 'qwen3-rerank 支持 · 留空=不发送（可被请求覆盖）' },
          ],
        },
        {
          // [2026-10-22] 模型级「额外请求体」：后端逐键合并进请求体（只增/覆盖同名键，不替换整包）。
          //   think 字段保持原样（不动），本字段是独立通道。
          title: '高级 · 额外请求体（extraBody）',
          fields: [
            {
              type: 'json',
              name: 'extraBody',
              label: 'extraBody · 额外请求体 JSON',
              placeholder: '{"reasoning": true}',
              hint: '按 key 合并进请求体 · 例：DeepSeek {"reasoning":true} · 智谱 {"thinking":{"type":"enabled"}} · 自定义参数直接写 key',
              rows: 3,
            },
          ],
        },
        {
          title: '行为',
          fields: [
            { type: 'toggle', name: 'enabled', label: '启用', hint: '关闭后该模型不出现在选择器中' },
          ],
        },
      ]}
      onSave={handleSave}
      onCancel={onCancel}
      destructiveLabel={onDelete ? '移除此模型 · 不可恢复' : undefined}
      onDestructive={onDelete}
    />
  )
}
