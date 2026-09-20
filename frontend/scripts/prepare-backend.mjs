#!/usr/bin/env node
/**
 * 打包后端产物并裁剪 JRE，供 Tauri 桌面端随应用分发。
 *
 * 职责链路：
 *   mvn clean package（backend）→ 校验 jar 内迁移版本号唯一 → 用 JDK25 jdeps 求 jar 模块
 *   + 拼 Spring 补丁模块集 → jlink 裁 JRE → jar + jre 拷入 front/src-tauri/backend/
 *
 * 用法：
 *   JAVA_HOME=/path/to/jdk25 node scripts/prepare-backend.mjs
 *
 * 路径约定（本脚本位于 front/scripts/，front/package.json 已 "type":"module"）：
 *   backend = <仓库根>/backend          —— resolve(__dirname, '../..', 'backend')
 *   out     = <front>/src-tauri/backend —— resolve(__dirname, '../src-tauri/backend')
 *     （out 对应 tauri.conf.json bundle.resources 的 "backend" 条目）
 */
import { execSync } from 'node:child_process'
import { cpSync, existsSync, readdirSync, rmSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// ESM 下定位本文件目录（不能用 CommonJS 的 __dirname）
const __dirname = dirname(fileURLToPath(import.meta.url))

const backend = resolve(__dirname, '../..', 'backend')
const out = resolve(__dirname, '../src-tauri/backend')
const JAR_REL = join('target', 'nexusai-backend.jar') // backend-relative，勿用 resolve（会锚定 cwd）

/** Spring Boot 运行必需、但 jdeps 可能漏报/裁剪过度的补丁模块集（与基线并集去重排序） */
const PATCH_MODULES = [
  'java.naming',
  'java.management',
  'java.instrument',
  'java.security.jgss',
  'jdk.unsupported',
  'java.sql',
  'java.xml',
  'java.net.http',
  'java.rmi',
  'jdk.crypto.ec',
  'jdk.zipfs',
  'jdk.localedata',
]

/** 取 JDK bin 下工具全路径（Windows 带 .exe，Unix 无后缀） */
function jdkBinTool(javaHome, name) {
  const exe = process.platform === 'win32' ? `${name}.exe` : name
  return resolve(javaHome, 'bin', exe)
}

/** 递归统计目录字节大小 */
function dirSize(dir) {
  let total = 0
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name)
    total += entry.isDirectory() ? dirSize(p) : statSync(p).size
  }
  return total
}

/** 输出 out 目录产物与 jre/bin 体积 */
function printSummary(jreDir, jarPath) {
  const jarSizeMB = (statSync(jarPath).size / 1024 / 1024).toFixed(1)
  const binSizeMB = (dirSize(join(jreDir, 'bin')) / 1024 / 1024).toFixed(1)
  console.log(`[prepare-backend] 完成 → out: ${out}`)
  console.log(`[prepare-backend] nexusai-backend.jar ${jarSizeMB} MB；jre/bin 约 ${binSizeMB} MB（jre 整体见上）`)
}

/** jar 内迁移目录（spring-boot repackage 把 target/classes 放到 BOOT-INF/classes 下） */
const MIGRATION_DIR_IN_JAR = 'BOOT-INF/classes/db/migration/'

/**
 * 从 jar 条目名取归一化后的迁移版本号；不是迁移 .sql 返回 null。
 *
 * 归一化对齐 Flyway 的 MigrationVersion：版本段里 `_` 与 `.` 等价，纯数字段的前导零无效
 * （V07 与 V7 会被 Flyway 当成同一条 ⇒ 必须判为撞号）。Flyway 默认后缀是小写 `.sql`，
 * 故只认小写 —— 大写 .SQL 会被 Flyway 忽略，计入反而会假报。
 * 版本段里出现非数字段的怪异命名（本仓不存在）会被这里跳过，属已知边界。
 */
function migrationVersionOf(entry) {
  if (!entry.startsWith(MIGRATION_DIR_IN_JAR)) return null
  const base = entry.slice(MIGRATION_DIR_IN_JAR.length)
  const m = /^V([0-9][0-9._]*)__(.*)\.sql$/.exec(base)
  if (!m) return null
  return m[1]
    .split(/[._]/)
    .filter((s) => s !== '')
    .map((s) => String(parseInt(s, 10)))
    .join('.')
}

