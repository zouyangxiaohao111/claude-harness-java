import { useState } from 'react'
import type { ChatMessageDto } from '@/api/types'

interface Props {
  messages: ChatMessageDto[]
  onConfirm: (messageId: string, direction: 'from' | 'up_to') => void
  onClose: () => void
}

export function MessageSelector({ messages, onConfirm, onClose }: Props) {
  const [selected, setSelected] = useState<string | null>(null)
  const [direction, setDirection] = useState<'from' | 'up_to'>('from')
  return (
    <div className="message-selector">
      <div className="ms-head">选择压缩切点</div>
      <div className="ms-list">
        {messages.map((m) => (
          <div key={m.id} className={selected === m.id ? 'ms-item active' : 'ms-item'}
            onClick={() => setSelected(m.id)}>
            {m.role === 'user' ? '你' : 'nexus'}: {(m.content ?? '').slice(0, 60)}
          </div>
        ))}
      </div>
      <div className="ms-dir">
        {/* F11：前端 build 非 ant（summarize_up_to 为 ant 专属选项），默认隐藏 up_to，仅保留 from */}
        <label><input type="radio" checked={direction === 'from'} onChange={() => setDirection('from')} /> 保留之后（from）</label>
      </div>
      <div className="ms-actions">
        <button onClick={onClose}>取消</button>
        <button className="primary" disabled={!selected} onClick={() => selected && onConfirm(selected, direction)}>压缩</button>
      </div>
    </div>
  )
}
