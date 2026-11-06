package com.nexusai.kb.app.retrieval.engine;

import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbVectorStoreRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbVectorStoreMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索驱动解析顺序 · <b>批次 W8 新增「实例层覆盖类型层」</b>。
 *
 * <p><b>WHY</b>：W8 加了 {@code vector_stores} 表之后，「这个库到底用哪个驱动」
 * 变成两级判断。这里最容易出的错是<b>顺序反了</b> ——
 * 绑了实例却仍按 {@code retriever_engine} 走，用户在界面上明明选了
 * 「生产 Qdrant 集群」，实际查的是默认 sqlite，而且不报错。
 * ⇒ 断言必须固定顺序：<b>实例 &gt; 库类型 &gt; 全局 &gt; sqlite</b>。
 */
@DisplayName("[W8] 驱动解析：实例层覆盖类型层")
class KbRetrievalEngineInstanceResolutionTest {

    private static KbRetrievalEngine fake(String type) {
        return new KbRetrievalEngine() {
            @Override public String engineType() { return type; }
            @Override public boolean available() { return true; }
            @Override public boolean supports(Feature feature) { return true; }
            @Override public void index(String kbId, List<IndexDocument> docs, String modelId) { }
            @Override public void deleteByKnowledgeIds(String kbId, List<String> ids) { }
            @Override public List<Hit> retrieve(RetrieveParams params) { return List.of(); }
        };
    }

    /** 用一个只实现 selectOneById 的动态代理冒充 mapper。 */
    @SuppressWarnings("unchecked")
    private static <T> T stubMapper(Class<T> type, String id, Object row) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> {
                if (method.getName().equals("selectOneById")) {
                    return (args != null && args.length == 1 && id.equals(args[0])) ? row : null;
                }
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) return false;
                if (rt == int.class) return 0;
                if (rt == long.class) return 0L;
                return null;
            });
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static KbKnowledgeBaseRecord kb(String retrieverEngine, String vectorStoreId) {
        KbKnowledgeBaseRecord r = new KbKnowledgeBaseRecord();
        r.setRetrieverEngine(retrieverEngine);
        r.setVectorStoreId(vectorStoreId);
        return r;
    }

    private static KbVectorStoreRecord store(String engineType) {
        KbVectorStoreRecord r = new KbVectorStoreRecord();
        r.setId("vs-1");
        r.setName("prod-qdrant");
        r.setEngineType(engineType);
        return r;
    }

    @Nested
    @DisplayName("⭐ 顺序：实例 > 库类型 > 全局 > sqlite")
    class Order {

        @Test
        @DisplayName("⭐ 绑了实例 ⇒ 用实例的引擎类型（覆盖库上的 retriever_engine）")
        void boundInstanceWins() throws Exception {
            KbRetrievalEngineRegistry reg = new KbRetrievalEngineRegistry(List.of(fake("sqlite")));
            inject(reg, "knowledgeBaseMapper", stubMapper(
                KbKnowledgeBaseMapper.class, "kb-1", kb("sqlite", "vs-1")));
            inject(reg, "vectorStoreMapper", stubMapper(
                KbVectorStoreMapper.class, "vs-1", store("qdrant")));

            assertThat(reg.resolveEngineType("kb-1"))
                .as("⭐ 绑了实例就必须用实例的类型，否则「选了生产集群却查本地库」，且不报错")
                .isEqualTo("qdrant");
        }

        @Test
        @DisplayName("没绑实例 ⇒ 回落库级 retriever_engine")
        void fallsBackToKbType() throws Exception {
            KbRetrievalEngineRegistry reg = new KbRetrievalEngineRegistry(List.of(fake("sqlite")));
            inject(reg, "knowledgeBaseMapper", stubMapper(
                KbKnowledgeBaseMapper.class, "kb-1", kb("milvus", null)));
            inject(reg, "vectorStoreMapper", stubMapper(
                KbVectorStoreMapper.class, "vs-1", store("qdrant")));

            assertThat(reg.resolveEngineType("kb-1")).isEqualTo("milvus");
        }

        @Test
        @DisplayName("库上什么都没配 ⇒ sqlite")
        void defaultsToSqlite() throws Exception {
            KbRetrievalEngineRegistry reg = new KbRetrievalEngineRegistry(List.of(fake("sqlite")));
            inject(reg, "knowledgeBaseMapper", stubMapper(
                KbKnowledgeBaseMapper.class, "kb-1", kb(null, null)));

            assertThat(reg.resolveEngineType("kb-1")).isEqualTo("sqlite");
        }

        @Test
        @DisplayName("knowledgeBaseId 为 null ⇒ 直接 sqlite（不查库）")
        void nullKbIdDefaults() {
            KbRetrievalEngineRegistry reg = new KbRetrievalEngineRegistry(List.of(fake("sqlite")));
            assertThat(reg.resolveEngineType(null)).isEqualTo("sqlite");
        }
    }

    @Nested
    @DisplayName("脏数据容错")
    class DirtyData {

        @Test
        @DisplayName("⭐ 绑定存在但实例查不到 ⇒ 回落库级类型（⛔ 不让整库不可读）")
        void missingInstanceFallsBack() throws Exception {
            KbRetrievalEngineRegistry reg = new KbRetrievalEngineRegistry(List.of(fake("sqlite")));
            inject(reg, "knowledgeBaseMapper", stubMapper(
                KbKnowledgeBaseMapper.class, "kb-1", kb("milvus", "vs-gone")));
            // 实例表里没有 vs-gone
            inject(reg, "vectorStoreMapper", stubMapper(
                KbVectorStoreMapper.class, "vs-other", store("qdrant")));

            assertThat(reg.resolveEngineType("kb-1"))
                .as("实例被误删后，让用户继续用原驱动，比「整个库读不出来」好")
                .isEqualTo("milvus");
        }

        @Test
        @DisplayName("库不存在 ⇒ sqlite")
        void missingKbDefaults() throws Exception {
            KbRetrievalEngineRegistry reg = new KbRetrievalEngineRegistry(List.of(fake("sqlite")));
            inject(reg, "knowledgeBaseMapper", stubMapper(
                KbKnowledgeBaseMapper.class, "some-other", kb("milvus", null)));

            assertThat(reg.resolveEngineType("kb-1")).isEqualTo("sqlite");
        }

        @Test
        @DisplayName("实例的 engineType 为空 ⇒ 回落库级类型")
        void blankStoreTypeFallsBack() throws Exception {
            KbRetrievalEngineRegistry reg = new KbRetrievalEngineRegistry(List.of(fake("sqlite")));
            inject(reg, "knowledgeBaseMapper", stubMapper(
                KbKnowledgeBaseMapper.class, "kb-1", kb("milvus", "vs-1")));
            inject(reg, "vectorStoreMapper", stubMapper(
                KbVectorStoreMapper.class, "vs-1", store("")));

            assertThat(reg.resolveEngineType("kb-1")).isEqualTo("milvus");
        }

        @Test
        @DisplayName("mapper 缺失（精简装配）⇒ 仍能返回 sqlite，不抛 NPE")
        void noMapperStillWorks() {
            KbRetrievalEngineRegistry reg = new KbRetrievalEngineRegistry(List.of(fake("sqlite")));
            assertThat(reg.resolveEngineType("kb-1")).isEqualTo("sqlite");
        }
    }
}
