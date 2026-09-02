package com.nexusai.test.support;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.mybatis.Mappers;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MyBatis-Flex DB 测试共享支撑 · 解决全局单例 + mapper 代理缓存跨测试类冲突。
 *
 * <p><b>WHY（规则九）</b>: MyBatis-Flex 的 mapper 代理经静态 {@code Mappers.MAPPER_OBJECTS}
 * 缓存，且 {@code Mappers.MapperHandler} 在<b>代理创建时</b>捕获 {@code FlexGlobalConfig}
 * 的 SqlSessionFactory（非调用时读取）。同一 JVM 内多个测试类若各自 bind 不同 datasource /
 * mapper 集合，后跑的测试 getMapper 拿到的是绑旧工厂的缓存代理 → {@code not known to
 * MapperRegistry} / SQLite 路径不存在。全量套件（单 fork JVM）必然触发。
 *
 * <p>本类在每个测试类 {@code @BeforeAll} 调用 {@link #resetAndStart}：
 * <ol>
 *   <li>清空 {@code Mappers.MAPPER_OBJECTS}（下次 ofMapperClass 重建代理，绑定新工厂）</li>
 *   <li>重置单例 {@code started=false + configuration=null + mappers 清空}（start() 用新 datasource 重建）</li>
 *   <li>注册本类 mapper 并 start()（经 FlexSqlSessionFactoryBuilder 更新 FlexGlobalConfig）</li>
 * </ol>
 * 共享稳定 DB 路径（{@link #sharedDbPath}）：Flyway 一次性建全部表，各测试只读写自身表 + clean() 清行，
 * 表间互不污染，且避免 JUnit @TempDir 目录在 @BeforeAll 时未物化的 Windows 时序问题。
 */
public final class MybatisFlexDbTestSupport {

    private MybatisFlexDbTestSupport() {}

    /** 共享稳定 SQLite DB 路径（target/ 不被 mid-suite 清理，mvn clean 才删）。 */
    public static Path sharedDbPath() {
        return Path.of("target", "flex-dbtest", "flex.db");
    }

    /** 重置 MyBatis-Flex 全局状态并绑定新 datasource + mappers（@BeforeAll 首行调用）。 */
    public static void resetAndStart(DataSource ds, Class<?>... mapperClasses) throws Exception {
        // 1. 清全局 mapper 代理缓存
        Field mapperObjects = Mappers.class.getDeclaredField("MAPPER_OBJECTS");
        mapperObjects.setAccessible(true);
        ((Map<?, ?>) mapperObjects.get(null)).clear();
        // 2. 重置单例内部状态，使 start() 用新 datasource 重建
        MybatisFlexBootstrap inst = MybatisFlexBootstrap.getInstance();
        Field started = MybatisFlexBootstrap.class.getDeclaredField("started");
        started.setAccessible(true);
        ((AtomicBoolean) started.get(inst)).set(false);
        Field configuration = MybatisFlexBootstrap.class.getDeclaredField("configuration");
        configuration.setAccessible(true);
        configuration.set(inst, null);
        Field mappers = MybatisFlexBootstrap.class.getDeclaredField("mappers");
        mappers.setAccessible(true);
        Object mappersValue = mappers.get(inst);
        if (mappersValue instanceof List<?> mappersList) {
            mappersList.clear();
        }
        // 3. 注册本类 mapper + start（更新 FlexGlobalConfig 的 SqlSessionFactory）
        for (Class<?> m : mapperClasses) {
            inst.addMapper(m);
        }
        inst.setDataSource(ds).start();
    }
}
