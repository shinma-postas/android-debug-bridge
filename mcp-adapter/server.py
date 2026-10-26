#!/usr/bin/env python3
"""stdio MCP adapter. Forwards tool calls to the localhost debug bridge."""

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

HOME = Path.home() / ".android-debug-bridge"
ENDPOINT = HOME / "endpoint.json"

TOOLS = [
    {
        "name": "bp_add",
        "description": "Add a breakpoint. type is line (default), method, field, or exception. line, method, and field need file and a 1-based line. method goes on a function declaration, field on a property declaration. exception needs className, or * for any exception.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "type": {"type": "string", "enum": ["line", "method", "field", "exception"]},
                "file": {"type": "string"},
                "line": {"type": "integer"},
                "className": {"type": "string"},
                "condition": {"type": "string"},
                "suspend": {"type": "string", "enum": ["all", "thread", "none"]},
                "temporary": {"type": "boolean"},
                "projectPath": {"type": "string"},
            },
        },
    },
    {
        "name": "bp_list",
        "description": "List breakpoints. Each item has type: line, method, field, or exception.",
        "inputSchema": {"type": "object", "properties": {"projectPath": {"type": "string"}}},
    },
    {
        "name": "bp_remove",
        "description": "Remove a breakpoint created by this bridge.",
        "inputSchema": {
            "type": "object",
            "properties": {"id": {"type": "string"}, "projectPath": {"type": "string"}},
            "required": ["id"],
        },
    },
    {
        "name": "bp_clear",
        "description": "Remove every breakpoint in the IDE for this project.",
        "inputSchema": {"type": "object", "properties": {"projectPath": {"type": "string"}}},
    },
    {
        "name": "attach",
        "description": "Attach the IDE debugger to an already running Android process. Does not reinstall the app. serial is the device serial. package defaults to jp.co.persol.postas.selfregi.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "serial": {"type": "string"},
                "package": {"type": "string"},
                "timeout": {"type": "integer", "description": "milliseconds. under 1000 is rejected"},
                "projectPath": {"type": "string"},
            },
            "required": ["serial"],
        },
    },
    {
        "name": "start",
        "description": "Start an existing run configuration under the debugger.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "configuration": {"type": "string"},
                "timeout": {"type": "integer"},
                "projectPath": {"type": "string"},
            },
            "required": ["configuration"],
        },
    },
    {
        "name": "sessions",
        "description": "List debug sessions owned by Android Studio.",
        "inputSchema": {"type": "object", "properties": {"projectPath": {"type": "string"}}},
    },
    {
        "name": "control",
        "description": "PAUSE, RESUME, RESUME_VM, STEP_OVER, STEP_INTO, STEP_OUT, STOP, or WAIT_FOR_PAUSE. RESUME also resumes suspend=thread stops. RESUME_VM resumes the whole VM as a last resort.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "action": {"type": "string"},
                "sessionId": {"type": "string"},
                "timeout": {"type": "integer"},
                "projectPath": {"type": "string"},
            },
            "required": ["action"],
        },
    },
    {
        "name": "stack",
        "description": "Call stack of a suspended session.",
        "inputSchema": {
            "type": "object",
            "properties": {"sessionId": {"type": "string"}, "projectPath": {"type": "string"}},
        },
    },
    {
        "name": "variables",
        "description": "Locals in the current frame. Secret-like names are masked.",
        "inputSchema": {
            "type": "object",
            "properties": {"sessionId": {"type": "string"}, "projectPath": {"type": "string"}},
        },
    },
    {
        "name": "evaluate",
        "description": "Evaluate an expression in the current frame. This runs code in the debuggee. Call it only when you mean to.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "expression": {"type": "string"},
                "sessionId": {"type": "string"},
                "projectPath": {"type": "string"},
            },
            "required": ["expression"],
        },
    },
]


def read_message():
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            return None
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.lower().startswith(b"content-length:"):
            length = int(stripped.split(b":", 1)[1].strip())
            while True:
                extra = sys.stdin.buffer.readline()
                if extra in (b"\r\n", b"\n", b""):
                    break
                if extra.lower().startswith(b"content-length:"):
                    length = int(extra.split(b":", 1)[1].strip())
            if length <= 0:
                return None
            return json.loads(sys.stdin.buffer.read(length).decode())
        return json.loads(stripped.decode())


