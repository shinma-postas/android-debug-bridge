#!/usr/bin/env python3
"""Talk to the Android Studio debug bridge on 127.0.0.1."""

import argparse
import json
import os
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HOME = Path(os.environ["ASDBG_HOME"]) if os.environ.get("ASDBG_HOME") else Path.home() / ".android-debug-bridge"
ENDPOINT = HOME / "endpoint.json"
ACTIONS = (
    "PAUSE", "RESUME", "RESUME_VM", "STEP_OVER", "STEP_INTO", "STEP_OUT", "STOP",
    "WAIT_FOR_PAUSE", "SNAPSHOT", "MUTE", "UNMUTE",
    "SUSPEND_THREAD", "RESUME_THREAD", "RUN_TO_LINE", "SELECT_FRAME", "DETACH", "SET_VALUE",
)
VALUE_KINDS = ("int", "long", "short", "byte", "double", "float", "boolean", "char", "string", "enum", "object-id")


def endpoint():
    if os.environ.get("ASDBG_URL") and os.environ.get("ASDBG_TOKEN"):
        return os.environ["ASDBG_URL"].rstrip("/"), os.environ["ASDBG_TOKEN"]
    if not ENDPOINT.exists():
        sys.exit("bridge endpoint is missing. Start Android Studio after installing the plugin.")
    data = json.loads(ENDPOINT.read_text())
    token = Path(data["tokenFile"]).read_text().strip()
    return data["url"].rstrip("/"), token


def project_path():
    return os.environ.get("ANDROID_STUDIO_PROJECT") or os.getcwd()


def request(method, path, body=None, timeout=120, allow_eval=False):
    base, token = endpoint()
    data = None if body is None else json.dumps(body).encode()
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Project-Path": project_path(),
        "Accept": "application/json",
    }
    if data is not None:
        headers["Content-Type"] = "application/json"
    if allow_eval:
        headers["X-ASdbg-Allow-Eval"] = "1"
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        detail = error.read().decode()
        sys.exit(detail or f"HTTP {error.code}")


def check_timeout(value, strict=False):
    """--timeout is in milliseconds. Values under 1000 are almost always seconds by mistake."""
    if value is None or value >= 1000:
        return value
    hint = f"--timeout is in milliseconds. {value} means {value} ms. For {value} seconds pass {value * 1000}."
    if strict:
        sys.exit(json.dumps({"error": hint}))
    print("warning: " + hint, file=sys.stderr)
    return value


def only_session(explicit):
    if explicit:
        return explicit
    listed = request("GET", "/v1/sessions")["sessions"]
    if len(listed) == 1:
        return listed[0]["id"]
    if not listed:
        sys.exit("no debug session")
    names = ", ".join(f"{item['id']} ({item['name']})" for item in listed)
    sys.exit(f"pass --session. running: {names}")


def cmd_tools(_):
    print("\n".join([
        "bp-add", "bp-list", "bp-update", "bp-enable", "bp-disable", "bp-rm", "bp-clear", "attach", "attach-wait", "restoration-debug", "start", "sessions", "mute", "control", "snapshot", "threads", "thread-suspend", "thread-resume",
        "step", "run-to", "frame", "detach", "set", "resume-with", "stack", "vars", "eval", "webhook", "watch",
    ]))


def cmd_schema(args):
    schemas = {
        "xdebug_set_breakpoint": {"file": "app/src/main/java/com/example/MainActivity.kt", "line": 42, "condition": None, "temporary": False},
        "xdebug_control_session": {"action": "WAIT_FOR_PAUSE", "timeout": 60000, "snapshot": False},
        "xdebug_snapshot": {"frameIndex": 0, "depth": 2, "maxChildren": 50, "maxString": 1000},
        "bp-add": {"file": "path", "line": 1},
    }
    print(json.dumps(schemas.get(args.name, {"error": "unknown schema", "known": sorted(schemas)}), indent=2))


def cmd_bp_add(args):
    kind = {"function": "method", "watchpoint": "field", "watch": "field"}.get(args.type, args.type)
    body = {"type": kind, "temporary": args.temporary}
    if args.condition:
        body["condition"] = args.condition
    if args.suspend:
        body["suspend"] = args.suspend
    if kind == "exception":
        if not args.class_name:
            sys.exit("exception breakpoint needs --class. Use * for any exception")
        body["class"] = args.class_name
    else:
        if not args.file or args.line is None:
            sys.exit("file and line are required")
        body["file"] = args.file
        body["line"] = args.line
    print(json.dumps(request("POST", "/v1/breakpoints", body), indent=2))


def cmd_bp_list(_):
    print(json.dumps(request("GET", "/v1/breakpoints"), indent=2))


def cmd_bp_update(args):
    body = {}
    if args.enable is not None:
        body["enabled"] = args.enable
    if args.condition is not None:
        body["condition"] = args.condition
    if args.log_message is not None:
        body["logMessage"] = args.log_message == "on"
    if args.log_expression is not None:
        body["logExpression"] = args.log_expression
    if args.log_stack is not None:
        body["logStack"] = args.log_stack == "on"
    if args.suspend:
        body["suspend"] = args.suspend
    if args.pass_count is not None:
        body["passCount"] = args.pass_count
    if not body:
        sys.exit("nothing to change")
    print(json.dumps(request("POST", f"/v1/breakpoints/{args.id}", body), indent=2))


