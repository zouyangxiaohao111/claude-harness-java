import { describe, expect, it } from 'vitest'
import { DEFAULT_UPDATER_SOURCES, SCENE_UPDATER_SOURCES, updaterSourcesFor } from '@/api/updater'

/**
 * 更新源选择 —— 防止「场景版点检查更新装成通用版」（用户 2026-10-26 实测）。
 *
 * <b>WHY（意图）</b>：场景版 appName=nexusai-scene，但更新源原先硬编码为通用版的
 * `nexusai/updater/latest.json` ⇒ 场景用户点「检查更新」会下载并安装**通用版安装包**。
 * 本测试钉死：
 *   ① 场景版只读 `updater-scene` 前缀，且**绝不**包含通用 `updater/latest.json`；
 *   ② 场景产物只上 MinIO ⇒ 场景源**不含 GitHub**；
 *   ③ 通用版 / appName 未加载时仍用原双源（不改变通用行为）。
 */
describe('updaterSourcesFor', () => {
  it('场景版只走 updater-scene，不含通用源与 GitHub', () => {
    const sources = updaterSourcesFor('nexusai-scene')

    expect(sources).toEqual(SCENE_UPDATER_SOURCES)
    expect(sources.some((s) => s.includes('/updater-scene/latest.json'))).toBe(true)
    expect(sources.some((s) => s.includes('/updater/latest.json'))).toBe(false)
    expect(sources.some((s) => s.includes('github.com'))).toBe(false)
  })

  it('通用版与未加载（null）使用默认双源', () => {
    expect(updaterSourcesFor('nexusai')).toEqual(DEFAULT_UPDATER_SOURCES)
    expect(updaterSourcesFor(null)).toEqual(DEFAULT_UPDATER_SOURCES)
    expect(updaterSourcesFor(undefined)).toEqual(DEFAULT_UPDATER_SOURCES)
  })
})
