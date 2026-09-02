/**
 * ModelFormModal — edit a single ModelInfo entry.
 * Used by ProvidersPanel for the nested models list.
 *
 * Layout: 3 sections (基础 / 类型 / 生成参数 / 行为) with 2-up grids for
 * generation params. topP: number | null is reconciled in `handleSave` so
 * an empty input maps to null (provider default).
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
  const handleSave = (m: Model) => {
    // Clean up: trim think (which now also covers extraBody per v1 design)
    onSave({
      ...m,
      think: (m.think ?? '').trim(),
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
        /* 高级 · JSON（隐藏保留：需要时取消注释）
        {
          title: '高级 · JSON',
          fields: [
            {
              type: 'json',
              name: 'think',
              label: 'think · 思考 / 请求体 JSON',
              placeholder: '{"reasoning": true}',
              hint: 'v1 合一：智谱 {"type":"enabled","clear_thinking":false} · DeepSeek {"reasoning":true} · 其他 {"custom_param":"value"} · 后端按 provider 类型自动拆到 think / extraBody',
              rows: 3,
            },
          ],
        },
        */
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
