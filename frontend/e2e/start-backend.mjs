/**
 * e2e 独立后端启动器 · 由 playwright.config.ts 的 webServer 调用。
 *
 * 隔离纪律（用户裁定 / memory e2e-port-discipline）：
 *   - 端口 **3460**（⛔ 绝不 3458 日常 / 3000 工作 agent / 3518 场景）
 *   - 独立 SQLite 文件（D:/tmp/e2e-nexusai/data/nexusai.db）⇒ Flyway 迁移只作用于 e2e 库，**不碰用户真库**
 *   - -Duser.home 指向 D:/tmp/e2e-nexusai/home ⇒ 配置根 ~/.nexusai 也被隔离（不读写用户配置/项目）
 * 每次启动前清空上述两个目录，保证用例从干净库开始（迁移会重新执行）。
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = resolve(HERE, '..', '..')
const HOME = 'D:/tmp/e2e-nexusai/home'
const DATA = 'D:/tmp/e2e-nexusai/data'
const PROJECT = 'D:/tmp/e2e-nexusai/project'
const PORT = process.env.E2E_BACKEND_PORT || '3460'

// 兼容两种仓库布局：nexusai（backend/target/…）与镜像仓 claude-harness-java（根 target/…）
const jar = [
  resolve(REPO, 'backend', 'target', 'nexusai-backend.jar'),
  resolve(REPO, 'target', 'nexusai-backend.jar'),
].find(existsSync)
if (!jar) {
  console.error('[e2e] 未找到 nexusai-backend.jar：请先 `mvn -o package -DskipTests` 产出后端 jar')
  process.exit(1)
}
const javaBin = process.env.E2E_JAVA || 'C:/Program Files/Java/jdk-25.0.3/bin/java.exe'

for (const dir of [HOME, DATA, PROJECT]) {
  rmSync(dir, { recursive: true, force: true })
  mkdirSync(dir, { recursive: true })
}

const dbUrl = `jdbc:sqlite:${DATA}/nexusai.db?journal_mode=WAL&synchronous=NORMAL&foreign_keys=on&busy_timeout=5000`
const child = spawn(
  javaBin,
  [`-Duser.home=${HOME}`, '-jar', jar, `--server.port=${PORT}`, `--spring.datasource.url=${dbUrl}`],
  { stdio: 'inherit' },
)

child.on('exit', (code) => process.exit(code ?? 0))
process.on('SIGTERM', () => child.kill())
process.on('SIGINT', () => child.kill())
