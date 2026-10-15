#!/usr/bin/env python3
"""Talk to the Android Studio debug bridge on 127.0.0.1."""

import argparse
import json
import os
import sys
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
        "bp-add", "bp-list", "bp-rm", "attach", "start", "sessions", "control", "stack", "vars", "eval",
    ]))


def cmd_schema(args):
    schemas = {
        "xdebug_set_breakpoint": {"file": "app/src/main/java/com/example/MainActivity.kt", "line": 42, "condition": None, "temporary": False},
        "xdebug_control_session": {"action": "WAIT_FOR_PAUSE", "timeout": 60000},
        "bp-add": {"file": "path", "line": 1},
    }
    print(json.dumps(schemas.get(args.name, {"error": "unknown schema", "known": sorted(schemas)}), indent=2))


def cmd_bp_add(args):
    body = {"file": args.file, "line": args.line, "temporary": args.temporary}
    if args.condition:
        body["condition"] = args.condition
    print(json.dumps(request("POST", "/v1/breakpoints", body), indent=2))


def cmd_bp_list(_):
    print(json.dumps(request("GET", "/v1/breakpoints"), indent=2))


def cmd_bp_rm(args):
    print(json.dumps(request("DELETE", f"/v1/breakpoints/{args.id}"), indent=2))


def cmd_attach(args):
    body = {"serial": args.serial, "timeout": args.timeout}
    if args.package:
        body["package"] = args.package
    print(json.dumps(request("POST", "/v1/attach", body, timeout=max(args.timeout / 1000 + 5, 10)), indent=2))


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
    add.add_argument("file")
    add.add_argument("line", type=int)
    add.add_argument("--condition")
    add.add_argument("--temporary", action="store_true")
    add.set_defaults(func=cmd_bp_add)

    sub.add_parser("bp-list").set_defaults(func=cmd_bp_list)
    rm = sub.add_parser("bp-rm")
    rm.add_argument("id")
    rm.set_defaults(func=cmd_bp_rm)

    attach = sub.add_parser("attach")
    attach.add_argument("--serial", required=True)
    attach.add_argument("--package", default="jp.co.persol.postas.selfregi")
    attach.add_argument("--timeout", type=int, default=60000)
    attach.set_defaults(func=cmd_attach)

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
    args = parser.parse_args(["bp-add", "app/Main.kt", "42", "--condition", "x == 1"])
    assert args.file == "app/Main.kt" and args.line == 42 and args.condition == "x == 1"
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
