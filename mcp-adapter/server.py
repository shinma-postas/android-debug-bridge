#!/usr/bin/env python3
"""stdio MCP adapter. Forwards tool calls to the localhost debug bridge."""

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HOME = Path(os.environ["ASDBG_HOME"]) if os.environ.get("ASDBG_HOME") else Path.home() / ".android-debug-bridge"
ENDPOINT = HOME / "endpoint.json"

SNAPSHOT_PROPS = {
    "frameIndex": {"type": "integer", "description": "frame index. 0 is the top frame"},
    "depth": {"type": "integer", "description": "how deep to expand fields. default 2, max 6"},
    "maxChildren": {"type": "integer", "description": "children per object. default 50"},
    "maxString": {"type": "integer", "description": "characters per string. default 1000"},
    "maxNodes": {"type": "integer", "description": "values in total. default 2000"},
    "thread": {"type": "string", "description": "thread name. default: the thread that stopped"},
    "snapshotTimeout": {"type": "integer", "description": "milliseconds for reading values. default 10000"},
    "toString": {"type": "boolean", "description": "also call toString() on this and locals. This runs code in the app. default false"},
}
SNAPSHOT_KEYS = tuple(SNAPSHOT_PROPS)

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
        "name": "bp_update",
        "description": "Change an existing breakpoint, including ones set by hand: enabled, condition (empty clears), logMessage, logExpression (empty clears), logStack, suspend (all, thread, none), passCount (stop on the Nth hit, 0 turns it off). Only given fields change.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "id": {"type": "string"},
                "enabled": {"type": "boolean"},
                "condition": {"type": "string"},
                "logMessage": {"type": "boolean"},
                "logExpression": {"type": "string"},
                "logStack": {"type": "boolean"},
                "suspend": {"type": "string", "enum": ["all", "thread", "none"]},
                "passCount": {"type": "integer"},
                "projectPath": {"type": "string"},
            },
            "required": ["id"],
        },
    },
    {
        "name": "threads",
        "description": "Every thread in the debugged VM with status, suspended, suspendCount and stoppedHere. frames > 0 adds that many top frames for suspended threads.",
        "inputSchema": {
            "type": "object",
            "properties": {"sessionId": {"type": "string"}, "frames": {"type": "integer"}, "projectPath": {"type": "string"}},
        },
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
                "force": {"type": "boolean"},
                "waitForProcess": {"type": "boolean"},
                "waitReady": {"type": "boolean", "description": "wait within timeout until indexing and project loading end. the reply has warnings when not ready"},
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
        "description": "PAUSE, RESUME, RESUME_VM, STEP_OVER, STEP_INTO, STEP_OUT, STOP, DETACH, WAIT_FOR_PAUSE, SNAPSHOT, SUSPEND_THREAD, RESUME_THREAD, RUN_TO_LINE, SELECT_FRAME, or SET_VALUE (prefer the set_value tool). RESUME also resumes suspend=thread stops. RESUME_VM resumes the whole VM as a last resort. DETACH leaves the app running. STEP_* take an optional thread. SUSPEND_THREAD and RESUME_THREAD need thread. RUN_TO_LINE needs file and line (1-based). SELECT_FRAME takes frameIndex and an optional thread; stack, variables and evaluate then use that frame. WAIT_FOR_PAUSE with snapshot=true adds the snapshot tool's result when it stops.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "action": {"type": "string"},
                "sessionId": {"type": "string"},
                "timeout": {"type": "integer", "description": "milliseconds"},
                "snapshot": {"type": "boolean"},
                "file": {"type": "string", "description": "RUN_TO_LINE"},
                "line": {"type": "integer", "description": "RUN_TO_LINE, 1-based"},
                "ignoreBreakpoints": {"type": "boolean", "description": "RUN_TO_LINE"},
                **SNAPSHOT_PROPS,
                "projectPath": {"type": "string"},
            },
            "required": ["action"],
        },
    },
    {
        "name": "mute",
        "description": "Mute or unmute all breakpoints, like Mute Breakpoints in the Debug window. Applies to every session unless sessionId is given. sessions shows muted.",
        "inputSchema": {
            "type": "object",
            "properties": {"muted": {"type": "boolean"}, "sessionId": {"type": "string"}, "projectPath": {"type": "string"}},
            "required": ["muted"],
        },
    },
    {
        "name": "snapshot",
        "description": "Everything the IDE debugger shows at the current stop, read-only: thread, file and line, the stack, and this and locals with types and values expanded to depth. Secret-like names are masked. Values can still hold personal or payment data, so do not paste them outside this machine.",
        "inputSchema": {
            "type": "object",
            "properties": {"sessionId": {"type": "string"}, **SNAPSHOT_PROPS, "projectPath": {"type": "string"}},
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
    {
        "name": "set_value",
        "description": (
            "Write a value into a stopped frame while the debuggee is suspended, then optionally resume. "
            "Primitives, String, null and enum constants need no method call, so this works on suspend=thread stops. "
            "path targets a local variable, a field of this, a nested path through fields and array elements "
            "(responsePay.result.code, arr[2]), or a fully-qualified static field (com.pkg.Foo.FIELD). "
            "value is one kind: {int|long|short|byte|double|float: \"5\"}, {boolean: true}, {char: \"A\"}, "
            "{string: \"hi\"}, {null: true} (object refs only), or {enum: \"PAID\"}. Assigning an object by id is not supported. "
            "Give either one path+value, or a sets array of {path, value}. resume=true resumes after setting. "
            "RISK: this changes state in a live payment flow and values can be sensitive; only set what you mean to. "
            "The reply returns the old and new value (secret-like names masked) and a warning for final fields."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "single target; omit when using sets"},
                "value": {"type": "object", "description": "single value spec; omit when using sets"},
                "sets": {
                    "type": "array",
                    "description": "batch: [{path, value}] applied in order",
                    "items": {
                        "type": "object",
                        "properties": {"path": {"type": "string"}, "value": {"type": "object"}},
                        "required": ["path", "value"],
                    },
                },
                "frameIndex": {"type": "integer", "description": "frame index. 0 is the current frame"},
                "thread": {"type": "string", "description": "thread name. default: the stopped thread"},
                "resume": {"type": "boolean", "description": "resume right after setting"},
                "sessionId": {"type": "string"},
                "projectPath": {"type": "string"},
            },
        },
    },
    {
        "name": "webhook_get",
        "description": (
            "Show the pause hooks config (webhook.json), file paths and counters. Pause hooks fire on every stop "
            "(breakpoint, step end, exception/method/field breakpoint, manual pause) with location only: "
            "file, line, class, method, thread, kind, session, breakpoint id, timestamp. Never values. "
            "Header values and the url path are masked."
        ),
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "webhook_set",
        "description": (
            "Change pause hooks. Only given keys change. enabled turns everything on or off (default off). "
            "url: http(s) to POST the pause json to (\"\" clears). headers: literal headers, replaces all. "
            "headersFromEnv: {header: ENV_VAR} read from Android Studio's environment. "
            "eventsFile: append each pause to ~/.android-debug-bridge/pause-events.jsonl (default true). "
            "onPauseCommand: argv array run per pause with the json on stdin and ASDBG_* env, no shell. "
            "debounceMs, commandTimeoutMs, timeoutMs (http, default 3000)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "enabled": {"type": "boolean"},
                "url": {"type": "string"},
                "headers": {"type": "object"},
                "headersFromEnv": {"type": "object"},
                "debounceMs": {"type": "integer"},
                "eventsFile": {"type": "boolean"},
                "eventsMaxBytes": {"type": "integer"},
                "onPauseCommand": {"type": "array", "items": {"type": "string"}},
                "commandTimeoutMs": {"type": "integer"},
                "timeoutMs": {"type": "integer"},
            },
        },
    },
    {
        "name": "webhook_test",
        "description": "Send a sample pause event through every configured channel (url, events file, command), even when disabled. Returns per-channel results.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "wait_pause_event",
        "description": (
            "Wait for the next line in pause-events.jsonl and return it. Needs pause hooks enabled with eventsFile on. "
            "Works for every session and project, including suspend=thread stops. Returns {event: timeout} when nothing comes."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"timeout": {"type": "integer", "description": "milliseconds. default 60000, max 600000"}},
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


def snapshot_body(args):
    return {key: args[key] for key in SNAPSHOT_KEYS if args.get(key) is not None}


def wait_pause_event(timeout_ms):
    path = HOME / "pause-events.jsonl"
    def stat():
        try:
            st = path.stat()
            return st.st_ino, st.st_size
        except FileNotFoundError:
            return None, 0
    inode, offset = stat()
    buf = b""
    deadline = time.monotonic() + timeout_ms / 1000
    while time.monotonic() < deadline:
        cur, size = stat()
        if cur is not None and (cur != inode or size < offset):
            inode, offset, buf = cur, 0, b""
        if cur is not None and size > offset:
            with open(path, "rb") as handle:
                handle.seek(offset)
                chunk = handle.read(size - offset)
            offset += len(chunk)
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                if line.strip():
                    try:
                        json.loads(line)
                        return line.decode(), False
                    except ValueError:
                        continue
            continue
        time.sleep(0.2)
    return json.dumps({"event": "timeout", "timeoutMs": timeout_ms}), False


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
    if name == "bp_update":
        keys = ("enabled", "condition", "logMessage", "logExpression", "logStack", "suspend", "passCount")
        body = {key: args[key] for key in keys if key in args}
        if not body:
            return json.dumps({"error": "nothing to change"}), True
        return bridge("POST", "/v1/breakpoints/" + args["id"], body, project)
    if name == "threads":
        frames = int(args.get("frames") or 0)
        return bridge("GET", f"/v1/sessions/{session_id(args)}/threads?frames={frames}", project=project)
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
        if args.get("waitReady"):
            body["waitReady"] = True
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
        body = {"action": args["action"], "timeout": timeout}
        body.update(snapshot_body(args))
        for key in ("file", "line", "ignoreBreakpoints"):
            if args.get(key) is not None:
                body[key] = args[key]
        if args.get("snapshot"):
            body["snapshot"] = True
        extra = int(args.get("snapshotTimeout") or 10000) / 1000
        return bridge("POST", f"/v1/sessions/{sid}/control", body, project,
                      allow_eval=bool(args.get("toString")), timeout=timeout / 1000 + extra + 5)
    if name == "mute":
        if args.get("sessionId"):
            ids = [args["sessionId"]]
        else:
            text, failed = bridge("GET", "/v1/sessions", project=project)
            if failed:
                return text, True
            ids = [item["id"] for item in json.loads(text)["sessions"]]
        if not ids:
            return json.dumps({"error": "no debug session. mute is per session"}), True
        action = "MUTE" if args.get("muted") else "UNMUTE"
        results = []
        for sid in ids:
            text, failed = bridge("POST", f"/v1/sessions/{sid}/control", {"action": action}, project, timeout=30)
            if failed:
                return text, True
            results.append(json.loads(text))
        return json.dumps({"muted": bool(args.get("muted")), "sessions": results}), False
    if name == "snapshot":
        body = {"action": "SNAPSHOT"}
        body.update(snapshot_body(args))
        extra = int(args.get("snapshotTimeout") or 10000) / 1000
        return bridge("POST", f"/v1/sessions/{session_id(args)}/control", body, project,
                      allow_eval=bool(args.get("toString")), timeout=extra + 5)
    if name == "stack":
        return bridge("GET", f"/v1/sessions/{session_id(args)}/stack", project=project)
    if name == "variables":
        return bridge("GET", f"/v1/sessions/{session_id(args)}/variables", project=project)
    if name == "evaluate":
        return bridge("POST", f"/v1/sessions/{session_id(args)}/evaluate", {
            "expression": args["expression"],
        }, project, allow_eval=True)
    if name == "set_value":
        body = {"action": "SET_VALUE"}
        if args.get("sets") is not None:
            body["sets"] = args["sets"]
        if args.get("path") is not None:
            body["path"] = args["path"]
        if args.get("value") is not None:
            body["value"] = args["value"]
        if args.get("frameIndex") is not None:
            body["frameIndex"] = args["frameIndex"]
        if args.get("thread"):
            body["thread"] = args["thread"]
        if args.get("resume"):
            body["resume"] = True
        return bridge("POST", f"/v1/sessions/{session_id(args)}/control", body, project, timeout=30)
    if name == "webhook_get":
        return bridge("GET", "/v1/webhook", project=project, timeout=10)
    if name == "webhook_set":
        keys = ("enabled", "url", "headers", "headersFromEnv", "debounceMs", "eventsFile", "eventsMaxBytes",
                "onPauseCommand", "commandTimeoutMs", "timeoutMs")
        body = {key: args[key] for key in keys if key in args}
        if not body:
            return json.dumps({"error": "pass at least one setting"}), True
        return bridge("POST", "/v1/webhook", body, project, timeout=10)
    if name == "webhook_test":
        return bridge("POST", "/v1/webhook/test", {}, project, timeout=40)
    if name == "wait_pause_event":
        return wait_pause_event(min(max(int(args.get("timeout") or 60000), 100), 600000))
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
                "serverInfo": {"name": "android-studio-debug", "version": "0.1.8"},
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
    names = [tool["name"] for tool in TOOLS]
    for tool in ("snapshot", "mute", "threads", "bp_update", "set_value", "webhook_get", "webhook_set", "webhook_test", "wait_pause_event"):
        assert tool in names, tool
    assert snapshot_body({"depth": 3, "sessionId": "x", "toString": None}) == {"depth": 3}
    assert json.loads(call_tool("webhook_set", {})[0])["error"]
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
