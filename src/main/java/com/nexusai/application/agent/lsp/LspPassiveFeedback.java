package com.nexusai.application.agent.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LspPassiveFeedback · 对齐 CC services/lsp/passiveFeedback.ts (severity + format helpers).
 *
 * <p>L1 语义: 文本诊断辅助函数,用于把 LSP textDocument/publishDiagnostics 通知
 * 转成 Claude 的 {@code DiagnosticFile[]} 附件格式。包含:
 * <ul>
 *   <li>{@link #mapLSPSeverity(Integer)} — LSP severity 数字 (1=Error,2=Warning,3=Info,4=Hint)
 *       → Claude severity 字符串;默认 Error</li>
 *   <li>{@link #formatDiagnosticsForAttachment(PublishDiagnosticsParams)} — URI 解析
 *       (file:// → path) + 转换 diagnostics 字段;错误 URI → 原 URI fallback</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 public static method;{@link Diagnostic} / {@link Range} / {@link DiagnosticFile}
 *       record 严格对齐 CC 字段名</li>
 *   <li><b>A2 Golden Trace</b>: severity 1/2/3/4/null → Error/Warning/Info/Hint/Error;
 *       formatParams(file:// URI + diag 数组) → file path + Diagnostic[] + null code → undefined</li>
 *   <li><b>A3 纯函数</b>: 无副作用;异常 URI → logError + 返回原 URI (不抛)</li>
 *   <li><b>A4 边界</b>: 非 file:// URI → 原 URI 透传;severity 0/5/100/null → Error 默认;
 *       code null → undefined (即 null);empty diagnostics → 单元素 [{uri, diagnostics: []}]</li>
 *   <li><b>A5 业务场景</b>: TS LSP 服务器发出
 *       {@code {uri: "file:///proj/src.java", diagnostics: [{severity:2, range:..., message:"..."}]}}
 *       → Claude 附件 [{uri:"/proj/src.java", diagnostics:[{severity:"Warning", range:..., message:"..."}]}]</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS {@code fileURLToPath} → Java {@link URI#getSchemeSpecificPart}
 * + URLDecoder;TS switch (severity) → Java switch expression;TS
 * {@code code !== undefined && code !== null ? String(code) : undefined} → Java
 * {@code diag.code() == null ? null : diag.code().toString()}。
 */
public final class LspPassiveFeedback {

    private static final Logger log = LoggerFactory.getLogger(LspPassiveFeedback.class);

    /** Claude 诊断严重度 4 档 · 对齐 CC mapLSPSeverity 返回 union. */
    public enum DiagnosticSeverity { Error, Warning, Info, Hint }

    /** LSP severity numeric per LSP spec. */
    public static final int LSP_SEVERITY_ERROR = 1;
    public static final int LSP_SEVERITY_WARNING = 2;
    public static final int LSP_SEVERITY_INFORMATION = 3;
    public static final int LSP_SEVERITY_HINT = 4;

    public record Position(int line, int character) {}
    public record Range(Position start, Position end) {}
    public record Diagnostic(
        String message,
        DiagnosticSeverity severity,
        Range range,
        String source,
        String code) {}

    public record DiagnosticFile(String uri, List<Diagnostic> diagnostics) {}

    /** Raw LSP publish params — minimal record matching VS Code LSP wire shape. */
    public record PublishDiagnosticsParams(String uri, List<RawDiagnostic> diagnostics) {}
    public record RawDiagnostic(
        String message,
        Integer severity,
        Range range,
        String source,
        Object code) {}

    private LspPassiveFeedback() {
        // 工具类
    }

    /**
     * Maps LSP numeric severity (1=Error, 2=Warning, 3=Info, 4=Hint) to Claude severity.
     * Unknown / undefined / out-of-range → Error (default).
     */
    public static DiagnosticSeverity mapLSPSeverity(Integer lspSeverity) {
        if (lspSeverity == null) {
            return DiagnosticSeverity.Error;
        }
        return switch (lspSeverity) {
            case LSP_SEVERITY_ERROR -> DiagnosticSeverity.Error;
            case LSP_SEVERITY_WARNING -> DiagnosticSeverity.Warning;
            case LSP_SEVERITY_INFORMATION -> DiagnosticSeverity.Info;
            case LSP_SEVERITY_HINT -> DiagnosticSeverity.Hint;
            default -> DiagnosticSeverity.Error;
        };
    }

    /**
     * Convert LSP {@code PublishDiagnosticsParams} to Claude's {@code DiagnosticFile} format.
     *
     * <p>URIs prefixed with {@code file://} are converted to filesystem paths via {@link URI}.
     * Malformed URIs fall back to the original string (CC: logError + use raw URI).
     *
     * @return single-element list. (CC returns one-element list for the whole file.)
     */
    public static List<DiagnosticFile> formatDiagnosticsForAttachment(PublishDiagnosticsParams params) {
        String uri = resolveUri(params);
        List<Diagnostic> converted = new ArrayList<>(params.diagnostics().size());
        for (RawDiagnostic d : params.diagnostics()) {
            converted.add(new Diagnostic(
                d.message(),
                mapLSPSeverity(d.severity()),
                d.range(),
                d.source(),
                d.code() == null ? null : d.code().toString()
            ));
        }
        return List.of(new DiagnosticFile(uri, converted));
    }

    private static String resolveUri(PublishDiagnosticsParams params) {
        String raw = params.uri();
        if (raw == null || !raw.startsWith("file://")) {
            return raw;
        }
        try {
            URI parsed = new URI(raw);
            // Paths.get(URI) handles file:// scheme properly, decoding %xx escapes,
            // matching CC fileURLToPath semantics.
            Path path = Paths.get(parsed);
            // FIX-R12-2: 规范化路径分隔符为 '/', 避免 Windows 反斜杠导致跨平台断言失败
            // CC (TS) 一律用 '/', LLM 看到一致路径格式
            return path.toString().replace('\\', '/');
        } catch (Exception ex) {
            log.error("Failed to convert URI to file path: {} — using original URI", raw, ex);
            return raw;
        }
    }
}
