/**
 * tdesign 图标 sprite。
 *
 * <p>含两组：
 * <ol>
 *   <li><b>8 个文件类型图标</b>（解析引擎节用）</li>
 *   <li><b>9 个文档行操作菜单图标</b>（⋯ 菜单用）</li>
 * </ol>
 *
 * 为什么需要它：解析引擎节每行要显示文件类型图标，用的是
 * `<use href="#t-icon-file-pdf" />` 这种 sprite 引用写法（对齐源站 tdesign）。
 * 本仓没有 tdesign 运行时，没有这份 symbol 定义，`<use>` 取不到东西 ⇒
 * 渲染成空白方框。
 *
 * symbol 逐字取自设计稿 `kb-design/06-知识库设置.html` 第 206 行内联 sprite
 * （#t-icon-file-pdf / file-word / file-powerpoint / file-excel / file / file-code / image / sound），
 * ⛔ 不是手画的。仅做了 JSX 必需的属性名大小写转换（stroke-width → strokeWidth 等）。
 *
 * 为什么用 `position:absolute;width:0;height:0;overflow:hidden`：这是 SVG sprite
 * 的标准做法 —— symbol 本身不该占布局空间，只提供 id 供 `<use>` 引用。源站也是这么放的。
 *
 * 生命周期边界：`<use href="#id">` 是全局 id 查找，sprite 只要在文档里存在即可，
 * 不必是使用方的祖先。本组件挂在 KbCreateModal 内 ⇒ 弹窗关闭后 sprite 随之卸载，
 * 此时那些 `<use>` 也一并卸载，故无影响。
 */
