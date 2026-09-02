/**
 * Mock data + small pure helpers shared across the app.
 * Keeping all seed data in one file makes future replacement (e.g. real API) easier.
 */

import type {
  DatabaseConnection,
  DiffFile,
  MCPServer,
  ModelInfo,
  ModelTag,
  Project,
  Provider,
  Schedule,
  SearchItem,
  Session,
  SessionContext,
  SessionFile,
  Skill,
  TabInfo,
  TrackItem,
} from './types'

export const allProjects: Project[] = [
  { name: 'javaclawbot', branch: 'main', dirty: 3, agents: 2, path: '/usr/local/code' },
  { name: 'shared-utils', branch: 'main', dirty: 0, agents: 0, path: '~/projects' },
  { name: 'api-client', branch: 'dev', dirty: 1, agents: 1, path: '~/projects' },
  { name: 'nexusai-ui', branch: 'feat/ui', dirty: 5, agents: 0, path: '~/Desktop' },
  { name: 'erp-report', branch: 'main', dirty: 0, agents: 0, path: '~/scripts' },
  { name: 'side-proj', branch: 'dev', dirty: 2, agents: 0, path: '~/code' },
]

export const recentProjects: Project[] = [{ name: 'erp-report', branch: 'main', dirty: 0, agents: 0, path: '~/scripts' }]

export const mockDiffs: Record<string, DiffFile> = {
  'MessageBus.java': {
    name: 'MessageBus.java',
    path: 'src/main/java/bus/MessageBus.java',
    adds: 13,
    dels: 1,
    hunks: [
      {
        oldStart: 24,
        newStart: 24,
        lines: [
          { type: 'ctx', oldNum: 24, newNum: 24, text: 'public class MessageBus {' },
          {
            type: 'ctx',
            oldNum: 25,
            newNum: 25,
            text: '    private static final Logger log = LoggerFactory.getLogger(MessageBus.class);',
          },
          {
            type: 'del',
            oldNum: 26,
            text: '    private final BlockingQueue<OutboundMessage> outbound = new LinkedBlockingQueue<>();',
          },
          {
            type: 'add',
            newNum: 26,
            text: '    private final ConcurrentMap<String, BlockingQueue<OutboundMessage>> outboundQueues',
          },
          { type: 'add', newNum: 27, text: '        = new ConcurrentHashMap<>();' },
          {
            type: 'add',
            newNum: 28,
            text: '    private final ConcurrentMap<String, List<Consumer<OutboundMessage>>> subscribers',
          },
          { type: 'add', newNum: 29, text: '        = new ConcurrentHashMap<>();' },
          { type: 'ctx', oldNum: 27, newNum: 30, text: '' },
          { type: 'ctx', oldNum: 28, newNum: 31, text: '    public void publishOutbound(OutboundMessage msg) {' },
          { type: 'add', newNum: 32, text: '        String key = msg.getChannel() + ":" + msg.getChatId();' },
          {
            type: 'add',
            newNum: 33,
            text: '        outboundQueues.computeIfAbsent(key, k -> new LinkedBlockingQueue<>()).offer(msg);',
          },
          {
            type: 'add',
            newNum: 34,
            text: '        subscribers.getOrDefault(key, List.of()).forEach(fn -> fn.accept(msg));',
          },
          { type: 'ctx', oldNum: 29, newNum: 35, text: '    }' },
          { type: 'ctx', oldNum: 30, newNum: 36, text: '}' },
        ],
      },
    ],
  },
  'BackendBridge.java': {
    name: 'BackendBridge.java',
    path: 'src/main/java/bridge/BackendBridge.java',
    adds: 8,
    dels: 4,
    hunks: [
      {
        oldStart: 41,
        newStart: 41,
        lines: [
          { type: 'ctx', oldNum: 41, newNum: 41, text: 'public class BackendBridge {' },
          { type: 'del', oldNum: 42, text: '    private WsBroadcaster broadcaster;' },
          { type: 'del', oldNum: 43, text: '    private boolean connected = false;' },
          { type: 'add', newNum: 42, text: '    private final WsBroadcaster broadcaster;' },
          { type: 'add', newNum: 43, text: '    private volatile boolean connected = false;' },
          { type: 'add', newNum: 44, text: '    private final Object connectLock = new Object();' },
          { type: 'ctx', oldNum: 44, newNum: 45, text: '    public BackendBridge(MessageBus bus) {' },
          { type: 'del', oldNum: 45, text: '        this.broadcaster = new WsBroadcaster(bus);' },
          { type: 'add', newNum: 46, text: '        this.broadcaster = new WsBroadcaster(bus, connectLock);' },
          { type: 'ctx', oldNum: 46, newNum: 47, text: '    }' },
        ],
      },
    ],
  },
  'SessionSubscriptionManager.java': {
    name: 'SessionSubscriptionManager.java',
    path: 'src/main/java/bus/SessionSubscriptionManager.java',
    adds: 52,
    dels: 0,
    isNew: true,
    hunks: [
      {
        oldStart: 0,
        newStart: 1,
        lines: [
          { type: 'add', newNum: 1, text: 'package bus;' },
          { type: 'add', newNum: 2, text: '' },
          { type: 'add', newNum: 3, text: 'import java.util.List;' },
          { type: 'add', newNum: 4, text: 'import java.util.concurrent.ConcurrentHashMap;' },
          { type: 'add', newNum: 5, text: 'import java.util.concurrent.ConcurrentMap;' },
          { type: 'add', newNum: 6, text: 'import java.util.function.Consumer;' },
          { type: 'add', newNum: 7, text: '' },
          { type: 'add', newNum: 8, text: '/**' },
          { type: 'add', newNum: 9, text: ' * Manages per-session fan-out subscriptions.' },
          { type: 'add', newNum: 10, text: ' */' },
          { type: 'add', newNum: 11, text: 'public class SessionSubscriptionManager {' },
          { type: 'add', newNum: 12, text: '    private final ConcurrentMap<String, List<Consumer<OutboundMessage>>> subs' },
          { type: 'add', newNum: 13, text: '        = new ConcurrentHashMap<>();' },
          { type: 'add', newNum: 14, text: '' },
          {
            type: 'add',
            newNum: 15,
            text: '    public void subscribe(String sessionKey, Consumer<OutboundMessage> handler) {',
          },
          {
            type: 'add',
            newNum: 16,
            text: '        subs.computeIfAbsent(sessionKey, k -> new CopyOnWriteArrayList<>()).add(handler);',
          },
          { type: 'add', newNum: 17, text: '    }' },
          { type: 'add', newNum: 18, text: '' },
          { type: 'add', newNum: 19, text: '    public void unsubscribe(String sessionKey) {' },
          { type: 'add', newNum: 20, text: '        subs.remove(sessionKey);' },
          { type: 'add', newNum: 21, text: '    }' },
          { type: 'add', newNum: 22, text: '}' },
        ],
      },
    ],
  },
}

