import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  IMAGE_PIPELINE_OFF_HINT,
  buildKbImagePipelinePatch,
  imagePipelineOffHintVisible,
  normalizeKbImagePipeline,
  ocrOnUnobservedVisible,
  readKbImagePipeline,
} from '../kbImagePipeline'
import type { CreateKnowledgeBaseBody, KnowledgeBase, UpdateKnowledgeBaseBody } from '@/api/knowledge'

/**
 * 知识库设置 · 「图片处理」三项（V286）闭环。
 *
 * <h2>守什么</h2>
 * <ol>
 *   <li><b>写</b>：PATCH 请求体必须带 `image_caption_enabled` / `image_ocr_enabled` /
 *       `image_pipeline`，且 `image_pipeline` 只出现两个合法值（其它值**不发**）；</li>
 *   <li><b>读</b>：保存后重新打开（DTO 回传）必须回填新值，⛔ 不能读死默认 / 旧键
 *       `image_processing_config.image_pipeline`（`""`/`"attrs"`，同名不同义）；</li>
 *   <li><b>灰字</b>：两个开关都关时出现「关闭后不再调用视觉模型，图片只保留原图」。</li>
 * </ol>
 *
 * <p>⚠️ `image_pipeline` 的同名旧键是**另一份配置**（后端零消费者），本组测试
 * 反向断言：回填函数**不读** `image_processing_config`。
 */

const MODAL = fileURLToPath(new URL('../KbSettingsModal.tsx', import.meta.url))

function src(): string {
  return readFileSync(MODAL, 'utf8').replace(/\r\n?/g, '\n')
}

/** 模拟后端 DTO 的出厂值（V286 三列 NOT NULL DEFAULT：1 / 1 / 'default'）。 */
function defaultDto(): KnowledgeBase {
  return {
    id: 'kb-1',
    name: '测试库',
    description: null,
    type: 'document',
    is_temporary: false,
    chunking_config: '{}',
    image_processing_config: '{"enable_multimodal": true, "image_pipeline": "attrs"}',
    embedding_model_id: null,
    summary_model_id: null,
    extract_config: null,
    vector_store_id: null,
    storage_backend_id: null,
    created_at: null,
    updated_at: null,
    image_caption_enabled: true,
    image_ocr_enabled: true,
    image_pipeline: 'default',
  }
}

/** 保存时「请求体 → 落库 → GET 回传」的最小 wire 模拟（JSON 往返，字段名逐字）。 */
function applyPatchAndReload(dto: KnowledgeBase, body: Record<string, unknown>): KnowledgeBase {
  return { ...dto, ...(JSON.parse(JSON.stringify(body)) as Partial<KnowledgeBase>) }
}

describe('图片处理（V286）· 保存请求体', () => {
  it('⭐⭐ 三个字段都能进 PATCH 体（带全量）', () => {
    expect(buildKbImagePipelinePatch({
      captionEnabled: false,
      ocrEnabled: true,
      pipeline: 'smartocr',
    })).toEqual({
      image_caption_enabled: false,
      image_ocr_enabled: true,
      image_pipeline: 'smartocr',
    })
  })

  it('⭐ 单个控件只发改动的那一列（PATCH 语义，不顺手覆盖其它两项）', () => {
    expect(buildKbImagePipelinePatch({ ocrEnabled: false }))
      .toEqual({ image_ocr_enabled: false })
    expect(buildKbImagePipelinePatch({ pipeline: 'smartocr' }))
      .toEqual({ image_pipeline: 'smartocr' })
  })

  it('⭐⭐ pipeline 只发 default / smartocr：其它值（含旧键的 attrs）整个键不发', () => {
    expect(buildKbImagePipelinePatch({ pipeline: 'attrs' })).toEqual({})
    expect(buildKbImagePipelinePatch({ pipeline: '' })).toEqual({})
    expect(buildKbImagePipelinePatch({ pipeline: 'AUTO' })).toEqual({})
    expect(buildKbImagePipelinePatch({ pipeline: 'default' }))
      .toEqual({ image_pipeline: 'default' })
  })

  it('创建请求体同样受类型约束（三字段可选，pipeline 只两个值）', () => {
    const create: CreateKnowledgeBaseBody = {
      name: 'x',
      image_caption_enabled: false,
      image_ocr_enabled: false,
      image_pipeline: 'smartocr',
    }
    const update: UpdateKnowledgeBaseBody = { image_pipeline: 'default' }
    expect(create.image_pipeline).toBe('smartocr')
    expect(update.image_pipeline).toBe('default')
  })
})