def cmd_bp_toggle(args):
    print(json.dumps(request("POST", f"/v1/breakpoints/{args.id}", {"enabled": args.enabled}), indent=2))


def cmd_bp_rm(args):
    print(json.dumps(request("DELETE", f"/v1/breakpoints/{args.id}"), indent=2))


def cmd_bp_clear(_):
    print(json.dumps(request("DELETE", "/v1/breakpoints"), indent=2))


def cmd_attach(args):
    check_timeout(args.timeout, strict=True)
    body = {"serial": args.serial, "timeout": args.timeout}
    if args.package:
        body["package"] = args.package
    if args.force:
        body["force"] = True
    if args.wait_for_process:
        body["waitForProcess"] = True
    if args.wait_ready:
        body["waitReady"] = True
    result = request("POST", "/v1/attach", body, timeout=max(args.timeout / 1000 + 5, 10))
    for warning in result.get("warnings") or []:
        print("warning: " + warning, file=sys.stderr)
    print(json.dumps(result, indent=2))


def adb_pid(serial, package):
    cmd = ["adb", "-s", serial, "shell", "pidof", package]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return None
    text = (out.stdout or "").strip()
    if not text:
        return None
    return text.split()[0]


def stop_debug_sessions():
    listed = request("GET", "/v1/sessions").get("sessions") or []
    for item in listed:
        request(
            "POST",
            f"/v1/sessions/{item['id']}/control",
            {"action": "STOP"},
            timeout=30,
        )


def clear_owned_breakpoints():
    for bp in request("GET", "/v1/breakpoints").get("breakpoints", []):
        if bp.get("owned"):
            request("DELETE", f"/v1/breakpoints/{bp['id']}")


def clear_all_breakpoints():
    try:
        request("DELETE", "/v1/breakpoints")
    except SystemExit:
        clear_owned_breakpoints()


def bp_add_line(file, line, suspend="thread"):
    return request(
        "POST",
        "/v1/breakpoints",
        {"file": file, "line": line, "suspend": suspend},
    )


def do_attach(serial, package, timeout=60000, force=False, wait_for_process=False):
    body = {"serial": serial, "timeout": timeout, "package": package}
    if force:
        body["force"] = True
    if wait_for_process:
        body["waitForProcess"] = True
    return request("POST", "/v1/attach", body, timeout=max(timeout / 1000 + 5, 10))


def cmd_restoration_debug(args):
    """BP1 + attach, then on app restart auto BP2 + force attach."""
    check_timeout(args.attach_timeout, strict=True)
    package = args.package
    serial = args.serial
    interval = args.interval / 1000.0
    deadline = time.time() + args.timeout / 1000.0

    def emit(obj):
        print(json.dumps(obj, ensure_ascii=False), flush=True)

    if not args.skip_phase1:
        clear_all_breakpoints()
        bp1 = bp_add_line(args.file, args.bp1_line)
        stop_debug_sessions()
        attach1 = do_attach(serial, package, timeout=args.attach_timeout, force=args.force_phase1)
        emit({
            "event": "phase1_ready",
            "line": args.bp1_line,
            "breakpoint": bp1,
            "attach": attach1,
            "pid": adb_pid(serial, package),
        })

    last_pid = adb_pid(serial, package)
    seen_death = False
    emit({"event": "watching_restart", "pid": last_pid})

    while time.time() < deadline:
        cur = adb_pid(serial, package)
        restarted = False
        if last_pid and not cur:
            seen_death = True
            emit({"event": "process_died", "was": last_pid})
            last_pid = None
        elif cur and seen_death:
            restarted = True
        elif cur and last_pid and cur != last_pid:
            restarted = True

        if restarted:
            clear_all_breakpoints()
            bp2 = bp_add_line(args.file, args.bp2_line)
            stop_debug_sessions()
            attach2 = None
            for _ in range(40):
                try:
                    attach2 = do_attach(
                        serial,
                        package,
                        timeout=args.attach_timeout,
                        force=True,
                        wait_for_process=True,
                    )
                    if attach2.get("attached") or attach2.get("already"):
                        break
                except SystemExit:
                    time.sleep(0.5)
            emit({
                "event": "phase2_ready",
                "line": args.bp2_line,
                "breakpoint": bp2,
                "attach": attach2,
                "pid": cur,
            })
            if args.once:
                return
            seen_death = False
            last_pid = cur
        elif cur:
            last_pid = cur
        time.sleep(interval)
    emit({"event": "watch_timeout"})


