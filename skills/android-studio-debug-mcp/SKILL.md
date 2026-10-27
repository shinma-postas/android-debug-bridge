---
name: android-studio-debug-mcp
description: >-
  MCP サーバ android-studio-debug のツールで、Android Studio が持っているデバッグセッションを操作する。
  bp_add、attach、control、snapshot、set_value、wait_pause_event、webhook_set などを使い、
  ブレークポイント、停止待ち、ステップ、スレッド単位の停止、変数の取得、値の書き換え、停止の通知を扱う。
  Cursor の user-android-studio-debug や Claude Code の android-studio-debug ツールでデバッグするときに使う。
  シェルから asdbg を使うときは android-studio-debug を使う。
---

# Android Studio デバッグ（MCP）

MCP サーバ `android-studio-debug` のツールで Android Studio のデバッガを操作する。jdb は張らない。Android Studio の画面をクリックしない。シェルの手順はスキル `android-studio-debug` にある。

## 接続

- Cursor では namespace `user-android-studio-debug` として見える。Claude Code では `mcp__android-studio-debug__<tool>` として見える。
- 状態が `ready` でツールが返るときだけ呼ぶ。`loading` や `error` なら呼ばずに、人間に MCP の再読み込みを頼む。
- 最初に `bp_list` か `sessions` を呼ぶ。JSON が返ればブリッジは生きている。`bridge endpoint is missing` なら、Android Studio の起動か、プラグインを入れたあとの再起動を人間に頼む。プラグインの入れ替えや jdb には進まない。
- `projectPath` は Android Studio で開いているプロジェクトのルートの絶対パス。サーバの `ANDROID_STUDIO_PROJECT` と同じなら省ける。
- token は `~/.android-debug-bridge/token` にあり、サーバが読む。中身を読まない。表示しない。
- 端末のシリアル、接続先、パスワードはこのスキルに書かない。必要なら Wiki参照。

## ツール

行番号は 1 から。`file` はプロジェクトルートからの相対パス。`timeout` と `snapshotTimeout` はミリ秒。すべてのツール（webhook 系と `wait_pause_event` を除く）が `projectPath` を取る。

| ツール | 必須 | 任意 |
| --- | --- | --- |
| `bp_add` | `file` と `line`（`type: exception` のときは `className`） | `type`（`line` `method` `field` `exception`）、`suspend`（`all` `thread` `none`）、`condition`、`temporary` |
| `bp_list` | | |
| `bp_update` | `id` | `enabled`、`condition`、`logMessage`、`logExpression`、`logStack`、`suspend`、`passCount` |
| `bp_remove` | `id` | |
| `bp_clear` | | |
| `attach` | `serial` | `package`、`timeout`、`force`、`waitForProcess`、`waitReady` |
| `start` | `configuration` | `timeout` |
| `sessions` | | |
| `mute` | `muted` | `sessionId` |
| `threads` | | `sessionId`、`frames` |
| `control` | `action` | `sessionId`、`timeout`、`snapshot`、`file`、`line`、`ignoreBreakpoints`、`frameIndex`、`thread`、snapshot の引数 |
| `snapshot` | | `sessionId`、`frameIndex`、`depth`、`maxChildren`、`maxString`、`maxNodes`、`thread`、`snapshotTimeout`、`toString` |
| `stack` | | `sessionId` |
| `variables` | | `sessionId` |
| `evaluate` | `expression` | `sessionId` |
| `set_value` | `path` と `value`、または `sets` | `frameIndex`、`thread`、`resume`、`sessionId` |
| `webhook_get` | | |
| `webhook_set` | | `enabled`、`url`、`headers`、`headersFromEnv`、`debounceMs`、`eventsFile`、`eventsMaxBytes`、`onPauseCommand`、`commandTimeoutMs`、`timeoutMs` |
| `webhook_test` | | |
| `wait_pause_event` | | `timeout`（100 から 600000、既定 60000） |

- `control` の `action` は `PAUSE` `RESUME` `RESUME_VM` `STEP_OVER` `STEP_INTO` `STEP_OUT` `STOP` `WAIT_FOR_PAUSE` `SNAPSHOT` `MUTE` `UNMUTE` `SUSPEND_THREAD` `RESUME_THREAD` `RUN_TO_LINE` `SELECT_FRAME` `DETACH` `SET_VALUE`。
  - `SUSPEND_THREAD`、`RESUME_THREAD`、`STEP_*` は `thread` を取る。
  - `RUN_TO_LINE` は `file` と `line`（と `ignoreBreakpoints`）を取る。
  - `SELECT_FRAME` は `frameIndex` と `thread` を取る。
  - `WAIT_FOR_PAUSE` に `snapshot: true` を付けると、止まったときに snapshot が付く。読めなければ `snapshotError` が付く。`event` は `paused` `timeout` `stopped`。
