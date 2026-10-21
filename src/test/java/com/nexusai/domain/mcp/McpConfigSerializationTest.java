package com.nexusai.domain.mcp;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MCP 配置 args/env 的序列化往返。
 *
 * <p>验证意图（规则九）：这两个字段**存进 DB 又读回**必须逐字符不变。
 * 旧实现手写 JSON 转义、读侧只剥引号 ⇒ 含反斜杠的值（Windows 路径、`\\.\pipe\...` 命名管道）
 * 与转义引号（`["--headless"]`）全部走样 —— 表面「添加成功、连接测试通过」，
 * 实际子进程拿到的是坏路径/坏环境变量，表现为「MCP 加进去了但跑不起来」。
 * 这正是 Open Design MCP 案例的根因，因此用真实形态的样本钉住。
 */
class McpConfigSerializationTest {

    @Test
    void args_roundtrip_preserves_windows_paths_and_quotes() {
        List<String> args = List.of(
            "D:\\apps\\OpenDesign\\resources\\app\\prebundled\\daemon\\daemon-cli.mjs",
            "mcp",
            "a,b",                    // 含逗号：旧 split(",") 会拆坏
            "say \"hi\"");            // 含引号：旧实现会留下转义反斜杠

        String json = McpServerService.serializeArgs(args);
        List<String> back = McpServerService.deserializeArgs(json);

        assertEquals(args, back, "args 往返必须逐字符一致（含反斜杠 / 逗号 / 引号）");
    }

    @Test
    void env_roundtrip_preserves_pipe_path_and_escaped_json_value() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("OD_DATA_DIR", "C:\\Users\\WIN\\AppData\\Roaming\\Open Design\\namespaces\\release-stable-win\\data");
        // 命名管道前缀：`\\.\pipe\<name>` —— 少一个反斜杠就连不上 sidecar
        env.put("OD_SIDECAR_CLIENT_ENDPOINT", "\\\\.\\pipe\\open-design-sidecar-219e2f9db76efc09502269bc1595beb3");
        // 值本身是一段 JSON 字符串：引号必须原样保留（不是 \" 转义形态）
        env.put("OD_MCP_BOOTSTRAP_ARGS", "[\"--headless\"]");
        env.put("ELECTRON_RUN_AS_NODE", "1");

        String json = McpServerService.serializeEnv(env);
        Map<String, String> back = McpServerService.deserializeEnv(json);

        assertEquals(env, back, "env 往返必须逐字符一致");
        assertEquals("\\\\.\\pipe\\open-design-sidecar-219e2f9db76efc09502269bc1595beb3",
            back.get("OD_SIDECAR_CLIENT_ENDPOINT"),
            "命名管道前缀必须是 \\\\.\\pipe\\（双反斜杠 + 点），多一层或少一层都连不上");
        assertEquals("[\"--headless\"]", back.get("OD_MCP_BOOTSTRAP_ARGS"),
            "值里的引号必须是真引号，不能残留 \\\" 转义");
    }

    @Test
    void legacy_hand_escaped_db_rows_are_healed() {
        // 旧版本写进 DB 的形态（手写转义）：读回应还原成单反斜杠
        String legacyArgs = "[\"D:\\\\apps\\\\OpenDesign\\\\Open Design.exe\",\"mcp\"]";
        assertEquals(List.of("D:\\apps\\OpenDesign\\Open Design.exe", "mcp"),
            McpServerService.deserializeArgs(legacyArgs),
            "历史行必须自愈成正确路径（否则老用户重装后仍然跑不起来）");

        String legacyEnv = "{\"OD_SIDECAR_CLIENT_ENDPOINT\":\"\\\\\\\\.\\\\pipe\\\\open-design-sidecar-1\",\"OD_MCP_BOOTSTRAP_ARGS\":\"[\\\"--headless\\\"]\"}";
        Map<String, String> back = McpServerService.deserializeEnv(legacyEnv);
        assertEquals("\\\\.\\pipe\\open-design-sidecar-1", back.get("OD_SIDECAR_CLIENT_ENDPOINT"));
        assertEquals("[\"--headless\"]", back.get("OD_MCP_BOOTSTRAP_ARGS"));
    }

    @Test
    void malformed_json_falls_back_without_throwing() {
        // 历史脏数据：旧实现可能写出非法 JSON；兜底必须不抛异常、不丢参数
        assertEquals(List.of("a b"), McpServerService.deserializeArgs("[a b]"));
        assertEquals(Map.of("K", "v"), McpServerService.deserializeEnv("{K:v}"));
    }
}
