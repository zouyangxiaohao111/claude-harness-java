#!/usr/bin/env python3
"""
Mock MCP server for testing McpToolPool · NEWLINE-delimited JSON-RPC (stdio transport).
Implements: initialize, tools/list, tools/call, resources/list, prompts/list, prompts/get.
Usage: launched as subprocess via StdioMcpTransport.
"""
import sys
import json


def send_response(id_, result):
    body = {"jsonrpc": "2.0", "id": id_, "result": result}
    sys.stdout.write(json.dumps(body) + "\n")
    sys.stdout.flush()


def send_error(id_, code, message):
    body = {"jsonrpc": "2.0", "id": id_, "error": {"code": code, "message": message}}
    sys.stdout.write(json.dumps(body) + "\n")
    sys.stdout.flush()


# P1-17: capabilities 增加 resources/prompts 声明（client.ts:2169 supportsResources=!!capabilities.resources）
INIT_RESULT = {
    "protocolVersion": "2024-11-05",
    "capabilities": {
        "tools": {"listChanged": False},
        "resources": {"subscribe": False, "listChanged": False},
        "prompts": {},
    },
    "serverInfo": {"name": "mock-mcp-server", "version": "1.0.0-test"},
}

TOOLS_LIST_RESULT = {
    "tools": [
        {
            "name": "echo",
            "description": "Echoes the input message",
            "inputSchema": {
                "type": "object",
                "properties": {"message": {"type": "string"}},
                "required": ["message"],
            },
        },
        {
            "name": "add",
            "description": "Adds two numbers",
            "inputSchema": {
                "type": "object",
                "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
                "required": ["a", "b"],
            },
        },
    ]
}

# P1-17: resources/list 响应（MCP ListResourcesResultSchema）
RESOURCES_LIST_RESULT = {
    "resources": [
        {
            "uri": "mock://docs/readme",
            "name": "Mock Readme",
            "description": "A mock resource for testing",
            "mimeType": "text/plain",
        },
        {
            "uri": "mock://data/example.json",
            "name": "Example JSON",
            "description": "Example JSON data",
            "mimeType": "application/json",
        },
    ]
}

# P1-17: prompts/list 响应（MCP ListPromptsResultSchema）
PROMPTS_LIST_RESULT = {
    "prompts": [
        {
            "name": "summarize",
            "description": "Summarize the given text",
            "arguments": [
                {"name": "text", "description": "Text to summarize"},
            ],
        },
        {
            "name": "translate",
            "description": "Translate text",
            "arguments": [
                {"name": "text", "description": "Text to translate"},
                {"name": "target", "description": "Target language"},
            ],
        },
    ]
}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception:
            continue
        method = msg.get("method", "")
        id_ = msg.get("id", 0)
        if method == "initialize":
            send_response(id_, INIT_RESULT)
        elif method == "tools/list":
            send_response(id_, TOOLS_LIST_RESULT)
        elif method == "resources/list":
            send_response(id_, RESOURCES_LIST_RESULT)
        elif method == "resources/read":
            params = msg.get("params", {})
            uri = params.get("uri", "")
            if uri == "mock://docs/readme":
                send_response(id_, {
                    "contents": [
                        {"uri": uri, "mimeType": "text/plain", "text": "Mock readme content for testing."},
                    ],
                })
            elif uri == "mock://data/example.json":
                send_response(id_, {
                    "contents": [
                        {"uri": uri, "mimeType": "application/json", "text": "{\"key\": \"value\"}"},
                    ],
                })
            else:
                send_error(id_, -32002, f"resource not found: {uri}")
        elif method == "prompts/list":
            send_response(id_, PROMPTS_LIST_RESULT)
        elif method == "prompts/get":
            params = msg.get("params", {})
            name = params.get("name", "")
            args = params.get("arguments", {})
            send_response(id_, {
                "description": f"prompt {name}",
                "messages": [
                    {"role": "user", "content": {"type": "text", "text": f"executed {name} with {json.dumps(args)}"}},
                ],
            })
        elif method == "tools/call":
            params = msg.get("params", {})
            tool_name = params.get("name", "")
            args = params.get("arguments", {})
            if tool_name == "echo":
                send_response(id_, {
                    "content": [{"type": "text", "text": args.get("message", "")}],
                    "isError": False,
                })
            elif tool_name == "add":
                result = args.get("a", 0) + args.get("b", 0)
                send_response(id_, {
                    "content": [{"type": "text", "text": str(result)}],
                    "isError": False,
                })
            elif tool_name == "needs_elicitation":
                # [impl-I-4 T6] URL elicitation 错误（ErrorCode.UrlElicitationRequired = -32042）
                body = {
                    "jsonrpc": "2.0", "id": id_,
                    "error": {
                        "code": -32042,
                        "message": "URL elicitation required",
                        "data": {
                            "elicitations": [
                                {
                                    "mode": "url",
                                    "url": "https://example.com/consent",
                                    "elicitationId": "elicit-mock-1",
                                    "message": "Please open the URL to authorize access",
                                },
                            ],
                        },
                    },
                }
                sys.stdout.write(json.dumps(body) + "\n")
                sys.stdout.flush()
            elif tool_name == "big_output":
                # [impl-I-4 T5] 大结果工具：默认 ~120k 字符（远超默认 25000 token * 4 阈值），
                # 经 args["size"] 可调（测试缩小阈值时用）
                size = int(args.get("size", 120000))
                text = "A" * size
                send_response(id_, {
                    "content": [{"type": "text", "text": text}],
                    "isError": False,
                })
            elif tool_name == "big_image":
                # [impl-I-4 T5] 含图片的大结果（图片走截断而非落盘）
                send_response(id_, {
                    "content": [
                        {"type": "text", "text": "A" * 120000},
                        {"type": "image", "data": "iVBORw0KGgoAAAANSUhEUg==", "mimeType": "image/png"},
                    ],
                    "isError": False,
                })
            else:
                send_error(id_, -32601, f"unknown tool: {tool_name}")
        elif method == "notifications/initialized":
            pass


if __name__ == "__main__":
    main()