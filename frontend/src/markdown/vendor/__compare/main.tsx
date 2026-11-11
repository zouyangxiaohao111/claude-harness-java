/**
 * Markdown 渲染对照页 · V1（本仓自研）vs V2（源站流水线移植）。
 *
 * <h2>怎么用</h2>
 * 1. `cd front && npx vite`（或复用已在跑的 dev 服务）
 * 2. 打开 `http://localhost:3000/md-compare.html`
 * 3. 左右两栏同时渲染同一份 markdown，直接肉眼比
 *
 * <h2>⭐ 三个开关</h2>
 * <ul>
 *   <li><b>流式</b>：切到「流式中途」把文本截断到 60%，这是最能看出差别的地方 ——
 *       V1 会裸露 `**` / `|` / 未闭合代码围栏，V2 不会</li>
 *   <li><b>样例</b>：切换不同内容类型</li>
 *   <li><b>拖进度条</b>：模拟流式逐字增长，观察两栏的稳定性</li>
 * </ul>
 *
 * ⚠️ 这是**开发对照工具**，不参与产品构建（不在 `src/main.tsx` 的路由里）。
 */
import { StrictMode, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'

import { MarkdownText } from '../../MarkdownText'
import { MarkdownTextV2 } from '../renderer'

/** 样例集：覆盖常见的模型输出形态。 */
const SAMPLES: Array<{ id: string; label: string; text: string }> = [
  {
    id: 'mixed',
    label: '① 综合（标题/表格/列表/公式/代码/引用）',
    text: `## 政策背景

根据《AI赋能建筑行业智能化场景落地规划建设方案》，政策背景包含两个层面：

| 层级 | 文件 | 要点 |
|---|---|---|
| 国务院 | 人工智能+行动意见 | 赋能市政基础设施、工程建设 |
| 住建部 | 数字住建整体布局规划 | 场景牵引、数据赋能、安全可控 |

### 关键指标

- 2027 年智能体应用普及率 **超过 70%**
- 2030 年提升至 **90%**，建成垂直大模型产业生态

行内公式 $E = mc^2$ 与块级公式：

\\[a^2 + b^2 = c^2\\]

\`\`\`java
public class Demo {
    public static void main(String[] args) {
        System.out.println("hello");
    }
}
\`\`\`

> 注意：文中多处语句重复，不宜直接引用作为规范表述。`,
  },
  {
    id: 'table',
    label: '② 宽表格（看横向滚动容器）',
    text: `| 指标名称 | 2021 | 2022 | 2023 | 2024 | 2025 | 说明 |
|---|---|---|---|---|---|---|
| 污水处理率 | 68% | 72% | 76% | 81% | 85% | 县城及以上 |
| 达标排放率 | 71% | 75% | 79% | 83% | 88% | 按一级A |
| 在线监测覆盖 | 55% | 62% | 70% | 78% | 85% | 日处理≥500吨 |`,
  },
  {
    id: 'cjk',
    label: '③ 中文夹粗体/斜体（看是否断词）',
    text: `这是**重要结论**需要注意，还有*强调文字*混在中文里。

错误写法：**加粗**紧贴中文、*斜体*也是。正确应能正常加粗不断词。

再加一组：**结论：** 数据表明**达标率达到88%**，同比**提升5个百分点**。`,
  },
  {
    id: 'code',
    label: '④ 长代码块（看高亮与滚动）',
    text: `\`\`\`typescript
interface Config {
  name: string
  values: number[]
}

export function resolve(cfg: Config): number {
  // 求和并返回
  return cfg.values.reduce((a, b) => a + b, 0)
}
\`\`\`

行内代码 \`npm install\` 也要正常。`,
  },
  {
    id: 'list',
    label: '⑤ 多层列表 + 任务列表',
    text: `1. 第一层
   - 第二层 A
   - 第二层 B
     1. 第三层
2. 另一个第一层

- [x] 已完成项
- [ ] 未完成项`,
  },
  {
    id: 'stream-cut',
    label: '⑥ 未闭合语法（最能看出差别）',
    text: `这是**加粗还没写完

| 表格 | 第二列 |
| --- |

\`\`\`java
int a = 1;
`,
  },
]

/** 根组件。 */
function App() {
  const [sampleId, setSampleId] = useState(SAMPLES[0].id)
  const [streaming, setStreaming] = useState(false)
  const [percent, setPercent] = useState(100)

  const sample = SAMPLES.find((s) => s.id === sampleId) ?? SAMPLES[0]
  /** 按百分比截断，模拟流式中途的样子。 */
  const text = useMemo(() => {
    if (percent >= 100) return sample.text
    return sample.text.slice(0, Math.max(1, Math.floor((sample.text.length * percent) / 100)))
  }, [sample.text, percent])

  const cellStyle: React.CSSProperties = {
    flex: 1,
    minWidth: 0,
    border: '1px solid #ddd',
    borderRadius: 6,
    padding: 12,
    background: '#fff',
    overflow: 'auto',
  }

  return (
    <div style={{ fontFamily: 'system-ui, sans-serif', padding: 16, background: '#f5f6f8', minHeight: '100vh' }}>
      <h1 style={{ fontSize: 18, margin: '0 0 12px' }}>Markdown 渲染对照 · V1 vs V2</h1>

      {/* 控制区 */}
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', marginBottom: 12 }}>
        <select
          value={sampleId}
          onChange={(e) => setSampleId(e.target.value)}
          style={{ padding: '6px 10px', fontSize: 13 }}
        >
          {SAMPLES.map((s) => (
            <option key={s.id} value={s.id}>{s.label}</option>
          ))}
        </select>

        <label style={{ fontSize: 13, display: 'flex', alignItems: 'center', gap: 6 }}>
          <input type="checkbox" checked={streaming} onChange={(e) => setStreaming(e.target.checked)} />
          流式模式（streaming=true）
        </label>

        <label style={{ fontSize: 13, display: 'flex', alignItems: 'center', gap: 6, flex: 1, minWidth: 240 }}>
          模拟流式进度 {percent}%
          <input
            type="range"
            min={5}
            max={100}
            value={percent}
            onChange={(e) => setPercent(Number(e.target.value))}
            style={{ flex: 1 }}
          />
        </label>
      </div>

      {streaming && (
        <p style={{ fontSize: 12, color: '#a15c00', background: '#fff7e6', border: '1px solid #ffd591', padding: '6px 10px', borderRadius: 4 }}>
          ⭐ <b>流式模式是重点</b>：V1 在流式期<b>纯文本直出</b>（不解析，所以会看到 <code>**</code>、<code>|</code> 这类原始符号）；
          V2 照常解析，靠守卫函数兜住未闭合语法。把进度条拖到 40%–80% 最能看出差别。
        </p>
      )}

      {/* 两栏对照 */}
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
        <div style={cellStyle}>
          <div style={{ fontSize: 12, fontWeight: 600, color: '#666', marginBottom: 8 }}>
            V1 · 本仓自研（MarkdownText）
          </div>
          <div style={{ borderTop: '1px dashed #eee', paddingTop: 10 }}>
            <MarkdownText text={text} streaming={streaming} />
          </div>
        </div>

        <div style={cellStyle}>
          <div style={{ fontSize: 12, fontWeight: 600, color: '#096' , marginBottom: 8 }}>
            V2 · 源站流水线（MarkdownTextV2）
          </div>
          <div style={{ borderTop: '1px dashed #eee', paddingTop: 10 }}>
            <MarkdownTextV2 text={text} streaming={streaming} />
          </div>
        </div>
      </div>

      {/* 原始文本（便于对照哪里没渲染对） */}
      <details style={{ marginTop: 12 }}>
        <summary style={{ fontSize: 13, cursor: 'pointer' }}>查看当前输入的原始 Markdown（{text.length} 字符）</summary>
        <pre style={{ fontSize: 12, background: '#fff', padding: 10, borderRadius: 4, overflow: 'auto', maxHeight: 300 }}>
          {text}
        </pre>
      </details>
    </div>
  )
}

const root = document.getElementById('root')
if (root) {
  createRoot(root).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}
