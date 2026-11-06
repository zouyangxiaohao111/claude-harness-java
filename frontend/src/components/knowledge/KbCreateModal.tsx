import { useState } from 'react'

export interface KbCreateSubmit {
  name: string
  description: string
  type: 'document' | 'faq'
  embeddingModelId: string | null
  chunkSize: number
  chunkOverlap: number
}

interface Props {
  showToast: (msg: string, type?: 'success' | 'info') => void
  onClose: () => void
  onCreate: (payload: KbCreateSubmit) => Promise<void>
  /** 已存在的知识库（编辑态）*/
  initial?: { name: string; description: string; type: 'document' | 'faq' } | null
}

/**
 * 「新建知识库」配置弹窗 · 逐项对齐源侧真机
 * （`SettingsModalShell.vue` + `KnowledgeBaseEditorModal.vue`）。
 *
 * <h2>⭐ 为什么是「左侧分组导航 + 右侧分节」而不是一个输入框</h2>
 * 我原来只有一个输入框 —— 但源侧点「新建知识库」打开的是 1080×780 的
 * 配置弹窗，左边 208px 分三组共 11 项导航，右边按「基本信息/模型配置/
 * 向量存储/…」分节滚动。用户明确要求「完全按照这种拔下来」，所以按真机重做。
 *
 * <h2>真机实测尺寸（⛔ 不要凭感觉改）</h2>
 * <pre>
 * 弹窗      1080 × 780，圆角 12px，居中
 * 侧栏      208px，背景 #f9f9f9，右边框 1px #e7e7e7
 * 侧栏标题  padding 16px 14px 12px + 底部 1px 分割线，16px/600
 * 分组标题  12px/600，color rgba(0,0,0,.4)，padding 8px 14px 2px
 * 导航项    padding 6px 12px，圆角 6px，14px；激活 #f3f3f3 背景 + 绿字 #07c05f
 * 右侧内容  padding 24px 32px
 * 节标题    20px/600，描述 14px rgba(0,0,0,.4)，标题下 6px
 * 表单项    下间距 16px；label 15px/500，下 8px
 * 输入框    高 32px，边框 1px #dcdcdc，圆角 3px，内文 14px
 * 页脚      高 57px，右对齐，上边框 1px #e7e7e7，padding 12px 40px
 * 主按钮    32px 高，绿底，圆角 3px，padding 0 15px
 * </pre>
 *
 * <h2>⚠️ 与源侧的差异（本仓后端不具备的，如实收窄而非画空壳）</h2>
 * <ul>
 *   <li>「向量存储 / 解析引擎 / 图像处理 / 音频处理 / 知识图谱 / 高级设置 / 存储引擎」
 *       这些分组本仓有独立配置页（知识库设置里），**创建时不暴露** ——
 *       画了也没后端字段可写，属于假开关。只保留真正写进 `CreateKnowledgeBaseRequest` 的四节。</li>
 *   <li>知识库类型只有 `文档` / `问答`（后端 `type` 枚举），源侧的 `Wiki` 是本仓的索引策略。</li>
 *   <li>「Wiki 索引」勾选在本仓对应 `type=wiki`，但创建接口暂不支持 ⇒ 灰置 + 提示。</li>
 * </ul>
 */