export const models: ModelInfo[] = [
  { id: 'ds-3.2',  name: 'DeepSeek-V3.2',     alias: 'DS-V3.2',  tag: 'DS', desc: '通用场景，平衡速度与质量',  type: 'chat',  maxTokens: 65536, temperature: -1, topP: null, contextWindow: 512000, think: '', enabled: true },
  { id: 'cl-3.5',  name: 'Claude-3.5-Sonnet', alias: 'CL-3.5',   tag: 'CL', desc: '代码与推理最强',           type: 'chat',  maxTokens: 65536, temperature: -1, topP: null, contextWindow: 512000, think: '', enabled: true },
  { id: 'gp-4o',   name: 'GPT-4o',            alias: 'GP-4o',    tag: 'GP', desc: '多模态理解',               type: 'multimodal', maxTokens: 65536, temperature: -1, topP: null, contextWindow: 512000, think: '', enabled: true },
  { id: 'qw-2.5',  name: 'Qwen-2.5-72B',      alias: 'QW-2.5',   tag: 'QW', desc: '中文场景优选',             type: 'chat',  maxTokens: 65536, temperature: -1, topP: null, contextWindow: 512000, think: '', enabled: true },
]

/* ------------------------------------------------------------------ */
/*  P2 — Providers / Skills / MCP / Database / Schedules              */
/* ------------------------------------------------------------------ */

