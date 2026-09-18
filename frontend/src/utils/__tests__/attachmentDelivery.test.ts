import { describe, expect, it } from 'vitest'
import {
  BASE64_CONSUMER_TYPES,
  BASE64_LIMIT,
  classifyAttachmentFile,
  deliverAttachmentFile,
  deliverAttachmentFiles,
  planAttachmentChannel,
  type AcceptedAttachment,
  type AttachmentDeliveryDeps,
  type AttachmentFileType,
} from '../attachmentDelivery'

/**
 * addFiles 通道（浏览器拖拽 / 原生 input / 粘贴）的投递契约守卫。
 *
 * <b>被钉住的红线</b>：本通道每个附件必须落进「真的进入投递通道」或「同步拒绝」之一，
 * 绝不出现「前端已显示 chip、模型却收不到」的静默丢弃（2026-09-18 批 ATT-DROP 修的正是这个）。
 *
 * <b>为什么「base64」对 type=file 就等于静默丢弃</b>（后端源码实证，非本测试自述）：
 * `ChatService.resolveAttachments:2816` 对非 pdf 类型原样透传 base64 → 三个消费点全部按类型过滤
 * （image → `LlmAgentLoop:13360` 只留 base64 非空的 image；pdf → `registerRunPromptPdfs`；
 * video/audio/file → `buildMediaAttachmentNotes:13499` 因 contentId 为空 `continue`，**该 continue 无日志**）
 * ⇒ 模型侧零痕迹。
 */

type FakeFile = { name: string; type: string; size: number; arrayBuffer: () => Promise<ArrayBuffer> }

function fakeFile(name: string, type: string, size: number): FakeFile {
  return {
    name,
    type,
    size,
    arrayBuffer: async () => new Uint8Array([1, 2, 3, 4]).buffer as ArrayBuffer,
  }
}