def cmd_attach_wait(args):
    check_timeout(args.timeout, strict=True)
    package = args.package
    deadline = time.time() + args.timeout / 1000.0
    if args.stop_sessions:
        stop_debug_sessions()
    last_pid = None
    while time.time() < deadline:
        pid = adb_pid(args.serial, package)
        if pid and (not args.after_kill or last_pid is not None or pid):
            if args.after_kill and pid == last_pid and last_pid is not None:
                time.sleep(args.interval / 1000.0)
                continue
            body = {
                "serial": args.serial,
                "timeout": min(int((deadline - time.time()) * 1000), args.timeout),
                "package": package,
                "force": True,
            }
            try:
                result = request(
                    "POST",
                    "/v1/attach",
                    body,
                    timeout=max(body["timeout"] / 1000 + 5, 10),
                )
            except SystemExit as exc:
                msg = str(exc.args[0]) if exc.args else ""
                if "not found" in msg:
                    time.sleep(args.interval / 1000.0)
                    continue
                raise
            if result.get("attached") or result.get("already"):
                print(json.dumps(result, indent=2))
                return
            stop_debug_sessions()
        if pid:
            last_pid = pid
        time.sleep(args.interval / 1000.0)
    sys.exit(json.dumps({"error": "attach-wait timed out", "package": package}))


def cmd_start(args):
    check_timeout(args.timeout, strict=True)
    print(json.dumps(request("POST", "/v1/sessions", {
        "configuration": args.configuration,
        "timeout": args.timeout,
    }, timeout=max(args.timeout / 1000 + 5, 10)), indent=2))


def cmd_sessions(_):
    print(json.dumps(request("GET", "/v1/sessions"), indent=2))


def snapshot_body(args):
    body = {
        "frameIndex": args.frame,
        "depth": args.depth,
        "maxChildren": args.max_children,
        "maxString": args.max_string,
        "maxNodes": args.max_nodes,
        "snapshotTimeout": args.snapshot_timeout,
    }
    if args.thread:
        body["thread"] = args.thread
    if args.to_string:
        body["toString"] = True
    return body


def add_snapshot_args(cmd):
    cmd.add_argument("--frame", type=int, default=0, help="frame index. 0 is the top frame")
    cmd.add_argument("--depth", type=int, default=2, help="how deep to expand fields (0-6)")
    cmd.add_argument("--max-children", type=int, default=50)
    cmd.add_argument("--max-string", type=int, default=1000)
    cmd.add_argument("--max-nodes", type=int, default=2000)
    cmd.add_argument("--thread", help="thread name. default: the thread that hit the breakpoint")
    cmd.add_argument("--snapshot-timeout", type=int, default=10000, help="milliseconds for reading values")
    cmd.add_argument(
        "--to-string",
        action="store_true",
        help="also call toString() on this and locals. This runs code in the app",
    )


def control_call(session, body, timeout=30):
    return request("POST", f"/v1/sessions/{session}/control", body, timeout=timeout)


def cmd_threads(args):
    session = only_session(args.session)
    print(json.dumps(request("GET", f"/v1/sessions/{session}/threads?frames={args.frames}"), indent=2, ensure_ascii=False))


def cmd_thread_suspend(args):
    session = only_session(args.session)
    print(json.dumps(control_call(session, {"action": "SUSPEND_THREAD", "thread": args.thread}), indent=2))


def cmd_thread_resume(args):
    session = only_session(args.session)
    print(json.dumps(control_call(session, {"action": "RESUME_THREAD", "thread": args.thread}), indent=2))


def cmd_step(args):
    session = only_session(args.session)
    body = {"action": "STEP_" + args.kind.upper()}
    if args.thread:
        body["thread"] = args.thread
    print(json.dumps(control_call(session, body), indent=2))


def cmd_run_to(args):
    session = only_session(args.session)
    body = {"action": "RUN_TO_LINE", "file": args.file, "line": args.line, "ignoreBreakpoints": args.ignore_breakpoints}
    if args.thread:
        body["thread"] = args.thread
    print(json.dumps(control_call(session, body), indent=2))


def cmd_frame(args):
    session = only_session(args.session)
    body = {"action": "SELECT_FRAME", "frameIndex": args.index}
    if args.thread:
        body["thread"] = args.thread
    print(json.dumps(control_call(session, body), indent=2))


def cmd_detach(args):
    session = only_session(args.session)
    print(json.dumps(control_call(session, {"action": "DETACH"}), indent=2))


def value_spec_from_args(args):
    """Exactly one of the value flags, as the JSON the bridge expects."""
    chosen = []
    for flag in ("int", "long", "short", "byte", "double", "float", "char", "string", "enum"):
        value = getattr(args, flag.replace("-", "_"))
        if value is not None:
            chosen.append((flag, value))
    if args.boolean is not None:
        chosen.append(("boolean", args.boolean == "true"))
    if args.set_null:
        chosen.append(("null", True))
    if args.object_id is not None:
        chosen.append(("objectId", args.object_id))
    if not chosen:
        sys.exit("give one value: --int, --string, --boolean, --null, --enum, etc.")
    if len(chosen) > 1:
        sys.exit("give exactly one value kind, got: " + ", ".join(k for k, _ in chosen))
    key, value = chosen[0]
    return {key: value}


def cmd_set(args):
    session = only_session(args.session)
    body = {"action": "SET_VALUE", "path": args.path, "value": value_spec_from_args(args)}
    if args.frame:
        body["frameIndex"] = args.frame
    if args.thread:
        body["thread"] = args.thread
    if args.resume:
        body["resume"] = True
    result = control_call(session, body, timeout=30)
    for item in result.get("sets", []):
        if item.get("warning"):
            print("warning: " + item["warning"], file=sys.stderr)
    print(json.dumps(result, indent=2, ensure_ascii=False))


