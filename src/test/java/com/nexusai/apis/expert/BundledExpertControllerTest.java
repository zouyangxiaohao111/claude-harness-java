package com.nexusai.apis.expert;

import com.nexusai.application.agent.bundled.BundledExpert;
import com.nexusai.application.agent.bundled.BundledExpertInstaller;
import com.nexusai.application.agent.bundled.BundledExpertsCatalog;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.loadAgentsDir;
import com.nexusai.apis.expert.BundledExpertController.BundledExpertDto;
import com.nexusai.apis.expert.BundledExpertController.InstallRequest;
import com.nexusai.apis.expert.BundledExpertController.InstallResponse;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内置专家「按需安装」链路：目录 API、幂等、409 冲突 / overwrite、安装后注册表可见。
 *
 * <h2>WHY</h2>
 * <p>这条链路的失败模式都是静默的：覆盖了用户改过的 md（数据丢失）、
 * 重复安装触发无谓的注册表重建、装完却因为缓存看不到（用户以为没装上）。
 * 用例用 {@link TempDir} 做隔离 HOME，不碰本机 {@code ~/.nexusai}。
 */
@DisplayName("[bundled-experts] 目录 API 与按需安装")
class BundledExpertControllerTest {

    private static final String SLUG = "engineering-backend-architect";

    @TempDir
    Path tmp;

    private Path home;
    private BundledExpertsCatalog catalog;
    private AtomicInteger refreshes;
    private BundledExpertInstaller installer;
    private BundledExpertController controller;

    @BeforeEach
    void setUp() {
        home = tmp.resolve("home");
        catalog = new BundledExpertsCatalog();
        refreshes = new AtomicInteger();
        installer = new BundledExpertInstaller(catalog, () -> home, refreshes::incrementAndGet);
        controller = new BundledExpertController(catalog, installer);
    }

    private Path targetOf(String slug) {
        BundledExpert e = catalog.findBySlug(slug).orElseThrow();
        return home.resolve("agents").resolve("octop").resolve(e.division()).resolve(slug + ".md");
    }

    @Test
    @DisplayName("目录 API：272 条、分类过滤、搜索、installed=false")
    void listsCatalog() {
        List<BundledExpertDto> all = controller.list(null, null);
        assertThat(all).hasSize(272);
        assertThat(all).allMatch(d -> !d.installed());
        assertThat(all.stream().map(BundledExpertDto::slug).distinct().count()).isEqualTo(272);

        assertThat(controller.list("engineering", null)).hasSize(42);
        assertThat(controller.list("engineering", null))
            .allMatch(d -> d.division().equals("engineering"));

        assertThat(controller.list(null, "后端架构师"))
            .extracting(BundledExpertDto::slug)
            .contains(SLUG);
    }

    @Test
    @DisplayName("安装幂等：首装 INSTALLED，重复 UNCHANGED 且不重复刷新注册表")
    void installIsIdempotent() throws Exception {
        InstallResponse first = controller.install(new InstallRequest(SLUG), false);
        assertThat(first.status()).isEqualTo("INSTALLED");
        Path target = targetOf(SLUG);
        assertThat(target).isRegularFile();
        assertThat(Files.readString(target, StandardCharsets.UTF_8))
            .contains("name: 后端架构师")
            .contains("source: TencentCloud/Octop");
        assertThat(refreshes.get()).isEqualTo(1);

        InstallResponse second = controller.install(new InstallRequest(SLUG), false);
        assertThat(second.status()).isEqualTo("UNCHANGED");
        assertThat(refreshes.get()).as("内容一致时不触发注册表重建").isEqualTo(1);

        assertThat(controller.list(null, "后端架构师").get(0).installed()).isTrue();
    }

    @Test
    @DisplayName("目标被本地改过：无 overwrite → 409 且不覆盖；overwrite → 覆盖并刷新")
    void conflictRequiresExplicitOverwrite() throws Exception {
        controller.install(new InstallRequest(SLUG), false);
        Path target = targetOf(SLUG);
        Files.writeString(target, "\n<!-- local edit -->\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        int refreshesBefore = refreshes.get();

        assertThatThrownBy(() -> controller.install(new InstallRequest(SLUG), false))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("overwrite=true");
        assertThat(Files.readString(target)).as("冲突时不得覆盖用户修改").contains("<!-- local edit -->");
        assertThat(refreshes.get()).as("冲突不触发刷新").isEqualTo(refreshesBefore);

        InstallResponse overwritten = controller.install(new InstallRequest(SLUG), true);
        assertThat(overwritten.status()).isEqualTo("OVERWRITTEN");
        assertThat(Files.readString(target)).doesNotContain("<!-- local edit -->");
        assertThat(refreshes.get()).isEqualTo(refreshesBefore + 1);
    }

    @Test
    @DisplayName("安装后：用户级 agent 加载链能看到该专家")
    void installedExpertIsVisibleToLoader() throws Exception {
        controller.install(new InstallRequest(SLUG), false);

        Map<String, AgentDefinition> agents = loadAgentsDir.load(home, "userSettings");
        assertThat(agents).containsKey("后端架构师");
        AgentDefinition def = agents.get("后端架构师");
        assertThat(def.whenToUse()).isNotBlank();
        assertThat(def.getSystemPrompt(null, List.of())).isNotBlank();
        assertThat(def.source()).isEqualTo("userSettings");
    }

    @Test
    @DisplayName("缺 slug → 400；未知 slug → 404")
    void validatesRequest() {
        assertThatThrownBy(() -> controller.install(new InstallRequest("  "), false))
            .isInstanceOf(ValidationException.class);

        assertThatThrownBy(() -> controller.install(new InstallRequest("no-such-expert"), false))
            .isInstanceOf(NotFoundException.class);
    }
}
