package com.nexusai.application.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E4-2（IMP-MV2-25）· ?U-1 YAML 三方对抗 Java 侧（SnakeYAML）。
 *
 * <p>与 JS 侧（Bun.YAML.parse / eemeli 'yaml'，归档 e4-evidence/yaml-{bun,eemeli}.json）对照：
 * 样本集 = 重复 key / 数字 value / YAML 1.1 vs 1.2 布尔 / 锚点别名 / 空值 / 引号数字。
 * Java 侧 = MemoryScanner 同款 {@code new Yaml()}（SnakeYAML 2.4，YAML 1.1 语义——实际版本
 * 实证：mvn dependency:tree 唯一解析 org.yaml:snakeyaml:jar:2.4:compile，pom 无 yaml 直声明；
 * 运行时含 2.x 独有 "duplicate keys found" 告警；m2 snakeyaml-2.4.jar 独立探针复现全部行为）。
 * 样本源 = 归档目录 {@code 探查/memory_v2/implementation/e4-evidence/}（7 个 *.yaml），
 * 与 .cjs/.json/.txt 证据文件同目录，读取时按 .yaml 后缀过滤。
 */
@DisplayName("[E4-2] YAML 三方对抗 Java 侧：SnakeYAML 行为固化")
class E4YamlSnakeyamlProbeTest {

    private static final Path SAMPLES = Path.of(
        "F:/nexusai-backend/.worktrees/impl-mv2-h/探查/memory_v2/implementation/e4-evidence");

    private static final Yaml YAML = new Yaml();

    /** 与 JS JSON.stringify 对齐的规范化序列化。 */
    private static String norm(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            map.forEach((k, v) -> sb.append('"').append(k).append("\":").append(norm(v)).append(','));
            if (!map.isEmpty()) sb.setLength(sb.length() - 1);
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            list.forEach(v -> sb.append(norm(v)).append(','));
            if (!list.isEmpty()) sb.setLength(sb.length() - 1);
            return sb.append(']').toString();
        }
        if (value instanceof String s) return "\"" + s + "\"";
        if (value instanceof Boolean b) return b ? "true" : "false";
        return String.valueOf(value);
    }

    @Test
    @DisplayName("SnakeYAML 解析样本集并输出规范化结果（对照 JS 双解析器）")
    void snakeyamlProbe() throws Exception {
        StringBuilder report = new StringBuilder();
        for (Path sample : Files.list(SAMPLES)
            .filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
            String text = Files.readString(sample);
            report.append(sample.getFileName()).append(": ");
            try {
                Object parsed = YAML.load(text);
                report.append(norm(parsed)).append('\n');
            } catch (Exception e) {
                report.append("ERROR: ").append(e.getMessage()).append('\n');
            }
        }
        System.out.println("=====SNAKEYAML-PROBE-START=====");
        System.out.print(report);
        System.out.println("=====SNAKEYAML-PROBE-END=====");
        // 非空断言：脚本载体，跑通即证据（输出经 surefire 捕获）
        assertThat(report).isNotEmpty();
    }

    @Test
    @DisplayName("SnakeYAML 标量类型（对 CC 消费面 type/description 的语义）")
    void snakeyamlScalarTypes() {
        // YAML 1.1 布尔：on/yes 是布尔（YAML 1.2/eemeli 为字符串）——Java 消费面类型偏移源；
        // 单字母 y/n 除外：SnakeYAML 2.4 不认（YAML 1.1 规范中 y/n 为可选表示），按字符串解析
        Object on = ((java.util.Map<String, Object>) YAML.load("v: on\n")).get("v");
        assertThat(on).isEqualTo(Boolean.TRUE);
        Object yes = ((java.util.Map<String, Object>) YAML.load("v: yes\n")).get("v");
        assertThat(yes).isEqualTo(Boolean.TRUE);
        // 裸 y/n：SnakeYAML 2.4 → String（与 Bun.YAML/eemeli 三方一致，非 YAML 1.1 布尔）——
        // 实测 bool-12-y.yaml 输出 {"yes_key":"y","no_key":"n"}（e4-evidence/yaml-bun.json 同）
        Object y = ((java.util.Map<String, Object>) YAML.load("v: y\n")).get("v");
        assertThat(y).as("SnakeYAML 2.4 裸 y → String \"y\"（三方一致）").isEqualTo("y");
        Object n = ((java.util.Map<String, Object>) YAML.load("v: n\n")).get("v");
        assertThat(n).as("SnakeYAML 2.4 裸 n → String \"n\"（三方一致）").isEqualTo("n");
        // YAML 1.2（eemeli/Bun.YAML）→ String "on"/"yes" —— 三方差异点（MemoryScanner 消费
        // description 转字符串，type 转字符串，差异仅在下游「type==='true'」类字符串比较场景）
        Object plainTrue = ((java.util.Map<String, Object>) YAML.load("v: true\n")).get("v");
        assertThat(plainTrue).isEqualTo(Boolean.TRUE);
        // 数字 value：007 → Integer 7（前导零非八进制）；1e3 → Double 1000.0
        Object rank = ((java.util.Map<String, Object>) YAML.load("v: 007\n")).get("v");
        assertThat(rank).isEqualTo(7);
        Object score = ((java.util.Map<String, Object>) YAML.load("v: 1e3\n")).get("v");
        assertThat(score).isEqualTo(1000.0);
        // 重复 key：SnakeYAML 2.4（本仓实际版本——mvn dependency:tree 唯一解析
        // org.yaml:snakeyaml:jar:2.4:compile，pom 无直声明，纯传递依赖；运行时仅告警
        // "duplicate keys found"（2.x 独有，1.26 静默）不抛错）→ 后者胜（与 Bun.YAML 一致；
        // eemeli 抛 "Map keys must be unique" → MemoryScanner 双重失败降级 {} —— 病态输入分歧，
        // 正常 frontmatter 无重复 key）
        Object dup = ((java.util.Map<String, Object>) YAML.load("a: 1\na: 2\n")).get("a");
        assertThat(dup).as("SnakeYAML 2.4 重复 key 后者胜（同 Bun.YAML；eemeli 报错）").isEqualTo(2);
        // 空值 → null（三方一致）
        Object empty = ((java.util.Map<String, Object>) YAML.load("v: \n")).get("v");
        assertThat(empty).isNull();
        Object nullish = ((java.util.Map<String, Object>) YAML.load("v: null\n")).get("v");
        assertThat(nullish).isNull();
        // 引号数字 → String（三方一致）
        Object quoted = ((java.util.Map<String, Object>) YAML.load("v: \"1.0\"\n")).get("v");
        assertThat(quoted).isEqualTo("1.0");
    }
}
