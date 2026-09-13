package com.nexusai.application.agent.plugin;

import java.nio.file.Path;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.common.SessionProjectRoot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [cwd-align-extended A1/A2] MarketplaceConfigStore 默认 cwdSupplier 接线意图测试 ·
 * 对齐 CC {@code getOriginalCwd()}（addMarketplaceSource resolve(source.path)=process.cwd()=启动 cwd 语义，
 * marketplaceManager.ts:1792-1793；reconciler.ts:131 diffMarketplaces 传 projectRoot: getOriginalCwd()）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：cwdSupplier 由
 * {@code () -> CwdResolution.getOriginalCwdLayer(sessionId)} 提供（方案1），
 * 而非直读 {@code System.getProperty("user.dir")}。会话绑定项目（{@link SessionProjectRoot#setForSession}）
 * 时，local marketplace 源归一化（resolveLocalPath / normalizeSource 的 base）必须解析到会话绑定项目根，
 * 否则 worktree/多项目部署下相对源会被解析到错误的 JVM 启动目录。无会话时回落 user.dir（零行为变化）。
 *
 * <p><b>[批 3c]</b>：本类消费链无会话来源（全链无 sessionId 形参/字段可穿透，见
 * MarketplaceConfigStore 默认构造器 javadoc），故<b>默认</b> cwdSupplier 显式按「无会话」解析
 * （{@code getOriginalCwdLayer(null)}）。会话感知调用方须显式注入
 * {@code () -> CwdResolution.getOriginalCwdLayer(sessionId)}。
 */
@DisplayName("[cwd-align-extended] MarketplaceConfigStore 默认 cwdSupplier → CwdResolution.getOriginalCwdLayer 接线")
class MarketplaceConfigStoreCwdWiringTest {

    @Test
    @DisplayName("默认 cwdSupplier 经 CwdResolution.getOriginalCwdLayer：无会话回落 user.dir（零行为变化）")
    void defaultCwdSupplier_noSession_fallsBackToUserDir() {
        // WHY: startup 无会话路径（PluginStartupAssembler）→ getOriginalCwdLayer(null) 逐层回落 user.dir，
        //      与旧 `() -> System.getProperty("user.dir")` 行为一致 —— 接线零回归的锚定。
        MarketplaceConfigStore store = new MarketplaceConfigStore(null);
        assertThat(store.getOriginalCwd())
            .as("无会话 → getOriginalCwd() 回落 user.dir（对齐 CC 无会话兜底）")
            .isEqualTo(CwdResolution.getOriginalCwdLayer(null))
            .isEqualTo(CwdResolution.normalizeCwd(System.getProperty("user.dir", ".")));
    }

    @Test
    @DisplayName("默认 cwdSupplier 会话绑定项目生效：getOriginalCwd() 返回绑定项目而非 user.dir（CC getOriginalCwd 语义）")
    void defaultCwdSupplier_boundSession_returnsBoundProject(@TempDir Path boundProject) {
        // WHY: cwdSupplier 经会话 id 解析会话 original cwd（对齐 CC getOriginalCwd 稳定启动 cwd）。会话
        //      绑定项目（SessionProjectRoot.setForSession）时，resolveLocalPath/normalizeSource 的 base
        //      必须用会话 original cwd，而非 JVM user.dir —— 这是 getOriginalCwdLayer 接线相对直读
        //      user.dir 的语义价值所在。
        String sessionId = "cwd-wiring-test-" + System.nanoTime();
        SessionProjectRoot.setForSession(sessionId, boundProject.toString());
        try {
            // [批 3c] 语义消失：MarketplaceConfigStore 默认 cwdSupplier 已显式按「无会话」解析
            //   （getOriginalCwdLayer(null)；裸 MDC 会话槽已彻底删除）⇒ 原「默认 supplier 经裸 MDC 取
            //   会话」不可达。改为会话感知调用方按 javadoc 显式注入 supplier；断言文本不变。
            MarketplaceConfigStore store = new MarketplaceConfigStore(null, null,
                () -> CwdResolution.getOriginalCwdLayer(sessionId));
            assertThat(store.getOriginalCwd())
                .as("会话绑定项目 → getOriginalCwd() 返回绑定项目（对齐 CC getOriginalCwd 稳定 original cwd）")
                .isEqualTo(CwdResolution.normalizeCwd(boundProject.toString()));
        } finally {
            SessionProjectRoot.clearSession(sessionId);
        }
    }
}
