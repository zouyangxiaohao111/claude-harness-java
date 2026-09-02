package com.nexusai.application.agent.mcp.config;

import java.io.IOException;
import java.nio.file.NoSuchFileException;

/**
 * 文件读取函数（.mcp.json IO 由 caller 注入）· 对齐 CC config.ts parseMcpConfigFromFilePath
 * 的 fs.readFileSync（config.ts:1397）。ENOENT 抛 {@link NoSuchFileException}。
 */
@FunctionalInterface
public interface McpFileReader {
    String read(String path) throws IOException;
}