/** Build a small diff for a file when there isn't a hand-curated one.
 *  Used so every file in any session can open a diff view, even if
 *  we didn't author the full hunk-by-hunk content. */
function makeSimpleDiff(name: string, path: string, adds: number, dels: number, isNew = false): DiffFile {
  const lines: { type: 'ctx' | 'add' | 'del'; oldNum?: number; newNum?: number; text: string }[] = []
  if (isNew) {
    let n = 1
    for (let i = 0; i < adds; i++) {
      lines.push({ type: 'add', newNum: n++, text: `// line ${n} in new file ${name}` })
    }
  } else {
    let o = 1
    let n = 1
    for (let i = 0; i < dels; i++) {
      lines.push({ type: 'del', oldNum: o++, text: `// removed line ${o} from ${name}` })
    }
    lines.push({ type: 'ctx', oldNum: o, newNum: n, text: `// unchanged context line in ${name}` })
    for (let i = 0; i < adds; i++) {
      lines.push({ type: 'add', newNum: n++, text: `// added line ${n} to ${name}` })
    }
  }
  return {
    name,
    path,
    adds: Math.max(adds, 1),
    dels: Math.max(dels, 0),
    isNew,
    hunks: [{ oldStart: 1, newStart: 1, lines }],
  }
}

/** Look up a diff by file name. Falls back to a synthesized one so any
 *  session file can still open a diff view (better UX than empty modal). */
export function getDiffFor(name: string): DiffFile {
  return (
    mockDiffs[name] ??
    makeSimpleDiff(name, name, 3, 1, false)
  )
}

/* ------------------------------------------------------------------ */
/*  P2 — Providers / Skills / MCP / Database / Schedules              */
/* ------------------------------------------------------------------ */

export const providers: Provider[] = [
  {
    id: 'p-deepseek',
    name: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com/v1',
    apiKeyMasked: 'sk-ds****a4b2',
    enabled: true,
    models: [
      { id: 'ds-3.2', name: 'DeepSeek-V3.2', alias: 'DS-V3.2', tag: 'DS', desc: '通用场景，平衡速度与质量', type: 'chat', maxTokens: 65536, temperature: -1, topP: null, contextWindow: 512000, think: '', enabled: true },
      { id: 'ds-r1',  name: 'DeepSeek-R1',   alias: 'DS-R1',   tag: 'DS', desc: '推理增强版',               type: 'chat', maxTokens: 65536, temperature: -1, topP: null, contextWindow: 512000, think: '{"reasoning": true}', enabled: true },
    ],
  },
  {
    id: 'p-anthropic',
    name: 'Anthropic',
    baseUrl: 'https://api.anthropic.com',
    apiKeyMasked: 'sk-ant****c901',
    enabled: true,
    models: [
      { id: 'cl-3.5',      name: 'Claude-3.5-Sonnet', alias: 'CL-3.5',    tag: 'CL', desc: '代码与推理最强', type: 'chat', maxTokens: 65536, temperature: -1, topP: null, contextWindow: 512000, think: '', enabled: true },
      { id: 'cl-3-haiku',  name: 'Claude-3-Haiku',    alias: 'CL-Haiku',  tag: 'CL', desc: '快速低成本',     type: 'chat', maxTokens: 65536, temperature: -1, topP: null, contextWindow: 512000, think: '', enabled: true },
    ],
  },
  {
    id: 'p-openai',
    name: 'OpenAI',
    baseUrl: 'https://api.openai.com/v1',
    apiKeyMasked: 'sk-pr****f7d2',
    enabled: false,
    models: [
      { id: 'gp-4o',      name: 'GPT-4o',      alias: 'GP-4o',     tag: 'GP', desc: '多模态理解', type: 'multimodal', maxTokens: 65536, temperature: -1, topP: null, contextWindow: 512000, think: '', enabled: true },
      { id: 'gp-4o-mini', name: 'GPT-4o-mini', alias: 'GP-4o-mini', tag: 'GP', desc: '轻量快速',   type: 'chat',       maxTokens: 65536, temperature: -1, topP: null, contextWindow: 512000, think: '', enabled: true },
    ],
  },
  {
    id: 'p-qwen',
    name: '通义千问',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    apiKeyMasked: 'sk-qw****88e1',
    enabled: true,
    models: [
      { id: 'qw-2.5',   name: 'Qwen-2.5-72B',       alias: 'QW-2.5',  tag: 'QW', desc: '中文场景优选', type: 'chat',     maxTokens: 65536, temperature: -1, topP: null, contextWindow: 512000, think: '', enabled: true },
      { id: 'qw-coder', name: 'Qwen-2.5-Coder-32B', alias: 'QW-Coder', tag: 'QW', desc: '代码专用',     type: 'chat',     maxTokens: 65536, temperature: -1, topP: null, contextWindow: 512000, think: '', enabled: true },
    ],
  },
]

