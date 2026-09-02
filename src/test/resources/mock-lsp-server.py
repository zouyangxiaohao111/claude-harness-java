#!/usr/bin/env python3
"""
Mock LSP server for testing ProcessLspClient · stdio JSON-RPC over LSP framed protocol.
Reads Content-Length framed JSON requests, responds to initialize/hover/definition.
Records didOpen/didChange/didSave notifications and exposes them via test/getNotifications.
Usage: launched as subprocess by ProcessLspClient tests.
"""
import sys
import json
import threading

_lock = threading.Lock()
_notifications = []


def send_response(id_, result):
    body = {"jsonrpc": "2.0", "id": id_, "result": result}
    payload = json.dumps(body).encode("utf-8")
    sys.stdout.buffer.write(f"Content-Length: {len(payload)}\r\n\r\n".encode("ascii"))
    sys.stdout.buffer.write(payload)
    sys.stdout.buffer.flush()


INIT_RESULT = {
    "protocolVersion": "2024-11-05",
    "capabilities": {
        "definitionProvider": True,
        "hoverProvider": True,
        "referencesProvider": True,
        "documentSymbolProvider": True,
    },
    "serverInfo": {"name": "mock-lsp-server", "version": "1.0.0-test"},
}
HOVER_RESULT = {"contents": {"kind": "markdown", "value": "mock hover content"}}
DEF_RESULT = {"location": {"uri": "file:///mock/definition.ts", "range": {
    "start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 10}}}}

# LSP sync notifications to record (method + params) for test/getNotifications verification.
SYNC_NOTIFICATIONS = {
    "textDocument/didOpen",
    "textDocument/didChange",
    "textDocument/didSave",
}


def record_notification(method, params):
    with _lock:
        _notifications.append({"method": method, "params": params})


def main():
    stdin = sys.stdin.buffer
    while True:
        # Read headers
        content_length = 0
        while True:
            line = stdin.readline()
            if not line:
                return  # EOF
            line = line.strip()
            if not line:
                break
            if line.lower().startswith(b"content-length:"):
                content_length = int(line.split(b":", 1)[1].strip())
        if content_length <= 0:
            continue
        # Read body
        body_bytes = stdin.read(content_length)
        if not body_bytes:
            return
        try:
            msg = json.loads(body_bytes)
        except Exception:
            continue
        method = msg.get("method", "")
        id_ = msg.get("id")
        if method == "initialize":
            send_response(id_, INIT_RESULT)
        elif method == "textDocument/hover":
            send_response(id_, HOVER_RESULT)
        elif method == "textDocument/definition":
            send_response(id_, DEF_RESULT)
        elif method == "test/getNotifications":
            with _lock:
                send_response(id_, list(_notifications))
        elif id_ is None and method in SYNC_NOTIFICATIONS:
            # fire-and-forget notification → record for E2E verification
            record_notification(method, msg.get("params", {}))
        # unknown methods: silent (notification or unimplemented)


if __name__ == "__main__":
    main()
