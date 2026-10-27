---
name: android-studio-debug
description: >-
  シェルの asdbg CLI で、Android Studio が持っているデバッグセッションを操作する。
  ブレークポイントの追加と変更、動いているアプリへの attach、停止待ち、ステップ、スレッド単位の停止と再開、
  snapshot でのスタックと変数の取得、値の書き換え（set）、停止の通知（webhook と watch）を扱う。
  asdbg、asdbg.py、ターミナルからブレークポイントやステップを操作したいときに使う。
  MCP の android-studio-debug ツールが使えるときは android-studio-debug-mcp を使う。
---

# Android Studio デバッグ（CLI）

asdbg CLI で Android Studio のデバッガを操作する。jdb は張らない。Android Studio の画面をクリックしない。MCP の `android-studio-debug` ツールが使えるなら、スキル `android-studio-debug-mcp` を使う。

## 準備

```bash
export ANDROID_STUDIO_PROJECT="<Android Studio で開いているプロジェクトのルートの絶対パス>"
export ASDBG_PACKAGE="<アプリの applicationId>"   # attach で使う。--package でも渡せる
asdbg <subcommand>                                 # 無ければ python3 <repo>/cli/asdbg.py <subcommand>
```

- `ANDROID_STUDIO_PROJECT` は、エージェントのカレントディレクトリではなく、Android Studio で開いているプロジェクトのルートにする。無いとカレントディレクトリが使われる。
- token は `~/.android-debug-bridge/token` にあり、CLI が読む。中身を読まない。表示しない。本文やログに書かない。
- 端末のシリアル、接続先、パスワードはこのスキルに書かない。必要なら Wiki参照。

## 接続を確かめる

最初に `asdbg doctor` を実行する。`"ok": true` なら使える。`pluginVersion` も出る。

失敗したら、Android Studio が起動してプロジェクトを開いているかを人間に確かめてもらう。プラグインを入れた直後なら Android Studio の再起動を頼む。プラグインの再ビルド、入れ替え、jdb には進まない。

## よく使うコマンド

行番号は 1 から。ファイルはプロジェクトルートからの相対パス。`--timeout` はすべてミリ秒。

```bash
asdbg bp-add <file> <line>                                   # 行
asdbg bp-add <file> <line> --suspend thread                  # そのスレッドだけ止める
asdbg bp-add <file> <line> --type method                     # 関数宣言の行
asdbg bp-add <file> <line> --type field                      # プロパティ宣言の行
asdbg bp-add --type exception --class java.lang.IllegalStateException
asdbg bp-add <file> <line> --condition '<expr>' --temporary
asdbg bp-list
asdbg bp-update <id> --condition "count > 3" --pass-count 2 --suspend thread
asdbg bp-update <id> --log-expression "amount" --suspend none
asdbg bp-enable <id> / asdbg bp-disable <id>
asdbg bp-rm <id> / asdbg bp-clear
asdbg mute on / asdbg mute off [--session <id>]

asdbg attach --serial <serial> [--package <id>] --timeout 60000 [--wait-ready] [--wait-for-process] [--force]
asdbg start <configuration> --timeout 30000
asdbg sessions

asdbg control WAIT_FOR_PAUSE --timeout 60000 [--snapshot]
asdbg snapshot [--frame N] [--depth 3] [--thread <name>] [--max-children 20] [--max-string 200]
asdbg stack / asdbg vars
asdbg threads --frames 5
asdbg step over|into|out [--thread <name>]
asdbg run-to <file> <line> [--ignore-breakpoints]
asdbg frame <index> [--thread <name>]
asdbg thread-suspend <name> / asdbg thread-resume <name>
asdbg control RESUME / asdbg control RESUME_VM / asdbg control STOP / asdbg detach
asdbg eval '<expr>' --allow
```

- `control` の action は `PAUSE` `RESUME` `RESUME_VM` `STEP_OVER` `STEP_INTO` `STEP_OUT` `STOP` `WAIT_FOR_PAUSE` `SNAPSHOT` `MUTE` `UNMUTE` `SUSPEND_THREAD` `RESUME_THREAD` `RUN_TO_LINE` `SELECT_FRAME` `DETACH` `SET_VALUE`。行を指定する操作は `run-to`、フレームは `frame`、スレッドは `thread-suspend` と `thread-resume` を使う。
- セッションが複数あるときは、`sessions` で見た id を `--session` に付ける。1 件なら要らない。
- `bp-add` の応答の `created` が false なら、その行には既にブレークポイントがある。`owned` が false のものは消さない（403 になる）。
- `bp-list` の `verified` は `verified`（クラスに入った）、`pending`（まだクラスが読まれていない）、`invalid`（置けない。`verifyError` を見る）、`unknown`（セッションが無い）。
- 置けないときはエラー JSON の `available=` を見て `--type` を変える。`start` の名前が違うときは `known=` を見る。
- `attach` は動いているプロセスに付けるだけで、入れ直さない。`--package` も `ASDBG_PACKAGE` も無いとエラーになる。`start` は多くの場合アプリを入れ直すので、動いているアプリには使わない。
- `attach` が `warnings` を返したら、Android Studio がまだインデックス中。`--wait-ready` を付けて付け直す。