describe('图片处理（V286）· 保存 → 重新打开 回填闭环', () => {
  it('⭐⭐⭐ 关两个开关 + 选智能 ⇒ 重新打开读到 false / false / smartocr', () => {
    const saved = applyPatchAndReload(
      defaultDto(),
      buildKbImagePipelinePatch({ captionEnabled: false, ocrEnabled: false, pipeline: 'smartocr' })
    )
    expect(readKbImagePipeline(saved)).toEqual({
      captionEnabled: false,
      ocrEnabled: false,
      pipeline: 'smartocr',
      ocrOnUnobserved: true,
    })
  })

  it('⭐⭐ 缺字段的旧对象按后端默认回填（开 / 开 / default / 兜底开），⛔ 不是 undefined 猜值', () => {
    expect(readKbImagePipeline({})).toEqual({
      captionEnabled: true,
      ocrEnabled: true,
      pipeline: 'default',
      ocrOnUnobserved: true,
    })
  })

  it('⭐⭐ 回填只看顶层三列：旧键 `image_processing_config.image_pipeline=attrs` 不参与', () => {
    const dto = { ...defaultDto(), image_pipeline: 'smartocr' as const }
    // 旧键是 attrs，顶层是 smartocr ⇒ 必须读到 smartocr（两项互不干扰）
    expect(dto.image_processing_config).toContain('"attrs"')
    expect(readKbImagePipeline(dto).pipeline).toBe('smartocr')
    // 反向：只有旧键（顶层缺失）⇒ 回落 default，⛔ 不把 attrs 当本组取值
    const onlyLegacy = { ...dto, image_pipeline: undefined }
    expect(readKbImagePipeline(onlyLegacy).pipeline).toBe('default')
  })

  it('pipeline 规范化：大小写/空白宽容，脏值一律 default', () => {
    expect(normalizeKbImagePipeline(' SMARTOCR ')).toBe('smartocr')
    expect(normalizeKbImagePipeline('attrs')).toBe('default')
    expect(normalizeKbImagePipeline(null)).toBe('default')
  })
})

describe('图片处理（V286）· 两个开关都关时的灰字', () => {
  it('⭐⭐ 只有两个都关才显示，文案逐字一致', () => {
    expect(imagePipelineOffHintVisible(false, false)).toBe(true)
    expect(imagePipelineOffHintVisible(true, false)).toBe(false)
    expect(imagePipelineOffHintVisible(false, true)).toBe(false)
    expect(imagePipelineOffHintVisible(true, true)).toBe(false)
    expect(IMAGE_PIPELINE_OFF_HINT).toBe('关闭后不再调用视觉模型，图片只保留原图')
  })
})

describe('图片处理（V286）· 创建请求体', () => {
  it('⭐⭐ 创建知识库时三个字段都进请求体（无控件 ⇒ 显式写后端默认值）', () => {
    const page = readFileSync(
      fileURLToPath(new URL('../KnowledgeBasePage.tsx', import.meta.url)), 'utf8'
    ).replace(/\r\n?/g, '\n')
    const i = page.indexOf('knowledgeApi.createBase({')
    expect(i, '找不到 createBase 调用').toBeGreaterThan(0)
    const block = page.slice(i, page.indexOf('})', i))
    expect(block).toContain('image_caption_enabled: payload.imageCaptionEnabled ?? true')
    expect(block).toContain('image_ocr_enabled: payload.imageOcrEnabled ?? true')
    expect(block).toContain("image_pipeline: payload.imagePipeline ?? 'default'")
    // ⛔ 不能把旧 JSON 键透传成顶层字段（注释里可以提到它，但不能有赋值）
    expect(block).not.toMatch(/image_processing_config\s*:/)
  })
})