/**
 * 出包门禁：jar 内同一版本号不得对应多条迁移，否则中止构建（非 0 退出）并点名文件。
 *
 * WHY 必须在出包前硬拦：Flyway 在 validate 阶段扫迁移目录，撞号直接抛
 * `Found more than one migration with version X` ⇒ flywayInitializer bean 建不出来
 * ⇒ Spring 上下文起不来 ⇒ 用户端表现为「app 打不开 / 后端 60 秒超时」（0.1.15 实况）。
 * 这个错误在构建期拦截成本为零，漏到用户机器上则是应用完全不可用。
 *
 * 判据落在 jar 而非 target/classes：jar 才是真正被分发、被 Flyway 扫描的产物。
 * 只认 .sql：同名 .sql.conf 是 Flyway 的迁移级配置文件（本仓仅 V14 一例），不是第二条迁移，
 * 计入会对每个带 .conf 的版本假报重复。
 */
export function assertUniqueMigrationVersions(jarPath, jarTool) {
  const listing = execSync(`"${jarTool}" --list --file "${jarPath}"`, { encoding: 'utf8' })
  const byVersion = new Map()
  for (const line of listing.split(/\r?\n/)) {
    const entry = line.trim()
    const version = migrationVersionOf(entry)
    if (version === null) continue
    if (!byVersion.has(version)) byVersion.set(version, [])
    byVersion.get(version).push(entry.slice(MIGRATION_DIR_IN_JAR.length))
  }
  const conflicts = [...byVersion.entries()].filter(([, files]) => files.length > 1)
  if (conflicts.length === 0) {
    console.log(
      `[prepare-backend] 迁移版本号唯一性 OK：${byVersion.size} 个版本、${[...byVersion.values()].flat().length} 个文件，无撞号`
    )
    return
  }
  console.error('')
  console.error('[prepare-backend] 出包中止：jar 内存在重复的 Flyway 迁移版本号。')
  console.error('  Flyway 会在 validate 阶段抛 "Found more than one migration with version X"，')
  console.error('  导致后端起不来（用户端表现为 app 打不开）。')
  for (const [version, files] of conflicts) {
    console.error(`  版本 ${version} 出现 ${files.length} 次：`)
    for (const f of files) console.error(`    ${MIGRATION_DIR_IN_JAR}${f}`)
  }
  console.error('')
  console.error(`  jar: ${jarPath}`)
  console.error('  常见成因：迁移改名后旧文件残留在 backend/target/classes/db/migration/')
  console.error('  （mvn 不带 clean 时 maven-resources-plugin 不会删旧资源），随后被打进 jar。')
  console.error('  处置：确认 mvn 走的是 clean package；必要时手工删除 target/classes 下的陈旧迁移。')
  process.exit(1)
}