- セッションが複数あるときは、`sessions` の `id` を `sessionId` に付ける。1 件なら要らない。
- `attach` の `package` を省くと、サーバの `ASDBG_PACKAGE` を使う。どちらも無いとエラーになる。動いているプロセスに付けるだけで、入れ直さない。応答に `warnings` があれば `waitReady: true` で付け直す。
- `start` は多くの場合アプリを入れ直す。動いているアプリには使わない。名前が違うときはエラーの `known=` を見る。
- `bp_add` の応答の `created` が false なら、その行には既にブレークポイントがある。`owned` が false のものは `bp_remove` しない（403 になる）。置けないときは `available=` を見て `type` を変える。
- `bp_list` の `verified` は `verified` `pending` `invalid` `unknown`。`invalid` なら `verifyError` を見る。

## 止まるのを待つ

- `sleep` で代用しない。
- 自分の操作の直後に待つなら `control` の `WAIT_FOR_PAUSE`（`timeout: 60000`、`snapshot: true`）。
- 人間の画面操作で止まるのを待つなら pause hooks を使う。
  1. `webhook_get` で今の設定を見る。
  2. 止まっていれば `webhook_set` に `{"enabled": true, "eventsFile": true}` を渡す。
  3. `wait_pause_event`（`timeout: 600000` まで）で次の停止を待つ。返る JSON の `kind`、`file`、`line`、`class`、`method`、`thread`、`threadLevel`、`sessionId` を見る。変数の値は入らない。時間内に来なければ timeout を返す。
  4. 次に `snapshot` や `set_value` を行う。
  5. 自分が有効にしたときだけ、終わったら `webhook_set` に `{"enabled": false}` を渡して戻す。
- `webhook_set` の `onPauseCommand` は argv の配列で、シェルを通さない。`url` は http か https。ヘッダの秘密は `headersFromEnv` で環境変数の名前を渡す。`webhook_test` は見本を全部の経路に送る。

## suspend=thread で止めたとき

- `sessions` の `threadSuspended` と `suspendedThreads` を見る。セッション自体は suspended にならないことがある。
- `stack`、`variables`、`evaluate` はセッションが suspended のときだけ使える。スレッド単位の停止では `snapshot` に `thread`、`threads` に `frames`、`control` の `STEP_*` に `thread` を渡す。
- `control` の `RESUME` は止まっているスレッドを再開し、`resumed` を返す。戻らないときの最後の手段が `RESUME_VM`。
- ほかのスレッドは動いている。`evaluate` の式が、止めたスレッドのロックを待つと戻らなくなる。

## 値を書き換える（set_value）

- `path` はローカル変数、`this.` のフィールド、`a.b[2].c` のようなパス、完全修飾の静的フィールド。
- `value` は 1 種類だけ。`{"int": "5"}`（`long` `short` `byte` `double` `float` も同じ）、`{"boolean": true}`、`{"char": "A"}`、`{"string": "hi"}`、`{"null": true}`、`{"enum": "DONE"}`。
- まとめて書くときは `sets: [{"path": ..., "value": ...}]`。`resume: true` で書いたあと再開する。
- 応答に古い値と新しい値が出る。`final` フィールドには `warning` が付く。
- アプリの動きを本当に変える。人間が書き換える値と場所を決めたときだけ使う。本番の端末やデータでは使わない。

## 調べ方

1. コードを読み、仮説に対する停止行を 1 つ選ぶ。
2. `bp_add` する。固まりやすいアプリでは `suspend: "thread"` にする。
3. アプリが動いていれば `attach`。Debug 起動を頼まれたときだけ `start`。
4. 止まっていれば `snapshot`。動いていれば `control` の `WAIT_FOR_PAUSE` か `wait_pause_event` で待つ。
5. 見た値で次を決める。足りなければ `STEP_OVER` のあと、もう一度待つ。
6. 操作は合計 15 回までにする。同じ操作を 3 回続けて新しい事実が無ければ止めて、見た file、line、変数を報告する。
7. 後片付け。この調査で `created: true` だった id だけ `bp_remove` する。自分が `start` したセッションだけ `STOP` する。attach したセッションは `DETACH` する。

アプリを kill して付け直す試験は、シェルの `asdbg attach-wait` と `asdbg restoration-debug` が向いている。スキル `android-studio-debug` を見る。

## 気をつけること

- 報告にはツールの JSON に出た file、line、値だけを書く。推測した値は書かない。
- snapshot や変数には個人情報や識別子が入る。外に出すときは、カード番号、token、端末 ID、シリアルを末尾 4 桁まで伏せる。
- `evaluate` と `snapshot` の `toString: true` はアプリの中でコードを動かす。人間が式を指定したときだけ使う。確認のための `1+1` は打たない。
- 止まらない、または止まったあと固まるときは、人間に Settings > Build, Execution, Deployment > Debugger > Data Views > Java の「Enable alternative view for Collections classes」と「Enable 'toString()' object view」を外してもらう。ブレークポイントは `suspend: "thread"` にする。
- Android Studio 同梱 MCP の `execute_run_configuration` や `xdebug_*` でデバッグしない。
- 失敗したら JSON をそのまま報告する。別のデバッガには切り替えない。