export const skills: Skill[] = [
  { id: 's-web',    name: 'Web 搜索',     description: '调用 Bing/Google 搜索实时信息', enabled: true,  builtin: true  },
  { id: 's-exec',   name: '代码执行',     description: '在隔离沙盒里运行 Python / Node', enabled: true,  builtin: true  },
  { id: 's-fs',     name: '文件管理',     description: '读写本地项目文件',             enabled: true,  builtin: true  },
  { id: 's-git',    name: 'Git 操作',     description: 'commit / diff / branch',         enabled: true,  builtin: true  },
  { id: 's-img',    name: '图像生成',     description: '通过 DALL·E / SD 生成图像',     enabled: false, builtin: false },
  { id: 's-web-rw', name: '网页抓取',     description: '抓取并解析网页正文',           enabled: true,  builtin: false },
]

export const mcpServers: MCPServer[] = [
  {
    id: 'm-fs',
    name: 'filesystem',
    command: 'npx',
    args: '-y @modelcontextprotocol/server-filesystem /Users/zhengwei/.javaclawbot',
    envSummary: '无环境变量',
    status: 'running',
    enabled: true,
  },
  {
    id: 'm-gh',
    name: 'github',
    command: 'npx',
    args: '-y @modelcontextprotocol/server-github',
    envSummary: 'GITHUB_TOKEN=*** (1 var)',
    status: 'running',
    enabled: true,
  },
  {
    id: 'm-pg',
    name: 'postgres',
    command: 'npx',
    args: '-y @modelcontextprotocol/server-postgres postgresql://localhost:5432/dev',
    envSummary: 'PG_URL=*** (1 var)',
    status: 'stopped',
    enabled: false,
  },
]

export const databases: DatabaseConnection[] = [
  { id: 'd-pg-dev',   name: '本地开发库', type: 'postgres', host: '127.0.0.1', port: 5432, database: 'dev',   user: 'dev',   status: 'connected' },
  { id: 'd-my-stg',   name: 'MySQL 预发', type: 'mysql',    host: '10.0.1.20', port: 3306, database: 'staging', user: 'app', status: 'disconnected' },
  { id: 'd-sq-local', name: 'SQLite 本地', type: 'sqlite',  host: '—',        port: 0,    database: 'cache.db', user: '—',  status: 'connected' },
]

export const schedules: Schedule[] = [
  { id: 'sc-nightly',  name: '每晚全量备份', cron: '0 2 * * *',   command: 'scripts/backup.sh',          description: '备份所有项目到 NAS',     enabled: true,  lastRun: '7 小时前', nextRun: '明天 02:00' },
  { id: 'sc-hourly',   name: '每小时索引同步', cron: '0 * * * *',   command: 'scripts/sync-index.py',     description: '同步代码索引到 ES',     enabled: true,  lastRun: '12 分前',   nextRun: '下小时 :00' },
  { id: 'sc-weekly',   name: '周报生成',     cron: '0 9 * * 1',   command: 'scripts/weekly-report.ts',  description: '生成团队周报 PDF',       enabled: false, lastRun: '3 天前',   nextRun: '下周一 09:00' },
]

/**
 * Tab header info keyed by session id.
 * (In the original App.tsx this was `tabInfoMap`.)
 */
