#!/usr/bin/env bash
# asdbg のセットアップ。何度実行してもよい（冪等）。
# macOS と Linux 用。Windows は README の手動手順を使う。
#
#   scripts/setup.sh check                 Android Studio のバージョンを最初に確かめ、続けて前提条件を確かめる
#   scripts/setup.sh build                 プラグインをビルドしてテストする
#   scripts/setup.sh install               ビルドした zip を Android Studio の plugins に入れる（既存は退避）
#   scripts/setup.sh cli                   ~/.local/bin/asdbg を作る
#   scripts/setup.sh mcp [--cursor] [--claude] [--project DIR] [--package ID]
#                                          MCP サーバを登録する
#   scripts/setup.sh skills --dest DIR     skills/ を DIR にコピーする（既存は退避）
#   scripts/setup.sh verify                Android Studio 再起動後の確認
#   scripts/setup.sh all                   check build install cli をまとめて行う
#
# 共通オプション
#   --studio PATH   Android Studio の場所。環境変数 ANDROID_STUDIO_PATH でもよい
#   --plugins DIR   plugins フォルダ。環境変数 ASDBG_PLUGINS_DIR でもよい
#
# トークン（~/.android-debug-bridge/token）の中身は表示しない。

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_NAME="android-debug-bridge"
MCP_NAME="android-studio-debug"
ASDBG_HOME_DIR="${ASDBG_HOME:-$HOME/.android-debug-bridge}"
BACKUP_ROOT="${ASDBG_BACKUP_DIR:-$ASDBG_HOME_DIR/backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"

STUDIO="${ANDROID_STUDIO_PATH:-}"
PLUGINS_DIR="${ASDBG_PLUGINS_DIR:-}"
CMD=""
DESTS=()
DO_CURSOR=0
DO_CLAUDE=0
MCP_PROJECT=""
MCP_PACKAGE=""

say()  { printf '%s\n' "$*"; }
ok()   { printf '[ok] %s\n' "$*"; }
warn() { printf '[warn] %s\n' "$*" >&2; }
die()  { printf '[error] %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    check|build|install|cli|mcp|skills|verify|all) CMD="$1" ;;
    --studio) STUDIO="$2"; shift ;;
    --plugins) PLUGINS_DIR="$2"; shift ;;
    --dest) DESTS+=("$2"); shift ;;
    --cursor) DO_CURSOR=1 ;;
    --claude) DO_CLAUDE=1 ;;
    --project) MCP_PROJECT="$2"; shift ;;
    --package) MCP_PACKAGE="$2"; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
  shift
done
[ -n "$CMD" ] || { sed -n '2,22p' "$0"; exit 2; }

OS="$(uname -s)"
case "$OS" in
  Darwin) OS=mac ;;
  Linux) OS=linux ;;
  *) die "this script supports macOS and Linux. On Windows follow the manual steps in README" ;;
esac

find_studio() {
  if [ -n "$STUDIO" ]; then
    [ -d "$STUDIO" ] || die "Android Studio not found at $STUDIO"
    return
  fi
  local c
  for c in \
    "/Applications/Android Studio.app" \
    "$HOME/Applications/Android Studio.app" \
    "/opt/android-studio" \
    "/usr/local/android-studio" \
    "$HOME/android-studio" \
    "/snap/android-studio/current/android-studio"; do
    if [ -d "$c" ]; then STUDIO="$c"; return; fi
  done
  die "Android Studio not found. Pass --studio <path> or set ANDROID_STUDIO_PATH"
}

studio_file() {  # mac は Contents/ 以下、linux は直下
  if [ -d "$STUDIO/Contents" ]; then
    case "$1" in
      jbr) printf '%s' "$STUDIO/Contents/jbr/Contents/Home" ;;
      product-info) printf '%s' "$STUDIO/Contents/Resources/product-info.json" ;;
    esac
  else
    case "$1" in
      jbr) printf '%s' "$STUDIO/jbr" ;;
      product-info) printf '%s' "$STUDIO/product-info.json" ;;
    esac
  fi
}

product_field() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2], ""))' "$(studio_file product-info)" "$1"
}