def write_message(payload):
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def bridge(method, path, body=None, project=None, allow_eval=False, timeout=120):
    data = json.loads(ENDPOINT.read_text())
    token = Path(data["tokenFile"]).read_text().strip()
    raw = None if body is None else json.dumps(body).encode()
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Project-Path": project or os.environ.get("ANDROID_STUDIO_PROJECT") or os.getcwd(),
        "Accept": "application/json",
    }
    if raw is not None:
        headers["Content-Type"] = "application/json"
    if allow_eval:
        headers["X-ASdbg-Allow-Eval"] = "1"
    req = urllib.request.Request(data["url"].rstrip("/") + path, data=raw, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            text = response.read().decode()
            return text, False
    except urllib.error.HTTPError as error:
        return error.read().decode(), True


def session_id(args):
    if args.get("sessionId"):
        return args["sessionId"]
    text, failed = bridge("GET", "/v1/sessions", project=args.get("projectPath"))
    if failed:
        raise RuntimeError(text)
    listed = json.loads(text)["sessions"]
    if len(listed) == 1:
        return listed[0]["id"]
    raise RuntimeError(text if not listed else f"pass sessionId. sessions={text}")


def call_tool(name, args):
    project = args.get("projectPath")
    if name == "bp_add":
        kind = args.get("type") or "line"
        body = {"type": kind, "temporary": bool(args.get("temporary"))}
        if args.get("condition"):
            body["condition"] = args["condition"]
        if args.get("suspend"):
            body["suspend"] = args["suspend"]
        if kind == "exception":
            body["class"] = args.get("className") or args.get("class")
        else:
            body["file"] = args["file"]
            body["line"] = args["line"]
        return bridge("POST", "/v1/breakpoints", body, project)
    if name == "bp_list":
        return bridge("GET", "/v1/breakpoints", project=project)
    if name == "bp_remove":
        return bridge("DELETE", "/v1/breakpoints/" + args["id"], project=project)
    if name == "bp_clear":
        return bridge("DELETE", "/v1/breakpoints", project=project)
    if name == "attach":
        timeout = int(args.get("timeout") or 60000)
        if timeout < 1000:
            return json.dumps({"error": f"timeout is in milliseconds. got {timeout} ms. Use 30000 for 30 seconds"}), True
        body = {"serial": args["serial"], "timeout": timeout}
        if args.get("package"):
            body["package"] = args["package"]
        if args.get("force"):
            body["force"] = True
        if args.get("waitForProcess"):
            body["waitForProcess"] = True
        return bridge("POST", "/v1/attach", body, project, timeout=timeout / 1000 + 5)
    if name == "start":
        timeout = int(args.get("timeout") or 30000)
        return bridge("POST", "/v1/sessions", {
            "configuration": args["configuration"],
            "timeout": timeout,
        }, project, timeout=timeout / 1000 + 5)
    if name == "sessions":
        return bridge("GET", "/v1/sessions", project=project)
    if name == "control":
        sid = session_id(args)
        timeout = int(args.get("timeout") or 60000)
        return bridge("POST", f"/v1/sessions/{sid}/control", {
            "action": args["action"],
            "timeout": timeout,
        }, project, timeout=timeout / 1000 + 5)
    if name == "stack":
        return bridge("GET", f"/v1/sessions/{session_id(args)}/stack", project=project)
    if name == "variables":
        return bridge("GET", f"/v1/sessions/{session_id(args)}/variables", project=project)
    if name == "evaluate":
        return bridge("POST", f"/v1/sessions/{session_id(args)}/evaluate", {
            "expression": args["expression"],
        }, project, allow_eval=True)
    return json.dumps({"error": f"unknown tool {name}"}), True


def handle(message):
    method = message.get("method")
    msg_id = message.get("id")
    if method == "notifications/initialized":
        return None
    if method == "initialize":
        requested = (message.get("params") or {}).get("protocolVersion") or "2024-11-05"
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {
                "protocolVersion": requested,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "android-studio-debug", "version": "0.1.0"},
            },
        }
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"tools": TOOLS}}
    if method == "tools/call":
        params = message.get("params") or {}
        try:
            text, failed = call_tool(params.get("name"), params.get("arguments") or {})
        except Exception as error:
            text, failed = str(error), True
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {"content": [{"type": "text", "text": text}], "isError": failed},
        }
    if msg_id is None:
        return None
    return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": -32601, "message": f"unknown method {method}"}}


def self_check():
    line = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}) + "\n"
    parsed = json.loads(line)
    assert parsed["method"] == "tools/list"
    print("ok")


def main():
    if sys.argv[1:] == ["--self-check"]:
        self_check()
        return
    while True:
        message = read_message()
        if message is None:
            return
        reply = handle(message)
        if reply is not None:
            write_message(reply)


if __name__ == "__main__":
    main()
