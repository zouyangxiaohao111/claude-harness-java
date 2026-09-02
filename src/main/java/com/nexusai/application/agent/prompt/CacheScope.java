package com.nexusai.application.agent.prompt;

/**
 * 系统提示缓存作用域 · 对齐 CC {@code CacheScope}
 * （CC original: {@code CacheScope = 'global' | 'org'} (utils/api.ts:80)）。
 *
 * <p>CC 中 {@code cacheScope: CacheScope | null}（utils/api.ts:83）三态联合的忠实编码：
 * GLOBAL / ORG 对应两个字符串成员，NULL 对应 null 联合成员（该 block 不参与缓存）。
 */
public enum CacheScope {

    /** CC original: 'global' (utils/api.ts:80) —— 静态部分，全局可缓存 */
    GLOBAL,

    /** CC original: 'org' (utils/api.ts:80) —— 组织级缓存 */
    ORG,

    /** CC original: null（CacheScope|null 的 null 联合成员, utils/api.ts:83）—— 该 block 不参与缓存 */
    NULL
}
