package com.nexusai.application.agent.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Teleported /ultrareview execution · 对齐 CC commands/review/reviewRemote.ts.
 *
 * <p>CC source: commands/review/reviewRemote.ts (316 LOC).
 * - checkOverageGate (4 kinds: proceed/not-enabled/low-balance/needs-confirm)
 * - launchRemoteReview (PR mode or branch mode)
 */
public final class ReviewRemoteService {

    private static final Logger log = LoggerFactory.getLogger(ReviewRemoteService.class);

    public enum OverageKind { PROCEED, NOT_ENABLED, LOW_BALANCE, NEEDS_CONFIRM }

    public record OverageGate(OverageKind kind, String billingNote, Double available) {
        public static OverageGate proceed(String note) { return new OverageGate(OverageKind.PROCEED, note, null); }
    }

    public record QuotaInfo(int reviewsRemaining, int reviewsUsed, int reviewsLimit) {}
    public record ExtraUsageInfo(boolean isEnabled, Integer monthlyLimit, Integer usedCredits) {}
    public record UtilizationInfo(ExtraUsageInfo extraUsage) {}
    public record RepoInfo(String host, String owner, String name) {}
    public record RemoteSession(String id, String description) {}
    public record ContentBlockParam(String type, String text) {}

    public record OverageContext(
        Predicate<Boolean> isTeamSubscriber,
        Predicate<Boolean> isEnterpriseSubscriber,
        java.util.concurrent.CompletableFuture<QuotaInfo> quotaFuture,
        java.util.concurrent.CompletableFuture<UtilizationInfo> utilizationFuture,
        Supplier<String> sessionOverageConfirmedFlag
    ) {}

    public OverageGate checkOverageGate(OverageContext ctx) {
        if (ctx.isTeamSubscriber().test(true) || ctx.isEnterpriseSubscriber().test(true)) {
            return OverageGate.proceed("");
        }
        QuotaInfo quota;
        UtilizationInfo util;
        try {
            quota = ctx.quotaFuture().get();
            util = ctx.utilizationFuture().get();
        } catch (Exception e) {
            log.debug("[ReviewRemote] quota fetch failed: {}", e.getMessage());
            return OverageGate.proceed("");
        }
        if (quota == null) return OverageGate.proceed("");
        if (quota.reviewsRemaining() > 0) {
            return OverageGate.proceed(
                " This is free ultrareview " + (quota.reviewsUsed() + 1) + " of " + quota.reviewsLimit() + ".");
        }
        if (util == null) return OverageGate.proceed("");
        ExtraUsageInfo extra = util.extraUsage();
        if (extra == null || !extra.isEnabled()) {
            return new OverageGate(OverageKind.NOT_ENABLED, null, null);
        }
        Integer limit = extra.monthlyLimit();
        int used = extra.usedCredits() != null ? extra.usedCredits() : 0;
        double avail = (limit == null) ? Double.POSITIVE_INFINITY : limit - used;
        if (avail < 10) {
            return new OverageGate(OverageKind.LOW_BALANCE, null, avail);
        }
        if (ctx.sessionOverageConfirmedFlag().get() == null
            || !Boolean.parseBoolean(ctx.sessionOverageConfirmedFlag().get())) {
            return new OverageGate(OverageKind.NEEDS_CONFIRM, null, null);
        }
        return OverageGate.proceed(" This review bills as Extra Usage.");
    }

    public void confirmOverage() {
        // No-op: real impl sets a session flag. Test seam.
    }

    /** CC launchRemoteReview (simplified). */
    public List<ContentBlockParam> launchRemoteReview(
        String args,
        RepoInfo repo,
        String defaultBranch,
        String mergeBaseSha,
        boolean isPrNumber,
        int emptyDiff,
        OverageGate gate,
        String contextMsg
    ) {
        Objects.requireNonNull(gate);
        if (gate.kind() == OverageKind.NOT_ENABLED) {
            return List.of(new ContentBlockParam("text", "Ultrareview not enabled"));
        }
        if (gate.kind() == OverageKind.LOW_BALANCE) {
            return List.of(new ContentBlockParam("text", "Low balance: " + gate.available()));
        }
        if (emptyDiff == 0) {
            return List.of(new ContentBlockParam("text", "No changes"));
        }
        // In real impl, teleport + register remote task. Stub returns success message.
        String target = isPrNumber ? repo.owner() + "/" + repo.name() + "#" + args.trim() : defaultBranch;
        return List.of(new ContentBlockParam("text",
            "Ultrareview launched for " + target + ". Track: " + contextMsg + gate.billingNote()));
    }
}
