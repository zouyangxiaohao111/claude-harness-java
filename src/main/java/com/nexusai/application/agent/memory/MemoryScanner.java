package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import com.nexusai.application.agent.tool.AbortController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 记忆文件扫描器 · 对齐 CC memoryScan.ts:35-77 scanMemoryFiles()
 *
 * <p>扫描 .memory/ 下所有 .md 文件（排除 MEMORY.md），解析 frontmatter 提取
 * description/type，按 mtime 降序排列，限制不超过 MAX_MEMORY_FILES。
 *
 * <p>CC 使用 readFileInRange 只读前 FRONTMATTER_MAX_LINES=30 行做两段式优化，
 * 教学中简化：完整读取后解析 frontmatter。
 *
 * <p>C-15 收敛（OPD-CM5-C-15）：frontmatter 解析（开闭符提取 + YAML 解析 + 引号重试）不再本类
 * 私有复制，统一委托共享 {@code ParseSkillFrontmatter}（对齐 CC 单一 utils/frontmatterParser.ts）。
 */
public class MemoryScanner {

    private static final Logger log = LoggerFactory.getLogger(MemoryScanner.class);

    /** CC memoryScan.ts:21 FRONTMATTER_MAX_LINES = 30 */
    private static final int FRONTMATTER_MAX_LINES = 30;
    // C-15 收敛（OPD-CM5-C-15）：FRONTMATTER_OPENING/YAML_SPECIAL_CHARS/KEY_VALUE_LINE/yaml 随私有
    // frontmatter 解析复制删除，统一走共享 ParseSkillFrontmatter（对齐 CC 单一 utils/frontmatterParser.ts）。

    // ── 扫描 ──

    /**
     * 扫描记忆目录下的所有 .md 文件（排除 MEMORY.md）· 对齐 CC memoryScan.ts:35-77 scanMemoryFiles。
     *
     * <p>对齐行为（REQ-M-03，IMP-M-P0-1）：
     * <ol>
     *   <li>readdir <b>recursive</b> 递归扫描（CC :40 {@code readdir(memoryDir, {recursive:true})}，
     *       旧实现 Files.list 非递归 → 子目录记忆被漏扫）</li>
     *   <li>排除 MEMORY.md 索引文件（CC :42 {@code basename(f) !== 'MEMORY.md'}）</li>
     *   <li>只读前 FRONTMATTER_MAX_LINES=30 行解析 frontmatter（CC :48-55 readFileInRange 两段式
     *       优化，旧实现全量读取）</li>
     *   <li>按 mtime 降序排列 + 截断到 MAX_MEMORY_FILES（CC :72-73）</li>
     *   <li><b>G-14 取消语义</b>（CC readFileInRange.ts:81 {@code signal?.throwIfAborted()} 每文件）
     *       —— signal 已取消 → 停止扫描后续文件，返回已读部分结果（CC allSettled 下 abort 文件被
     *       丢弃、已完成文件保留）</li>
     *   <li><b>C2 △-walk-err</b>：整体 catch → 空清单（CC :74-75 {@code catch { return [] }}）——
     *       递归 readdir 任意失败（不可读子目录 EACCES / 失效链接）→ 返回 [] 不抛异常；
     *       Java Files.walk 迭代期以 UncheckedIOException（RuntimeException）抛同型错误，
     *       与 IOException 一并捕获（旧实现仅 catch IOException → UncheckedIOException 逃逸，
     *       中断提取链 ExtractMemoriesAgent:522 storage.list()）</li>
     * </ol>
     *
     * @param memoryDir 记忆目录路径
     * @param signal    取消信号（可 null = 无取消；CC original: signal）
     * @return 按 mtime 降序排列的 MemoryEntry 列表
     */
    public List<MemoryEntry> scan(Path memoryDir, AbortController signal) {
        List<MemoryEntry> entries = new ArrayList<>();

        if (!Files.isDirectory(memoryDir)) {
            if (log.isDebugEnabled()) {
                log.debug("[MemoryScanner] 目录不存在，跳过扫描: {}", memoryDir);
            }
            return entries;
        }

        try (Stream<Path> files = Files.walk(memoryDir)) {
            entries = files
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                // 排除 MEMORY.md 索引文件 · 对齐 CC memoryScan.ts:42
                .filter(p -> !p.getFileName().toString().equals(MemoryConstants.MEMORY_INDEX_NAME))
                // G-14：每文件前 abort 检查（CC readFileInRange.ts:81 throwIfAborted）→ 部分结果
                .takeWhile(p -> signal == null || !signal.isCancelled())
                .map(p -> parseHeaderSafely(memoryDir, p))
                // CC allSettled（memoryScan.ts:45-64）：abort 时刻 in-flight 的读取被 rejected → 丢弃该文件。
                //   takeWhile 只拦"取消检查点之后未读"的文件；本 filter 补拦"读取期间 abort 已触发"的文件
                //   （顺序执行下等价 CC「abort 前已完成读入的保留」——R-7 接受前缀语义，D08-4）。
                .filter(e -> e != null && (signal == null || !signal.isCancelled()))
                .sorted(Comparator.comparing(MemoryEntry::mtime).reversed())
                .limit(MemoryConstants.MEMORY_MAX_FILES)
                .toList();
        } catch (IOException | UncheckedIOException e) {
            log.warn("[MemoryScanner] 扫描目录失败: {}", memoryDir, e);
        }

        if (log.isDebugEnabled()) {
            log.debug("[MemoryScanner] 扫描完成: {} 个记忆文件 (目录={})", entries.size(), memoryDir);
        }
        return entries;
    }

