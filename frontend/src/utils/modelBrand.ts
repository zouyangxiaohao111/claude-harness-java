/**
 * 模型品牌 → 短别名 / 图标（右侧模型胶囊用）。
 *
 * <p>胶囊收窄到 ≤120px 后不能再放完整模型名，故用两字母别名（用户指定口径：
 * deepseek→DS、claude→CL、openai→OA；未知取前两位大写）。下拉菜单仍展示完整模型名。
 *
 * <p>⚠️ 匹配按**模型串**（可能含 provider 前缀，如 `ds-openai/deepseek-v4-flash`），
 * 规则顺序有意义：deepseek 必须排在 openai 之前，否则会被前缀误判。
 */

export interface ModelBrand {
  /** 品牌标识（= `/provider-icons/<brand>.svg` 的文件名；未知为 `_fallback`） */
  brand: string
  /** 胶囊上的两字母别名 */
  alias: string
  /** 图标 URL（本地打包资源，⛔ 不引外链） */
  icon: string
  /** 给人看的供应商名（tooltip 用） */
  provider: string
}

interface BrandRule {
  match: RegExp
  brand: string
  alias: string
  provider: string
}

/** 规则表（顺序 = 优先级；deepseek 先于 openai 见文件头说明） */
const RULES: BrandRule[] = [
  { match: /deepseek/i, brand: 'deepseek', alias: 'DS', provider: 'DeepSeek' },
  { match: /claude|anthropic/i, brand: 'claude', alias: 'CL', provider: 'Anthropic' },
  { match: /openai|gpt|o1|o3|o4/i, brand: 'openai', alias: 'OA', provider: 'OpenAI' },
  { match: /gemini|google/i, brand: 'gemini', alias: 'GM', provider: 'Google' },
  { match: /qwen|tongyi|dashscope/i, brand: 'qwen', alias: 'QW', provider: '通义千问' },
  { match: /kimi|moonshot/i, brand: 'kimi', alias: 'KM', provider: 'Moonshot' },
  { match: /mistral/i, brand: 'mistral', alias: 'MI', provider: 'Mistral' },
  { match: /xai|grok/i, brand: 'xai', alias: 'XA', provider: 'xAI' },
  { match: /zhipu|glm/i, brand: 'zhipu', alias: 'ZP', provider: '智谱' },
  { match: /doubao|volc/i, brand: 'doubao', alias: 'DB', provider: '豆包' },
  { match: /minimax/i, brand: 'minimax', alias: 'MM', provider: 'MiniMax' },
  { match: /groq/i, brand: 'groq', alias: 'GQ', provider: 'Groq' },
  { match: /ollama/i, brand: 'ollama', alias: 'OL', provider: 'Ollama' },
]

/**
 * 解析品牌：命中规则用规则别名；未知取**模型名末段的头两位大写**（无则 `??`）。
 *
 * @param model 完整模型名（如 `ds-openai/deepseek-v4-flash`）
 */
export function resolveModelBrand(model: string | null | undefined): ModelBrand {
  const raw = (model ?? '').trim()
  const hit = RULES.find((r) => r.match.test(raw))
  if (hit) {
    return { brand: hit.brand, alias: hit.alias, icon: `/provider-icons/${hit.brand}.svg`, provider: hit.provider }
  }
  const seg = (raw.split('/').pop() ?? '').trim()
  const alias = (seg.slice(0, 2) || '??').toUpperCase()
  const provider = raw.includes('/') ? raw.split('/')[0] : '未知供应商'
  return { brand: '_fallback', alias, icon: '/provider-icons/_fallback.svg', provider }
}

/**
 * 推理档位标签（胶囊右侧小标签）· **没有档位返回 null（不渲染）**。
 *
 * <p>`ultracode` 是「xhigh + 编排」的组合开关，按最高档展示为 `max`；
 * `off` = 关闭思考 ⇒ 视为无档位。
 */
export function effortTag(effort: string | null | undefined, ultracode?: boolean | null): string | null {
  if (ultracode) return 'max'
  if (!effort || effort === 'off') return null
  return effort
}
