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
ACTIONS = ("PAUSE", "RESUME", "STEP_OVER", "STEP_INTO", "STEP_OUT", "STOP", "WAIT_FOR_PAUSE")


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
        "bp-add", "bp-list", "bp-rm", "bp-clear", "attach", "attach-wait", "restoration-debug", "start", "sessions", "control", "stack", "vars", "eval",
    ]))


def cmd_schema(args):
    schemas = {
        "xdebug_set_breakpoint": {"file": "app/src/main/java/com/example/MainActivity.kt", "line": 42, "condition": None, "temporary": False},
        "xdebug_control_session": {"action": "WAIT_FOR_PAUSE", "timeout": 60000},
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
    body = {"serial": args.serial, "timeout": args.timeout}
    if args.package:
        body["package"] = args.package
    if args.force:
        body["force"] = True
    if args.wait_for_process:
        body["waitForProcess"] = True
    print(json.dumps(request("POST", "/v1/attach", body, timeout=max(args.timeout / 1000 + 5, 10)), indent=2))


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
    print(json.dumps(request("POST", "/v1/sessions", {
        "configuration": args.configuration,
        "timeout": args.timeout,
    }, timeout=max(args.timeout / 1000 + 5, 10)), indent=2))


def cmd_sessions(_):
    print(json.dumps(request("GET", "/v1/sessions"), indent=2))


def cmd_control(args):
    session = only_session(args.session)
    print(json.dumps(request("POST", f"/v1/sessions/{session}/control", {
        "action": args.action,
        "timeout": args.timeout,
    }, timeout=max(args.timeout / 1000 + 5, 10)), indent=2))


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
    attach.add_argument("--timeout", type=int, default=60000)
    attach.add_argument("--force", action="store_true", help="stop existing IDE debug sessions before attach")
    attach.add_argument("--wait-for-process", action="store_true", help="poll until the package process appears")
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
    sub.add_parser("status").set_defaults(func=cmd_sessions)

    control = sub.add_parser("control")
    control.add_argument("action", choices=ACTIONS)
    control.add_argument("--session")
    control.add_argument("--timeout", type=int, default=60000)
    control.set_defaults(func=cmd_control)

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
