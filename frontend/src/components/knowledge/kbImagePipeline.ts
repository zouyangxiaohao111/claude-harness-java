/**
 * 知识库「图片处理」三项配置（V286）· 纯函数收口。
 *
 * <p>对应后端 `kb_knowledge_base` 的**三个顶层列**：
 * `image_caption_enabled` / `image_ocr_enabled` / `image_pipeline`，
 * 读侧由 `KnowledgeBaseDto` 回传、写侧由 `PATCH /knowledge-bases/{id}` 接收
 * （值域校验在 `KnowledgeBaseService`：`image_pipeline` 只认 `default` / `smartocr`，
 * 其它值直接 400）。
 *
 * <h2>⚠️⚠️ 与 `image_processing_config.image_pipeline` **同名不同义**（⛔ 别混）</h2>
 * <ul>
 *   <li><b>本模块管的 `image_pipeline`</b>：知识库**顶层列**，取值只有
 *       `default` / `smartocr`，后端 `KbImageSettings.from(record)` 读它并真正切换
 *       图片管线（`KbImageEnrichService`：smartocr = 先一次「观察」再只对含文字的图跑 OCR）。</li>
 *   <li><b>`image_processing_config.image_pipeline`（旧字段）</b>：是
 *       `kb_knowledge_base.image_processing_config` 这段 JSON 里的一个键，取值
 *       `""` / `"attrs"`，后端**零消费者**（只被设置弹窗自己的下拉读写）。
 *       它<b>不是</b>本模块的输入，也<b>不要</b>拿它回填这三个控件 ——
 *       读旧键会拿到 `""` / `"attrs"`，与本模块的两个合法值对不上，
 *       会把用户选的「智能」静默显示成「默认」。</li>
 * </ul>
 *
 * <p>本模块只做「读回填 / 组装 PATCH 体 / 灰字判据」，不碰 React，便于真值表测试。
 */

/** `image_pipeline` 的两个合法取值（后端 `KbImageSettings.PIPELINE_*`）。 */
export type KbImagePipelineName = 'default' | 'smartocr'

/** 三项配置的界面态（缺失 / null 一律按后端默认：两个开关开、管线 default）。 */
export interface KbImagePipelineState {
  captionEnabled: boolean
  ocrEnabled: boolean
  pipeline: KbImagePipelineName
  /** smartocr 的兜底：属性没观察到时是否仍做 OCR（源站 `ocr_on_unobserved`，默认 true）。 */
  ocrOnUnobserved: boolean
}

/** 回填所需的 DTO 形状（只取这几项，便于测试构造）。 */
export interface KbImagePipelineSource {
  image_caption_enabled?: boolean | null
  image_ocr_enabled?: boolean | null
  image_pipeline?: string | null
  /** `image_processing_config` 原始 JSON 字符串（`ocr_on_unobserved` 存在这里面）。 */
  image_processing_config?: string | null
}

/** 两个开关都关时的灰字说明（要求逐字一致，测试也断言这一条常量）。 */
export const IMAGE_PIPELINE_OFF_HINT = '关闭后不再调用视觉模型，图片只保留原图'

/**
 * 规范化 `image_pipeline`：只认 `smartocr`（大小写/首尾空白宽容），其余一律回落
 * `default`。与后端 `KbImageSettings.normalizePipeline` 同口径
 * （fail-soft：脏值不该让整条图片链异常）。
 */
export function normalizeKbImagePipeline(raw: unknown): KbImagePipelineName {
  if (typeof raw !== 'string') return 'default'
  return raw.trim().toLowerCase() === 'smartocr' ? 'smartocr' : 'default'
}

/**
 * 从知识库 DTO / 表单值读回三项配置。
 *
 * <p>⭐ 开关判据是 `!== false`（不是 `=== true`）：后端列 NOT NULL DEFAULT 1，
 * DTO 出站恒为 boolean；但老缓存对象可能缺字段，缺字段 = 未配置 = 后端默认开。
 */