gradle_value() {  # build.gradle.kts から sinceBuild などを読む
  sed -n "s/.*$1 = \"\\([^\"]*\\)\".*/\\1/p" "$REPO/ide-plugin/build.gradle.kts" | head -1
}

plugin_version() { sed -n 's/^version = "\(.*\)"/\1/p' "$REPO/ide-plugin/build.gradle.kts" | head -1; }

java_home() { studio_file jbr; }

find_plugins_dir() {
  [ -n "$PLUGINS_DIR" ] && return
  local data
  data="$(product_field dataDirectoryName)"
  [ -n "$data" ] || die "cannot read dataDirectoryName from product-info.json. Pass --plugins <dir>"
  if [ "$OS" = mac ]; then
    PLUGINS_DIR="$HOME/Library/Application Support/Google/$data/plugins"
  else
    PLUGINS_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/Google/$data"
  fi
}

check_studio_version() {  # 最初に呼ぶ。範囲外ならほかは調べずに止まる
  local info build since until b1 b2 s1 s2
  find_studio
  ok "Android Studio: $STUDIO"
  info="$(studio_file product-info)"
  [ -r "$info" ] || die "cannot read $info. Check the Android Studio version in About and compare it with README"
  build="$(sed -n 's/.*"buildNumber"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$info" | head -1)"
  since="$(gradle_value sinceBuild)"
  until="$(gradle_value untilBuild)"
  [ -n "$build" ] || die "cannot read buildNumber from $info"
  b1="${build%%.*}"; b2="$(printf '%s' "$build" | cut -d. -f2)"
  s1="${since%%.*}"; s2="$(printf '%s' "$since" | cut -d. -f2)"
  if [ "$b1" -lt "$s1" ] 2>/dev/null || { [ "$b1" = "$s1" ] && [ "$b2" -lt "$s2" ] 2>/dev/null; }; then
    die "Android Studio build $build is older than sinceBuild $since. Supported: $since - $until (see README: 対応する Android Studio のバージョン)"
  fi
  case "$until" in
    *'.*') [ "$b1" = "${until%%.*}" ] || die "Android Studio build $build is outside untilBuild $until. Supported: $since - $until (see README: 対応する Android Studio のバージョン)" ;;
  esac
  ok "Android Studio build: $build is supported ($since - $until)"
}

cmd_check() {
  local fail=0
  ok "OS: $OS ($(uname -m))"
  check_studio_version
  for tool in git python3 unzip; do
    if command -v "$tool" >/dev/null 2>&1; then ok "$tool: $(command -v "$tool")"; else warn "$tool is missing"; fail=1; fi
  done
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' \
      && ok "python3 $(python3 -c 'import platform; print(platform.python_version())')" \
      || { warn "python3 3.8 or newer is needed"; fail=1; }
  fi
  command -v adb >/dev/null 2>&1 && ok "adb: $(command -v adb)" || warn "adb is not on PATH. attach-wait and restoration-debug need it"
  local jh
  jh="$(java_home)"
  if [ -x "$jh/bin/java" ]; then
    local jv
    jv="$("$jh/bin/java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
    if [ "${jv%%.*}" -ge 21 ] 2>/dev/null; then ok "bundled JBR $jv: $jh"; else warn "bundled JBR is $jv. JDK 21+ is needed"; fail=1; fi
  else
    warn "bundled JBR not found at $jh"; fail=1
  fi
  find_plugins_dir
  ok "plugins dir: $PLUGINS_DIR"
  [ "$fail" = 0 ] || die "prerequisites are not met"
  ok "prerequisites are met"
}

cmd_build() {
  find_studio
  local jh
  jh="$(java_home)"
  [ -x "$jh/bin/java" ] || die "JBR not found at $jh"
  say "building with JAVA_HOME=$jh"
  (cd "$REPO/ide-plugin" && JAVA_HOME="$jh" ANDROID_STUDIO_PATH="$STUDIO" ./gradlew --no-daemon -q buildPlugin test)
  local zip="$REPO/ide-plugin/build/distributions/$PLUGIN_NAME-$(plugin_version).zip"
  [ -f "$zip" ] || die "build finished but $zip is missing"
  ok "built $zip"
}

