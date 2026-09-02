# NexusAI Tauri Client

基于 v10 设计稿的 NexusAI 桌面客户端，使用 React + TypeScript + Vite + Tailwind CSS 构建。

## 设计规范

详细的设计规范文档请查看：[design-spec.md](./design-spec.md)

## 技术栈

- **框架**: React 19 + TypeScript
- **构建工具**: Vite 8
- **样式**: Tailwind CSS 4 + CSS Variables
- **桌面应用**: Tauri 2.0 (待集成)
- **状态管理**: Zustand (待添加)
- **WebSocket**: 原生 WebSocket API (待添加)

## 快速开始

### 安装依赖

```bash
npm install
```

### 开发模式

```bash
npm run dev
```

应用将在 `http://localhost:5173` 启动。

### 构建生产版本

```bash
npm run build
```

### 预览构建结果

```bash
npm run preview
```

## 项目结构

```
nexusai-client/
├── src/
│   ├── components/         # React 组件
│   │   ├── layout/         # 布局组件
│   │   ├── ui/             # 通用 UI 组件
│   │   └── features/       # 功能组件
│   ├── hooks/              # 自定义 Hooks
│   ├── stores/             # 状态管理
│   ├── styles/             # 样式文件
│   │   └── globals.css     # 全局样式 + CSS Variables
│   ├── utils/              # 工具函数
│   ├── App.tsx             # 主应用组件
│   └── main.tsx            # 入口文件
├── index.html              # HTML 模板
├── vite.config.ts          # Vite 配置
├── tsconfig.json           # TypeScript 配置
├── tailwind.config.js      # Tailwind 配置
├── postcss.config.js       # PostCSS 配置
└── design-spec.md          # 设计规范文档
```

## 设计特性

### 色彩系统
- **画布色**: `#FAF9F5` (温暖米白)
- **强调色**: `#CC785C` (珊瑚色)
- **表面色**: 4 级灰度系统
- **语义色**: 成功 (绿)、运行中 (青)、警告 (黄)、错误 (红)

### 布局系统
- **三栏布局**: 左侧 (280px) + 中间 (自适应) + 右侧 (320px)
- **固定高度**: 标题栏 (28px) + 菜单栏 (36px) + 状态栏 (24px)
- **CSS Grid**: 使用 `grid-template-areas` 定义布局区域

### 核心组件
1. **标题栏** - macOS 风格交通灯 + 应用名称 + 工作区状态
2. **菜单栏** - 菜单项 + 模型选择器 + 操作按钮
3. **左侧面板** - 工作区信息 + 项目列表 + 会话列表
4. **中间面板** - 标签页 + 对话流 + 输入框
5. **右侧面板** - 文件列表 + 追踪记录
6. **状态栏** - 连接状态 + 项目信息

### 交互动画
- **弹性动画**: `cubic-bezier(0.34, 1.56, 0.64, 1)` (360ms)
- **平滑过渡**: `cubic-bezier(0.16, 1, 0.3, 1)` (120-300ms)
- **脉冲动画**: 运行中状态指示 (1.5s)
- **闪烁动画**: 流式光标 (1s)

## 下一步

### Phase 2: 左侧面板 (2-3 天)
- [ ] 实现工作区信息组件
- [ ] 实现项目卡片（主/分项目）
- [ ] 实现对话列表
- [ ] 实现展开/折叠动画

### Phase 3: 中间面板 (3-4 天)
- [ ] 实现标签页切换
- [ ] 实现对话流
- [ ] 实现消息渲染（用户/助手）
- [ ] 实现推理块
- [ ] 实现工具卡片
- [ ] 实现输入框
- [ ] 实现 Token 进度条

### Phase 4: 右侧面板 (1-2 天)
- [ ] 实现文件列表
- [ ] 实现追踪记录
- [ ] 实现 Diff 弹窗

### Phase 5: WebSocket 集成 (2-3 天)
- [ ] 实现 WebSocket 连接
- [ ] 实现消息收发
- [ ] 实现流式输出
- [ ] 实现错误处理

### Phase 6: 优化与测试 (2-3 天)
- [ ] 性能优化
- [ ] 响应式适配
- [ ] 键盘快捷键
- [ ] 测试与修复

## 开发指南

### 添加新组件

1. 在 `src/components/` 下创建组件目录
2. 创建组件文件（`.tsx`）
3. 使用 Tailwind CSS 类名或自定义 CSS
4. 导入并使用组件

### 修改样式

1. 全局样式变量在 `src/styles/globals.css`
2. 组件样式使用 Tailwind 类名
3. 复杂样式使用 CSS Modules 或 styled-components

### 状态管理

目前使用 React 内置状态，后续将添加 Zustand：
```bash
npm install zustand
```

## 浏览器支持

- Chrome/Edge 90+
- Firefox 88+
- Safari 14+

## NexusAI in Chrome 浏览器自动化

让 AI 直接操作你的 Chrome 浏览器（点击、输入、滚动、截图、读取网页/控制台/网络请求）。AI 通过 18 个 `mcp__nexusai-in-chrome__*` 工具控制浏览器。

### 一键加载扩展（非开发者）

| 平台 | 方式 |
|---|---|
| **桌面版（推荐）** | 打开应用 → 输入框工具栏点「🌐 浏览器」→ ChromePanel 点「检查浏览器状态」→「一键安装扩展」（扩展随安装包分发，无需手动） |
| **Windows** | 双击项目根目录 `load-extension.bat` |
| **Mac / Linux** | `chmod +x load-extension.sh` 后双击（或 `./load-extension.sh`） |

> 一键加载用你的**默认 Chrome 登录配置**启动（保留已登录网站的登录态）；`--load-extension` 需 Chrome 冷启动，若 Chrome 已运行脚本会提示先关闭。

### 连接步骤

1. 加载扩展后，点 Chrome 工具栏的 **NexusAI in Chrome** 图标（拼图图标里找）→ popup 点「连接」一次（无需 sessionId）
2. 扩展全局连接后端（`/ws/browser`），**一个连接服务所有会话**
3. 打开 NexusAI 任意会话即可让 AI 操作浏览器（后端按 sessionId 为每个会话分配自己的标签页）

### 使用示例

在 NexusAI 对话里直接说：

- "帮我打开百度，搜索 iPhone 17 价格"
- "在这个页面上找到'加入购物车'按钮并点击"
- "把当前页面文字总结一下"
- "截图当前页面给我看"

### 原理

```
你说 → AI 判断需要操作浏览器 → 调 mcp__nexusai-in-chrome__* 工具
→ 后端 → WebSocket(/ws/browser) → 你的 Chrome 扩展 → 真实操作浏览器
→ 结果回传 → AI 总结回复
```

扩展代码在 `extension/`（manifest v3 + background/content/popup），对接协议见后端 `待前端对接.md` §46。

## 许可证

ISC

## 相关链接

- [设计稿](../codex-tauri-fusion-v10.html)
- [设计规范](./design-spec.md)
- [Vite 文档](https://vitejs.dev/)
- [React 文档](https://react.dev/)
- [Tailwind CSS 文档](https://tailwindcss.com/)