/** (文件名 × MIME) → 期望类型：把 `addFiles` 原内联判据逐字钉住，防「改判据」被顺手夹带进来。 */
const FIXTURES: { label: string; name: string; mime: string; type: AttachmentFileType }[] = [
  { label: 'png（MIME 命中）', name: 'a.png', mime: 'image/png', type: 'image' },
  { label: 'pdf（扩展名命中）', name: 'b.PDF', mime: 'application/octet-stream', type: 'pdf' },
  { label: 'mp4（MIME 命中）', name: 'c.mp4', mime: 'video/mp4', type: 'video' },
  { label: 'mp3（MIME 命中）', name: 'd.mp3', mime: 'audio/mpeg', type: 'audio' },
  { label: 'docx（Office MIME 不命中 → file）', name: 'e.docx', mime: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', type: 'file' },
  { label: 'xlsx（Office MIME 不命中 → file）', name: 'f.xlsx', mime: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', type: 'file' },
  { label: 'zip → file', name: 'g.zip', mime: 'application/zip', type: 'file' },
  { label: 'txt → file', name: 'h.txt', mime: 'text/plain', type: 'file' },
  { label: 'MIME 缺失的 mp3 → file（本通道判据只看 MIME，不看扩展名）', name: 'i.mp3', mime: '', type: 'file' },
]

/** 大小三档：1B · 恰好 5MB（边界，原实现走 base64）· 5MB+1（过线） */
const SIZES = [1, BASE64_LIMIT, BASE64_LIMIT + 1]

interface Recorder {
  deps: AttachmentDeliveryDeps<FakeFile>
  chips: AcceptedAttachment[]
  taken: Set<string>
  uploaded: [string, string][]
  failed: [string, string][]
  rejected: [string, string][]
}

function makeRecorder(overrides: Partial<AttachmentDeliveryDeps<FakeFile>> = {}): Recorder {
  const taken = new Set<string>()
  const chips: AcceptedAttachment[] = []
  const uploaded: [string, string][] = []
  const failed: [string, string][] = []
  const rejected: [string, string][] = []
  const deps: AttachmentDeliveryDeps<FakeFile> = {
    sessionId: 'sess-1',
    isDuplicate: (n) => taken.has(n),
    reserve: (n) => { taken.add(n) },
    release: (n) => { taken.delete(n) },
    onAccepted: (a) => { chips.push(a) },
    onUploaded: (n, cid) => { uploaded.push([n, cid]) },
    onFailed: (n, r) => { failed.push([n, r]) },
    onRejected: (n, r) => { rejected.push([n, r]) },
    encodeDataUrl: async (_f, mt) => `data:${mt};base64,AAAA`,
    upload: async () => ({ contentId: '4711' }),
    ...overrides,
  }
  return { deps, chips, taken, uploaded, failed, rejected }
}

/** 等上传腿的 then/catch 落地（本模块有意不 await 上传，保持整批并行）。 */
const flush = () => new Promise((r) => setTimeout(r, 0))

describe('addFiles 通道 · 判据（classifyAttachmentFile 必须与原内联表达式同源）', () => {
  for (const fx of FIXTURES) {
    it(`${fx.label} → type=${fx.type}`, () => {
      const got = classifyAttachmentFile(fx.name, fx.mime)
      expect(got.type).toBe(fx.type)
      // MIME 为空时回落 octet-stream（原实现 `f.type || 'application/octet-stream'`）
      expect(got.mediaType).toBe(fx.mime || 'application/octet-stream')
    })
  }
})

describe('⭐ 通道矩阵红线：无 base64 消费方的类型不得走 base64（走了 = 后端静默丢弃）', () => {
  for (const fx of FIXTURES) {
    for (const size of SIZES) {
      const shouldUseBase64 = BASE64_CONSUMER_TYPES.includes(fx.type)
      it(`${fx.label} @ ${size}B → ${shouldUseBase64 ? '可用 base64' : '不得走 base64'}`, () => {
        const channel = planAttachmentChannel(fx.type, size)
        if (!shouldUseBase64) {
          expect(channel, `${fx.label}@${size}B 走了 ${channel} —— 后端无消费方`).not.toBe('base64')
        } else {
          // 有消费方则边界保持原语义：恰好 5MB 仍走 base64，>5MB 转 upload
          expect(channel).toBe(size > BASE64_LIMIT ? 'upload' : 'base64')
        }
      })
    }
  }

  it('矩阵覆盖度自检：3 档大小 × 9 夹具，且「非 base64 消费方」的格子确实存在（否则本组会假绿）', () => {
    const nonConsumerCells = FIXTURES.filter((f) => !BASE64_CONSUMER_TYPES.includes(f.type)).length * SIZES.length
    expect(FIXTURES.length * SIZES.length).toBe(27)
    expect(nonConsumerCells).toBe(21)
  })
})

describe('⭐ chip 红线：每个矩阵格的结局必须是「进入投递通道」或「同步拒绝」，且 reject 绝不生成 chip', () => {
  for (const fx of FIXTURES) {
    for (const size of SIZES) {
      it(`${fx.label} @ ${size}B`, async () => {
        const rec = makeRecorder()
        const outcome = await deliverAttachmentFile(fakeFile(fx.name, fx.mime, size), rec.deps)
        await flush()
        if (outcome === 'reject-unsupported' || outcome === 'reject-no-session') {
          expect(rec.chips, '响亮失败必须不显示 chip').toEqual([])
          expect(rec.rejected).toHaveLength(1)
        } else {
          expect(rec.chips, `结局 ${outcome} 必须恰好生成 1 个 chip`).toHaveLength(1)
        }
        // 任何结局下都不允许「chip 生成了但既无投递也无提示」：三种副作用里必须命中至少一种
        const signals = rec.chips.length + rec.rejected.length + rec.failed.length
        expect(signals, '静默结局（无 chip 也无提示）').toBeGreaterThan(0)
      })
    }
  }
})

describe('⭐ [ATT-UPLOAD-DOC] upload 白名单放开 doc/docx/xls/xlsx ⇒ file 类一律 upload（≤5MB 不再同步拒绝）', () => {
  // 为什么这条断言重要：`file` 类 ≤5MB 原走 'reject'，唯一理由是「后端 upload 白名单不含
  //   doc/docx/xls/xlsx ⇒ 走 upload 必 400」。用户已裁定放开该白名单 ⇒ 前提消失 ⇒ 继续同步拒绝
  //   会**挡住一条现在能送达的腿**（模型拿不到用户发的 Word/Excel）。
  //   ⚠️ 依赖：后端批 ATT-UPLOAD-DOC 落地；若未落地，本格会退化为「upload 400 → 撤 chip + 提示」
  //   （响亮失败，仍优于静默拒绝——见下方第三条用例）。
  it('矩阵：file 类 3 档大小（1B / 恰好 5MB / 5MB+1）恒 upload', () => {
    for (const fx of FIXTURES.filter((f) => f.type === 'file')) {
      for (const size of SIZES) {
        expect(planAttachmentChannel(fx.type, size), `${fx.label}@${size}B`).toBe('upload')
      }
    }
  })

  it('投递：file 类 ≤5MB 生成占位 chip（uploading）→ 上传成功回填 contentId（不再 reject-unsupported）', async () => {
    for (const fx of FIXTURES.filter((f) => f.type === 'file')) {
      for (const size of [1, BASE64_LIMIT]) {
        const rec = makeRecorder()
        const outcome = await deliverAttachmentFile(fakeFile(fx.name, fx.mime, size), rec.deps)
        expect(outcome, `${fx.label}@${size}B`).toBe('upload')
        expect(rec.rejected, '不得再同步拒绝').toEqual([])
        expect(rec.chips).toHaveLength(1)
        expect(rec.chips[0].uploading).toBe(true)
        await flush()
        expect(rec.uploaded).toEqual([[fx.name, '4711']])
      }
    }
  })

  it('后端白名单未放开（upload 400）→ 撤 chip + onFailed + 释放去重键（红线：绝不留「有 chip 收不到」）', async () => {
    const rec = makeRecorder({
      upload: async () => { throw new Error('附件上传失败 (400): {"error":"不支持的文件类型"}') },
    })
    const f = fakeFile('e.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 1024)
    expect(await deliverAttachmentFile(f, rec.deps)).toBe('upload')
    expect(rec.chips, '先出占位 chip').toHaveLength(1)
    await flush()
    expect(rec.failed).toEqual([['e.docx', '附件上传失败 (400): {"error":"不支持的文件类型"}']])
    expect(rec.taken.has('e.docx'), '失败后必须释放去重键，否则同名文件再也加不进来').toBe(false)
  })
})

describe('投递通道细节', () => {
  it('video/audio ≤5MB 走 upload（本批修的静默丢弃点：原实现按图片阈值无差别走 base64）', async () => {
    for (const fx of FIXTURES.filter((f) => f.type === 'video' || f.type === 'audio')) {
      const rec = makeRecorder()
      const outcome = await deliverAttachmentFile(fakeFile(fx.name, fx.mime, 1024), rec.deps)
      expect(outcome, `${fx.label} 必须 upload`).toBe('upload')
      expect(rec.chips[0].uploading).toBe(true)
      expect(rec.chips[0].base64).toBeUndefined()
      await flush()
      expect(rec.uploaded).toEqual([[fx.name, '4711']])
    }
  })

  // ⚠️ 本条原为「file 类 ≤5MB → 同步拒绝（reject-unsupported）」。该断言钉的是**旧前提**：
  //   「upload 白名单不含 doc/docx/xls/xlsx ⇒ 走 upload 必 400」。用户裁定放开白名单（后端批
  //   ATT-UPLOAD-DOC）后该前提消失 ⇒ 断言按新规格改写为「5MB 阈值两侧**同腿**（都 upload）」。
  //   ⛔ 不是放宽：改后断言更强（要求 chip + 上传回填 contentId + 零拒绝），并额外钉住
  //   「同一个 .docx 不再有两种失败形态」这条 WHY。
  it('file 类 5MB 阈值两侧同腿（都是 upload）—— 消除「同一 .docx 时好时坏」的双失败形态', async () => {
    for (const fx of FIXTURES.filter((f) => f.type === 'file')) {
      for (const size of [1, BASE64_LIMIT, BASE64_LIMIT + 1]) {
        const rec = makeRecorder()
        const outcome = await deliverAttachmentFile(fakeFile(fx.name, fx.mime, size), rec.deps)
        expect(outcome, `${fx.label}@${size}B`).toBe('upload')
        expect(rec.rejected, `${fx.label}@${size}B 不得同步拒绝`).toEqual([])
        expect(rec.chips).toHaveLength(1)
        await flush()
        expect(rec.uploaded).toEqual([[fx.name, '4711']])
      }
    }
  })

  it('上传失败 → 撤 chip（onFailed）+ 释放去重键（允许重试）；绝不留下「有 chip 收不到」', async () => {
    const rec = makeRecorder({
      upload: async () => { throw new Error('附件上传失败 (400): {"error":"不支持的文件类型"}') },
    })
    const f = fakeFile('big.mp4', 'video/mp4', 6 * 1024 * 1024)
    expect(await deliverAttachmentFile(f, rec.deps)).toBe('upload')
    expect(rec.chips).toHaveLength(1) // 先出占位 chip（上传中）
    await flush()
    expect(rec.failed).toEqual([['big.mp4', '附件上传失败 (400): {"error":"不支持的文件类型"}']])
    expect(rec.taken.has('big.mp4'), '失败后必须释放去重键，否则同名文件再也加不进来').toBe(false)
  })

  it('读盘失败 → 不生成 chip + onFailed（不是静默跳过）', async () => {
    const rec = makeRecorder({ encodeDataUrl: async () => { throw new Error('permission denied') } })
    const outcome = await deliverAttachmentFile(fakeFile('a.png', 'image/png', 1024), rec.deps)
    expect(outcome).toBe('failed')
    expect(rec.chips).toEqual([])
    expect(rec.failed).toEqual([['a.png', 'permission denied']])
    expect(rec.taken.has('a.png'), '读盘失败不该占用去重键').toBe(false)
  })

  it('无 sessionId → 同步拒绝（附件靠 sessionId 归属，后端缺值 400；前端不再发出去）', async () => {
    const rec = makeRecorder({ sessionId: undefined })
    const outcome = await deliverAttachmentFile(fakeFile('a.png', 'image/png', 1024), rec.deps)
    expect(outcome).toBe('reject-no-session')
    expect(rec.chips).toEqual([])
    expect(rec.rejected).toEqual([['a.png', 'no-session']])
  })

  it('同名（含同批重名）只生成 1 个 chip，第二个为 duplicate（已在列表里，不是丢弃）', async () => {
    const rec = makeRecorder()
    const first = await deliverAttachmentFile(fakeFile('a.png', 'image/png', 1024), rec.deps)
    const second = await deliverAttachmentFile(fakeFile('a.png', 'image/png', 1024), rec.deps)
    expect(first).toBe('base64')
    expect(second).toBe('duplicate')
    expect(rec.chips).toHaveLength(1)
    expect(rec.rejected).toEqual([])
  })

  it('恰好 5MB 的图片仍走 base64（沿用原阈值语义：严格大于才转 upload）', async () => {
    const rec = makeRecorder()
    expect(await deliverAttachmentFile(fakeFile('a.png', 'image/png', BASE64_LIMIT), rec.deps)).toBe('base64')
    expect(rec.chips[0].base64).toBe('data:image/png;base64,AAAA')
  })

  it('汇总口径：accepted 只数「真的进入投递通道」的，不用入口文件数（原实现「已添加 N 个」虚报根因）', async () => {
    // ⚠️ 本用例原以 w.docx / x.xlsx **被同步拒绝**为 accepted 与入口数不等的证据（旧前提）。
    //   白名单放开后 docx/xlsx 走 upload ⇒ 两者计入 accepted，不等的证据改由「同名重复项」承担
    //   （4 个入口文件 → accepted=3，重复项不计）—— 断言的 WHY 不变：accepted ≠ 入口文件数。
    const rec = makeRecorder()
    const summary = await deliverAttachmentFiles(
      [
        fakeFile('a.png', 'image/png', 1024),
        fakeFile('w.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 1024),
        fakeFile('x.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 1024),
        fakeFile('a.png', 'image/png', 1024),
      ],
      rec.deps,
    )
    await flush()
    expect(summary.accepted).toBe(3)
    expect(summary.rejected).toEqual([])
    expect(summary.duplicates).toEqual(['a.png'])
    expect(rec.chips).toHaveLength(3)
    expect(rec.rejected).toHaveLength(0)
  })
})