studio_running() {
  if [ "$OS" = mac ]; then
    pgrep -f "$STUDIO/Contents/MacOS/studio" >/dev/null 2>&1
  else
    pgrep -f "$STUDIO/bin/" >/dev/null 2>&1
  fi
}

cmd_install() {
  find_studio
  find_plugins_dir
  local zip="$REPO/ide-plugin/build/distributions/$PLUGIN_NAME-$(plugin_version).zip"
  [ -f "$zip" ] || die "$zip is missing. Run: scripts/setup.sh build"
  local target="$PLUGINS_DIR/$PLUGIN_NAME"
  local stage="$ASDBG_HOME_DIR/stage-$STAMP"
  mkdir -p "$PLUGINS_DIR" "$stage"
  unzip -q "$zip" -d "$stage"
  [ -d "$stage/$PLUGIN_NAME" ] || die "unexpected zip layout"
  if [ -d "$target" ] && diff -rq "$stage/$PLUGIN_NAME" "$target" >/dev/null 2>&1; then
    rm -rf "$stage"
    ok "plugin $(plugin_version) is already installed at $target. nothing changed"
    return
  fi
  if [ -e "$target" ]; then
    mkdir -p "$BACKUP_ROOT"
    mv "$target" "$BACKUP_ROOT/$PLUGIN_NAME-$STAMP"
    ok "moved the old plugin to $BACKUP_ROOT/$PLUGIN_NAME-$STAMP"
  fi
  mv "$stage/$PLUGIN_NAME" "$target"
  rm -rf "$stage"
  ok "installed plugin $(plugin_version) to $target"
  if studio_running; then
    say "ACTION FOR THE HUMAN: Android Studio is running. Quit it completely and start it again, then run: scripts/setup.sh verify"
  else
    say "ACTION FOR THE HUMAN: start Android Studio, open your project, then run: scripts/setup.sh verify"
  fi
}

backup_path() {  # 既存のファイルやディレクトリを退避する
  mkdir -p "$BACKUP_ROOT"
  local dest="$BACKUP_ROOT/$(basename "$1")-$STAMP"
  mv "$1" "$dest"
  ok "moved $1 to $dest"
}

cmd_cli() {
  local bin="$HOME/.local/bin" link="$HOME/.local/bin/asdbg"
  chmod +x "$REPO/cli/asdbg.py" "$REPO/mcp-adapter/server.py"
  mkdir -p "$bin"
  if [ -L "$link" ] && [ "$(readlink "$link")" = "$REPO/cli/asdbg.py" ]; then
    ok "$link already points to $REPO/cli/asdbg.py"
  else
    [ -e "$link" ] || [ -L "$link" ] && backup_path "$link"
    ln -s "$REPO/cli/asdbg.py" "$link"
    ok "created $link"
  fi
  case ":$PATH:" in
    *":$bin:"*) ;;
    *) warn "$bin is not on PATH. Add it, or call python3 $REPO/cli/asdbg.py directly" ;;
  esac
}

