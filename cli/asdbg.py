#!/usr/bin/env python3
"""Talk to the Android Studio debug bridge on 127.0.0.1."""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HOME = Path.home() / ".android-debug-bridge"
ENDPOINT = HOME / "endpoint.json"
ACTIONS = ("PAUSE", "RESUME", "RESUME_VM", "STEP_OVER", "STEP_INTO", "STEP_OUT", "STOP", "WAIT_FOR_PAUSE", "SNAPSHOT", "MUTE", "UNMUTE")


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
        "bp-add", "bp-list", "bp-rm", "bp-clear", "attach", "attach-wait", "restoration-debug", "start", "sessions", "mute", "control", "snapshot", "stack", "vars", "eval",
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


def cmd_control(args):
    check_timeout(args.timeout)
    session = only_session(args.session)
    body = {"action": args.action, "timeout": args.timeout}
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
    print(json.dumps(request("POST", f"/v1/sessions/{session}/evaluate", {
        "expression": args.expression,
    }, allow_eval=True), indent=2))


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
    control.add_argument("--snapshot", action="store_true", help="WAIT_FOR_PAUSE: add a snapshot when it stops")
    add_snapshot_args(control)
    control.set_defaults(func=cmd_control)

    snap = sub.add_parser("snapshot", help="file, line, stack, this and locals of the current stop. read-only")
    snap.add_argument("--session")
    add_snapshot_args(snap)
    snap.set_defaults(func=cmd_snapshot)

    for name, func in (("stack", cmd_stack), ("vars", cmd_vars)):
        cmd = sub.add_parser(name)
        cmd.add_argument("--session")
        cmd.set_defaults(func=func)

    ev = sub.add_parser("eval")
    ev.add_argument("expression")
    ev.add_argument("--session")
    ev.add_argument("--allow", action="store_true")
    ev.set_defaults(func=cmd_eval)
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
    try:
        cmd_eval(parser.parse_args(["eval", "1+1"]))
    except SystemExit as error:
        if "allow" not in str(error):
            raise
    else:
        raise SystemExit("eval without --allow should stop")
    print("ok")


def main(argv):
    if argv == ["--self-check"]:
        self_check()
        return
    args = build_parser().parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main(sys.argv[1:])