def cmd_resume_with(args):
    session = only_session(args.session)
    if args.file:
        raw = Path(args.file).read_text()
    elif args.json:
        raw = args.json
    else:
        raw = sys.stdin.read()
    sets = json.loads(raw)
    if isinstance(sets, dict):
        sets = sets.get("sets", [sets])
    if not isinstance(sets, list) or not sets:
        sys.exit("expected a JSON array of {path, value} objects")
    body = {"action": "SET_VALUE", "sets": sets, "resume": not args.no_resume}
    if args.frame:
        body["frameIndex"] = args.frame
    if args.thread:
        body["thread"] = args.thread
    result = control_call(session, body, timeout=30)
    for item in result.get("sets", []):
        if item.get("warning"):
            print("warning: " + item["warning"], file=sys.stderr)
    print(json.dumps(result, indent=2, ensure_ascii=False))


def add_set_value_flags(parser):
    for flag in ("int", "long", "short", "byte", "enum", "string"):
        parser.add_argument("--" + flag)
    for flag in ("double", "float"):
        parser.add_argument("--" + flag)
    parser.add_argument("--char")
    parser.add_argument("--boolean", choices=["true", "false"])
    parser.add_argument("--null", dest="set_null", action="store_true", help="assign null (object fields only)")
    parser.add_argument("--object-id", dest="object_id", help="not supported yet; reserved")


def cmd_control(args):
    check_timeout(args.timeout)
    session = only_session(args.session)
    body = {"action": args.action, "timeout": args.timeout}
    if args.thread_name:
        body["thread"] = args.thread_name
    wants = args.snapshot or args.action == "SNAPSHOT"
    if wants:
        body.update(snapshot_body(args))
    if args.snapshot:
        body["snapshot"] = True
    wait = args.timeout / 1000 + (args.snapshot_timeout / 1000 if wants else 0) + 5
    print(json.dumps(request(
        "POST", f"/v1/sessions/{session}/control", body,
        timeout=max(wait, 10), allow_eval=wants and args.to_string,
    ), indent=2, ensure_ascii=False))


def cmd_snapshot(args):
    session = only_session(args.session)
    body = {"action": "SNAPSHOT"}
    body.update(snapshot_body(args))
    print(json.dumps(request(
        "POST", f"/v1/sessions/{session}/control", body,
        timeout=max(args.snapshot_timeout / 1000 + 5, 10), allow_eval=args.to_string,
    ), indent=2, ensure_ascii=False))


def cmd_mute(args):
    on = args.state == "on"
    ids = [args.session] if args.session else [item["id"] for item in request("GET", "/v1/sessions")["sessions"]]
    if not ids:
        sys.exit("no debug session. mute is per session in Android Studio")
    results = [
        request("POST", f"/v1/sessions/{sid}/control", {"action": "MUTE" if on else "UNMUTE"}, timeout=30)
        for sid in ids
    ]
    print(json.dumps({"muted": on, "sessions": results}, indent=2))


def cmd_stack(args):
    session = only_session(args.session)
    print(json.dumps(request("GET", f"/v1/sessions/{session}/stack"), indent=2))


def cmd_vars(args):
    session = only_session(args.session)
    print(json.dumps(request("GET", f"/v1/sessions/{session}/variables"), indent=2))


def cmd_eval(args):
    if not args.allow:
        sys.exit("eval runs code in the process. Pass --allow.")
    session = only_session(args.session)
    result = request("POST", f"/v1/sessions/{session}/evaluate", {
        "expression": args.expression,
    }, allow_eval=True)
    if result.get("warning"):
        print("warning: " + result["warning"], file=sys.stderr)
    print(json.dumps(result, indent=2, ensure_ascii=False))


WEBHOOK_FILE = "webhook.json"
EVENTS_FILE = "pause-events.jsonl"


def parse_header(text):
    name, sep, value = text.partition(":")
    if not sep or not name.strip():
        sys.exit(json.dumps({"error": "--header must look like 'Name: value'"}))
    return name.strip(), value.strip()


def webhook_patch(args):
    """Turns webhook set flags into the patch the bridge merges. Only given flags are sent."""
    patch = {}
    if args.clear_url:
        patch["url"] = ""
    elif args.url is not None:
        patch["url"] = args.url
    if args.header is not None:
        patch["headers"] = dict(parse_header(h) for h in args.header)
    if args.header_env is not None:
        pairs = {}
        for item in args.header_env:
            name, sep, env = item.partition("=")
            if not sep or not name.strip() or not env.strip():
                sys.exit(json.dumps({"error": "--header-env must look like Name=ENV_VAR"}))
            pairs[name.strip()] = env.strip()
        patch["headersFromEnv"] = pairs
    if args.debounce is not None:
        patch["debounceMs"] = args.debounce
    if args.events_file is not None:
        patch["eventsFile"] = args.events_file == "on"
    if args.clear_command:
        patch["onPauseCommand"] = []
    elif args.command is not None:
        patch["onPauseCommand"] = shlex.split(args.command)
    if args.command_timeout is not None:
        patch["commandTimeoutMs"] = args.command_timeout
    if args.timeout is not None:
        patch["timeoutMs"] = args.timeout
    if args.enable is not None:
        patch["enabled"] = args.enable
    return patch