export const tabInfoMap: Record<string, TabInfo> = {
  'sess-msgbus': { subtitle: 'Java 重构 · MessageBus fan-out', icon: '⚡' },
  'sess-npe': { subtitle: 'Bug 分析 · WebView NPE 堆栈', icon: '🔍' },
  'sess-pg': { subtitle: '数据库设计 · PostgreSQL schema', icon: '🗄' },
  'sess-p1': { subtitle: '回归测试 · Phase 1 全流程', icon: '✓' },
  'sess-unittest': { subtitle: '单元测试 · MessageBus 测试覆盖', icon: '🧪' },
  'sess-memleak': { subtitle: '性能分析 · 内存泄漏排查', icon: '◐' },
  'sess-perf': { subtitle: '性能优化 · tauri-bridge 瓶颈', icon: '◎' },
  'sess-refactor': { subtitle: '重构方案 · BackendBridge 拆分', icon: '⌘' },
  'sess-ws': { subtitle: '协议设计 · WebSocket 通信', icon: '⇄' },
  'sess-pr': { subtitle: 'Code Review · GUI 重构 PR', icon: '◉' },
}

export const sessionData: Session[] = [
  {
    id: 'sess-msgbus',
    model: 'DS',
    modelName: 'DeepSeek-V3.2',
    title: '把 MessageBus 改成 fan-out',
    time: '现在',
    group: 'current',
    tabId: 'tab-msgbus',
  },
  {
    id: 'sess-npe',
    model: 'CL',
    modelName: 'Claude-3.5-Sonnet',
    title: '分析 WebView 启动 NPE 堆栈',
    time: '2 分钟',
    group: 'current',
  },
  {
    id: 'sess-pg',
    model: 'GP',
    modelName: 'GPT-4o',
    title: '设计一个 PostgreSQL schema',
    time: '12 分钟',
    group: 'current',
    tabId: 'tab-postgres',
  },
  { id: 'sess-p1', model: 'DS', modelName: 'DeepSeek-V3.2', title: '跑通 Phase 1 回归测试', time: '1 小时', group: 'today' },
  { id: 'sess-unittest', model: 'DS', modelName: 'DeepSeek-V3.2', title: '写 MessageBus 单元测试', time: '2 小时', group: 'today' },
  { id: 'sess-memleak', model: 'CL', modelName: 'Claude-3.5-Sonnet', title: '检查 memory leak', time: '3 小时', group: 'today' },
  {
    id: 'sess-perf',
    model: 'DS',
    modelName: 'DeepSeek-V3.2',
    title: '分析 tauri-bridge 性能',
    time: '昨天',
    group: 'yesterday',
    tabId: 'tab-tauri',
  },
  { id: 'sess-refactor', model: 'GP', modelName: 'GPT-4o', title: '拆分 BackendBridge 重构', time: '昨天', group: 'yesterday' },
  { id: 'sess-ws', model: 'DS', modelName: 'DeepSeek-V3.2', title: '设计 WebSocket 协议', time: '周二', group: 'week' },
  { id: 'sess-pr', model: 'CL', modelName: 'Claude-3.5-Sonnet', title: 'review GUI 重构 PR', time: '周一', group: 'week' },
]

export const searchItems: SearchItem[] = [
  ...sessionData.map<SearchItem>((s) => ({
    type: 'session',
    id: s.id,
    title: s.title,
    sub: s.time,
    tag: s.model,
  })),
  ...allProjects.map<SearchItem>((p) => ({
    type: 'project',
    id: p.name,
    title: p.name,
    sub: p.path,
    tag: p.agents > 0 ? 'AG' : 'PR',
  })),
  { type: 'file', id: 'MessageBus.java', title: 'MessageBus.java', sub: 'src/main/java/bus/', tag: 'JS' },
  {
    type: 'file',
    id: 'BackendBridge.java',
    title: 'BackendBridge.java',
    sub: 'src/main/java/bridge/',
    tag: 'JV',
  },
  { type: 'command', id: 'switch-model', title: '切换模型', sub: 'DeepSeek / Claude / GPT / Qwen', tag: '⌘' },
  { type: 'command', id: 'bind-project', title: '绑定项目', sub: '打开项目绑定面板', tag: '⌘' },
  { type: 'command', id: 'open-settings', title: '设置', sub: '打开偏好设置', tag: '⌘' },
]

