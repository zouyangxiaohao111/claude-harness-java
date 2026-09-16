package com.nexusai.application.agent.bash;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.permission.PermissionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [SEC-FAIL-LOUD] BashPathValidator tokenize 失败 → fail-closed（Ask）· 对齐 CC
 * {@code PARSE_ABORTED} 契约 + bashPermissions.ts:1741-1760。
 *
 * <p><b>WHY（规则九 · 验证意图，而非仅行为）</b>：{@code tokenizeSubcommands} 曾经的
 * {@code catch (Exception) { return List.of(); }} 把「解析失败」与「没有子命令」混为一谈。
 * 二者对 {@code check()} 完全不可区分：
 * <pre>
 *   List&lt;Subcommand&gt; subcommands = tokenizeSubcommands(command);   // 失败 → 空列表
 *   boolean compoundCommandHasCd = subcommands.stream()...;          // 空 → false
 *   for (Subcommand sub : subcommands) { ...路径校验... }              // 空 → 一次都不执行
 *   return passthrough("所有路径命令校验通过");                          // ← 失败被包装成「校验通过」
 * </pre>
 * 一旦命中，后果不是「少一个 ask」，而是<b>整条路径约束链被静默绕过</b>
 * （危险删除 / cd+redirect / 路径越界读写全部失效）——安全方向是错的，必须 fail-closed。
 * CC 对同等情况明确要求「Callers MUST treat this as fail-closed (too-complex), NOT route
 * to legacy」（src/utils/bash/parser.ts:85-92），并在 bashPermissions.ts:1741-1760 落为
 * {@code behavior:'ask'}（先过 early-exit deny，再 ask）。
 *
 * <p><b>可达性实测（重要，避免「声称守护 X 实际守不住」）</b>：本测试<b>不</b>断言该分支今天
 * 可被真实命令命中。实测结论是<b>它目前不可达</b>：
 * <ul>
 *   <li>{@code BashParser.tokenize} 唯一被找到的抛异常输入是「未闭合 heredoc 且末行无换行」
 *       （{@code tryHeredoc} 的 {@code pos = lineEnd + 1} 越过末尾 → {@code substring} 越界，
 *       BashParser.java:3216-3226）；</li>
 *   <li>4000 条 fuzz 输入中 4 条令 tokenize 抛异常，<b>4 条全部</b>在 {@code check()} 步骤 2
 *       （{@code hasDangerousRedirection}）就被更早的 ask 拦下（"Shell expansion syntax in
 *       paths requires manual approval"），永远走不到步骤 3 的 tokenize。</li>
 * </ul>
 * 故本处是<b>潜伏</b>缺陷：今天被步骤 2 遮蔽，无真实输入可命中。它仍必须修——因为
 * (a) 它把「不可静态分析」错判成「校验通过」，一旦 tokenizer 增加抛点、或步骤 2 的顺序/判据
 * 调整、或上游改走别的调用路径，缺陷立即变活；(b) 一个 fail-open 的安全兜底本身就是错的，
 * 不因暂时不可达而正当。
 *
 * <p><b>注入缝的必要性（回应「反对不必要的机械」）</b>：正因为该分支今天不可达，真实命令
 * <b>无法</b>驱动它——不用注入缝则「fail-closed 实现」与「退回 {@code return List.of()}」
 * 两种实现无法被任何测试区分，任务要求的反向实验也无从进行。故
 * {@code BashPathValidator.TOKENIZER}（本仓既有 ENV_READER 注入惯例）是使该分支可验证的
 * 最小装置，不是新架构。
 */
@DisplayName("[SEC-FAIL-LOUD] BashPathValidator: tokenize 失败 → fail-closed Ask + WARN 留痕")
class BashPathValidatorTokenizeFailClosedTest {

    private static final Path CWD = Path.of("C:/work/project");

    /**
     * 基线无害命令：tokenize 正常时必须 Passthrough。
     * 选它而非 {@code rm -rf /} 是关键——危险删除本身恒 ask，用它做夹具会让
     * 「失败分支被绕过」的断言恒绿（零鉴别力），反向实验也就抓不住回归。
     */
    private static final String BENIGN_COMMAND = "ls .";

    private Logger validatorLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        validatorLogger = (Logger) LoggerFactory.getLogger(BashPathValidator.class);
        appender = new ListAppender<>();
        appender.start();
        validatorLogger.addAppender(appender);
        validatorLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void detachAppenderAndRestoreTokenizer() {
        validatorLogger.detachAppender(appender);
        appender.stop();
        BashPathValidator.TOKENIZER = BashParser::tokenize;
    }

    private List<String> warnMessages() {
        return appender.list.stream()
            .filter(ev -> ev.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    @Test
    @DisplayName("tokenize 抛异常 → 必须 Ask（不得 Passthrough）+ WARN 留痕含原始命令")
    void tokenizeFailure_failsClosedAsAsk_withWarnLog() {
        // 基线：同一命令在 tokenize 正常时 = Passthrough。
        // 该断言同时钉死夹具有效性——若 BASELINE 不再是 Passthrough，本用例失去鉴别力，
        // 下面的 Ask 断言就不再能证明「Ask 由失败分支产生」。
        assertThat(BashPathValidator.check(BENIGN_COMMAND, CWD, CWD, null))
            .as("夹具基线：'%s' 在 tokenize 正常时必须 Passthrough，否则本用例无鉴别力",
                BENIGN_COMMAND)
            .isInstanceOf(PermissionResult.Passthrough.class);

        // 注入 tokenize 失败（该分支实测不可达 —— 见类注释，故须注入才能驱动）
        BashPathValidator.TOKENIZER = cmd -> {
            throw new IllegalStateException("模拟 tokenizer 崩溃");
        };

        PermissionResult r = BashPathValidator.check(BENIGN_COMMAND, CWD, CWD, null);

        // 意图断言：不可静态分析的命令必须转人工确认（Ask），而不是被判定「校验通过」。
        // 反向实验：改回 return List.of() → 此处为 Passthrough → 红（实测输出见任务报告）。
        assertThat(r)
            .as("tokenize 失败 = 子命令不可静态分析 → 必须 fail-closed（Ask/Deny），"
                + "严禁 passthrough（= 路径校验被静默绕过）")
            .isInstanceOf(PermissionResult.Ask.class);

        // 可观测性断言（用户铁律「不传递不能有守卫，会默认吞掉异常，对后期修复不友好」）：
        // 失败必须 ≥ WARN 且带原始命令，否则线上无法反查是哪条命令绕过了校验。
        assertThat(warnMessages())
            .as("tokenize 失败必须留下 WARN 级日志（禁止零日志/DEBUG 吞掉）")
            .anyMatch(msg -> msg.contains("tokenize 失败") && msg.contains(BENIGN_COMMAND));
    }

    @Test
    @DisplayName("tokenize 正常 → 行为不变（Passthrough，且不产生 WARN 噪音）")
    void tokenizeOk_behaviorUnchanged() {
        // WHY: fail-closed 只允许作用于失败分支。若把它做成无条件 ask，正常命令会全部转人工，
        // 属过度收紧（可用性回归）。此用例钉死「正常路径零变化」。
        assertThat(BashPathValidator.check(BENIGN_COMMAND, CWD, CWD, null))
            .isInstanceOf(PermissionResult.Passthrough.class);
        assertThat(BashPathValidator.check("echo hi > /dev/null", CWD, CWD, null))
            .isInstanceOf(PermissionResult.Passthrough.class);

        assertThat(warnMessages())
            .as("正常路径不得产生 WARN（否则失败日志失去信号价值）")
            .isEmpty();
    }
}