cmd_mcp() {
  local server="$REPO/mcp-adapter/server.py"
  if [ "$DO_CURSOR" = 0 ] && [ "$DO_CLAUDE" = 0 ]; then
    say "Pass --cursor and/or --claude. Cursor entry for ~/.cursor/mcp.json:"
    python3 - "$server" "$MCP_PROJECT" "$MCP_PACKAGE" <<'PY'
import json, sys
server, project, package = sys.argv[1:4]
env = {k: v for k, v in (("ANDROID_STUDIO_PROJECT", project), ("ASDBG_PACKAGE", package)) if v}
entry = {"command": "python3", "args": [server]}
if env:
    entry["env"] = env
print(json.dumps({"mcpServers": {"android-studio-debug": entry}}, indent=2))
PY
    return
  fi
  if [ "$DO_CURSOR" = 1 ]; then
    local cfg="$HOME/.cursor/mcp.json"
    mkdir -p "$HOME/.cursor"
    local result
    result="$(python3 - "$cfg" "$server" "$MCP_PROJECT" "$MCP_PACKAGE" "$MCP_NAME" <<'PY'
import json, os, sys
cfg, server, project, package, name = sys.argv[1:6]
data = {}
if os.path.exists(cfg):
    with open(cfg) as handle:
        text = handle.read().strip()
    data = json.loads(text) if text else {}
servers = data.setdefault("mcpServers", {})
old = servers.get(name)
entry = {"command": "python3", "args": [server]}
env = dict((old or {}).get("env") or {})
if project:
    env["ANDROID_STUDIO_PROJECT"] = project
if package:
    env["ASDBG_PACKAGE"] = package
if env:
    entry["env"] = env
if old == entry:
    print("same")
    sys.exit(0)
print("changed" if os.path.exists(cfg) else "new")
servers[name] = entry
with open(cfg + ".asdbg-new", "w") as handle:
    json.dump(data, handle, indent=2, ensure_ascii=False)
    handle.write("\n")
PY
)"
    case "$result" in
      same) ok "Cursor: $cfg already has $MCP_NAME" ;;
      changed) backup_path "$cfg"; mv "$cfg.asdbg-new" "$cfg"; ok "Cursor: updated $MCP_NAME in $cfg" ;;
      new) mv "$cfg.asdbg-new" "$cfg"; ok "Cursor: created $cfg with $MCP_NAME" ;;
    esac
    say "Cursor shows it as user-$MCP_NAME. Reload MCP servers in Cursor settings if it is not ready"
  fi
  if [ "$DO_CLAUDE" = 1 ]; then
    command -v claude >/dev/null 2>&1 || die "claude command not found. Install Claude Code first"
    local envs=()
    [ -n "$MCP_PROJECT" ] && envs+=(-e "ANDROID_STUDIO_PROJECT=$MCP_PROJECT")
    [ -n "$MCP_PACKAGE" ] && envs+=(-e "ASDBG_PACKAGE=$MCP_PACKAGE")
    if claude mcp get "$MCP_NAME" >/dev/null 2>&1; then
      claude mcp remove --scope user "$MCP_NAME" >/dev/null 2>&1 || true
    fi
    claude mcp add --scope user ${envs[@]+"${envs[@]}"} "$MCP_NAME" -- python3 "$server"
    ok "Claude Code: registered $MCP_NAME (user scope)"
  fi
}

cmd_skills() {
  [ "${#DESTS[@]}" -gt 0 ] || die "pass --dest <skills dir>. e.g. ~/.cursor/skills or ~/.claude/skills"
  local dest name
  for dest in "${DESTS[@]}"; do
    mkdir -p "$dest"
    for src in "$REPO"/skills/*/; do
      name="$(basename "$src")"
      if [ -d "$dest/$name" ] && diff -rq "$src" "$dest/$name" >/dev/null 2>&1; then
        ok "$dest/$name is up to date"
        continue
      fi
      [ -e "$dest/$name" ] && backup_path "$dest/$name"
      cp -R "$src" "$dest/$name"
      ok "copied $name to $dest/$name"
    done
  done
}

cmd_verify() {
  local fail=0
  python3 "$REPO/cli/asdbg.py" --self-check >/dev/null && ok "cli self-check" || { warn "cli self-check failed"; fail=1; }
  python3 "$REPO/mcp-adapter/server.py" --self-check >/dev/null && ok "mcp self-check" || { warn "mcp self-check failed"; fail=1; }
  local tools
  tools="$(printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}' \
    '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | python3 "$REPO/mcp-adapter/server.py" \
    | python3 -c 'import json,sys; [print(len(m["result"]["tools"])) for m in map(json.loads, sys.stdin) if m.get("id") == 2]')"
  [ "${tools:-0}" -gt 0 ] && ok "mcp stdio: $tools tools" || { warn "mcp stdio handshake failed"; fail=1; }
  if python3 "$REPO/cli/asdbg.py" doctor --expect-version "$(plugin_version)"; then
    ok "bridge is up and the token works"
  else
    warn "bridge check failed. Is Android Studio running with the plugin, and was it restarted after install?"
    fail=1
  fi
  [ "$fail" = 0 ] || die "verify failed"
  say "SETUP COMPLETE"
}

case "$CMD" in
  check) cmd_check ;;
  build) cmd_build ;;
  install) cmd_install ;;
  cli) cmd_cli ;;
  mcp) cmd_mcp ;;
  skills) cmd_skills ;;
  verify) cmd_verify ;;
  all) cmd_check; cmd_build; cmd_install; cmd_cli ;;
esac
