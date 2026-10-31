package com.nexusai.apis.session;

import com.nexusai.application.terminal.TerminalInfo;
import com.nexusai.application.terminal.TerminalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集成终端 REST · 全部挂在 {@code /api/v1/sessions/{sessionId}/terminals} 之下：
 * <ul>
 *   <li>{@code GET    /}                          → 200 终端列表（含已退出记录，{@code alive=false}）</li>
 *   <li>{@code POST   /}                          → 201 新建终端（body {@code {shell?, cols?, rows?}}）</li>
 *   <li>{@code POST   /{terminalId}/input}        → 204 写入 PTY（body {@code {data}}）</li>
 *   <li>{@code POST   /{terminalId}/resize}       → 204 调整尺寸（body {@code {cols, rows}}）</li>
 *   <li>{@code DELETE /{terminalId}}              → 204 关闭并移除</li>
 *   <li>{@code GET    /{terminalId}/snapshot}     → 200 {@code {lines}}（去 ANSI 的最近 N 行）</li>
 * </ul>
 *
 * <p><b>为什么输入/尺寸走 REST 而不是 STOMP 入站</b>：与 {@link ChatController} 同款 —— 本仓的
 * 写路径统一走 REST（可校验、可审计、错误能转 HTTP 状态码），STOMP 只做<b>出站</b>推送。
 * 输出 topic 见 {@link TerminalService#topicOf}。
 *
 * <p><b>错误语义</b>（{@code GlobalExceptionHandler} 单点翻译）：终端不存在 → 404；shell 不在白名单 /
 * 请求参数越界 → 400；终端已退出仍要写入 → 409；PTY 启动失败 → 500。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/terminals")
public class TerminalController {

    private static final Logger log = LoggerFactory.getLogger(TerminalController.class);

    @Autowired private TerminalService terminalService;

    /**
     * 会话下的终端列表。
     *
     * @param sessionId 会话 ID
     * @return 200 终端描述数组（按启动时间升序；无终端 ⇒ 空数组，不返回 404）
     */
    @GetMapping
    public List<TerminalInfo> list(@PathVariable String sessionId) {
        List<TerminalInfo> terminals = terminalService.list(sessionId);
        if (log.isDebugEnabled()) {
            log.debug("[TerminalController] 终端列表: session={} 数量={}", sessionId, terminals.size());
        }
        return terminals;
    }

    /**
     * 新建终端。
     *
     * @param sessionId 会话 ID
     * @param req       可选请求体（全字段可缺省 ⇒ 默认 shell + 80x24）
     * @return 201 新终端描述
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TerminalInfo create(@PathVariable String sessionId,
                               @RequestBody(required = false) CreateTerminalRequest req) {
        String shell = req != null ? req.shell() : null;
        int cols = req != null && req.cols() != null ? req.cols() : TerminalService.DEFAULT_COLS;
        int rows = req != null && req.rows() != null ? req.rows() : TerminalService.DEFAULT_ROWS;
        TerminalInfo info = terminalService.create(sessionId, shell, cols, rows);
        log.info("[TerminalController] 新建终端: session={} terminal={} shell={} cwd={}",
            sessionId, info.id(), info.shell(), info.cwd());
        return info;
    }

    /**
     * 写入 PTY（键盘输入 / 粘贴）。
     *
     * @param sessionId  会话 ID
     * @param terminalId 终端 ID
     * @param req        请求体 {@code {data}}
     */
    @PostMapping("/{terminalId}/input")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void input(@PathVariable String sessionId,
                      @PathVariable String terminalId,
                      @RequestBody InputRequest req) {
        terminalService.write(sessionId, terminalId, req != null ? req.data() : null);
        if (log.isDebugEnabled()) {
            log.debug("[TerminalController] 终端输入: session={} terminal={} 字符数={}",
                sessionId, terminalId, req != null && req.data() != null ? req.data().length() : 0);
        }
    }

    /**
     * 调整终端尺寸。
     *
     * @param sessionId  会话 ID
     * @param terminalId 终端 ID
     * @param req        请求体 {@code {cols, rows}}
     */
    @PostMapping("/{terminalId}/resize")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resize(@PathVariable String sessionId,
                       @PathVariable String terminalId,
                       @RequestBody ResizeRequest req) {
        if (req == null) {
            throw new com.nexusai.infra.exception.ValidationException("resize 需要 {cols, rows}");
        }
        terminalService.resize(sessionId, terminalId,
            req.cols() != null ? req.cols() : 0,
            req.rows() != null ? req.rows() : 0);
    }

    /**
     * 关闭终端。
     *
     * @param sessionId  会话 ID
     * @param terminalId 终端 ID
     */
    @DeleteMapping("/{terminalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable String sessionId, @PathVariable String terminalId) {
        terminalService.close(sessionId, terminalId);
    }

    /**
     * 最近 N 行输出（已剥离 ANSI 转义）—— 前端重连回放 / Agent 读状态。
     *
     * @param sessionId  会话 ID
     * @param terminalId 终端 ID
     * @param lines      行数（缺省 200；{@code <=0} ⇒ 全部）
     * @return 200 {@code {lines: string}}
     */
    @GetMapping("/{terminalId}/snapshot")
    public SnapshotResponse snapshot(@PathVariable String sessionId,
                                     @PathVariable String terminalId,
                                     @RequestParam(name = "lines", defaultValue = "200") int lines) {
        return new SnapshotResponse(terminalService.snapshot(sessionId, terminalId, lines));
    }

    /** 新建终端请求体（全字段可缺省）。 */
    public record CreateTerminalRequest(String shell, Integer cols, Integer rows) {}

    /** 写入请求体。 */
    public record InputRequest(String data) {}

    /** 尺寸请求体。 */
    public record ResizeRequest(Integer cols, Integer rows) {}

    /** 快照响应体。 */
    public record SnapshotResponse(String lines) {}
}