export function IconSprite() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
      style={{ position: 'absolute', width: 0, height: 0, overflow: 'hidden' }}
    >
      {/* t-icon-file-pdf：457 B，取自 06-知识库设置.html 内联 sprite */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-file-pdf"><g stroke="currentColor" strokeWidth="2" strokeLinecap="square"><path d="M20 10V7l-5-5H4v20h16M14 2v6h6"></path><path d="M7 16v-3h2.714c.158 0 .286.128.286.286v2.428a.286.286 0 0 1-.286.286H7Zm0 0v3m13.667-6h-2.381a.286.286 0 0 0-.286.286V16m0 0h2.667M18 16v3m-5.5-6v6h2.714a.286.286 0 0 0 .286-.286v-5.428a.286.286 0 0 0-.286-.286H12.5Z"></path></g></symbol>
      {/* t-icon-file-word：392 B，取自 06-知识库设置.html 内联 sprite */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-file-word"><path d="M4 22h16V8h-6V2H4v20Z" fill="transparent"></path><path d="M14 2v6h6m-6-6h1l5 5v1m-6-6H4v20h16V8" stroke="currentColor" strokeWidth="2"></path><path d="M9 12v4a1 1 0 0 0 1 1h2m0 0v-5m0 5h2a1 1 0 0 0 1-1v-4" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>
      {/* t-icon-file-powerpoint：401 B，取自 06-知识库设置.html 内联 sprite */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-file-powerpoint"><path d="M4 22h16V8h-6V2H4v20Z" fill="transparent"></path><path d="M14 2v6h6m-6-6h1l5 5v1m-6-6H4v20h16V8" stroke="currentColor" strokeWidth="2"></path><path d="M10 14.6V11h3a1 1 0 0 1 1 1v1.6a1 1 0 0 1-1 1h-3Zm0 0V17" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>
      {/* t-icon-file-excel：483 B，取自 06-知识库设置.html 内联 sprite */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-file-excel"><path d="M4 22h16V8h-6V2H4v20Z" fill="transparent"></path><path d="M14 2v6h6m-6-6h1l5 5v1m-6-6H4v20h16V8" stroke="currentColor" strokeWidth="2"></path><path d="M14 11v.646a1 1 0 0 1-.306.72L12 14m-2-3v.646a1 1 0 0 0 .306.72L12 14m0 0-1.694 1.634a1 1 0 0 0-.306.72V17m2-3 1.694 1.634a1 1 0 0 1 .306.72V17" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>
      {/* t-icon-file：253 B，取自 06-知识库设置.html 内联 sprite */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-file"><path d="M4 22h16V8h-6V2H4v20Z" fill="transparent"></path><path d="M14 2v6h6m-6-6h1l5 5v1m-6-6H4v20h16V8" stroke="currentColor" strokeWidth="2"></path></symbol>
      {/* t-icon-file-code：267 B，取自 06-知识库设置.html 内联 sprite */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-file-code"><g stroke="currentColor" strokeWidth="2" strokeLinecap="square"><path d="M20 12V7l-5-5H4v20h6m4-20v6h6M14 22h2.5m5.75-1.24-2.784-2.755 2.784-2.756"></path></g></symbol>
      {/* t-icon-image：404 B，取自 06-知识库设置.html 内联 sprite */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-image"><path d="M3 21h17L9 10l-6 6v5Z" fill="transparent"></path><circle cx="15.75" cy="8.25" r="2" fill="transparent"></circle><path d="M3 16V3h18v18h-1M3 16v5h17M3 16l6-6 11 11" stroke="currentColor" strokeWidth="2"></path><circle cx="15.75" cy="8.25" r="2" stroke="currentColor" strokeWidth="2"></circle></symbol>
      {/* t-icon-sound：387 B，取自 06-知识库设置.html 内联 sprite */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-sound"><path d="M14 21 6 16.5v-9L14 3v18ZM2 16.5v-9h3.999v9H2Z" fill="transparent"></path><path d="M5.999 16.5H2v-9h3.999m0 9L14 21V3L5.999 7.5m0 9v-9M17.99 13.506a2.5 2.5 0 0 0 .001-3.015m2.79 5.127a6 6 0 0 0 .003-7.235" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>

      {/* ── 文档行操作菜单图标（9 个）─────────────────────────────────
          取自源码 views/knowledge/components/DocumentActionMenu.vue 里用到的
          t-icon name，path 数据逐个从官方包
          tdesign-icons-vue-next@0.4.12（esm/components/<name>.js 的 element 定义）提取，
          ⛔ 不是手画，也不是设计稿旧版残留。
          源站写法是 `<t-icon name="x" />`（图标字体运行时），本仓无该运行时
          ⇒ 用同 path 的 sprite symbol，`<use href="#t-icon-x">` 消费。 */}

      {/* download：DocumentActionMenu.vue:50（下载） */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-download"><g stroke="currentColor" strokeWidth="2" strokeLinecap="square"><path d="M16.5 10.5L12 15L7.5 10.5M12 13.75V4"></path><path d="M20.5 15V20H3.5V15"></path></g></symbol>

      {/* edit：DocumentActionMenu.vue:56（编辑文档） */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-edit"><path d="M14.1048 6.00427L4.78744 15.3216L3.99805 20.0001L8.67652 19.2107L17.9939 9.89336M14.1048 6.00427L17.9939 9.89336M14.1048 6.00427L17.1615 2.94727L21.0506 6.83635L17.9939 9.89336" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>

      {/* chart-bar：DocumentActionMenu.vue:62（查看 Trace） */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-chart-bar"><path d="M7 10H11V21H7V10Z M15 6H19V21H15V6Z" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path><path d="M21 21H3V3" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>

      {/* chart-line：doc-content.vue:1619 的「查看 Trace」按钮图标
          （源侧 `<t-icon name="chart-line" size="16px" />`）。
          path 取自 tdesign-icons-vue-next@0.4.12
          `esm/components/chart-line.js` 的 element（两条 stroke，逐字）。 */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-chart-line"><g stroke="currentColor" strokeWidth="2" strokeLinecap="square"><path d="M21 21H3V3"></path><path d="M20.5 8L15.5 13L11.5 9L6.5 14"></path></g></symbol>

      {/* close：t-drawer 自带的关闭按钮图标（源侧由 tdesign 内部渲染 `<t-icon name="close">`）。
          本仓抽屉是手写 DOM，必须自己给图标 —— ⛔ 用字符 `✕` 会跟其它图标的描边风格不一致。
          path 取自同包 `esm/components/close.js`。 */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-close"><g id="close"><path id="stroke1" stroke="currentColor" d="M16.9503 7.05029L12.0005 12M12.0005 12L7.05078 16.9498M12.0005 12L16.9503 16.9498M12.0005 12L7.05078 7.05029" strokeLinecap="square" strokeWidth="2"></path></g></symbol>

      {/* ── 分块头部 4 个操作图标 ────────────────────────────────────
          源侧 doc-content.vue:1912/1939/1954/1963/2037 用的是
          git-branch（查看父块上下文）/ help-circle（辅助召回问题）/
          edit / history（分块编辑历史）。path 逐个取自
          tdesign-icons-vue-next@0.4.12 同包，逐字未改。 */}

      {/* git-branch：doc-content.vue:1912 */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-git-branch"><path d="M5.5 15V9M18.5 8.5V9.5C18.5 11.1569 17.1569 12.5 15.5 12.5H8.5C6.84315 12.5 5.5 13.8431 5.5 15.5M18.5 8.5C17.1193 8.5 16 7.38071 16 6C16 4.61929 17.1193 3.5 18.5 3.5C19.8807 3.5 21 4.61929 21 6C21 7.38071 19.8807 8.5 18.5 8.5ZM5.5 15.5C6.88071 15.5 8 16.6193 8 18C8 19.3807 6.88071 20.5 5.5 20.5C4.11929 20.5 3 19.3807 3 18C3 16.6193 4.11929 15.5 5.5 15.5ZM8 6C8 4.61929 6.88071 3.5 5.5 3.5C4.11929 3.5 3 4.61929 3 6C3 7.38071 4.11929 8.5 5.5 8.5C6.88071 8.5 8 7.38071 8 6Z" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>

      {/* help-circle：doc-content.vue:1939（辅助召回问题） */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-help-circle"><g stroke="currentColor" strokeWidth="2" strokeLinecap="square"><path d="M2 12C2 6.47715 6.47715 2 12 2C17.5228 2 22 6.47715 22 12C22 17.5228 17.5228 22 12 22C6.47715 22 2 17.5228 2 12Z"></path><path d="M12.0002 14.25V14C12.0002 12.8954 12.9877 12.0414 13.8553 11.3578C14.5525 10.8085 15.0002 9.95652 15.0002 9C15.0002 7.34315 13.657 6 12.0002 6C10.694 6 9.58273 6.83481 9.1709 8M12.0002 17.25H12.0041V17.2539H12.0002V17.25Z"></path></g></symbol>

      {/* history：doc-content.vue:2037（分块编辑历史） */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-history"><path d="M2.552 13C3.0517 17.7767 7.09104 21.5 12 21.5C17.2467 21.5 21.5 17.2467 21.5 12C21.5 6.7533 17.2467 2.5 12 2.5C10.3719 2.5 8.8394 2.90957 7.5 3.63131C5.69871 4.60193 4.24661 6.13714 3.38065 8M12 7V12L14.5 14.5M2.5 3.5V8.5H7.5" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>

      {/* catalog：Wiki 侧栏「索引」入口图标（源站 WikiBrowser.vue:249 `<t-icon name="catalog" class="wiki-nav-icon" />`）
          path 取自设计稿 03-Wiki 页内联 sprite（`D:/tmp/03-Wiki.out.html` 的 `t-icon-catalog`）。 */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-catalog"><path d="M4 2h16v20H4V2Z" fill="transparent"></path><path d="M4 2h16v20H4V2Z" stroke="currentColor" strokeWidth="2"></path><path d="M9 8h6m-6 4h6m-6 4h6" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>

      {/* refresh：DocumentActionMenu.vue:69/80（重建知识） */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-refresh"><path d="M21.448 13C20.9483 17.7767 16.909 21.5 12 21.5C8.18227 21.5 4.89052 19.248 3.38065 16M2.5 20.5V15.5H5.5M2.55176 11C3.05145 6.22334 7.09079 2.5 11.9998 2.5C15.8175 2.5 19.1092 4.75197 20.6191 8M21.4998 3.5V8.5H18.4998" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>

      {/* close-circle：DocumentActionMenu.vue:92（停止解析） */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-close-circle"><path d="M2 12C2 6.47715 6.47715 2 12 2C17.5228 2 22 6.47715 22 12C22 17.5228 17.5228 22 12 22C6.47715 22 2 17.5228 2 12Z" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path><path d="M15.1813 8.81836L11.9997 12M11.9997 12L8.81738 15.1823M11.9997 12L15.1813 15.1816M11.9997 12L8.81738 8.81768" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>

      {/* folder：DocumentActionMenu.vue:99（移动到目录） */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-folder"><path d="M2 3.5H9L11 6H22V20H2V3.5Z" stroke="currentColor" strokeWidth="2"></path></symbol>

      {/* swap：DocumentActionMenu.vue:105（移动到...） */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-swap"><path d="M21 14.5H4L9 19.5M3 9.5L20 9.5L15 4.5" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>

      {/* queue：DocumentActionMenu.vue:111（批量管理） */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-queue"><path d="M6.5 14H10M10 14H13.5M10 14V17.5M10 14V10.5M3 7H17V21H3V7Z" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path><path d="M8.00098 3.00195L21 3.00098V17.001M21 17.001V18.001L21.001 18.002L21 17.001Z" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>

      {/* delete：DocumentActionMenu.vue:122（删除文档） */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-delete"><path d="M21 5H3M5 5H19L18.5 22H5.5L5 5ZM8.5 2H15.5V5H8.5V2Z" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path><path d="M12 9V18" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>
      {/* ⭐ 2026-11-09 补齐：摘要/元数据区要用。path 逐字取自
          tdesign-icons-vue-next@0.4.12 的 info-circle.js / add.js（官方 tarball 解出的 d 属性），
          ⛔ 不是自绘形状。 */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-info-circle"><path d="M2 12C2 6.47715 6.47715 2 12 2C17.5228 2 22 6.47715 22 12C22 17.5228 17.5228 22 12 22C6.47715 22 2 17.5228 2 12Z" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path><path d="M12 16.5L12 11M12 7.5L11.9961 7.5L11.9961 7.49609L12 7.49609L12 7.5Z" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-add"><path d="M12 5L12 19M19 12L5 12" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>
      {/* ── 设置弹窗导航图标（9 个）───────────────────────────────────
          symbol 逐字取自设计稿 06-知识库设置.html 的内联 sprite，
          ⛔ 不是手画的。仅做 JSX 必需的属性名大小写转换。 */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-control-platform"><path d="m12 2 9 5v10l-9 5-9-5V7l9-5Z" fill="transparent"></path><path d="m12 12 8.5-4.5M12 12v9.5m0-9.5L3.5 7.5M12 2l9 5v10l-9 5-9-5V7l9-5Z" stroke="currentColor" strokeWidth="2"></path></symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-data-base"><path d="M21 20h-.514c-1.236 1.165-4.568 2-8.486 2-3.918 0-7.25-.835-8.486-2H3V5c0 1.657 4.03 3 9 3s9-1.343 9-3v15Z" fill="transparent"></path><path d="M12 8c4.97 0 9-1.343 9-3s-4.03-3-9-3-9 1.343-9 3 4.03 3 9 3Z" fill="transparent"></path><path d="M21 12c0 1.657-4.03 3-9 3s-9-1.343-9-3m18 0v7c0 1.657-4.03 3-9 3s-9-1.343-9-3v-7m18 0V5M3 12V5m0 0c0 1.657 4.03 3 9 3s9-1.343 9-3M3 5c0-1.657 4.03-3 9-3s9 1.343 9 3" stroke="currentColor" strokeWidth="2"></path></symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-file-search"><path d="M21 17.25a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0Z" fill="transparent"></path><path d="M20 10.5V7l-5-5H4v20h7.5M14 2v6h6" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path><path d="m19.998 20 1.502 1.5m-.5-4.25a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0Z" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-file-copy"><path d="M7 18V2h7v6h6v10H7Z" fill="transparent"></path><path d="M14 2v6h6m-6-6h1l5 5v1m-6-6H7v16h13V8" stroke="currentColor" strokeWidth="2"></path><path d="M3 6v16h11" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-chart-bubble"><g fill="transparent"><path d="M13 14a3 3 0 1 1-6 0 3 3 0 0 1 6 0ZM18 6a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM20 14a1 1 0 1 1-2 0 1 1 0 0 1 2 0Z"></path></g><path d="M21 21H3V3" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path><g stroke="currentColor" strokeWidth="2" strokeLinecap="square"><path d="M18 6a2 2 0 1 1-4 0 2 2 0 0 1 4 0ZM19 15a1 1 0 1 1 0-2 1 1 0 0 1 0 2ZM13 14a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"></path></g></symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-setting"><path fillRule="evenodd" clipRule="evenodd" d="M20.66 7 12 2 3.34 7v10L12 22l8.66-5V7ZM12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z" fill="transparent"></path><path d="M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z" fill="transparent"></path><path d="m12 2 8.66 5v10L12 22l-8.66-5V7L12 2Z" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path><path d="M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-cloud"><path d="M2 15a4 4 0 0 0 4 4h11a5 5 0 1 0-.083-10 6.002 6.002 0 1 0-11.816 2.101A4.001 4.001 0 0 0 2 15Z" fill="transparent"></path><path d="M2 15a4 4 0 0 0 4 4h11a5 5 0 1 0-.083-10 6.002 6.002 0 1 0-11.816 2.101A4.001 4.001 0 0 0 2 15Z" stroke="currentColor" strokeWidth="2"></path></symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-cloud-download"><g stroke="currentColor" strokeWidth="2" strokeLinecap="square"><path d="M4 17.4A5.5 5.5 0 0 1 5.513 7.088a6.5 6.5 0 0 1 12.974 0A5.502 5.502 0 0 1 20 17.4M8 18l4 4 4-4m-4 2.75V11"></path></g></symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-share"><g fill="transparent"><path d="M19.49 7.097a3 3 0 1 0-3-5.197 3 3 0 0 0 3 5.197ZM16.49 22.099a3 3 0 1 0 3-5.197 3 3 0 0 0-3 5.197ZM6.5 14.597a3 3 0 1 0-3-5.197 3 3 0 0 0 3 5.197Z"></path><path d="M3.5 14.598a3 3 0 1 0 3-5.196 3 3 0 0 0-3 5.197Z"></path></g><path d="m7.599 13.501 7.794 4.5m-7.794-7.502 7.794-4.5m0 0a3 3 0 1 1 5.196-3 3 3 0 0 1-5.196 3Zm0 12.002a3 3 0 1 1 5.196 3 3 3 0 0 1-5.196-3Zm-7.794-7.503a3 3 0 1 1-5.197 3 3 3 0 0 1 5.197-3Zm0 3.002a3 3 0 1 1-5.197-3 3 3 0 0 1 5.197 3Z" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>
      {/* lock-on：源站「索引策略已锁定」提示用（`.locked-tip-icon`）。
          path 逐字取自 06-知识库设置.html 内联 sprite。 */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-lock-on"><path d="M4.5 10h15v11h-15V10Z" fill="transparent"></path><g stroke="currentColor" strokeWidth="2" strokeLinecap="square"><path d="M4.5 11h15v10h-15V11ZM7 7a5 5 0 0 1 10 0v4H7V7Z"></path></g><path d="M10 16h4" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>

      {/* ── 处理流水线（KP）所需的 4 个图标 ────────────────────────────
          `KbProcessingTimeline` 里「复制 / 耗时 / 展开-收起」三处要用的 symbol。
          path 来源逐个注明，⛔ 不是手画：
          · copy          —— tdesign-icons-vue-next@0.4.12 `esm/components/copy.js`
                              的 element（本仓设计稿里没有这个图标，故直接取官方包；
                              官方定义带一个 mask 做填充挖空，但 fillColor1 默认未设 ⇒
                              实际呈现就是两条描边，这里按本仓 sprite 的既有口径
                              写成 transparent 填充 + stroke=currentColor）。
          · time          —— 设计稿 `D:/tmp/06-kb-settings.html` 内联 sprite。
          · chevron-right —— 设计稿 06 内联 sprite。
          · chevron-down  —— 设计稿 `D:/tmp/03-Wiki.out.html` 内联 sprite。 */}
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-copy"><path d="M10 10H21V21H10V10Z" fill="transparent"></path><g stroke="currentColor" strokeWidth="2" strokeLinecap="square"><path d="M10 10H21V21H10V10Z"></path><path d="M14 6.5V3L3 3L3 14H6.5"></path></g></symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-time"><path d="M2 12C2 6.477 6.477 2 12 2s10 4.477 10 10-4.477 10-10 10S2 17.523 2 12Z" fill="transparent"></path><g stroke="currentColor" strokeWidth="2" strokeLinecap="square"><path d="M2 12C2 6.477 6.477 2 12 2s10 4.477 10 10-4.477 10-10 10S2 17.523 2 12Z"></path><path d="M12 6.5V12l3 3"></path></g></symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-chevron-right"><path d="M9.5 17.5 15 12 9.5 6.5" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>
      <symbol fill="none" viewBox="0 0 24 24" id="t-icon-chevron-down"><path d="M17.5 9.5 12 15 6.5 9.5" stroke="currentColor" strokeWidth="2" strokeLinecap="square"></path></symbol>
    </svg>
  )
}
