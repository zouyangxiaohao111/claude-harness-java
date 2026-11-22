package com.nexusai.domain.im_channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.domain.im_channel.oauth.FeishuUserTokenRefresher;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.model.im_channel.ImInstanceEntry;
import com.nexusai.repository.im_channel.entity.ImChannelInstanceRecord;
import com.nexusai.repository.im_channel.mapper.ImChannelInstanceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * IM 渠道实例业务逻辑（V302）。
 *
 * <p><b>实例 vs 接线</b>：本服务只管「连上了哪些账号」（凭证、连接状态、游标）。
 * 「消息该给哪个会话」是 {@code ChannelBindingService}（V255）的事。
 * 两者分开是刻意的：实例不归属会话 —— 任何会话都能用任一实例发消息（发送即接线）。
 *
 * <p><b>凭证安全</b>：{@code credential} 以 {@link CryptoUtil#encrypt} 密文落库；
 * {@link ImInstanceEntry} 不带该字段，因此列表接口<b>不可能</b>把凭证漏到前端。
 * 需要明文时只能走 {@link #credentialOf(String)}（内部发消息/建连用）。
 *
 * <p><b>白名单必须是显式的</b>（用户裁定「别用启发式兜底猜身份」）：
 * {@code platform} 只接受四个已知值，拼错直接 400，不做"看起来像微信就按微信处理"。
 */
@Service
public class ImChannelService {

    private static final Logger log = LoggerFactory.getLogger(ImChannelService.class);

    /** 平台白名单 —— 与前端 PCHAR/PNAME 一一对应，新增平台必须同步这里。 */
    public static final List<String> PLATFORMS = List.of("wechat", "feishu", "wecom", "dingtalk");

    @Autowired private ImChannelInstanceMapper mapper;
    @Autowired private CryptoUtil cryptoUtil;
    /** 创建完成事件（自动启动连接）；可空：纯单元测试可不起 Spring 事件。 */
    @Autowired(required = false) private org.springframework.context.ApplicationEventPublisher events;

    public List<ImInstanceEntry> list() {
        return mapper.selectAllOrdered().stream().map(ImChannelInstanceRecord::toDomain).toList();
    }

    public List<ImInstanceEntry> listConnected() {
        return mapper.selectListConnected().stream().map(ImChannelInstanceRecord::toDomain).toList();
    }

    public Optional<ImInstanceEntry> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        ImChannelInstanceRecord r = mapper.selectOneById(id);
        return r == null ? Optional.empty() : Optional.of(r.toDomain());
    }

    /**
     * 新建实例。
     *
     * @param credential 凭证明文 JSON（本方法内加密后落库，调用方不应记录它）
     * @param configJson 平台特有开关 JSON（可空）
     */
    public ImInstanceEntry create(String platform, String displayName, String credential, String configJson) {
        requirePlatform(platform);
        if (displayName == null || displayName.isBlank()) {
            throw new ValidationException("displayName 不能为空");
        }
        ImChannelInstanceRecord r = new ImChannelInstanceRecord();
        r.setId("imc-" + UUID.randomUUID().toString().substring(0, 8));
        r.setPlatform(platform);
        r.setDisplayName(displayName.trim());
        r.setCredential(credential == null || credential.isBlank() ? null : cryptoUtil.encrypt(credential));
        // 刚建出来还没建连 ⇒ disconnected（真正连上后由 adapter 回写 connected）
        r.setStatus("disconnected");
        r.setConfigJson(configJson);
        r.setCreatedAt(now());
        r.setUpdatedAt(now());
        mapper.insert(r);
        log.info("[ImChannelService] 新增实例: platform={} name={} id={}", platform, displayName, r.getId());
        // 用户定：连接后默认启动（手填 / 扫码 / 一键创建都走这里）。
        // 用事件而不是直接调 ImInboundManager：管理器依赖本服务，直接注入会成环。
        if (events != null) {
            try {
                events.publishEvent(new ImInstanceCreatedEvent(r.getId()));
            } catch (RuntimeException e) {
                // 自动启动失败不能把「已建好实例」变成创建失败；用户仍可在卡片上手动启动
                log.warn("[ImChannelService] 自动启动事件发布失败 id={} err={}", r.getId(), e.toString());
            }
        }
        return r.toDomain();
    }

    /** 改显示名 / 平台开关。凭证轮换走 {@link #rotateCredential}，不与本方法混。 */
    public ImInstanceEntry update(String id, String displayName, String configJson) {
        ImChannelInstanceRecord r = mapper.selectOneById(id);
        if (r == null) throw new ValidationException("实例不存在: " + id);
        if (displayName != null && !displayName.isBlank()) r.setDisplayName(displayName.trim());
        if (configJson != null) r.setConfigJson(configJson);
        r.setUpdatedAt(now());
        mapper.update(r);
        return r.toDomain();
    }

    /** 轮换凭证（重新扫码/换 Secret）。 */
    public void rotateCredential(String id, String credential) {
        ImChannelInstanceRecord r = mapper.selectOneById(id);
        if (r == null) throw new ValidationException("实例不存在: " + id);
        r.setCredential(cryptoUtil.encrypt(credential));
        r.setUpdatedAt(now());
        mapper.update(r);
        log.info("[ImChannelService] 凭证已轮换: id={}", id);
    }

    /** 回写连接状态（adapter 建连/断线时调）。 */
    public void markStatus(String id, String status, String detail) {
        ImChannelInstanceRecord r = mapper.selectOneById(id);
        if (r == null) return;
        r.setStatus(status);
        r.setStatusDetail(detail);
        r.setLastActiveAt(now());
        r.setUpdatedAt(now());
        mapper.update(r);
    }

    /** 持久化游标（微信 iLink 单消费者语义：进程重启必须从这里续读）。 */
    public void saveCursor(String id, String cursor) {
        ImChannelInstanceRecord r = mapper.selectOneById(id);
        if (r == null) return;
        r.setLastCursor(cursor);
        r.setLastActiveAt(now());
        mapper.update(r);
    }

    public String cursorOf(String id) {
        ImChannelInstanceRecord r = mapper.selectOneById(id);
        return r == null ? null : r.getLastCursor();
    }

    /**
     * 取凭证明文（发消息/建连内部用）。
     *
     * <p>⛔ 不要把这个方法的返回值放进任何 DTO / 日志 / 异常消息。
     */
    public Optional<String> credentialOf(String id) {
        ImChannelInstanceRecord r = mapper.selectOneById(id);
        if (r == null || r.getCredential() == null) return Optional.empty();
        return Optional.of(cryptoUtil.decrypt(r.getCredential()));
    }

    // ═════════════ V313：飞书 user_access_token（以用户本人名义发消息）═════════════

    /**
     * 保存 OAuth 授权得到的用户令牌。
     *
     * <p><b>为什么落在实例上而不是新表</b>：token 是「应用 × 用户」授权产物，
     * 而一个实例对应一个应用凭证 ⇒ 实例就是这一关系的主键，不需要第二张表。
     *
     * <p><b>加密范围</b>：access/refresh 两个 token 都是 Bearer 凭证，必须与
     * {@code credential} 同一套 {@link CryptoUtil} 密文落库；{@code openId} 是飞书
     * 可见字段，明文存（排障要能直接读到「授权的是谁」）。
     *
     * @param expiresAt ISO-8601 过期时间；null ⇒ 读侧按「已过期」处理（宁可让用户重授权）
     */
    public void saveUserToken(String id, String accessToken, String refreshToken,
                              String expiresAt, String openId) {
        saveUserToken(id, accessToken, refreshToken, expiresAt, openId, null);
    }

    /**
     * 保存用户令牌 + 授权人昵称（V314）。
     *
     * <p>{@code displayName} 只有 {@code authen/v1/user_info} 能给 —— 那是唯一
     * 「不受应用通讯录范围限制」的用户资料来源。拿不到就传 null，UI 回落显示 id 尾号。
     */
    public void saveUserToken(String id, String accessToken, String refreshToken,
                              String expiresAt, String openId, String displayName) {
        saveUserToken(id, accessToken, refreshToken, expiresAt, openId, displayName, null);
    }

    /**
     * 保存用户令牌 + 昵称 + **实际授予的 scope**（V316）。
     *
     * <p>{@code scope} 原样保存飞书返回的空格分隔串：以后 {@code DEFAULT_SCOPE} 扩展时，
     * 拿它比对就能知道「这张旧 token 缺哪些权限」，而不是等调用失败才暴露。
     */
    public void saveUserToken(String id, String accessToken, String refreshToken,
                              String expiresAt, String openId, String displayName, String scope) {
        ImChannelInstanceRecord r = mapper.selectOneById(id);
        if (r == null) throw new ValidationException("渠道实例不存在: " + id);
        r.setUserToken(accessToken == null || accessToken.isBlank() ? null : cryptoUtil.encrypt(accessToken));
        r.setUserRefreshToken(refreshToken == null || refreshToken.isBlank()
                ? null : cryptoUtil.encrypt(refreshToken));
        r.setUserTokenExpiresAt(expiresAt);
        r.setUserOpenId(openId);
        r.setUserDisplayName(displayName == null || displayName.isBlank() ? null : displayName);
        r.setUserScope(scope == null || scope.isBlank() ? null : scope.trim());
        r.setUpdatedAt(now());
        mapper.update(r);
        log.info("[ImChannelService] 已保存飞书用户令牌 instance={} openId={} name={} expiresAt={}",
                id, openId, displayName, expiresAt);
    }

    /**
     * 令牌续期器（飞书 {@code refresh_token} grant）。
     *
     * <p>{@code required=false}：本类被大量单测直接 new 出来用（无 Spring 上下文），
     * 缺它时退化成「过期即不可用」，不影响其它平台。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.domain.im_channel.oauth.FeishuUserTokenRefresher userTokenRefresher;

    /** 按实例串行化刷新，避免 8 个并发发送同时拿一张过期的票去换（会互相作废 refresh_token）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> refreshLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 下次允许尝试刷新的时间（毫秒）。失败后冷却，别让每条消息都去打一遍飞书。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> refreshCooldownUntil =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 读可用的 user_access_token。
     *
     * <h2>过期时先续期，而不是直接当没有</h2>
     * 授权时申请了 {@code offline_access} ⇒ 有 refresh_token，
     * 所以「过期」应当触发一次静默续期（见 {@link #refreshUserToken}）。
     * ⛔ 只有续期也失败（refresh_token 失效/被吊销）才返回 empty，
     * 表示**真的需要用户重新授权** —— 这两种情况对上层是不同语义。
     *
     * <p>为什么不能拿过期票硬试：飞书回 99991677（token invalid），
     * 上层会误判成权限问题而不是「票过期了」。
     */
    public Optional<String> userTokenOf(String id) {
        ImChannelInstanceRecord r = mapper.selectOneById(id);
        if (r == null || r.getUserToken() == null || r.getUserToken().isBlank()) {
            return Optional.empty();
        }
        try {
            String current = cryptoUtil.decrypt(r.getUserToken());
            if (!isExpired(r.getUserTokenExpiresAt())) {
                return Optional.of(current);
            }
            // 过期 ⇒ 先用 refresh_token 续一次（授权时申请的 offline_access 就是为这个）
            return Optional.ofNullable(refreshUserToken(id, r));
        } catch (RuntimeException e) {
            log.warn("[ImChannelService] 用户令牌解密失败 instance={} err={}", id, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 库里是否存在 user token 记录（不解密、不续期）。
     *
     * <p>用于区分「从未授权」与「授权过期且自动续期失败」——{@link #userTokenOf}
     * 两种情况都返回 empty，但给用户的提示不同：前者要他先授权，后者要他重新授权。
     */
    public boolean hasUserToken(String id) {
        ImChannelInstanceRecord r = mapper.selectOneById(id);
        return r != null && r.getUserToken() != null && !r.getUserToken().isBlank();
    }

    /**
     * 拿 {@code refresh_token} 换新票并落库。
     *
     * <h2>为什么「过期」不等于「要用户重新授权」</h2>
     * 授权时申请了 {@code offline_access} ⇒ 飞书给了 refresh_token。只要它还有效，
     * 刷新就能静默续期 —— 让用户每 2 小时点一次「同意」是实现缺陷，不是 OAuth 的必然。
     *
     * <h2>三重保护</h2>
     * <ol>
     *   <li><b>按实例加锁</b>：并发发送可能同时发现「过期」，若两个线程都去刷，
     *       第二次会拿一张已被消费的 refresh_token（飞书通常一次性）⇒ 必然失败；</li>
     *   <li><b>锁内二次检查</b>：等锁期间别人可能已经刷好了，直接返回新票；</li>
     *   <li><b>失败冷却</b>：refresh_token 真失效时，别让每条消息都去打一次飞书
     *       （每次都要等超时），冷却期内直接返回 empty 走降级。</li>
     * </ol>
     *
     * @return 新 token；刷新不可用/失败 ⇒ null（调用方按「没有用户令牌」降级）
     */
    private String refreshUserToken(String id, ImChannelInstanceRecord r) {
        if (userTokenRefresher == null || r.getUserRefreshToken() == null || r.getUserRefreshToken().isBlank()) {
            return null;
        }
        Long until = refreshCooldownUntil.get(id);
        if (until != null && System.currentTimeMillis() < until) {
            return null;
        }
        Object lock = refreshLocks.computeIfAbsent(id, k -> new Object());
        synchronized (lock) {
            // 锁内二次检查：别的线程可能刚刷新过（此时库里的过期时间已是新的）
            ImChannelInstanceRecord fresh = mapper.selectOneById(id);
            if (fresh == null || fresh.getUserToken() == null || fresh.getUserToken().isBlank()) {
                return null;
            }
            if (!isExpired(fresh.getUserTokenExpiresAt())) {
                try {
                    return cryptoUtil.decrypt(fresh.getUserToken());
                } catch (RuntimeException e) {
                    return null;
                }
            }
            String refreshToken;
            String appId;
            String appSecret;
            try {
                refreshToken = cryptoUtil.decrypt(fresh.getUserRefreshToken());
                JsonNode cred = objectMapperTree(credentialOf(id).orElse(null));
                if (cred == null) {
                    return null;
                }
                appId = text(cred, "appId", "app_id");
                appSecret = text(cred, "appSecret", "app_secret");
            } catch (RuntimeException e) {
                log.warn("[ImChannelService] 续期前读凭证失败 instance={} err={}", id, e.toString());
                return null;
            }
            try {
                FeishuUserTokenRefresher.Refreshed t = userTokenRefresher.refresh(appId, appSecret, refreshToken);
                // 只更新令牌三列：⛔ 不能走 saveUserToken（它会把 displayName/openId 一起覆盖）
                fresh.setUserToken(cryptoUtil.encrypt(t.accessToken()));
                fresh.setUserRefreshToken(t.refreshToken() == null || t.refreshToken().isBlank()
                        ? fresh.getUserRefreshToken()
                        : cryptoUtil.encrypt(t.refreshToken()));
                fresh.setUserTokenExpiresAt(t.expiresAt());
                fresh.setUpdatedAt(now());
                mapper.update(fresh);
                refreshCooldownUntil.remove(id);
                log.info("[ImChannelService] 用户令牌已自动续期 instance={} expiresAt={}", id, t.expiresAt());
                return t.accessToken();
            } catch (FeishuUserTokenRefresher.RefreshException e) {
                // 冷却 5 分钟：refresh_token 失效时反复打飞书毫无意义，只会拖慢每条消息
                refreshCooldownUntil.put(id, System.currentTimeMillis() + 5 * 60_000L);
                log.warn("[ImChannelService] 用户令牌续期失败（需重新授权）instance={} err={}", id, e.getMessage());
                return null;
            }
        }
    }

    private JsonNode objectMapperTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.asText("").isBlank()) {
                return v.asText("");
            }
        }
        return "";
    }

    /**
     * 授权用户的昵称（V314）。
     *
     * <p>⚠️ 只代表「授权的那个人」。入站 p2p 消息只有在
     * {@code sender.open_id == user_open_id} 时才能拿它当会话名。
     */
    public Optional<String> userNameOf(String id) {
        ImChannelInstanceRecord r = mapper.selectOneById(id);
        if (r == null || r.getUserDisplayName() == null || r.getUserDisplayName().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(r.getUserDisplayName());
    }

    /**
     * 这次授权实际拿到的 scope（V316）；没记录过 ⇒ empty。
     *
     * <p>⚠️ empty 有两种含义：① 从没授权 ② 老数据没有这一列（V316 之前授权的）。
     * 调用方（授权引导 UI）应把两者都当成「建议重新授权一次」。
     */
    public Optional<String> userScopeOf(String id) {
        ImChannelInstanceRecord r = mapper.selectOneById(id);
        if (r == null || r.getUserScope() == null || r.getUserScope().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(r.getUserScope());
    }

    /** 授权用户的 open_id（没授权过 ⇒ empty）。 */
    public Optional<String> userOpenIdOf(String id) {
        ImChannelInstanceRecord r = mapper.selectOneById(id);
        if (r == null || r.getUserOpenId() == null || r.getUserOpenId().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(r.getUserOpenId());
    }

    /**
     * 清除用户授权（用户点「取消授权」/ token 被吊销时）。
     *
     * <p>⚠️ 必须用 {@code UpdateChain} 显式写 null：MyBatis-Flex 的 {@code update(entity)}
     * <b>忽略 null 字段</b> ⇒ 用实体置 null 再 update 会「接口说成功、库里原样还在」。
     */
    public void clearUserToken(String id) {
        com.mybatisflex.core.update.UpdateChain.of(ImChannelInstanceRecord.class)
                .set(ImChannelInstanceRecord::getUserToken, null)
                .set(ImChannelInstanceRecord::getUserRefreshToken, null)
                .set(ImChannelInstanceRecord::getUserTokenExpiresAt, null)
                .set(ImChannelInstanceRecord::getUserOpenId, null)
                .set(ImChannelInstanceRecord::getUserDisplayName, null)
                .set(ImChannelInstanceRecord::getUserScope, null)
                .set(ImChannelInstanceRecord::getUpdatedAt, now())
                .where(ImChannelInstanceRecord::getId).eq(id)
                .update();
        log.info("[ImChannelService] 已清除飞书用户令牌 instance={}", id);
    }

    /** 过期为 true（空/非法时间也当过期，宁可不发也不发一条会被拒的请求）。 */
    private static boolean isExpired(String iso) {
        if (iso == null || iso.isBlank()) {
            return true;
        }
        try {
            return java.time.OffsetDateTime.parse(iso)
                    .isBefore(java.time.OffsetDateTime.now().minusSeconds(60));
        } catch (RuntimeException e) {
            return true;
        }
    }

    public boolean delete(String id) {
        if (id == null || id.isBlank()) return false;
        if (mapper.selectOneById(id) == null) return false;
        mapper.deleteById(id);
        log.info("[ImChannelService] 删除实例: id={}", id);
        return true;
    }

    private static void requirePlatform(String platform) {
        if (platform == null || !PLATFORMS.contains(platform)) {
            throw new ValidationException("platform 必须是 " + PLATFORMS + " 之一，实际: " + platform);
        }
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
