package com.nexusai.application.agent.memory;

import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 记忆存储层（纯读取）· 对齐 CC memdir.ts buildMemoryPrompt() 的只读文件访问。
 *
 * <p><b>FIX-MC CRUD 死层删除</b>：CC <b>无程序化记忆写 API</b>——记忆文件由<b>模型</b>用
 * Write/Edit 工具维护（buildMemoryLines Step 2，memdir.ts:223-227；buildMemoryPrompt:288-291
 * 只读 {@code fs.readFileSync(entrypoint)} 从不写回；memoryScan.ts:42 scanMemoryFiles 排除 MEMORY.md）。
 * 旧 Java {@code write/delete/buildFileContent/readEntry/loadIndex} 0 生产调用方（grep 自验），
 * 且 write 后自动 rebuildIndex 全量重写 MEMORY.md 会覆盖模型内容（DEL-M-04 冲突），已删除。
 * sectionCacheInvalidator 失效接线（IMP-SP-07）仅由 write/delete 触发，随 CRUD 一并删除
 * （模型 Write/Edit 触发系统提示缓存失效走 hook 路径，非本存储层职责）。
 *
 * <p>保留纯读取能力（CC scanMemoryFiles / buildMemoryPrompt 等价）：
 * <ul>
 *   <li>{@link #list()} —— 列记忆条目（恒按 mtime 降序，最多 200，排除 MEMORY.md，
 *       对齐 CC scanMemoryFiles memoryScan.ts:72-73）</li>
 *   <li>{@link #memoryDir(String)} / {@link #memoryDirForProjectRoot(String)} —— 记忆目录路径
 *       （显式会话入参，<b>[TL-W2 P9]</b> 零 ThreadLocal 读；旧无参 {@code memoryDir()} 已删）</li>
 * </ul>
 *
 * <p><b>G-16（IMP-M-R2-DEL-01）</b>：构造器<b>不创建</b>记忆目录——CC {@code ensureMemoryDirExists}
 * （memdir.ts:129-147）仅在 loadMemoryPrompt 的 auto/team 分支调用（memdir.ts:459/:479），
 * 无构造期副作用；目录由 prompt 构建路径（MemoryPromptBuilder.loadMemoryPrompt）按需确保，
 * 缺失时 list 由 MemoryScanner 返回空列表（对齐 CC readdir 失败 catch → []，memoryScan.ts:74-75）。
 *
 * <p><b>索引模型裁决（IMP-M-P1-1，DEL-M-04/05）</b>：MEMORY.md 索引由<b>模型</b>用 Write/Edit
 * 工具维护，程序永不写回（buildMemoryPrompt:288-291 只读）。旧 Java 写后自动 rebuildIndex 已移除。
 */
public class MemoryStorage {

    private static final Logger log = LoggerFactory.getLogger(MemoryStorage.class);

    /**
     * [A1 修复 2026-09-04] 记忆目录不再构造期冻结。
     *
     * <p><b>根因</b>：旧实现 {@code new MemoryStorage(autoMemPaths)} 在 Spring bean 构造期
     * 即调 {@code getAutoMemPath()} 冻结 final 字段 —— 构造时无会话上下文（ThreadLocal
     * projectRoot 未注入）→ 回落 config-home 自身 slug（{@code C--Users-WIN--nexusai}），
     * 此后所有会话（无论绑定哪个项目）记忆都写错目录（生产实测
     * {@code memoryDir=C:\Users\WIN\.nexusai\projects\C--Users-WIN--nexusai\memory\}）。
     *
     * <p><b>方案 A（重做 2026-09-04 · [TL-W2 P9] 再收紧）</b>：字段改为二选一 —— 固定 Path
     * （测试注入）或 AutoMemPaths 引用（生产）。解析型<b>不再惰性现算 ThreadLocal</b>：
     * {@link #memoryDir(String)} 按入参 sessionId 经
     * {@link com.nexusai.common.SessionProjectRoot#getForSession} 取项目根后解析；
     * {@link #memoryDirForProjectRoot(String)} 直接吃显式 projectRoot。
     * <b>extract/dream 异步 fork 不读本类解析方法</b>：LlmAgentLoop 会话线程用
     * {@code AutoMemPaths.getAutoMemPath(boundProject)}（显式重载）解析后参数直传 fork
     * （ExtractMemoriesAgent/AutoDreamConsolidator 接收 memoryDir 参数）。
     *
     * <p>@NonNull：AutoMemPaths 引用不可空（仅 {@code memoryDir != null} 时允许 null 另一侧）。
     */
    private final Path memoryDir;
    private final AutoMemPaths autoMemPaths;
    private final MemoryScanner scanner;

    /**
     * @param memoryDir 记忆目录路径（测试/直构固定注入）
     */
    public MemoryStorage(Path memoryDir) {
        this.memoryDir = memoryDir;
        this.autoMemPaths = null;
        this.scanner = new MemoryScanner();
    }

    /**
     * 注入 AutoMemPaths 构造器 · 默认记忆目录 = {@code autoMemPaths.getAutoMemPath()}
     * （CC per-project 路径，DEL-M-06 对齐）。
     *
     * <p><b>[A1 修复]</b>：不再构造期冻结 —— 保留 AutoMemPaths 引用，{@link #memoryDir()}
     * 每次现算（按当前线程 projectRoot 惰性解析）。extract/dream fork 不走本方法（参数直传，
     * 见 {@code AutoMemPaths#getAutoMemPath(String)} 显式重载）；本惰性面供会话线程消费者。
     */
    public MemoryStorage(AutoMemPaths autoMemPaths) {
        this.memoryDir = null;
        this.autoMemPaths = autoMemPaths;
        this.scanner = new MemoryScanner();
    }

    /**
     * 列出所有记忆条目（排除 MEMORY.md 索引 · 对齐 CC memoryScan.ts:42 basename !== 'MEMORY.md'），
     * 恒按 mtime 降序排列（CC memoryScan.ts:72-73 {@code sort((a, b) => b.mtimeMs - a.mtimeMs)}，
     * 无 name 排序模式 —— D-05）。结果上限恒为 MEMORY_MAX_FILES（CC memoryScan.ts:73
     * {@code slice(0, MAX_MEMORY_FILES)}）——原 max 参数全仓调用方均传 MEMORY_MAX_FILES，
     * 语义恒为上限，冗余删除（OPD-CM3-27/E04）。
     *
     * @return 记忆条目列表（mtime 降序，最多 MEMORY_MAX_FILES）
     */
    public List<MemoryEntry> list() {
        // [TL-W2 P9] 只认冻结目录（new MemoryStorage(Path) · 测试/直构）；解析型（AutoMemPaths 构造）
        //   无会话参数 ⇒ 无从解析 per-project 目录 ⇒ 空列表（A′ 语义，对齐 CC readdir 失败
        //   catch → [] memoryScan.ts:74-75）。旧实现在此经无参 memoryDir() 现算 ThreadLocal。
        Path dir = frozenMemoryDir();
        // A′: 无有效项目 → 无记忆目录 → 空列表
        if (dir == null) {
            return List.of();
        }
        // 提取路径无外部取消（CC extractMemories.ts:399 新建 createAbortController().signal 恒未取消）
        List<MemoryEntry> entries = scanner.scan(dir, null);
        if (log.isDebugEnabled()) {
            log.debug("[MemoryStorage] 列出记忆: {} 条", entries.size());
        }
        return entries;
    }

    /**
     * 获取记忆目录（<b>按 sessionId 现算</b>）· 消费者必须显式传会话。
     *
     * <p><b>[TL-W2 P9] 已删除无参 {@code memoryDir()} 的 ThreadLocal 现算面</b>：旧实现
     * {@code autoMemPaths.getAutoMemPath()}（无参）在<b>消费线程</b>读
     * {@code AutoMemPaths.currentSessionProjectRoot()} —— 非会话线程（REST / ForkJoinPool fork /
     * hook / TaskStop）ThreadLocal 必空 ⇒ 回落 config home ⇒ A′ 判无效返回 null ⇒ 下游
     * {@code new ConsolidationLock(null)} NPE / {@code memoryDir().toString()} NPE（审计 P9）。
     * 现契约（对齐用户定案「会话态一律直传 / 按 sessionId 从 DB 现算，绝不读 ThreadLocal」）：
     * <ul>
     *   <li>冻结型（{@code new MemoryStorage(Path)} · 测试/直构）→ 恒返回冻结路径（忽略 sessionId）；</li>
     *   <li>解析型（{@code new MemoryStorage(AutoMemPaths)} · 生产）→ 按入参 sessionId 经
     *       {@code SessionProjectRoot.getForSession}（全局冻结表 · 未绑定 → null，<b>不回落</b>）
     *       取 projectRoot 再解析 per-project 记忆目录；无绑定 → null（A′，调用方跳过）。</li>
     * </ul>
     *
     * @param sessionId 会话 ID（冻结型忽略；解析型必传 —— null/未绑定 → 无有效项目 → null）
     * @return 记忆目录；无有效项目 → null
     */
    public Path memoryDir(@Nullable String sessionId) {
        if (autoMemPaths == null) {
            return memoryDir;
        }
        String projectRoot = com.nexusai.common.SessionProjectRoot.getForSession(sessionId);
        return memoryDirForProjectRoot(projectRoot);
    }

    /**
     * 获取记忆目录（<b>显式 projectRoot 入参</b>）· 调用方已持有会话绑定项目根时用（如手动 /dream
     * 的 workspaceDir = CC getOriginalCwd 语义）。
     *
     * <p>绝不读 ThreadLocal：projectRoot null/blank/config-home → {@code getAutoMemPath} A′ 返回
     * null（per-project auto 记忆不存在）。
     *
     * @param projectRoot 会话绑定项目根（boundProject / originalCwd 层）；null → null
     * @return 记忆目录；无有效项目 → null
     */
    public Path memoryDirForProjectRoot(@Nullable String projectRoot) {
        if (autoMemPaths == null) {
            return memoryDir;
        }
        if (projectRoot == null || projectRoot.isBlank()) {
            return null;
        }
        String autoMem = autoMemPaths.getAutoMemPath(projectRoot);
        // A′: 无有效项目（config-home 回落）→ per-project auto 记忆目录不存在 → null（调用方跳过）
        return autoMem == null ? null : Paths.get(autoMem);
    }

    /**
     * [TL-W1 P2] 冻结记忆目录（仅 {@code new MemoryStorage(Path)} 直构 · 测试/POJO）。
     *
     * <p><b>WHY</b>：ExtractMemoriesAgent 的便捷重载（2/3/4 参）需要 memoryDir 却无会话参数
     * —— 旧实现经 {@link #memoryDir()} 惰性现算，在解析型 storage（生产 {@code new MemoryStorage(
     * AutoMemPaths)}）下会读会话 ThreadLocal；调用线程若是 fork/hook 线程（ThreadLocal 空）即回落
     * config home → A′ 判无效 → null → 下游 NPE 被吞（审计 P2/P9）。现便捷重载只允许消费**冻结**
     * 值：解析型 storage 返回 null → 调用方 fail-loud（生产必须走显式 memoryDir 参数）。
     *
     * @return 冻结的 memoryDir；解析型（AutoMemPaths 构造）→ null
     */
    public Path frozenMemoryDir() {
        return autoMemPaths == null ? memoryDir : null;
    }
}
