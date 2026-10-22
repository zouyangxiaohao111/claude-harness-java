import { test, expect } from '@playwright/test'

/**
 * e2e · 消息行序（独立环境：后端 3460 + 独立 SQLite；前端 5174）。
 *
 * 验证目标（对应 2026-10-22 anchorSeq 修复）：用户消息 → 助手回复的**几何顺序**正确；
 * 回复渲染在用户气泡之后（而不是插到历史中间）。
 *
 * 前置：会话必须绑定项目（后端 SessionCreateRequest.mainProjectId 必填，空 ⇒ 400），
 * 独立库里没有项目 ⇒ 用 request fixture 直接建「项目 + 会话」，再让 UI 打开它。
 *
 * 模型：独立库无 provider 配置 → 回落 DEFAULT_MODEL（mock-fast）→ MockLlmProvider 回显用户文本，
 * 不消耗真实额度、不需要 API key。
 */

const MARK = 'e2e-order-check'

test('助手回复渲染在用户消息之后（行序不逆）', async ({ page, request }) => {
  const consoleErrors: string[] = []
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()) })

  // ---- 前置：项目 + 会话（走真实后端 3460）----
  const projRes = await request.post('/api/v1/projects', {
    data: { name: 'e2e-project', path: 'D:/tmp/e2e-nexusai/project' },
  })
  expect(projRes.ok(), `创建项目失败: ${projRes.status()} ${await projRes.text()}`).toBeTruthy()
  const project = await projRes.json()

  const sessRes = await request.post('/api/v1/sessions', {
    data: { title: 'e2e-order', mainProjectId: project.id },
  })
  expect(sessRes.ok(), `创建会话失败: ${sessRes.status()} ${await sessRes.text()}`).toBeTruthy()
  const session = await sessRes.json()

  // ---- UI：打开该会话 → 发送消息 ----
  await page.goto('/')
  const item = page.locator('.session-item', { hasText: 'e2e-order' }).first()
  await expect(item).toBeVisible({ timeout: 30_000 })
  await item.click()

  const input = page.locator('.composer textarea').first()
  await expect(input).toBeVisible()
  await input.fill(MARK)
  await input.press('Enter')

  // 等助手回复落 DOM（mock 不回显原文 —— 固定回复文本；这里只按结构与几何顺序断言）
  const reply = page.locator('.content.md').last()
  await expect(reply).toBeVisible({ timeout: 90_000 })
  await expect(page.getByText(MARK, { exact: true }).first(), '用户消息应已渲染').toBeVisible()

  const userBubble = page.locator('.msg.user').last()
  await expect(userBubble).toBeVisible()

  const userBox = await userBubble.boundingBox()
  const replyBox = await reply.boundingBox()
  expect(userBox, '用户气泡应有布局盒').not.toBeNull()
  expect(replyBox, '助手回复应有布局盒').not.toBeNull()
  expect(replyBox!.y, '助手回复必须渲染在用户气泡下方（行序逆序 = 本次修复的缺陷）')
    .toBeGreaterThan(userBox!.y)

  // 冒烟：无前端 error 级控制台错误
  expect(consoleErrors, `控制台错误：${consoleErrors.join(' | ')}`).toHaveLength(0)
})
