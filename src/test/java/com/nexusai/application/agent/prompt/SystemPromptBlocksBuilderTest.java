package com.nexusai.application.agent.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SP-06] {@link SystemPromptBlocksBuilder} blocks/cache_control 门控意图测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9)</b>:
 * <ol>
 *   <li><b>enablePromptCaching=false → 无 cache_control</b>（claude.ts:3225-3229）——
 *       缓存关时请求体必须无 cache_control，否则服务端可能因缓存头报错/产生意外缓存。</li>
 *   <li><b>cacheScope==NULL → 无 cache_control</b>（CacheScope.NULL 不参与缓存）。</li>
 *   <li><b>GLOBAL → cache_control.scope='global'</b>（claude.ts:371）；ORG 仅 type:'ephemeral'。</li>
 *   <li><b>[RES-R7] 默认 1h TTL</b>（09-open-decisions.md §六 R7）：默认配置 enable=true,
 *       ttl='1h' → cache_control 输出 {@code ttl:'1h'}；enable=false → 恒不输出 ttl（字节与改造前
 *       一致，可回退）；ttl 可配改值（如 '5m'）。</li>
 * </ol>
 */
class SystemPromptBlocksBuilderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 每用例后复位静态 ttl 配置（防 register 泄漏到后续用例）。 */
    @AfterEach
    void restoreTtlConfig() {
        PromptCachingTtlConfig.register(PromptCachingTtlConfig.DEFAULTS);
    }

    @Test
    @DisplayName("enablePromptCaching=false → 全部 block 无 cache_control（claude.ts:3225-3229）")
    void cachingDisabled_noCacheControl() {
        ArrayNode arr = SystemPromptBlocksBuilder.buildSystemPromptBlocks(
            List.of(new SystemPromptBlock("a", CacheScope.ORG),
                new SystemPromptBlock("b", CacheScope.GLOBAL)), false);
        assertThat(arr).hasSize(2);
        for (JsonNode block : arr) {
            assertThat(block.get("type").asText()).isEqualTo("text");
            assertThat(block.has("cache_control")).as("caching 关 → 无 cache_control").isFalse();
        }
    }

    @Test
    @DisplayName("cacheScope=NULL → 无 cache_control（该 block 不参与缓存）")
    void nullScope_noCacheControl() {
        ArrayNode arr = SystemPromptBlocksBuilder.buildSystemPromptBlocks(
            List.of(new SystemPromptBlock("attribution", CacheScope.NULL)), true);
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).has("cache_control")).isFalse();
    }

    @Test
    @DisplayName("caching 开 + ORG → cache_control={type:'ephemeral'} 无 scope")
    void cachingOn_orgScope_ephemeralOnly() {
        ArrayNode arr = SystemPromptBlocksBuilder.buildSystemPromptBlocks(
            List.of(new SystemPromptBlock("org", CacheScope.ORG)), true);
        JsonNode cc = arr.get(0).get("cache_control");
        assertThat(cc).isNotNull();
        assertThat(cc.get("type").asText()).isEqualTo("ephemeral");
        assertThat(cc.has("scope")).as("ORG → 无 scope 字段（claude.ts:371 仅 global 输出 scope）").isFalse();
    }

    @Test
    @DisplayName("caching 开 + GLOBAL → cache_control={type:'ephemeral', ttl:'1h', scope:'global'}（claude.ts:371 + :358-374）")
    void cachingOn_globalScope_hasScope() {
        ArrayNode arr = SystemPromptBlocksBuilder.buildSystemPromptBlocks(
            List.of(new SystemPromptBlock("static", CacheScope.GLOBAL)), true);
        JsonNode cc = arr.get(0).get("cache_control");
        assertThat(cc.get("type").asText()).isEqualTo("ephemeral");
        assertThat(cc.get("ttl").asText()).as("默认配置 → ttl:'1h'（RES-R7）").isEqualTo("1h");
        assertThat(cc.get("scope").asText()).isEqualTo("global");
    }

    @Test
    @DisplayName("getCacheControl(GLOBAL) 默认配置 → {type:'ephemeral', ttl:'1h', scope:'global'}（CC claude.ts:358-374 + :371）")
    void getCacheControl_global() {
        // 用户拍板（09 §六 R7）：默认 1h 生效（enable=true, ttl='1h'），不做 CC 用户资格门控
        JsonNode cc = SystemPromptBlocksBuilder.getCacheControl(CacheScope.GLOBAL);
        assertThat(cc.get("type").asText()).isEqualTo("ephemeral");
        assertThat(cc.get("ttl").asText()).as("默认配置 → ttl:'1h'（REQ-R7-1/2）").isEqualTo("1h");
        assertThat(cc.get("scope").asText()).isEqualTo("global");
    }

    @Test
    @DisplayName("配置 enable=false → cache_control 恒不输出 ttl（仅 {type:'ephemeral', scope?}，字节与改造前一致）")
    void getCacheControl_ttlDisabled_noTtl() {
        // 用户拍板 RES-R7：关闭时恒省略 ttl（可回退，REQ-R7-3）
        PromptCachingTtlConfig.register(new PromptCachingTtlConfig(false, "1h"));
        JsonNode cc = SystemPromptBlocksBuilder.getCacheControl(CacheScope.GLOBAL);
        assertThat(cc.get("type").asText()).isEqualTo("ephemeral");
        assertThat(cc.get("scope").asText()).isEqualTo("global");
        assertThat(cc.has("ttl")).as("enable=false → 无 ttl 字段").isFalse();
        // buildSystemPromptBlocks 全链同样省略
        ArrayNode arr = SystemPromptBlocksBuilder.buildSystemPromptBlocks(
            List.of(new SystemPromptBlock("static", CacheScope.GLOBAL)), true);
        assertThat(arr.get(0).get("cache_control").has("ttl")).isFalse();
    }

    @Test
    @DisplayName("配置 ttl 改值（'5m'）→ 输出该值（REQ-R7-3 可配 ttl）")
    void getCacheControl_ttlCustomValue() {
        PromptCachingTtlConfig.register(new PromptCachingTtlConfig(true, "5m"));
        JsonNode cc = SystemPromptBlocksBuilder.getCacheControl(CacheScope.GLOBAL);
        assertThat(cc.get("ttl").asText()).isEqualTo("5m");
        assertThat(cc.get("scope").asText()).isEqualTo("global");
        // ORG scope 同样输出改值 ttl
        JsonNode orgCc = SystemPromptBlocksBuilder.getCacheControl(CacheScope.ORG, "5m");
        assertThat(orgCc.get("ttl").asText()).isEqualTo("5m");
        assertThat(orgCc.has("scope")).isFalse();
    }

    @Test
    @DisplayName("ORG + ttl → {type:'ephemeral', ttl:'1h'} 无 scope（claude.ts:371 scope 仅 global）")
    void getCacheControl_orgScope_withTtl() {
        JsonNode cc = SystemPromptBlocksBuilder.getCacheControl(CacheScope.ORG);
        assertThat(cc.get("type").asText()).isEqualTo("ephemeral");
        assertThat(cc.get("ttl").asText()).isEqualTo("1h");
        assertThat(cc.has("scope")).isFalse();
    }

    @Test
    @DisplayName("null block / null text → 跳过不产出（防御）")
    void nullBlocks_skipped() {
        // Arrays.asList 允许 null 元素（List.of 拒绝 null → 用 asList 模拟含 null 的输入数组）
        java.util.List<SystemPromptBlock> input = java.util.Arrays.asList(
            new SystemPromptBlock(null, CacheScope.ORG), null);
        ArrayNode arr = SystemPromptBlocksBuilder.buildSystemPromptBlocks(input, true);
        assertThat(arr).isEmpty();
    }

    @Test
    @DisplayName("序列化产物可被 Jackson 反序列化（发送边界 JSON 合法）")
    void outputIsValidJson() throws Exception {
        ArrayNode arr = SystemPromptBlocksBuilder.buildSystemPromptBlocks(
            List.of(new SystemPromptBlock("t", CacheScope.GLOBAL)), true);
        JsonNode parsed = JSON.readTree(arr.toString());
        assertThat(parsed.get(0).get("text").asText()).isEqualTo("t");
    }
}
