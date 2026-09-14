/**
 * [S3 · F-03c] 新建会话「本次可绑定项目 id」的解析单点。
 *
 * <p>**WHY 必须显式收口空串（不能用 `??`）**：`??`（nullish 合并）**只拦 `null`/`undefined`，
 * 不拦空串**。前端 `EMPTY_PROJECT.id === ''`（App.tsx `EMPTY_PROJECT`）正是「无项目」的表示，
 * `'' ?? x` 得 `''` —— 会把空串当有效项目 id 发给后端。三条现实路径都指向 `''`：
 * <ol>
 *   <li>存量库存在 `TRIM(main_project_id) = ''` 的行（计划盘点 SQL）；</li>
 *   <li>左栏分组键是 `s.mainProjectId ?? '__unbound__'`（SessionList.tsx:97）—— `''` 不是 nullish
 *       ⇒ 这些行落在键为 `''` 的组、`isUnbound === false` ⇒ 组标题上的「+」会渲染并以 `''` 回调；</li>
 *   <li>`perSessionProjects[sid].main` 未绑定时就是 `EMPTY_PROJECT`。</li>
 * </ol>
 * 故 `''`（以及纯空白）必须显式归 `null`。
 *
 * <p>**判据是「空白即无项目」**：与本批后端 `@NotBlank`（而非 `@NotNull`）是同一条判据的
 * 前后端两半 —— 前端负责不产生，后端负责不接收。两端判据不一致时，`''` 会从「前端放行」
 * 变成「后端 400」，用户看到的是无引导的报错而不是「请先选择项目」。
 *
 * <p>**优先级**（与 App.tsx 原实现同序）：当前会话已绑定项目 → 该会话的 per-session main 项目。
 * 两者都取不到 ⇒ `null` ⇒ 调用方走「引导先选项目」，绝不回落空串去造一个未绑定会话。
 */
export function resolveNewSessionProjectId(
  activeSessionMainProjectId: string | null | undefined,
  perSessionMainProjectId: string | null | undefined,
): string | null {
  const norm = (v: string | null | undefined): string | null =>
    typeof v === 'string' && v.trim() !== '' ? v : null
  return norm(activeSessionMainProjectId) ?? norm(perSessionMainProjectId)
}
