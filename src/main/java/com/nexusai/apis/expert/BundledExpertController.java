package com.nexusai.apis.expert;

import com.nexusai.application.agent.bundled.BundledExpert;
import com.nexusai.application.agent.bundled.BundledExpertInstaller;
import com.nexusai.application.agent.bundled.BundledExpertsCatalog;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

/**
 * 内置专家目录 + 按需安装。
 *
 * <p>目录 = classpath {@code experts/octop/**}（272 个，随安装包内置，默认不激活）；
 * 安装 = 复制到 {@code ~/.nexusai/agents/octop/<division>/<slug>.md} 并刷新注册表缓存。
 *
 * <p>契约：
 * <ul>
 *   <li>{@code GET /api/v1/experts/bundled?division=&query=} → {@link BundledExpertDto}[]</li>
 *   <li>{@code POST /api/v1/experts/bundled/install} body {@code {"slug": "..."}}
 *       （可选 {@code ?overwrite=true}）→ {@link InstallResponse}；
 *       目标已存在且内容不同且未允许覆盖 → 409。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/experts/bundled")
public class BundledExpertController {

    private static final Logger log = LoggerFactory.getLogger(BundledExpertController.class);

    private final BundledExpertsCatalog catalog;
    private final BundledExpertInstaller installer;

    public BundledExpertController(BundledExpertsCatalog catalog, BundledExpertInstaller installer) {
        this.catalog = catalog;
        this.installer = installer;
    }

    /** 目录列表（可过滤），`installed` = 目标文件当前是否存在。 */
    @GetMapping
    public List<BundledExpertDto> list(@RequestParam(required = false) String division,
                                       @RequestParam(required = false) String query) {
        List<BundledExpertDto> out = catalog.list(division, query).stream()
            .map(e -> new BundledExpertDto(
                e.slug(), e.division(), e.name(), e.description(),
                e.emoji(), e.color(), installer.isInstalled(e)))
            .toList();
        log.info("[BundledExperts] /list 查询命中 {} 个（division={}，query={}）", out.size(), division, query);
        return out;
    }

    /** 安装单个内置专家。 */
    @PostMapping("/install")
    public InstallResponse install(@RequestBody InstallRequest request,
                                   @RequestParam(defaultValue = "false") boolean overwrite) {
        if (request == null || request.slug() == null || request.slug().isBlank()) {
            // 用仓库既有异常族：GlobalExceptionHandler 把它们映射成 400/404/409。
            // ⛔ 不用 ResponseStatusException —— 它会落进兜底 500（真机实测）。
            throw new ValidationException("缺少 slug");
        }
        BundledExpert expert = catalog.findBySlug(request.slug())
            .orElseThrow(() -> new NotFoundException("内置专家不存在: " + request.slug()));
        BundledExpertInstaller.Result result;
        try {
            result = installer.install(expert, overwrite);
        } catch (IOException e) {
            log.warn("[BundledExperts] 安装写盘失败 slug={}: {}", expert.slug(), e.toString());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "安装失败: " + e.getMessage());
        }
        if (result == BundledExpertInstaller.Result.CONFLICT) {
            // 已有同名文件且内容被改过：不静默覆盖用户的本地修改。
            throw new ConflictException(
                "该专家已安装且内容与内置版本不同；确认覆盖请带 overwrite=true");
        }
        return new InstallResponse(result.name(), expert.slug(), installer.targetPath(expert).toString());
    }

    /** 目录条目视图（emoji/color 仅展示用）。 */
    public record BundledExpertDto(
        String slug,
        String division,
        String name,
        String description,
        String emoji,
        String color,
        boolean installed
    ) {}

    /** 安装请求。 */
    public record InstallRequest(String slug) {}

    /** 安装响应 · status ∈ INSTALLED / OVERWRITTEN / UNCHANGED。 */
    public record InstallResponse(String status, String slug, String path) {}
}
