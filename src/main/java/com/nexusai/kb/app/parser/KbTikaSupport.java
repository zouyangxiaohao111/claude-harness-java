package com.nexusai.kb.app.parser;

import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tika 公共支撑 · 复用同一个 {@link AutoDetectParser} 实例（Tika 文档明确 AutoDetectParser 线程安全，
 * 且构造代价高：要扫描全部 SPI 注册的 Parser/Detector），探测 MIME，并把 {@link Metadata} 转成 Map。
 *
 * <p><b>WHY 不做 Spring bean</b>：解析器要能在无 Spring 的单测里跑（本仓禁 {@code @SpringBootTest}）。
 * Tika 自身无状态、无外部配置依赖，做成静态工具最省心；有状态的东西（模型配置）才注入。
 */
final class KbTikaSupport {

    private static final Logger log = LoggerFactory.getLogger(KbTikaSupport.class);

    /** 进程级共享实例（Tika 2.x 官方声明 AutoDetectParser 线程安全）。 */
    private static volatile AutoDetectParser parser;
    /** MIME 探测器 · 与 parser 同源（避免两套检测逻辑给出不同答案）。 */
    private static volatile Detector detector;

    private KbTikaSupport() {
    }

    /**
     * 取共享 parser · 首次调用惰性构造（避免类加载期扫 SPI 拖慢启动）。
     *
     * <p>并发首次构造会多建一两份实例，随后丢弃 —— 代价可忽略，不值得为此加锁（Tika 构造本身
     * 内部有文件 IO，加锁反而串行化首个请求）。用 volatile + 双检保证可见性。
     */
    static AutoDetectParser parser() {
        AutoDetectParser p = parser;
        if (p == null) {
            synchronized (KbTikaSupport.class) {
                p = parser;
                if (p == null) {
                    p = new AutoDetectParser();
                    detector = p.getDetector();
                    parser = p;
                    log.info("KbTikaSupport: AutoDetectParser 已初始化（进程级共享，含 Office/Text/HTML 解析模块）");
                }
            }
        }
        return p;
    }

    /** MIME 探测（只读文件头，不解析内容）。 */
    static String detectMime(Path file, String fileName) {
        AutoDetectParser p = parser();
        Detector d = detector != null ? detector : p.getDetector();
        Metadata md = new Metadata();
        if (fileName != null && !fileName.isBlank()) {
            md.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            MediaType type = d.detect(in, md);
            return type == null ? null : type.toString();
        } catch (IOException e) {
            // 探测失败不是解析失败：调用方还有扩展名兜底，记 warn 后交回 null
            log.warn("KbTikaSupport: MIME 探测失败 file={} 原因={}", file, e.getMessage());
            return null;
        }
    }

    /**
     * 构造带文件名提示的 Metadata · 文件名必须给，否则 Tika 只能靠 magic 猜，对被改名的
     * .doc（OLE2 容器）这类会猜成通用 {@code application/x-tika-msoffice}。
     */
    static Metadata newMetadata(String fileName) {
        Metadata md = new Metadata();
        if (fileName != null && !fileName.isBlank()) {
            md.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        }
        return md;
    }

    /**
     * Tika Metadata → 字符串 Map。
     *
     * <p><b>多值处理</b>：同名键取第一个值（Tika 的 {@code X-TIKA:Parsed-By} 等会重复）。键名去重后
     * 保留原顺序，便于排查时按 Tika 自身顺序读。
     *
     * <p><b>跳过内部键</b>：{@code X-TIKA:Parsed-By*} 只是解析器链诊断信息，对文档含义无价值，
     * 但体积不小（每个都记），故过滤。
     */
    static java.util.Map<String, String> toMetadataMap(Metadata md) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (md == null) {
            return out;
        }
        for (String name : md.names()) {
            if (name == null || name.startsWith("X-TIKA:Parsed-By")) {
                continue;
            }
            String value = md.get(name);
            if (value != null && !value.isBlank()) {
                out.putIfAbsent(name, value);
            }
        }
        return out;
    }

    /** 从已解析的 Metadata 里按 Tika 语义取标题（{@code title} → {@code dc:title}）。 */
    static String titleOf(Metadata md) {
        if (md == null) {
            return null;
        }
        String t = md.get(TikaCoreProperties.TITLE);
        return t == null || t.isBlank() ? null : t;
    }

    /** 从已解析的 Metadata 里取作者（{@code dc:creator} → {@code meta:author}）。 */
    static String authorOf(Metadata md) {
        if (md == null) {
            return null;
        }
        String a = md.get(TikaCoreProperties.CREATOR);
        return a == null || a.isBlank() ? null : a;
    }

    /** 取所在目录（临时代码里频繁需要，集中一处便于排查路径问题）。 */
    static Path parentOf(Path file) {
        Path parent = file == null ? null : file.getParent();
        return parent != null ? parent : Path.of(".").toAbsolutePath();
    }

    /** Tika 异常统一包装（解析器只抛 {@link KbParseException}）。 */
    static KbParseException wrap(String action, Path file, Exception e) {
        if (e instanceof TikaException) {
            return new KbParseException(action + "失败（文档格式异常）: " + file.getFileName() + " — " + e.getMessage(), e);
        }
        if (e instanceof IOException) {
            return new KbParseException(action + "失败（读写异常）: " + file.getFileName() + " — " + e.getMessage(), e);
        }
        return new KbParseException(action + "失败: " + file.getFileName() + " — " + e, e);
    }
}
