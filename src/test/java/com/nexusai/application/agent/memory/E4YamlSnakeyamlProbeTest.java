package com.nexusai.application.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E4-2（IMP-MV2-25）· skill frontmatter 的 SnakeYAML 标量 / 重复 key 解析语义固化。
 *
 * <p><b>本类只固化 Java 侧解析语义</b>：夹具全部是内存字符串（{@code new Yaml().load("v: on\n")}），
 * 不读文件系统、不读任何外部样本。被固化的解析库版本：{@code org.yaml:snakeyaml} **2.4**
 * （证据 {@code mvn -o dependency:tree -Dincludes=org.yaml:snakeyaml} →
 * {@code spring-boot-starter → org.yaml:snakeyaml:jar:2.4:compile}，backend/pom.xml 无直声明 ⇒
 * 纯传递依赖，全仓 classpath 上只有一个 snakeyaml 版本）。生产 frontmatter 解析入口
 * （{@code ParseSkillFrontmatter.java:80} 的 Jackson {@code YAMLMapper(new YAMLFactory())}）底层
 * YAML 解析库即该版本 ⇒ 升级/降级 snakeyaml 会改变消费面（{@code description}/{@code type}）拿到的
 * 标量类型，本类必须随升级显式红，而不是静默漂移。
 *
 * <p><b>退役记录 · 原三方对照探针 {@code snakeyamlProbe}</b>（本类原第 1 个用例，已删）：
 * 它扫描归档目录 {@code 探查/memory_v2/implementation/e4-evidence/} 下的 7 个 {@code *.yaml} 样本，
 * 把 SnakeYAML 的规范化输出与 JS 侧（{@code Bun.YAML.parse} / eemeli {@code yaml}）的
 * {@code yaml-bun.json} / {@code yaml-eemeli.json} 对照。退役理由三条，逐条可核：
 * <ol>
 *   <li><b>样本与对照物都不在本仓</b>：{@code git ls-files | grep -c 探查} = 0；
 *       {@code backend/src/test/resources/e4-evidence/} 目录在本仓**不存在**。脚本硬编码的绝对路径
 *       指向一个不存在的盘 ⇒ 环境性恒红。</li>
 *   <li><b>唯一断言恒真</b>：{@code assertThat(report).isNotEmpty()} —— {@code report} 对每个样本
 *       至少 append 一行「文件名: 」，只要样本目录非空就必然通过；它对样本内容、解析结果、与 JS 侧
 *       的一致性**零断言**。它是「跑通即证据」的脚本，不是测试（夹具在则永绿，夹具丢则永红）。</li>
 *   <li><b>不重建样本</b>：原始 7 个 yaml 与两份 JS 输出已不在本仓，凭记忆重建等于伪造外部对照证据
 *       （触铁律：不得凭空捏造代码/证据状态）。如需恢复该探针，须由用户提供原始素材后另立批次。</li>
 * </ol>
 */
@DisplayName("[E4-2] SnakeYAML 2.4 对 skill frontmatter 标量 / 重复 key 的解析语义")
class E4YamlSnakeyamlProbeTest {

    private static final Yaml YAML = new Yaml();

    @Test
    @DisplayName("SnakeYAML 标量类型（对 CC 消费面 type/description 的语义）")
    void snakeyamlScalarTypes() {
        // YAML 1.1 布尔：on/yes 是布尔；单字母 y/n 除外 —— SnakeYAML 2.4 不认（YAML 1.1 规范中
        // y/n 属下表可选表示），按字符串解析。换用 YAML 1.2 语义的解析器则 on/yes/n/y 全为字符串
        // ⇒ 消费面类型会静默改变，故在此钉死当前行为。
        Object on = ((java.util.Map<String, Object>) YAML.load("v: on\n")).get("v");
        assertThat(on).isEqualTo(Boolean.TRUE);
        Object yes = ((java.util.Map<String, Object>) YAML.load("v: yes\n")).get("v");
        assertThat(yes).isEqualTo(Boolean.TRUE);
        // 裸 y/n：SnakeYAML 2.4 → String "y"/"n"（非布尔）
        Object y = ((java.util.Map<String, Object>) YAML.load("v: y\n")).get("v");
        assertThat(y).as("SnakeYAML 2.4 裸 y → String \"y\"（非布尔）").isEqualTo("y");
        Object n = ((java.util.Map<String, Object>) YAML.load("v: n\n")).get("v");
        assertThat(n).as("SnakeYAML 2.4 裸 n → String \"n\"（非布尔）").isEqualTo("n");
        Object plainTrue = ((java.util.Map<String, Object>) YAML.load("v: true\n")).get("v");
        assertThat(plainTrue).isEqualTo(Boolean.TRUE);
        // 数字 value：007 → Integer 7（前导零非八进制）；1e3 → Double 1000.0
        Object rank = ((java.util.Map<String, Object>) YAML.load("v: 007\n")).get("v");
        assertThat(rank).isEqualTo(7);
        Object score = ((java.util.Map<String, Object>) YAML.load("v: 1e3\n")).get("v");
        assertThat(score).isEqualTo(1000.0);
        // 重复 key：SnakeYAML 2.x 仅告警 "duplicate keys found"（1.26 静默）不抛错，后者胜。
        // 病态输入下各解析器分歧（YAML 1.2 的 eemeli 会直接报错），正常 frontmatter 无重复 key。
        Object dup = ((java.util.Map<String, Object>) YAML.load("a: 1\na: 2\n")).get("a");
        assertThat(dup).as("SnakeYAML 2.x 重复 key 后者胜（仅告警，不抛错）").isEqualTo(2);
        // 空值 → null
        Object empty = ((java.util.Map<String, Object>) YAML.load("v: \n")).get("v");
        assertThat(empty).isNull();
        Object nullish = ((java.util.Map<String, Object>) YAML.load("v: null\n")).get("v");
        assertThat(nullish).isNull();
        // 引号数字 → String
        Object quoted = ((java.util.Map<String, Object>) YAML.load("v: \"1.0\"\n")).get("v");
        assertThat(quoted).isEqualTo("1.0");
    }
}
