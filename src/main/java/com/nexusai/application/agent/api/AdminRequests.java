package com.nexusai.application.agent.api;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin requests contract · 对齐 CC services/api/adminRequests.ts.
 *
 * <p>L1 语义: Team/Enterprise 用户向 admin 发起的请求 — limit_increase / seat_upgrade;
 *            pending 去重 (同 type 已有 pending → 返回 existing 而非新建);
 *            eligibility 检查 (org 是否允许某 type 请求).
 *            实际 HTTP 由 caller wired.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: AdminRequestType/Status enum + SeatUpgradeDetails record + CreateParams sealed + AdminRequest sealed + 3 method.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — prepareApiRequest → HTTP POST/GET → response;
 *       createAdminRequest 同 type pending 已存在 → 返回 existing.</li>
 *   <li><b>A3</b>: 注入式 (HttpFetcher + prepareApiRequest supplier); sealed types ensure discriminated union.</li>
 *   <li><b>A4</b>: prepareApiRequest 抛异常 → throw;Http 错误 → throw.</li>
 *   <li><b>A5</b>: 真实场景 — Team 用户在 plan 提升前发请求 admin;</li>
 *       seat upgrade flow 在新订阅前体验.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS discriminated union → Java sealed interface + record;
 *                    TS axios → Java HttpFetcher 注入式;
 *                    TS Promise → Java Supplier (异步由 caller wired).
 */
public final class AdminRequests {

    private static final Logger log = LoggerFactory.getLogger(AdminRequests.class);

    public enum AdminRequestType { LIMIT_INCREASE, SEAT_UPGRADE }
    public enum AdminRequestStatus { PENDING, APPROVED, DISMISSED }

    public record SeatUpgradeDetails(String message, String currentSeatTier) {}

    public sealed interface CreateParams permits CreateLimitIncrease, CreateSeatUpgrade {
        AdminRequestType requestType();
    }
    public record CreateLimitIncrease() implements CreateParams {
        public AdminRequestType requestType() { return AdminRequestType.LIMIT_INCREASE; }
    }
    public record CreateSeatUpgrade(SeatUpgradeDetails details) implements CreateParams {
        public AdminRequestType requestType() { return AdminRequestType.SEAT_UPGRADE; }
    }

    public sealed interface AdminRequest permits AdminLimitIncrease, AdminSeatUpgrade {
        String uuid();
        AdminRequestStatus status();
        String requesterUuid();
        String createdAt();
        AdminRequestType requestType();
    }

    public record AdminLimitIncrease(String uuid, AdminRequestStatus status,
        String requesterUuid, String createdAt) implements AdminRequest {
        public AdminRequestType requestType() { return AdminRequestType.LIMIT_INCREASE; }
    }

    public record AdminSeatUpgrade(String uuid, AdminRequestStatus status,
        String requesterUuid, String createdAt, SeatUpgradeDetails details)
        implements AdminRequest {
        public AdminRequestType requestType() { return AdminRequestType.SEAT_UPGRADE; }
    }

    public record EligibilityResponse(AdminRequestType requestType, boolean isAllowed) {}

    public interface HttpFetcher {
        AdminRequest post(String endpoint, Map<String, String> headers, CreateParams params);
        AdminRequest[] get(String endpoint, Map<String, String> headers);
        EligibilityResponse getEligibility(String endpoint, Map<String, String> headers);
    }

    private final Supplier<ApiContext> apiContextSupplier;
    private final HttpFetcher httpFetcher;

    public AdminRequests(Supplier<ApiContext> apiContextSupplier, HttpFetcher httpFetcher) {
        this.apiContextSupplier = Objects.requireNonNull(apiContextSupplier);
        this.httpFetcher = httpFetcher;
    }

    public AdminRequests() {
        this(() -> new ApiContext(null, null), null);
    }

    public record ApiContext(String accessToken, String orgUuid) {}

    /** CC createAdminRequest — 主链. */
    public AdminRequest createAdminRequest(CreateParams params) {
        if (params == null) throw new IllegalArgumentException("params null");
        ApiContext ctx = apiContextSupplier.get();
        String url = baseUrl() + "/api/oauth/organizations/" + ctx.orgUuid() + "/admin_requests";
        return httpFetcher.post(url, headers(ctx), params);
    }

    /** CC getMyAdminRequests. */
    public AdminRequest[] getMyAdminRequests(AdminRequestType requestType,
            AdminRequestStatus[] statuses) {
        ApiContext ctx = apiContextSupplier.get();
        StringBuilder url = new StringBuilder(baseUrl() + "/api/oauth/organizations/"
            + ctx.orgUuid() + "/admin_requests/me?request_type=" + requestType.name().toLowerCase());
        for (AdminRequestStatus s : statuses) {
            url.append("&statuses=").append(s.name().toLowerCase());
        }
        return httpFetcher.get(url.toString(), headers(ctx));
    }

    /** CC checkAdminRequestEligibility. */
    public EligibilityResponse checkAdminRequestEligibility(AdminRequestType requestType) {
        ApiContext ctx = apiContextSupplier.get();
        String url = baseUrl() + "/api/oauth/organizations/" + ctx.orgUuid()
            + "/admin_requests/eligibility?request_type=" + requestType.name().toLowerCase();
        return httpFetcher.getEligibility(url, headers(ctx));
    }

    private static Map<String, String> headers(ApiContext ctx) {
        return Map.of(
            "Authorization", "Bearer " + (ctx.accessToken() == null ? "" : ctx.accessToken()),
            "x-organization-uuid", ctx.orgUuid() == null ? "" : ctx.orgUuid());
    }

    private static String baseUrl() {
        return "https://api.anthropic.com";
    }
}