export function readKbImagePipeline(src: KbImagePipelineSource | null | undefined): KbImagePipelineState {
  return {
    captionEnabled: src?.image_caption_enabled !== false,
    ocrEnabled: src?.image_ocr_enabled !== false,
    pipeline: normalizeKbImagePipeline(src?.image_pipeline),
    ocrOnUnobserved: readOcrOnUnobserved(parseImageProcessingConfig(src?.image_processing_config)),
  }
}

/** 源站槽位键：`image_pipeline_params["smartocr.ocr_on_unobserved"]`。 */
export const SLOT_KEY_OCR_ON_UNOBSERVED = 'smartocr.ocr_on_unobserved'

/** 解析 `image_processing_config`（坏 JSON / 非对象 ⇒ null，调用方按默认处理）。 */
function parseImageProcessingConfig(raw: unknown): Record<string, any> | null {
  if (typeof raw !== 'string' || !raw.trim()) return null
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null
  } catch {
    return null
  }
}

/**
 * 读 `ocr_on_unobserved`：源站槽位键 → 扁平键 → 默认 true。
 *
 * <p>⭐ 与后端 `KbImageSettings.ocrOnUnobservedOf` 同口径：只有**显式 false** 才关，
 * 其它（缺键 / 非布尔 / 坏 JSON）一律 true —— 读错成 false 会让图片静默不做 OCR。
 */
function readOcrOnUnobserved(cfg: Record<string, any> | null): boolean {
  const params = cfg?.image_pipeline_params
  if (params && typeof params === 'object' && typeof params[SLOT_KEY_OCR_ON_UNOBSERVED] === 'boolean') {
    return params[SLOT_KEY_OCR_ON_UNOBSERVED]
  }
  if (typeof cfg?.ocr_on_unobserved === 'boolean') return cfg.ocr_on_unobserved
  return true
}

/**
 * 组装 PATCH 请求体：只带调用方明确给到的字段（PATCH 语义 = 不传不改）。
 *
 * <p>⚠️ `image_pipeline` 只发两个合法值：传入其它字符串（例如旧字段的 `attrs`）
 * 时**整个键不发送**，⛔ 不把脏值丢给后端换一个 400。
 */
export function buildKbImagePipelinePatch(next: {
  captionEnabled?: boolean
  ocrEnabled?: boolean
  pipeline?: string
  /** 改了才发；发的时候必须带上原 JSON（做合并，见下）。 */
  ocrOnUnobserved?: boolean
  /** 当前整份 `image_processing_config` 原始字符串（用于合并写回）。 */
  imageProcessingConfig?: string | null
}): Record<string, unknown> {
  const body: Record<string, unknown> = {}
  if (typeof next.captionEnabled === 'boolean') body.image_caption_enabled = next.captionEnabled
  if (typeof next.ocrEnabled === 'boolean') body.image_ocr_enabled = next.ocrEnabled
  if (next.pipeline === 'default' || next.pipeline === 'smartocr') {
    body.image_pipeline = next.pipeline
  }
  if (typeof next.ocrOnUnobserved === 'boolean') {
    // ⚠️ 后端 `setJsonField` 是**整块替换** ⇒ 必须基于原 JSON 合并，
    //    否则会把同一串里的 enable_multimodal / model_id 一起冲掉。
    const cfg: Record<string, any> = { ...(parseImageProcessingConfig(next.imageProcessingConfig) ?? {}) }
    const params: Record<string, any> =
      cfg.image_pipeline_params && typeof cfg.image_pipeline_params === 'object'
        ? { ...cfg.image_pipeline_params }
        : {}
    params[SLOT_KEY_OCR_ON_UNOBSERVED] = next.ocrOnUnobserved
    cfg.image_pipeline_params = params
    body.image_processing_config = JSON.stringify(cfg)
  }
  return body
}

/** smartocr 才需要这个兜底开关（default 管线不观察、无从谈「没观察到」）。 */
export function ocrOnUnobservedVisible(pipeline: KbImagePipelineName): boolean {
  return pipeline === 'smartocr'
}

/** 是否显示「两个开关都关」的灰字说明。 */
export function imagePipelineOffHintVisible(captionEnabled: boolean, ocrEnabled: boolean): boolean {
  return !captionEnabled && !ocrEnabled
}