export const trackItems: { dot: 'running' | 'ok' | 'warn'; name: string; time: string }[] = [
  { dot: 'running', name: '分析 MessageBus', time: '进行中' },
  { dot: 'ok', name: '查找 NPE 堆栈', time: '3 分前' },
  { dot: 'ok', name: '设计 fan-out 协议', time: '8 分前' },
  { dot: 'warn', name: '检查 concurrent safety', time: '12 分前' },
  { dot: 'ok', name: '对比 Lock vs CAS', time: '15 分前' },
  { dot: 'ok', name: '枚举 sessionKey 模式', time: '20 分前' },
  { dot: 'running', name: '评估内存占用', time: '进行中' },
]

/* ---------- pure helpers (replace inline condition chains) ---------- */

/** Map a model tag like "DS" to the CSS class suffix (e.g. "ds"). */
export const tagToClass = (tag: string): string => {
  switch (tag) {
    case 'DS':
      return 'ds'
    case 'CL':
      return 'cl'
    case 'GP':
      return 'gp'
    case 'QW':
      return 'qw'
    default:
      return 'ds'
  }
}

/** Derive the short tag (DS/CL/GP/QW) from a full model name. */
export const modelNameToTag = (name: string): ModelTag => {
  if (name.startsWith('DeepSeek')) return 'DS'
  if (name.startsWith('Claude')) return 'CL'
  if (name.startsWith('GPT')) return 'GP'
  return 'QW'
}

/* ------------------------------------------------------------------ */
/*  Per-session context (chat history + right panel data per session) */
/* ------------------------------------------------------------------ */

const F: SessionFile[] = [
  { name: 'MessageBus.java', path: 'src/main/java/bus/MessageBus.java', adds: 13, dels: 1 },
  { name: 'BackendBridge.java', path: 'src/main/java/bridge/BackendBridge.java', adds: 8, dels: 4 },
  { name: 'SessionSubscriptionManager.java', path: 'src/main/java/bus/', adds: 52, dels: 0, isNew: true },
]
const T: TrackItem[] = [
  { dot: 'running', name: '分析 MessageBus', time: '进行中' },
  { dot: 'ok', name: '查找 NPE 堆栈', time: '3 分前' },
  { dot: 'ok', name: '设计 fan-out 协议', time: '8 分前' },
  { dot: 'warn', name: '检查 concurrent safety', time: '12 分前' },
]

const EMPTY_CONTEXT: SessionContext = {
  files: [],
  tracks: [],
  mainProject: allProjects[0],
  subProjects: [],
  messages: [],
}