function main() {
  // 1) 打包后端 jar
  // 必须带 clean：maven-resources-plugin 只拷贝、不删除，已改名/已删除的资源会永久留在
  // target/classes 并被 spring-boot repackage 卷进 jar。0.1.15 事故即由此而来 —— 09-18 21:31
  // 把 coordinator 迁移从 V75 改名成 V76，旧文件一直躺在 target/classes，出包后 jar 里有两个
  // V75，Flyway 在 validate 阶段抛 "Found more than one migration with version 75"，
  // flywayInitializer bean 建不出来 ⇒ Spring 上下文起不来 ⇒ 用户端 app 打不开（后端 60 秒超时）。
  // 同类还有改名/删除过的 .class，clean 一并覆盖；不做增量优化是因为「不产生陈旧产物」这条
  // 无法用「少编一点」换回来。
  console.log('[prepare-backend] [1/5] mvn clean package（backend，跳过测试）…')
  execSync('mvn -DskipTests clean package', { cwd: backend, stdio: 'inherit' })

  // 2) JDK 定位与校验（需完整 JDK 25：jlink/jdeps/java/jar 齐全）
  const javaHome = process.env.JAVA_HOME
  if (!javaHome) {
    console.error('[prepare-backend] 未检测到 JAVA_HOME 环境变量。')
    console.error('  请设置 JAVA_HOME 指向 JDK 25，例如：')
    console.error('    Windows: set JAVA_HOME=C:\\Program Files\\Java\\jdk-25.0.3')
    console.error('    macOS/Linux: export JAVA_HOME=/path/to/jdk-25')
    process.exit(1)
  }
  const tools = {
    jlink: jdkBinTool(javaHome, 'jlink'),
    java: jdkBinTool(javaHome, 'java'),
    jdeps: jdkBinTool(javaHome, 'jdeps'),
    jar: jdkBinTool(javaHome, 'jar'), // 出包门禁列 jar 条目用（见 assertUniqueMigrationVersions）
  }
  for (const [label, p] of Object.entries(tools)) {
    if (!existsSync(p)) {
      console.error(`[prepare-backend] 缺少 JDK 工具 ${label}: ${p}`)
      console.error('  请设置 JAVA_HOME 指向完整 JDK 25（含 jlink/jdeps/jar），而非仅 JRE。')
      process.exit(1)
    }
  }
  console.log(`[prepare-backend] [2/5] 使用 JAVA_HOME: ${javaHome}`)

  const jarPath = resolve(backend, JAR_REL)
  if (!existsSync(jarPath)) {
    console.error(`[prepare-backend] 未找到打包产物: ${jarPath}`)
    console.error('  请确认 mvn package 成功且 finalName=nexusai-backend。')
    process.exit(1)
  }

  // 3) 出包门禁：jar 内迁移版本号必须唯一（否则 Flyway validate 就拒，用户端 app 起不来）
  console.log(`[prepare-backend] [3/5] 校验迁移版本号唯一性: ${jarPath}`)
  assertUniqueMigrationVersions(jarPath, tools.jar)

  // 4) jdeps 求基线模块，与补丁集并集去重排序 → --add-modules 串
  console.log(`[prepare-backend] [4/5] jdeps 分析模块依赖: ${jarPath}`)
  const raw = execSync(`"${tools.jdeps}" --print-module-deps --ignore-missing-deps "${jarPath}"`, {
    encoding: 'utf8',
  }).trim()
  const baseModules = raw ? raw.split(',').map((s) => s.trim()).filter(Boolean) : []
  const finalModules = [...new Set([...baseModules, ...PATCH_MODULES])].sort()
  const addModules = finalModules.join(',')
  console.log(`[prepare-backend] jdeps 基线模块 ${baseModules.length} 个；补丁 ${PATCH_MODULES.length} 个；`)
  console.log(`[prepare-backend] 最终 --add-modules 共 ${finalModules.length} 个，关键含:`)
  console.log(`    ${PATCH_MODULES.join(', ')}`)
  console.log(`    （全量模块串已写入 jlink 参数，共 ${addModules.length} 字符）`)

  // 5) jlink 裁 JRE → out/jre；jar 拷入 out
  const jreDir = resolve(out, 'jre')
  // 清旧产物，防 jlink 「目录已存在」；上次打包留下的 jre 被 .gitignore 忽略但仍在磁盘，
  // 若不删则重复 tauri build 时 jlink 必然失败
  rmSync(jreDir, { recursive: true, force: true })
  console.log(`[prepare-backend] [5/5] jlink 裁剪 JRE → ${jreDir}`)
  execSync(
    `"${tools.jlink}" --add-modules ${addModules} --output "${jreDir}" --strip-debug --no-man-pages --no-header-files --compress=zip-6`,
    { stdio: 'inherit' }
  )
  cpSync(jarPath, resolve(out, 'nexusai-backend.jar'))
  printSummary(jreDir, jarPath)
}

// 直接执行才运行主流程；被 import 时仅导出/自检（不触发打包，避免误烧机）
// Windows 路径大小写不敏感 → 比较前统一小写，避免 d:/ 与 D:/ 差异导致误判
const invokedDirectly = (() => {
  if (!process.argv[1]) return false
  const scriptPath = resolve(process.argv[1])
  const selfPath = fileURLToPath(import.meta.url)
  return process.platform === 'win32'
    ? scriptPath.toLowerCase() === selfPath.toLowerCase()
    : scriptPath === selfPath
})()
if (invokedDirectly) {
  main()
}