export function KbCreateModal({ showToast, onClose, onCreate, initial }: Props) {
  const [tab, setTab] = useState<'basic' | 'model' | 'index' | 'advanced'>('basic')
  const [name, setName] = useState(initial?.name ?? '')
  const [description, setDescription] = useState(initial?.description ?? '')
  const [type, setType] = useState<'document' | 'faq'>(initial?.type ?? 'document')
  const [rag, setRag] = useState(true)
  const [wiki, setWiki] = useState(false)
  const [chunkSize, setChunkSize] = useState(512)
  const [chunkOverlap, setChunkOverlap] = useState(80)
  const [submitting, setSubmitting] = useState(false)

  const submit = async () => {
    if (!name.trim()) {
      showToast('请填写知识库名称', 'info')
      return
    }
    setSubmitting(true)
    try {
      await onCreate({
        name: name.trim(),
        description: description.trim(),
        type,
        embeddingModelId: null,
        chunkSize,
        chunkOverlap,
      })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="kb-create-overlay" role="dialog" aria-modal="true" aria-label="新建知识库">
      <div className="kb-create-modal">
        <button type="button" className="kb-create-close" onClick={onClose} aria-label="关闭">✕</button>

        <aside className="kb-create-nav">
          <div className="kb-create-nav-head">
            <h2 className="kb-create-nav-title">{initial ? '编辑知识库' : '新建知识库'}</h2>
          </div>
          <nav className="kb-create-nav-list">
            <div className="kb-create-nav-group">基础</div>
            <div className={`kb-create-nav-item${tab === 'basic' ? ' active' : ''}`} onClick={() => setTab('basic')}>
              基本信息
            </div>
            <div className={`kb-create-nav-item${tab === 'model' ? ' active' : ''}`} onClick={() => setTab('model')}>
              模型配置
            </div>

            <div className="kb-create-nav-group">索引与解析</div>
            <div className={`kb-create-nav-item${tab === 'index' ? ' active' : ''}`} onClick={() => setTab('index')}>
              分块设置
            </div>

            <div className="kb-create-nav-group">存储与数据</div>
            <div className={`kb-create-nav-item${tab === 'advanced' ? ' active' : ''}`} onClick={() => setTab('advanced')}>
              高级设置
            </div>
          </nav>
        </aside>

        <div className="kb-create-content">
          <div className="kb-create-body">
            {tab === 'basic' && (
              <section className="kb-create-sec">
                <div className="kb-create-sec-head">
                  <h3 className="kb-create-sec-title">基本信息</h3>
                  <p className="kb-create-sec-desc">设置知识库的名称和描述信息</p>
                </div>
                <div className="kb-create-sec-body">
                  <div className="kb-create-form-item">
                    <label className="kb-create-label required">知识库类型</label>
                    <div className="kb-create-radio-group">
                      <label className={`kb-create-radio${type === 'document' ? ' is-checked' : ''}`}>
                        <input type="radio" name="kbtype" checked={type === 'document'} onChange={() => setType('document')} />
                        <span>文档</span>
                      </label>
                      <label className={`kb-create-radio${type === 'faq' ? ' is-checked' : ''}`}>
                        <input type="radio" name="kbtype" checked={type === 'faq'} onChange={() => setType('faq')} />
                        <span>问答</span>
                      </label>
                    </div>
                    <p className="kb-create-tip">
                      FAQ 类型适合结构化问答数据；文档型支持文件解析与分块。
                    </p>
                  </div>

                  <div className="kb-create-form-item">
                    <label className="kb-create-label required">索引策略</label>
                    <p className="kb-create-tip">配置文档上传后的处理管道，可以独立开启或关闭每种索引方式</p>
                    <div className="kb-create-checks">
                      <div className={`kb-create-check${rag ? ' is-checked' : ''}`}>
                        <label className="kb-create-check-row">
                          <input type="checkbox" checked={rag} onChange={(e) => setRag(e.target.checked)} />
                          <span className="kb-create-check-label">RAG 检索</span>
                        </label>
                        <p className="kb-create-check-desc">对文档进行分块、向量化和关键词索引，支持混合检索</p>
                      </div>
                      <div className={`kb-create-check${wiki ? ' is-checked' : ''} is-disabled`} title="本仓创建接口暂不支持 Wiki 索引">
                        <label className="kb-create-check-row">
                          <input type="checkbox" checked={wiki} disabled onChange={(e) => setWiki(e.target.checked)} />
                          <span className="kb-create-check-label">
                            知识页面
                            <span className="kb-create-new">NEW</span>
                          </span>
                        </label>
                        <p className="kb-create-check-desc">自动生成互相关联的 Wiki 知识页面，构建结构化知识体系（创建后可在「Wiki」页开启）</p>
                      </div>
                    </div>
                  </div>

                  <div className="kb-create-form-item">
                    <label className="kb-create-label required">知识库名称</label>
                    <input
                      className="kb-create-input"
                      placeholder="请输入知识库名称"
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                      autoFocus
                      onKeyDown={(e) => { if (e.key === 'Enter') void submit() }}
                    />
                  </div>

                  <div className="kb-create-form-item">
                    <label className="kb-create-label">知识库描述</label>
                    <div className="kb-create-textarea-wrap">
                      <textarea
                        className="kb-create-textarea"
                        placeholder="请输入知识库描述（可选）"
                        value={description}
                        maxLength={200}
                        onChange={(e) => setDescription(e.target.value)}
                      />
                      <span className="kb-create-limit">{description.length}/200</span>
                    </div>
                  </div>
                </div>
              </section>
            )}

            {tab === 'model' && (
              <section className="kb-create-sec">
                <div className="kb-create-sec-head">
                  <h3 className="kb-create-sec-title">模型配置</h3>
                  <p className="kb-create-sec-desc">选择该知识库使用的向量化与摘要模型（不选则用全局默认）</p>
                </div>
                <div className="kb-create-sec-body">
                  <div className="kb-create-form-item">
                    <label className="kb-create-label">Embedding 模型</label>
                    <select className="kb-create-input" defaultValue="">
                      <option value="">跟随全局默认（自动选用第一个启用的 embedding 模型）</option>
                    </select>
                    <p className="kb-create-tip">留空时由「设置 → 知识库配置」统一决定。</p>
                  </div>
                  <div className="kb-create-form-item">
                    <label className="kb-create-label">摘要模型</label>
                    <select className="kb-create-input" defaultValue="">
                      <option value="">跟随全局默认</option>
                    </select>
                  </div>
                </div>
              </section>
            )}

            {tab === 'index' && (
              <section className="kb-create-sec">
                <div className="kb-create-sec-head">
                  <h3 className="kb-create-sec-title">分块设置</h3>
                  <p className="kb-create-sec-desc">控制文档切分粒度，影响检索召回精度</p>
                </div>
                <div className="kb-create-sec-body">
                  <div className="kb-create-form-item">
                    <label className="kb-create-label">分块大小</label>
                    <input
                      className="kb-create-input"
                      type="number"
                      min={100}
                      max={7500}
                      value={chunkSize}
                      onChange={(e) => setChunkSize(Number(e.target.value) || 512)}
                    />
                    <p className="kb-create-tip">默认 512，范围 100–7500。</p>
                  </div>
                  <div className="kb-create-form-item">
                    <label className="kb-create-label">重叠长度</label>
                    <input
                      className="kb-create-input"
                      type="number"
                      min={0}
                      max={500}
                      value={chunkOverlap}
                      onChange={(e) => setChunkOverlap(Number(e.target.value) || 80)}
                    />
                    <p className="kb-create-tip">默认 80，重叠越大上下文越连续。</p>
                  </div>
                </div>
              </section>
            )}

            {tab === 'advanced' && (
              <section className="kb-create-sec">
                <div className="kb-create-sec-head">
                  <h3 className="kb-create-sec-title">高级设置</h3>
                  <p className="kb-create-sec-desc">创建后可随时在知识库设置里调整</p>
                </div>
                <div className="kb-create-sec-body">
                  <p className="kb-create-tip">
                    向量存储、解析引擎、图像/音频处理、知识图谱、存储引擎等配置，
                    创建后可在知识库右上角 ⚙ 打开完整设置页调整。
                  </p>
                </div>
              </section>
            )}
          </div>

          <div className="kb-create-footer">
            <button type="button" className="kb-create-btn" onClick={onClose}>取消</button>
            <button type="button" className="kb-create-btn primary" disabled={submitting} onClick={() => void submit()}>
              {submitting ? '创建中…' : (initial ? '保存' : '创建知识库')}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