def cmd_webhook(args):
    if args.op == "show":
        out = request("GET", "/v1/webhook", timeout=10)
    elif args.op == "test":
        out = request("POST", "/v1/webhook/test", {}, timeout=40)
    elif args.op in ("enable", "disable"):
        out = request("POST", "/v1/webhook", {"enabled": args.op == "enable"}, timeout=10)
    else:
        patch = webhook_patch(args)
        if not patch:
            sys.exit(json.dumps({"error": "webhook set needs at least one flag"}))
        out = request("POST", "/v1/webhook", patch, timeout=10)
    print(json.dumps(out, indent=2, ensure_ascii=False))


def pause_env(event):
    env = dict(os.environ)
    for key, name in (("event", "EVENT"), ("kind", "KIND"), ("file", "FILE"), ("line", "LINE"),
                      ("thread", "THREAD"), ("sessionName", "SESSION"), ("timestamp", "TIMESTAMP")):
        value = event.get(key)
        env["ASDBG_" + name] = "" if value is None else str(value)
    return env


def run_exec(argv, event, timeout_ms):
    """Runs argv (never a shell string) with the event json on stdin. Returns the exit code."""
    try:
        done = subprocess.run(argv, input=json.dumps(event, ensure_ascii=False).encode(),
                              env=pause_env(event), timeout=timeout_ms / 1000)
        return done.returncode
    except subprocess.TimeoutExpired:
        print(json.dumps({"warning": "exec timed out", "timeoutMs": timeout_ms}), file=sys.stderr)
        return 124
    except OSError as error:
        print(json.dumps({"warning": "exec failed", "error": type(error).__name__}), file=sys.stderr)
        return 127


def watch_warnings():
    config = HOME / WEBHOOK_FILE
    try:
        data = json.loads(config.read_text()) if config.exists() else {}
    except (OSError, ValueError):
        data = {}
    if not data.get("enabled"):
        print(f"warning: pause hooks are off. Run: asdbg.py webhook enable  ({config})", file=sys.stderr)
    elif data.get("eventsFile") is False:
        print("warning: eventsFile is off, so the events file is not written. Run: asdbg.py webhook set --events-file on", file=sys.stderr)


def tail_events(path, deadline, poll=0.2):
    """Yields new json lines appended to path after the call. Follows rotation to path.1."""
    def stat():
        try:
            st = path.stat()
            return st.st_ino, st.st_size
        except FileNotFoundError:
            return None, 0
    inode, offset = stat()
    buf = b""
    while deadline is None or time.monotonic() < deadline:
        cur_inode, size = stat()
        if cur_inode is not None and (cur_inode != inode or size < offset):
            inode, offset, buf = cur_inode, 0, b""
        if cur_inode is not None and size > offset:
            with open(path, "rb") as handle:
                handle.seek(offset)
                chunk = handle.read(size - offset)
            offset += len(chunk)
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                if line.strip():
                    try:
                        yield json.loads(line)
                    except ValueError:
                        continue
            continue
        time.sleep(poll)