    // ── frontmatter 解析 ──

    /**
     * 安全解析单个文件头（前 30 行，扫描用 · 捕获异常不中断批量扫描）
     *
     * @param memoryDir 记忆目录（计算 CC relativePath filename 用，memoryScan.ts:57）
     * @param filePath  .md 文件完整路径
     */
    private MemoryEntry parseHeaderSafely(Path memoryDir, Path filePath) {
        try {
            return parseFileHeader(memoryDir, filePath);
        } catch (Exception e) {
            log.warn("[MemoryScanner] 解析文件失败，跳过: {}", filePath, e);
            return null;
        }
    }

    /**
     * 读文件前 FRONTMATTER_MAX_LINES 行解析 frontmatter（scan 用）· 对齐 CC memoryScan.ts:48-55
     * {@code readFileInRange(filePath, 0, FRONTMATTER_MAX_LINES)} 两段式优化。
     *
     * <p>CC 返回 MemoryHeader（memoryScan.ts:13-19，无 body/name/byteSize）；Java MemoryEntry
     * 同字段集（type/description/filename/filePath/mtime）。
     */
    MemoryEntry parseFileHeader(Path memoryDir, Path filePath) throws IOException {
        return parseFromContent(memoryDir, filePath, readFirstLines(filePath, FRONTMATTER_MAX_LINES));
    }

    /** 从给定内容解析 frontmatter + 构造 MemoryEntry（scan 共用）。 */
    private MemoryEntry parseFromContent(Path memoryDir, Path filePath, String raw) throws IOException {
        // CC memoryScan.ts:57 filename=relativePath（recursive readdir 相对路径，非 basename）。
        // Windows 下 Java Path.toString() 用 '\'，CC Node 用 '/' → 统一转 '/'（跨平台渲染一致）。
        String filename = memoryDir.relativize(filePath).toString().replace('\\', '/');

        BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
        Instant mtime = attrs.lastModifiedTime().toInstant();

        // C-15 收敛（OPD-CM5-C-15）：frontmatter 解析统一走共享 ParseSkillFrontmatter
        // （对齐 CC 单一 utils/frontmatterParser.ts，替代本类私有 extractFrontmatterText/
        // parseFrontmatterSafely/quoteProblematicValues 三份复制）。共享类语义与本类旧实现等价：
        //   G-13 开闭符 —— CC frontmatterParser.ts:123 FRONTMATTER_REGEX 惰性捕获，首个 `---` 即闭
        //       （`description: foo---bar` → foo）；开符须 `---` + 仅空白 + 必选换行
        //       （`---foo`/`----` 开头不匹配 → 无 frontmatter，文件保留、type/desc 降级）；
        //   G-13 YAML 解析失败 → quoteProblemativeValues 加引号重试 → 再失败 {} 保留（:147-169）
        //       —— 文件绝不因解析失败从 scan 结果/manifest 消失。
        Map<String, Object> frontmatter =
            ParseSkillFrontmatter.parseFrontmatterStatic(raw, filePath.toString()).frontmatter();

        // CC memoryScan.ts:60-61：仅 description/type 进入 MemoryHeader
        String description = stringField(frontmatter, "description");
        String typeStr = stringField(frontmatter, "type");
        // CC memoryTypes.ts:28-31 parseMemoryType：未知/缺失 → undefined（Java null）
        MemoryType type = MemoryType.fromString(typeStr);

        // D08-2：CC memoryScan.ts:60 `description: frontmatter.description || null` ——
        //   缺失/空串 → null（非空串）。formatManifest 消费方 `desc != null && !desc.isBlank()`
        //   对 null 与空串行为一致（FindRelevantMemories.formatManifest:456-457），契约对齐无渲染差异。
        // 无 frontmatter / YAML 双重失败 → frontmatter={} → description null + type null
        // （CC `undefined || null` → null；type 不再默认为 USER，旧默认会污染四类 taxonomy）。
        return new MemoryEntry(type,
            description != null && !description.isEmpty() ? description : null,
            filename, filePath, mtime);
    }

    /** 只读前 maxLines 行（CC readFileInRange offset=0 maxLines=30 语义）。 */
    private static String readFirstLines(Path filePath, int maxLines) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            int count = 0;
            boolean first = true;
            while (count < maxLines && (line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    // MEM-07/G-24：剥 BOM（CC readFileInRange.ts:138）——否则 BOM+`---` 文件
                    // frontmatter 不识别（type/desc 丢失）
                    if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                        line = line.substring(1);
                    }
                }
                sb.append(line).append('\n');
                count++;
            }
        }
        return sb.toString();
    }

    // ── helper ──

    /** 从 YAML 解析结果中安全提取字符串字段 */
    private String stringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}