export const sessionContexts: Record<string, SessionContext> = {
  'sess-msgbus': {
    files: F,
    tracks: T,
    mainProject: allProjects[0],
    subProjects: allProjects.slice(1, 3),
    messages: [
      { id: 'm1', role: 'user',      author: '你',     time: '14:32', content: '我想把 MessageBus 改成 fan-out 队列模型，但担心破坏现有 GUI 模式。能不能帮我做个兼容性方案？' },
      { id: 'm2', role: 'assistant', author: 'nexus', time: '14:32',
        reasoning: '用户问的是 Java 端 MessageBus 重构。当前 outbound 是单队列，要加 Tauri 客户端就必须让多个 consumer 同时拿到消息——fan-out 是正解。但要保证 3647 行的 AgentLoop 一行不动。',
        content: '可以。把 MessageBus 内部从"单公共队列"改成"每 sessionKey 一条独立队列 + 内部 fan-out"，外部 API 保持不变。',
        toolCard: { name: 'edit_file', status: '已完成 · 0.3 秒', body: 'path="bus/MessageBus.java"\noperation="draft_plan_only"' },
      },
      { id: 'm3', role: 'user',      author: '你',     time: '14:35', content: '先不写代码。让我看下改动后的内部结构？' },
      { id: 'm4', role: 'assistant', author: 'nexus', time: '14:35', content: '核心思路：把"广播"下沉到 MessageBus 内部。\n\npublic class MessageBus {\n    private final ConcurrentMap<String, BlockingQueue<OutboundMessage>> outboundQueues\n        = new ConcurrentHashMap<>();\n\n    public void publishOutbound(OutboundMessage msg) {\n        String key = msg.getChannel() + ":" + msg.getChatId();\n        outboundQueues.computeIfAbsent(key, k -> new LinkedBlockingQueue<>()).offer(msg);\n    }\n}', streaming: true },
    ],
  },
  'sess-npe': {
    files: [
      { name: 'WebViewActivity.kt', path: 'app/src/main/java/', adds: 4, dels: 2 },
      { name: 'BridgeClient.java', path: 'core/src/main/java/', adds: 6, dels: 0, isNew: true },
    ],
    tracks: [
      { dot: 'running', name: '定位 NPE 触发栈', time: '进行中' },
      { dot: 'ok', name: '查看 onCreate 调用链', time: '5 分前' },
      { dot: 'ok', name: '比对 working 版本', time: '12 分前' },
    ],
    mainProject: allProjects[3],
    subProjects: [allProjects[0]],
    messages: [
      { id: 'n1', role: 'user',      author: '你',     time: '10:15', content: 'WebView 启动时偶发 NPE，stack 里看到 BridgeClient 已经 uninit 但回调还在跑。这种情况怎么定位？' },
      { id: 'n2', role: 'assistant', author: 'nexus', time: '10:16',
        reasoning: '这是经典的 lifecycle 竞态。WebView 在 onDestroy 后还可能触发 onPageFinished，需要在 BridgeClient 持有 activity 的 weak ref。',
        content: '生命周期竞态。建议把 BridgeClient 改成弱引用持有 WebView，并在 onDestroy 里强制 unbind。你想看修复 patch 还是先复现？' },
      { id: 'n3', role: 'user',      author: '你',     time: '10:18', content: '先复现。我看 BridgeClient.java 第 89 行那段。' },
    ],
  },
  'sess-pg': {
    files: [
      { name: 'schema.sql', path: 'db/migrations/', adds: 47, dels: 0, isNew: true },
      { name: 'seed.sql', path: 'db/seed/', adds: 23, dels: 0, isNew: true },
    ],
    tracks: [
      { dot: 'running', name: '设计索引策略', time: '进行中' },
      { dot: 'ok', name: '列出核心表', time: '8 分前' },
    ],
    mainProject: allProjects[1],
    subProjects: [],
    messages: [
      { id: 'p1', role: 'user',      author: '你',     time: '昨天', content: '设计一个 PostgreSQL schema：用户、订单、商品、库存、评价。' },
      { id: 'p2', role: 'assistant', author: 'nexus', time: '昨天', content: '核心 5 张表 + 3 张关联。给个最小可用 schema：\n\nusers(id, email, name, created_at)\norders(id, user_id, total, status, created_at)\nproducts(id, sku, name, price)\norder_items(order_id, product_id, qty, price)\ninventory(product_id, warehouse_id, qty)\nreviews(id, user_id, product_id, rating, body)' },
    ],
  },
  'sess-p1': {
    files: [
      { name: 'Phase1IntegrationTest.java', path: 'src/test/java/integration/', adds: 0, dels: 0 },
    ],
    tracks: [
      { dot: 'ok', name: 'Phase 1 回归测试', time: '1 小时前 · 287 通过' },
    ],
    mainProject: allProjects[0],
    subProjects: [allProjects[2]],
    messages: [
      { id: 'p11', role: 'user',      author: '你',     time: '1 小时前', content: 'Phase 1 全流程跑一遍，看哪些挂了。' },
      { id: 'p12', role: 'assistant', author: 'nexus', time: '1 小时前', content: '跑完了，287 通过、0 失败、3 跳过。耗时 4 分 12 秒。可以提交。' },
    ],
  },
  'sess-unittest': {
    files: [
      { name: 'MessageBusTest.java', path: 'src/test/java/bus/', adds: 124, dels: 0, isNew: true },
    ],
    tracks: [
      { dot: 'running', name: '写 fan-out 用例', time: '进行中' },
    ],
    mainProject: allProjects[0],
    subProjects: [],
    messages: [
      { id: 'u1', role: 'user',      author: '你',     time: '2 小时前', content: '给新的 fan-out MessageBus 写单元测试。覆盖：单 consumer、多 consumer、慢 consumer、崩溃恢复。' },
      { id: 'u2', role: 'assistant', author: 'nexus', time: '2 小时前', content: '给个 124 行的 MessageBusTest.java 框架，4 个 @Nested 内部类覆盖你列的 4 个场景。要不要我加 JMH benchmark？' },
    ],
  },
  'sess-memleak': {
    files: [
      { name: 'WsBroadcaster.java', path: 'core/src/main/java/', adds: 11, dels: 8 },
    ],
    tracks: [
      { dot: 'warn', name: 'heap dump 异常', time: '3 小时前' },
    ],
    mainProject: allProjects[0],
    subProjects: [],
    messages: [
      { id: 'l1', role: 'user',      author: '你',     time: '3 小时前', content: '长跑 6 小时后堆涨到 1.8G。jmap 看到大量 WsBroadcaster 实例，每个持有一个 outbound queue。' },
      { id: 'l2', role: 'assistant', author: 'nexus', time: '3 小时前', content: '经典 Map 泄漏：sessionKey 在 WsBroadcaster 里 put 后没有 remove，session 断开时残留。修法：让 publishOutbound 用 try-with-resources 锁住 outbound queue，session 销毁时清空 map。' },
    ],
  },
  'sess-perf': {
    files: [
      { name: 'tauri-bridge.ts', path: 'src-bridge/src/', adds: 0, dels: 0 },
    ],
    tracks: [
      { dot: 'running', name: 'profile WebView 启动', time: '进行中' },
    ],
    mainProject: allProjects[3],
    subProjects: [allProjects[0]],
    messages: [
      { id: 'p21', role: 'user',      author: '你',     time: '昨天', content: 'tauri-bridge 在 WebView 首次启动时阻塞主线程 1.4s，profile 一下。' },
      { id: 'p22', role: 'assistant', author: 'nexus', time: '昨天', content: '主要时间在同步加载 Wasm 字节（820KB）。改成 lazy + 缓存 + OffscreenCanvas 解码，预计降到 280ms。' },
    ],
  },
  'sess-refactor': {
    files: [
      { name: 'BackendBridge.java', path: 'src/main/java/bridge/', adds: 0, dels: 142 },
      { name: 'MessageBridge.java', path: 'src/main/java/bridge/', adds: 96, dels: 0, isNew: true },
    ],
    tracks: [
      { dot: 'ok', name: '拆分规划', time: '昨天 · 评审通过' },
    ],
    mainProject: allProjects[0],
    subProjects: [],
    messages: [
      { id: 'r1', role: 'user',      author: '你',     time: '昨天', content: 'BackendBridge.java 已经 800 行了，拆成 MessageBridge / ConnectionBridge / HealthBridge 三个文件。' },
    ],
  },
  'sess-ws': {
    files: [
      { name: 'ws-protocol.md', path: 'docs/', adds: 47, dels: 0, isNew: true },
    ],
    tracks: [
      { dot: 'ok', name: 'draft v1', time: '周二' },
    ],
    mainProject: allProjects[0],
    subProjects: [],
    messages: [
      { id: 'w1', role: 'user',      author: '你',     time: '周二', content: 'WebSocket 子协议设计：消息分帧、心跳、重连。' },
    ],
  },
  'sess-pr': {
    files: [
      { name: 'App.tsx', path: 'src/', adds: 314, dels: 998 },
    ],
    tracks: [
      { dot: 'ok', name: 'review 完毕', time: '周一' },
    ],
    mainProject: allProjects[3],
    subProjects: [],
    messages: [
      { id: 'pr1', role: 'user',      author: '你',     time: '周一', content: 'GUI 重构 PR 看起来太大，拆成 3 个小 PR。' },
      { id: 'pr2', role: 'assistant', author: 'nexus', time: '周一', content: '建议拆分：1) Icon dict + globals.css tokens 2) 组件拆文件 3) reducer 集成。先后顺序 1→2→3，方便 review。' },
    ],
  },
}

/** Look up a session's context; returns a sensible empty default if missing. */
export function getSessionContext(id: string): SessionContext {
  return sessionContexts[id] ?? EMPTY_CONTEXT
}

/** A neutral default context for newly-created sessions (no main project yet). */
export const newSessionContext: SessionContext = {
  files: [],
  tracks: [],
  mainProject: allProjects[0],
  subProjects: [],
  messages: [
    { id: 'welcome', role: 'assistant', author: 'nexus', time: '现在',
      content: '新会话已创建。问我任何问题，或绑定一个项目开始。' },
  ],
}
