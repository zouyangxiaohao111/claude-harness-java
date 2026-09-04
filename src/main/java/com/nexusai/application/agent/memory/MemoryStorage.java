package com.nexusai.application.agent.memory;

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
 *   <li>{@link #memoryDir()} —— 记忆目录路径（ExtractMemoriesAgent / AutoDreamConsolidator 注入）</li>
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
     * <p><b>方案 A（重做 2026-09-04）</b>：字段改为二选一 —— 固定 Path（测试注入）或 AutoMemPaths
     * 引用（生产惰性）。{@link #memoryDir()} 每次调用现算（生产走 {@code autoMemPaths.getAutoMemPath()}
     * 按<b>当前线程</b> projectRoot 解析 → per-project per-session，对齐 CC paths.ts:223-235）。
     * <b>extract/dream 异步 fork 不读本方法</b>：LlmAgentLoop 会话线程用
     * {@code AutoMemPaths.getAutoMemPath(boundProject)}（显式重载）解析后参数直传 fork
     * （ExtractMemoriesAgent/AutoDreamConsolidator 接收 memoryDir 参数），杜绝 ForkJoinPool
     * 无 ThreadLocal 回落 config-home。其余消费者（MemoryPromptBuilder/Prefetcher 会话线程）
     * 仍经本惰性 memoryDir() 现算（当前线程 projectRoot 可靠）。
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
        // 提取路径无外部取消（CC extractMemories.ts:399 新建 createAbortController().signal 恒未取消）
        List<MemoryEntry> entries = scanner.scan(memoryDir(), null);
        if (log.isDebugEnabled()) {
            log.debug("[MemoryStorage] 列出记忆: {} 条", entries.size());
        }
        return entries;
    }

    /**
     * 获取记忆目录路径（会话线程消费者用 · MemoryPromptBuilder/Prefetcher 等）。
     *
     * <p><b>[A1 修复]</b>：生产（AutoMemPaths 构造）每次现算 —— 按<b>当前线程</b>
     * projectRoot 解析 per-project 记忆目录（对齐 CC paths.ts:223-235 每次调用 getAutoMemPath）；
     * 测试/直构（Path 构造）返回固定路径。<b>extract/dream fork 不调本方法</b> —— 会话线程经
     * {@code AutoMemPaths#getAutoMemPath(String)} 显式重载解析后参数直传（无 ThreadLocal 依赖）。
     */
    public Path memoryDir() {
        return autoMemPaths != null ? Paths.get(autoMemPaths.getAutoMemPath()) : memoryDir;
    }
}