def bridge_pause_event(args, timeout_ms):
    session = only_session(args.session)
    out = control_call(session, {"action": "WAIT_FOR_PAUSE", "timeout": timeout_ms}, timeout=timeout_ms / 1000 + 10)
    if out.get("event") != "paused":
        return None
    pos = out.get("position") or {}
    return {"event": "paused", "kind": None, "source": "bridge-wait", "sessionId": session,
            "file": pos.get("file"), "line": pos.get("line"), "thread": None,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z")}


def cmd_watch(args):
    if args.timeout:
        check_timeout(args.timeout)
    argv = shlex.split(args.exec) if args.exec else None
    if args.exec and not argv:
        sys.exit(json.dumps({"error": "--exec is empty"}))
    deadline = None if not args.timeout else time.monotonic() + args.timeout / 1000
    if args.bridge:
        event = bridge_pause_event(args, args.timeout or 600000)
        events = iter([event] if event else [])
    else:
        watch_warnings()
        events = tail_events(HOME / EVENTS_FILE, deadline)
    got = False
    for event in events:
        got = True
        print(json.dumps(event, ensure_ascii=False), flush=True)
        if argv:
            run_exec(argv, event, args.exec_timeout)
        if args.once:
            return
    if not got:
        print(json.dumps({"event": "timeout"}), flush=True)
        sys.exit(2)


def build_parser():
    parser = argparse.ArgumentParser(prog="asdbg")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("tools").set_defaults(func=cmd_tools)
    schema = sub.add_parser("schema")
    schema.add_argument("name")
    schema.set_defaults(func=cmd_schema)

    add = sub.add_parser("bp-add")
    add.add_argument("file", nargs="?")
    add.add_argument("line", nargs="?", type=int)
    add.add_argument("--type", default="line", choices=["line", "method", "field", "exception", "function", "watchpoint"])
    add.add_argument("--class", dest="class_name")
    add.add_argument("--condition")
    add.add_argument("--suspend", choices=["all", "thread", "none"])
    add.add_argument("--temporary", action="store_true")
    add.set_defaults(func=cmd_bp_add)

    sub.add_parser("bp-list").set_defaults(func=cmd_bp_list)
    sub.add_parser("bp-clear").set_defaults(func=cmd_bp_clear)
    upd = sub.add_parser("bp-update", help="change an existing breakpoint. id from bp-list")
    upd.add_argument("id")
    upd.add_argument("--enable", dest="enable", action="store_true", default=None)
    upd.add_argument("--disable", dest="enable", action="store_false")
    upd.add_argument("--condition", help="empty string clears it")
    upd.add_argument("--log-message", choices=["on", "off"], help="log 'Breakpoint hit' message")
    upd.add_argument("--log-expression", help="evaluate and log. empty string clears it")
    upd.add_argument("--log-stack", choices=["on", "off"])
    upd.add_argument("--suspend", choices=["all", "thread", "none"], help="none logs without stopping")
    upd.add_argument("--pass-count", type=int, help="stop on the Nth hit. 0 turns it off")
    upd.set_defaults(func=cmd_bp_update)
    for name, enabled in (("bp-enable", True), ("bp-disable", False)):
        toggle = sub.add_parser(name)
        toggle.add_argument("id")
        toggle.set_defaults(func=cmd_bp_toggle, enabled=enabled)

    rm = sub.add_parser("bp-rm")
    rm.add_argument("id")
    rm.set_defaults(func=cmd_bp_rm)

    attach = sub.add_parser("attach")
    attach.add_argument("--serial", required=True)
    attach.add_argument("--package", default="jp.co.persol.postas.selfregi")
    attach.add_argument("--timeout", type=int, default=60000, help="milliseconds. under 1000 is rejected")
    attach.add_argument("--force", action="store_true", help="stop existing IDE debug sessions before attach")
    attach.add_argument("--wait-for-process", action="store_true", help="poll until the package process appears")
    attach.add_argument(
        "--wait-ready",
        action="store_true",
        help="wait within --timeout until the project finishes indexing and loading before attach",
    )
    attach.set_defaults(func=cmd_attach)

    attach_wait = sub.add_parser(
        "attach-wait",
        help="after app kill/restart: stop stale sessions, wait for process, force attach",
    )
    attach_wait.add_argument("--serial", required=True)
    attach_wait.add_argument("--package", default="jp.co.persol.postas.selfregi")
    attach_wait.add_argument("--timeout", type=int, default=180000)
    attach_wait.add_argument("--interval", type=int, default=500, help="poll interval ms")
    attach_wait.add_argument("--no-stop-sessions", dest="stop_sessions", action="store_false")
    attach_wait.add_argument(
        "--after-kill",
        action="store_true",
        help="wait until pid changes (set when process was just killed)",
    )
    attach_wait.set_defaults(func=cmd_attach_wait, stop_sessions=True)

    restore = sub.add_parser(
        "restoration-debug",
        help="BP1+attach, then on process restart switch to BP2 and force attach",
    )
    restore.add_argument("--serial", required=True)
    restore.add_argument("--package", default="jp.co.persol.postas.selfregi")
    restore.add_argument(
        "--file",
        default="app/src/main/java/jp/co/persol/postas/selfregi/Activity/base/SrPaymentBase.kt",
    )
    restore.add_argument("--bp1-line", type=int, default=993)
    restore.add_argument("--bp2-line", type=int, default=2986)
    restore.add_argument("--timeout", type=int, default=3600000, help="watch timeout ms")
    restore.add_argument("--attach-timeout", type=int, default=60000)
    restore.add_argument("--interval", type=int, default=500)
    restore.add_argument("--once", action="store_true", help="stop watch after first restart")
    restore.add_argument("--skip-phase1", action="store_true", help="only watch for restart -> BP2")
    restore.add_argument("--force-phase1", action="store_true")
    restore.set_defaults(func=cmd_restoration_debug)

    start = sub.add_parser("start")
    start.add_argument("configuration")
    start.add_argument("--timeout", type=int, default=30000)
    start.set_defaults(func=cmd_start)

    sub.add_parser("sessions").set_defaults(func=cmd_sessions)
    mute = sub.add_parser("mute", help="mute or unmute all breakpoints. every session unless --session")
    mute.add_argument("state", choices=["on", "off"])
    mute.add_argument("--session")
    mute.set_defaults(func=cmd_mute)
    sub.add_parser("status").set_defaults(func=cmd_sessions)

    control = sub.add_parser(
        "control",
        help="RESUME also resumes suspend=thread stops; RESUME_VM resumes the whole VM as a last resort",
    )
    control.add_argument("action", choices=ACTIONS)
    control.add_argument("--session")
    control.add_argument("--timeout", type=int, default=60000, help="milliseconds")
    control.add_argument("--thread-name", help="STEP_*, SUSPEND_THREAD, RESUME_THREAD, RUN_TO_LINE, SELECT_FRAME: thread name")
    control.add_argument("--snapshot", action="store_true", help="WAIT_FOR_PAUSE: add a snapshot when it stops")
    add_snapshot_args(control)
    control.set_defaults(func=cmd_control)

    snap = sub.add_parser("snapshot", help="file, line, stack, this and locals of the current stop. read-only")
    snap.add_argument("--session")
    add_snapshot_args(snap)
    snap.set_defaults(func=cmd_snapshot)

    threads = sub.add_parser("threads", help="every thread with status. --frames N adds top frames of suspended threads")
    threads.add_argument("--session")
    threads.add_argument("--frames", type=int, default=0)
    threads.set_defaults(func=cmd_threads)

    for name, func in (("thread-suspend", cmd_thread_suspend), ("thread-resume", cmd_thread_resume)):
        cmd = sub.add_parser(name)
        cmd.add_argument("thread")
        cmd.add_argument("--session")
        cmd.set_defaults(func=func)

    step = sub.add_parser("step", help="step over, into, or out. --thread picks a suspended thread")
    step.add_argument("kind", choices=["over", "into", "out"])
    step.add_argument("--thread")
    step.add_argument("--session")
    step.set_defaults(func=cmd_step)

    run_to = sub.add_parser("run-to", help="run to a line, like Run to Cursor")
    run_to.add_argument("file")
    run_to.add_argument("line", type=int)
    run_to.add_argument("--thread")
    run_to.add_argument("--ignore-breakpoints", action="store_true")
    run_to.add_argument("--session")
    run_to.set_defaults(func=cmd_run_to)

    frame = sub.add_parser("frame", help="select a frame (and thread). stack, vars and eval then use it")
    frame.add_argument("index", type=int)
    frame.add_argument("--thread")
    frame.add_argument("--session")
    frame.set_defaults(func=cmd_frame)

    detach = sub.add_parser("detach", help="detach the debugger and leave the app running")
    detach.add_argument("--session")
    detach.set_defaults(func=cmd_detach)

    for name, func in (("stack", cmd_stack), ("vars", cmd_vars)):
        cmd = sub.add_parser(name)
        cmd.add_argument("--session")
        cmd.set_defaults(func=func)

    set_cmd = sub.add_parser(
        "set",
        help="write a value into a stopped frame. e.g. set responsePay.result.code --int 200",
    )
    set_cmd.add_argument("path", help="local, this.field, nested path, arr[2], or com.pkg.Foo.FIELD")
    add_set_value_flags(set_cmd)
    set_cmd.add_argument("--frame", type=int, default=0, help="frame index. 0 is the current frame")
    set_cmd.add_argument("--thread", help="thread name. default: the stopped thread")
    set_cmd.add_argument("--resume", action="store_true", help="resume right after setting")
    set_cmd.add_argument("--session")
    set_cmd.set_defaults(func=cmd_set)

    rw = sub.add_parser(
        "resume-with",
        help="set several values from JSON, then resume. JSON: [{\"path\":..,\"value\":{..}}]",
    )
    group = rw.add_mutually_exclusive_group()
    group.add_argument("--file", help="path to a JSON file")
    group.add_argument("--json", help="inline JSON")
    rw.add_argument("--frame", type=int, default=0)
    rw.add_argument("--thread")
    rw.add_argument("--no-resume", action="store_true", help="only set, do not resume")
    rw.add_argument("--session")
    rw.set_defaults(func=cmd_resume_with)

    ev = sub.add_parser("eval")
    ev.add_argument("expression")
    ev.add_argument("--session")
    ev.add_argument("--allow", action="store_true")
    ev.set_defaults(func=cmd_eval)
    hook = sub.add_parser("webhook", help="pause hooks: http post, events file and command on pause. off by default")
    hook.add_argument("op", choices=("show", "set", "enable", "disable", "test"))
    hook.add_argument("--url", help="http or https url to POST the pause json to")
    hook.add_argument("--clear-url", action="store_true")
    hook.add_argument("--header", action="append", help="'Name: value'. repeat. replaces all literal headers")
    hook.add_argument("--header-env", action="append", help="Name=ENV_VAR. value read from Android Studio's env. replaces all")
    hook.add_argument("--debounce", type=int, help="ms. drop a repeat stop at the same place within this window")
    hook.add_argument("--events-file", choices=("on", "off"))
    hook.add_argument("--command", help="argv for onPauseCommand, split like a shell but never run by one")
    hook.add_argument("--clear-command", action="store_true")
    hook.add_argument("--command-timeout", type=int, help="ms")
    hook.add_argument("--timeout", type=int, help="http timeout in ms (default 3000)")
    hook.add_argument("--enable", dest="enable", action="store_true", default=None)
    hook.add_argument("--disable", dest="enable", action="store_false")
    hook.set_defaults(func=cmd_webhook)

    watch = sub.add_parser("watch", help="wait for the next pause event, print its json, optionally run --exec")
    watch.add_argument("--once", action="store_true", help="exit after the first event")
    watch.add_argument("--timeout", type=int, default=0, help="ms. 0 waits forever. on timeout prints {\"event\":\"timeout\"} and exits 2")
    watch.add_argument("--exec", help="command run per event with the json on stdin and ASDBG_* env. no shell")
    watch.add_argument("--exec-timeout", type=int, default=60000, help="ms")
    watch.add_argument("--bridge", action="store_true", help="use WAIT_FOR_PAUSE on the bridge instead of the events file")
    watch.add_argument("--session")
    watch.set_defaults(func=cmd_watch)
    return parser


def self_check():
    parser = build_parser()
    args = parser.parse_args(["bp-add", "app/Main.kt", "42", "--condition", "x == 1", "--type", "method", "--suspend", "thread"])
    assert args.file == "app/Main.kt" and args.line == 42 and args.type == "method" and args.suspend == "thread"
    args = parser.parse_args(["bp-add", "--type", "exception", "--class", "java.lang.NullPointerException"])
    assert args.type == "exception" and args.class_name == "java.lang.NullPointerException"
    args = parser.parse_args(["control", "WAIT_FOR_PAUSE", "--timeout", "1000"])
    assert args.action == "WAIT_FOR_PAUSE" and args.timeout == 1000
    assert parser.parse_args(["control", "RESUME_VM"]).action == "RESUME_VM"
    args = parser.parse_args(["snapshot", "--frame", "2", "--depth", "3", "--thread", "main"])
    body = snapshot_body(args)
    assert body["frameIndex"] == 2 and body["depth"] == 3 and body["thread"] == "main" and "toString" not in body
    args = parser.parse_args(["control", "WAIT_FOR_PAUSE", "--snapshot", "--to-string"])
    assert args.snapshot and snapshot_body(args)["toString"] is True
    try:
        cmd_attach(parser.parse_args(["attach", "--serial", "X", "--timeout", "30"]))
    except SystemExit as error:
        if "milliseconds" not in str(error):
            raise
    else:
        raise SystemExit("attach --timeout 30 should stop")
    assert check_timeout(30000, strict=True) == 30000
    assert parser.parse_args(["attach", "--serial", "X", "--wait-ready"]).wait_ready
    assert parser.parse_args(["mute", "off"]).state == "off"
    args = parser.parse_args(["bp-update", "abc", "--disable", "--pass-count", "3", "--suspend", "none"])
    assert args.enable is False and args.pass_count == 3 and args.suspend == "none"
    assert parser.parse_args(["bp-update", "abc"]).enable is None
    assert parser.parse_args(["bp-enable", "abc"]).enabled is True
    assert parser.parse_args(["step", "over", "--thread", "main"]).thread == "main"
    args = parser.parse_args(["run-to", "app/Main.kt", "10", "--ignore-breakpoints"])
    assert args.line == 10 and args.ignore_breakpoints
    assert parser.parse_args(["frame", "2"]).index == 2
    assert parser.parse_args(["threads", "--frames", "5"]).frames == 5
    assert parser.parse_args(["control", "STEP_OVER", "--thread-name", "main"]).thread_name == "main"
    assert parser.parse_args(["control", "SET_VALUE"]).action == "SET_VALUE"
    args = parser.parse_args(["set", "responsePay.result.code", "--int", "200", "--frame", "1", "--resume"])
    assert value_spec_from_args(args) == {"int": "200"} and args.frame == 1 and args.resume
    assert value_spec_from_args(parser.parse_args(["set", "x", "--null"])) == {"null": True}
    assert value_spec_from_args(parser.parse_args(["set", "x", "--boolean", "true"])) == {"boolean": True}
    assert value_spec_from_args(parser.parse_args(["set", "x", "--string", "hi"])) == {"string": "hi"}
    try:
        value_spec_from_args(parser.parse_args(["set", "x", "--int", "1", "--long", "2"]))
    except SystemExit as error:
        assert "exactly one" in str(error)
    else:
        raise SystemExit("two value kinds should stop")
    try:
        value_spec_from_args(parser.parse_args(["set", "x"]))
    except SystemExit as error:
        assert "one value" in str(error)
    else:
        raise SystemExit("no value should stop")
    assert parser.parse_args(["resume-with", "--json", "[]"]).json == "[]"
    try:
        cmd_eval(parser.parse_args(["eval", "1+1"]))
    except SystemExit as error:
        if "allow" not in str(error):
            raise
    else:
        raise SystemExit("eval without --allow should stop")
    args = parser.parse_args(["webhook", "set", "--url", "http://127.0.0.1:9/hook", "--header", "Authorization: Bearer x",
                              "--header-env", "X-Key=MY_KEY", "--debounce", "500", "--events-file", "off",
                              "--command", "/usr/bin/say 'asdbg paused'", "--enable"])
    patch = webhook_patch(args)
    assert patch == {"url": "http://127.0.0.1:9/hook", "headers": {"Authorization": "Bearer x"},
                     "headersFromEnv": {"X-Key": "MY_KEY"}, "debounceMs": 500, "eventsFile": False,
                     "onPauseCommand": ["/usr/bin/say", "asdbg paused"], "enabled": True}, patch
    assert webhook_patch(parser.parse_args(["webhook", "set", "--clear-url", "--clear-command"])) == {"url": "", "onPauseCommand": []}
    assert webhook_patch(parser.parse_args(["webhook", "set", "--disable"])) == {"enabled": False}
    assert parser.parse_args(["webhook", "show"]).op == "show"
    args = parser.parse_args(["watch", "--once", "--timeout", "5000", "--exec", "echo hi"])
    assert args.once and args.timeout == 5000 and shlex.split(args.exec) == ["echo", "hi"]
    env = pause_env({"file": "a.kt", "line": 3, "thread": "main", "sessionName": None})
    assert env["ASDBG_FILE"] == "a.kt" and env["ASDBG_LINE"] == "3" and env["ASDBG_SESSION"] == ""
    print("ok")


def main(argv):
    if argv == ["--self-check"]:
        self_check()
        return
    args = build_parser().parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main(sys.argv[1:])