describe('图片处理（V286）· 弹窗接线', () => {
  it('⭐⭐ 读侧必须读 form（对应 readKbImagePipeline(form)），⛔ 不能读旧 JSON 键', () => {
    const s = src()
    expect(s, '找不到新的图片处理读值点').toContain('const imagePipeline = readKbImagePipeline(form)')
    // ⛔ 不允许把旧 JSON 当输入：那会把 "" / "attrs" 读成本组配置
    expect(s).not.toContain('readKbImagePipeline(imageCfg')
    expect(s).not.toContain('readKbImagePipeline(form.image_processing_config')
  })

  it('⭐⭐ 写侧走统一组装函数（三个字段名只在 kbImagePipeline.ts 出现一次）', () => {
    const s = src()
    // 2026-11-12：调用形态从 `buildKbImagePipelinePatch(next)` 变成
    // `buildKbImagePipelinePatch({ ...next, imageProcessingConfig })`
    //（`ocr_on_unobserved` 存在 JSON 里，需要带原串做合并）⇒ 断言放宽到函数调用本身，
    // 仍守住「三个 raw 字段名不许在弹窗里手写」这条意图。
    expect(s).toContain('buildKbImagePipelinePatch({')
    expect(s).toContain("okMsg = '图片处理配置已保存'")
    // 三个 raw 字段名不应该在弹窗里手写（否则很容易写错一个就静默丢字段）
    expect(s).not.toContain('image_caption_enabled:')
    expect(s).not.toContain('image_ocr_enabled:')
  })

  it('⭐ 三个控件都在「图片处理」组里（caption / OCR / 处理方式）', () => {
    const s = src()
    const i = s.indexOf('className="kb-image-pipeline-config"')
    expect(i, '找不到「图片处理」组').toBeGreaterThan(0)
    const block = s.slice(i, i + 4000)
    expect(block).toContain('生成图片描述（caption）')
    expect(block).toContain('识别图片内文字（OCR）')
    expect(block).toContain('处理方式')
    expect(block).toContain('<option value="default">默认</option>')
    expect(block).toContain('<option value="smartocr">智能（先观察再决定是否 OCR）</option>')
    // 灰字必须由同一个判据控制（保证「两开关都关」才出现）
    expect(block).toContain('imagePipelineOffHintVisible(imagePipeline.captionEnabled, imagePipeline.ocrEnabled)')
    expect(block).toContain('{IMAGE_PIPELINE_OFF_HINT}')
  })

  it('⛔ 旧字段 `image_processing_config.image_pipeline` 的原读写点未被改动', () => {
    const s = src()
    // 旧下拉仍在（本批明确不改那个字段）
    expect(s).toContain('value={imageCfg.image_pipeline ?? \'\'}')
    expect(s).toContain("saveImage({ image_pipeline: e.target.value }, '图片解析方案已保存')")
    expect(s).toContain('<option value="attrs">图片属性观察（先观察属性再决定是否 OCR）</option>')
  })
})

describe('图片处理 · ocr_on_unobserved（源站槽位键，可配置）', () => {
  it('槽位键 false ⇒ 读回 false', () => {
    const dto = {
      image_processing_config: JSON.stringify({
        image_pipeline_params: { 'smartocr.ocr_on_unobserved': false },
      }),
    }
    expect(readKbImagePipeline(dto).ocrOnUnobserved).toBe(false)
  })

  it('槽位缺失时读扁平键（兼容简写），槽位键优先', () => {
    expect(
      readKbImagePipeline({ image_processing_config: JSON.stringify({ ocr_on_unobserved: false }) })
        .ocrOnUnobserved
    ).toBe(false)
    expect(
      readKbImagePipeline({
        image_processing_config: JSON.stringify({
          ocr_on_unobserved: false,
          image_pipeline_params: { 'smartocr.ocr_on_unobserved': true },
        }),
      }).ocrOnUnobserved
    ).toBe(true)
  })

  it('坏 JSON / 非布尔 ⇒ 默认 true（宁可多跑一次，不静默丢字）', () => {
    expect(readKbImagePipeline({ image_processing_config: '{bad' }).ocrOnUnobserved).toBe(true)
    expect(
      readKbImagePipeline({
        image_processing_config: JSON.stringify({ ocr_on_unobserved: 'false' }),
      }).ocrOnUnobserved
    ).toBe(true)
  })

  it('⭐⭐ 写回是合并：原 JSON 的 enable_multimodal / model_id / 其它槽位不能被冲掉', () => {
    const original = JSON.stringify({
      enable_multimodal: true,
      model_id: 'vlm-x',
      image_pipeline_params: { 'default.caption_enabled': false },
    })
    const patch = buildKbImagePipelinePatch({
      ocrOnUnobserved: false,
      imageProcessingConfig: original,
    })
    const written = JSON.parse(String(patch.image_processing_config))
    expect(written.enable_multimodal).toBe(true)
    expect(written.model_id).toBe('vlm-x')
    expect(written.image_pipeline_params['default.caption_enabled']).toBe(false)
    expect(written.image_pipeline_params['smartocr.ocr_on_unobserved']).toBe(false)
  })

  it('只有 smartocr 才显示该开关', () => {
    expect(ocrOnUnobservedVisible('smartocr')).toBe(true)
    expect(ocrOnUnobservedVisible('default')).toBe(false)
  })
})
