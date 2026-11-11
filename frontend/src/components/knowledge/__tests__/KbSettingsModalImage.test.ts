import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 知识库设置 · 「图片理解」分区（2026-11-09 接线）。
 *
 * <h2>为什么要有这一组</h2>
 * `kb_knowledge_base.image_processing_config` 这个列从 DDL 起就有（含默认值
 * `{"enable_multimodal": false, "model_id": ""}`），PATCH 也一直能存进库，
 * 但**入库链从来不读它** ⇒ 用户把开关打开后什么都不会发生，且没有任何日志。
 * 本次把它接进 `KbPostProcessConfig.imageUnderstanding()` 并派发
 * `TYPE_IMAGE_UNDERSTAND` 任务。
 *
 * <p>本组守三件事：
 * <ol>
 *   <li>分区真的挂在导航里（否则用户根本点不到）；</li>
 *   <li>读侧读 `form.image_processing_config`（⛔ 不能读死 null —— 那就是
 *       上一条「开关回显不出来」的同款缺陷）；</li>
 *   <li>写侧必须 spread 现有值（该字段是整体覆盖语义，只发 `enable_multimodal`
 *       会把 `model_id` 一起冲掉）。</li>
 * </ol>
 */

const MODAL = fileURLToPath(new URL('../KbSettingsModal.tsx', import.meta.url))

function src(): string {
  return readFileSync(MODAL, 'utf8').replace(/\r\n?/g, '\n')
}

describe('知识库设置：图片理解分区', () => {
  it('⭐ 导航里必须有「图像处理」项（否则分区不可达）', () => {
    const s = src()
    // key 对齐源站：`multimodal`（旧的是 `image`，2026-11-10 外壳对齐批次改名）
    expect(s).toMatch(/key:\s*'multimodal'/)
    expect(s).toMatch(/section === 'multimodal'/)
  })

  it('⭐⭐ 读侧必须读 form.image_processing_config，⛔ 不能读死 null', () => {
    const s = src()
    const i = s.indexOf('const imageCfg = parseJson')
    expect(i, '找不到 imageCfg 的读值点').toBeGreaterThan(0)
    const block = s.slice(i, i + 400)
    expect(block).toContain('form.image_processing_config')
    expect(s, '读死 null ⇒ 开关永远显示未开启').not.toMatch(
      /parseJson<\{[^}]*enable_multimodal[^}]*\}>\s*\(\s*null/)
  })

  /* ⚠️ 2026-11-10（B6）：原来这两条判据依赖内联 `image_processing_config: JSON.stringify({`
     与 `onBlur={(e) => void patch({`。B6 把图像配置的合并写抽成了 `saveImage()`，
     并把模型输入框换成了 `ModelSelect` 下拉 ⇒ 两处字面量都不再存在，测试假红。
     改为语义判据：合并必须在 `saveImage()` 收口处 spread `imageCfg`。 */
  it('⭐⭐ 图像配置写入必须在 saveImage() 里 spread 现值（否则冲掉 model_id）', () => {
    const s = src()
    const i = s.indexOf('const saveImage =')
    expect(i, '找不到 saveImage 的合并收口点').toBeGreaterThan(0)
    const block = s.slice(i, i + 600)
    expect(block, '只发 enable_multimodal ⇒ 整体覆盖会抹掉 model_id / image_actions')
      .toContain('...cfg')
    expect(block).toContain('image_processing_config')
  })

  /* ⭐ 2026-11-11 用户最终裁定：「VLLM 视觉模型 是可以选择的，但是默认选中 使用全局默认，
     单独选择也能其效果」。
     ⇒ 既要有下拉（可选），又必须留空默认为「使用全局默认」，且选项标签统一
       `provider/modelName`（同名模型在多个 provider 下并存，不分组分不清）。 */
  it('⭐⭐ VLLM 视觉模型必须是下拉，且默认项是「使用全局默认」', () => {
    const s = src()
    const i = s.indexOf('VLLM 视觉模型')
    expect(i, '找不到 VLLM 视觉模型这一行').toBeGreaterThan(0)
    const block = s.slice(i, i + 700)
    expect(block, 'VLLM 模型必须是 ModelSelect 下拉').toContain('<ModelSelect')
    expect(block, '默认项必须写明「使用全局默认」').toContain('使用全局默认')
    expect(block, '必须绑定 image_processing_config.model_id').toContain('model_id')
  })

  it('⭐⭐ 显示 provider/modelName 并按 provider 分组（同名模型必须能区分）', () => {
    const s = src()
    const i = s.indexOf('function ModelSelect')
    expect(i, '找不到 ModelSelect 实现').toBeGreaterThan(0)
    const block = s.slice(i, i + 5000)
    expect(block, '缺 optgroup ⇒ 无法按提供者分组').toContain('<optgroup')
    expect(block, '必须拼出 provider/modelName').toContain('fullNameOf')
  })

  it('⭐⭐⭐ 存储必须是 models.id（不是全名）—— 全名会在 provider 改名后失效', () => {
    const s = src()
    const i = s.indexOf('function ModelSelect')
    const block = s.slice(i, i + 5000)
    /* 用户裁定（2026-11-11）：「存 ID + 界面显示全名」。
       判据：<option> 的 value 是主键 id ⇒ 调用链两次主键命中，绝不可能串到别的 provider。
       ⛔ 若改成 value={fullNameOf(m)}，`providers.name` 一旦改名（ProviderService.update
       允许改且不级联更新引用）所有已存值都会解析失败。 */
    expect(block, 'option 的 value 必须是主键 id').toContain('value={m.id}')
    expect(block, '⛔ 不得把全名当存储值').not.toContain('value={fullNameOf(m)}')
    /* 但标签必须是全名（否则同名模型在界面上三行一模一样） */
    expect(block, '标签必须是全名').toMatch(/\{fullNameOf\(m\)\}/)
  })
})
