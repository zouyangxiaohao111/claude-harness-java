import { describe, expect, it } from 'vitest'
import { detectPlatforms, platformKeyOf } from '../release-update.mjs'

/**
 * 平台识别 —— 清单的 `platforms` 必须**按产物实际平台**生成，
 * 且键名与 Rust 侧 `platform_key_for` 一致（契约：见计划「平台键契约」）。
 *
 * WHY（意图）：mac 客户端拿到 Windows 安装包（2026-11-13 用户实测），
 * 根因是清单只有 windows 一个键 + 客户端写死取它。
 * 两侧必须共用同一套键；发布侧写错键 ⇒ 客户端查不到 ⇒ 静默无更新。
 */
describe('platformKeyOf', () => {
  it('识别 Windows 产物', () => {
    expect(platformKeyOf('NexusAI_0.1.74_x64-setup.exe')).toBe('windows-x86_64')
    expect(platformKeyOf('NexusAI_0.1.74_x64_en-US.msi')).toBe('windows-x86_64')
  })

  it('识别 macOS 产物', () => {
    expect(platformKeyOf('NexusAI_0.1.74_aarch64.dmg')).toBe('darwin-aarch64')
    expect(platformKeyOf('NexusAI_0.1.74_x64.dmg')).toBe('darwin-x86_64')
    expect(platformKeyOf('NexusAI_0.1.74_aarch64.app.tar.gz')).toBe('darwin-aarch64')
    expect(platformKeyOf('NexusAI_0.1.74_x64.app.tar.gz')).toBe('darwin-x86_64')
  })

  it('无法识别的文件返回 null', () => {
    expect(platformKeyOf('latest.json')).toBeNull()
    expect(platformKeyOf('readme.txt')).toBeNull()
  })
})

describe('detectPlatforms', () => {
  it('只收本版本文件，多平台各一条', () => {
    const files = [
      'NexusAI_0.1.74_x64-setup.exe',
      'NexusAI_0.1.74_aarch64.dmg',
      'NexusAI_0.1.73_x64-setup.exe', // 旧版本 ⇒ 必须忽略
      'latest.json',
    ]
    expect(detectPlatforms(files, '0.1.74')).toEqual({
      'windows-x86_64': 'NexusAI_0.1.74_x64-setup.exe',
      'darwin-aarch64': 'NexusAI_0.1.74_aarch64.dmg',
    })
  })

  it('同一平台多个文件取第一个', () => {
    const files = ['NexusAI_0.1.74_x64-setup.exe', 'NexusAI_0.1.74_x64_en-US.msi']
    expect(detectPlatforms(files, '0.1.74')).toEqual({
      'windows-x86_64': 'NexusAI_0.1.74_x64-setup.exe',
    })
  })
})