## 止まるのを待つ

- `sleep` で代用しない。
- 自分が操作した直後に待つなら `asdbg control WAIT_FOR_PAUSE --timeout 60000 --snapshot`。`event` は `paused` `timeout` `stopped`。
- 人間の画面操作で止まるのを待つなら、pause hooks を使う。

```bash
asdbg webhook show
asdbg webhook enable                      # pause-events.jsonl に書き始める
asdbg watch --once --timeout 600000       # 次の停止を 1 行の JSON で返す。来なければ {"event":"timeout"}、終了コード 2
asdbg watch --once --bridge --timeout 60000   # イベントファイルを使わずブリッジで待つ
asdbg webhook set --command "<argv>" --command-timeout 5000
asdbg webhook set --url <http(s) URL> --header-env Authorization=<ENV名>
asdbg webhook set --events-file off --debounce 500
asdbg webhook set --clear-url --clear-command
asdbg webhook test
asdbg webhook disable
```

- イベントの JSON には `kind`（`breakpoint` `step` `exception` `method` `field` `pause` `run_to_line` `other`）、`file`、`line`、`class`、`method`、`thread`、`threadLevel`、`sessionId`、`breakpointId` が入る。変数の値は入らない。
- 返ってきた `file` と `line` を見て、次に `snapshot` や `set` を行う。
- 調べ終わったら、自分が `enable` した場合だけ `webhook disable` で戻す。

## suspend=thread で止めたとき

- Android Studio のセッションは suspended にならないことがある。`sessions` の `threadSuspended` と `suspendedThreads` を見る。
- `stack`、`vars`、`eval` はセッションが suspended のときだけ使える。スレッド単位の停止では `snapshot --thread <name>`、`threads --frames N`、`step <kind> --thread <name>` を使う。
- `control RESUME` は止まっているスレッドを再開し、`resumed` に再開したスレッドを返す。戻らないときの最後の手段が `control RESUME_VM`。
- ほかのスレッドは動いている。`eval` の式が、止めたスレッドのロックを待つと戻らなくなる。

## 値を書き換える

```bash
asdbg set <path> --int 200                  # --long --short --byte --double --float --boolean --char --string --null --enum
asdbg set this.state --enum DONE --resume
asdbg set flag --boolean true --thread main --frame 1
asdbg resume-with --json '[{"path":"a.code","value":{"int":"200"}},{"path":"a.ok","value":{"boolean":true}}]'
asdbg resume-with --file sets.json --no-resume
```

- `path` はローカル変数、`this.` のフィールド、`a.b[2].c` のようなパス、完全修飾の静的フィールド。
- 応答に古い値と新しい値が出る。`final` フィールドには `warning` が付く。
- アプリの動きを本当に変える。人間が書き換える値と場所を決めたときだけ使う。本番の端末やデータでは使わない。

## 調べ方

1. コードを読み、仮説に対する停止行を 1 つ選ぶ。
2. `bp-add` する。固まりやすいアプリでは `--suspend thread` にする。
3. アプリが動いていれば `attach --serial <serial>`。Debug 起動を頼まれたときだけ `start`。
4. 止まっていれば `snapshot`。動いていれば `control WAIT_FOR_PAUSE --snapshot` か `watch --once` で待つ。
5. 見た値で次を決める。足りなければ `step over` のあと、もう一度待つ。
6. 操作は合計 15 回までにする。同じ操作を 3 回続けて新しい事実が無ければ止めて、見た file、line、変数を報告する。
7. 後片付け。この調査で `created: true` だった id だけ `bp-rm` する。自分が `start` したセッションだけ `control STOP` する。attach したセッションは `detach` する。

アプリを kill して起動し直す試験では、`attach-wait --serial <serial> --after-kill` で古いセッションを止めてから付け直す。停止位置を 2 段階で変えるときは `restoration-debug --serial <serial> --file <file> --bp1-line <n> --bp2-line <m> --once` を使う。どちらも `adb` が要る。

## 気をつけること

- 報告には CLI の JSON に出た file、line、値だけを書く。推測した値は書かない。
- snapshot や変数には個人情報や識別子が入る。外に出すときは、カード番号、token、端末 ID、シリアルを末尾 4 桁まで伏せる。
- `eval` と `snapshot --to-string` はアプリの中でコードを動かす。人間が式を指定したときだけ使う。確認のための `1+1` は打たない。
- 止まらない、または止まったあと固まるときは、人間に Settings > Build, Execution, Deployment > Debugger > Data Views > Java の「Enable alternative view for Collections classes」と「Enable 'toString()' object view」を外してもらう。ブレークポイントは `--suspend thread` にする。
- Android Studio 同梱 MCP の `execute_run_configuration` や `xdebug_*` でデバッグしない。
- 失敗したら JSON をそのまま報告する。別のデバッガには切り替えない。
