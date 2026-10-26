# Android Studio debug bridge

Android Studio 2026.2.1 の MCP Server は `http://127.0.0.1:64342/stream` で動く。ツール一覧に `xdebug_*` は無い。Debugger MCP Toolset は IntelliJ IDEA 同梱で、この Android Studio には入っていない。

このディレクトリのプラグインは、Android Studio が持っているデバッグセッションを `127.0.0.1` の API にする。別の jdb は張らない。

## 入れる

```
cd ide-plugin
./gradlew buildPlugin
```

`ide-plugin/build/distributions/android-debug-bridge-0.1.5.zip` ができる。この zip を Android Studio の plugins に展開し、Android Studio を再起動する。起動後に `~/.android-debug-bridge/endpoint.json` と `token` ができる。

## CLI

```
export ANDROID_STUDIO_PROJECT="/path/to/project"
python3 cli/asdbg.py bp-add app/src/main/java/com/example/Main.kt 42
python3 cli/asdbg.py bp-list
python3 cli/asdbg.py start app
python3 cli/asdbg.py control WAIT_FOR_PAUSE --timeout 60000
python3 cli/asdbg.py stack
python3 cli/asdbg.py vars
python3 cli/asdbg.py control STEP_OVER
python3 cli/asdbg.py control RESUME
```

### ブレークポイントが効いているか

`bp-list` と `bp-add` の各ブレークポイントに `verified` が付く。

- `verified`: 動いているセッションでクラスに入った。Android Studio の赤丸にチェックが付いた状態。
- `pending`: セッションはあるが、まだクラスが読み込まれていない。そのコードを通る前はこうなる。
- `invalid`: 置けなかった。`verifyError` に Android Studio の理由が入る（行にコードが無いなど）。
- `unknown`: デバッグセッションが無い、または状態が読めなかった。

`verifySession` は判定に使ったセッション名。`sessions` にはセッションごとの `muted` と、プロジェクトがインデックス中かを示す `projectIndexing` が出る。

### ミュート

```
python3 cli/asdbg.py mute on
python3 cli/asdbg.py mute off --session <id>
```

Debug ウィンドウの Mute Breakpoints と同じ。ミュートはセッションごとなので、`--session` が無ければ全セッションに掛ける。ミュート中はブレークポイントが `verified` でも止まらない。

### attach の前に Android Studio の準備を待つ

Android Studio の起動直後はプロジェクトが読み込み途中だったり、インデックス中（dumb mode）だったりする。この間に attach すると、ブレークポイントが効かないことがある。

- `attach` は、プロジェクトが読み込み途中かインデックス中なら `warnings` を返す。CLI は標準エラーに出す。
- `attach --wait-ready` はインデックスが終わるまで待ってから attach する。待てるのは `--timeout` から 1000 ms 引いた分まで。待った時間は `waitedReadyMs` に出る。

### 操作の一覧

```
python3 cli/asdbg.py threads --frames 5
python3 cli/asdbg.py thread-suspend "Binder:1234_2"
python3 cli/asdbg.py thread-resume "Binder:1234_2"
python3 cli/asdbg.py step over --thread main
python3 cli/asdbg.py run-to app/src/main/java/com/example/Main.kt 60
python3 cli/asdbg.py frame 2 --thread main
python3 cli/asdbg.py bp-update <id> --condition "count > 3" --pass-count 2 --suspend thread
python3 cli/asdbg.py bp-update <id> --log-expression "amount" --suspend none
python3 cli/asdbg.py bp-disable <id>
python3 cli/asdbg.py detach
```

- `threads` は VM の全スレッドを返す。`status`（RUNNING、WAIT、MONITOR など）、`suspended`、`suspendCount`、ブレークポイントで止まったスレッドなら `stoppedHere: true`。`--frames N` で止まっているスレッドの上位 N フレームが付く。
- `step` に `--thread` を付けると、そのスレッドでステップする。suspend=thread で止まったときも使える。
- `run-to` は Run to Cursor と同じ。行は 1 から。`--ignore-breakpoints` で途中のブレークポイントを無視する。
- `frame` は Frames で別のフレーム（やスレッド）を選ぶのと同じ。そのあとの `stack`、`vars`、`eval` はそのフレームを使う。
- `bp-update` は手で置いたブレークポイントも変えられる。指定した項目だけ変わる。`--condition ""` と `--log-expression ""` で消える。`--pass-count 0` でパスカウントを外す。削除（`bp-rm`）はこれまでどおりこのブリッジが置いたものだけ。
- `detach` はアプリを動かしたままデバッガを外す。`control STOP` はセッションを止める。attach したプロセスでは Android Studio の動きに従う。
- `eval` は結果に `warning` を付け、CLI は標準エラーに出す。式はアプリで動く。suspend=thread で止めたときは他のスレッドが動いているので、止めたスレッドが持つロックを式が待つと戻らなくなる。

Debug ウィンドウの操作との対応:

| 操作 | 状態 | CLI |
|---|---|---|
| 行・メソッド・フィールドのブレークポイント | 既存 | `bp-add` |
| 例外ブレークポイント | 既存 | `bp-add --type exception --class ...` |
| ブレークポイント一覧 | 既存。0.1.5 で `verified` を追加 | `bp-list` |
| 有効・無効 | 追加 | `bp-enable`、`bp-disable`、`bp-update --enable/--disable` |
| 条件、ログ、ログ式、スタックのログ、suspend policy | 追加（既存ブレークポイントへの変更） | `bp-update` |
| パスカウント（Pass count） | 追加。Java のブレークポイントだけ | `bp-update --pass-count` |
| 削除、全削除 | 既存。削除はこのブリッジが置いたものだけ | `bp-rm`、`bp-clear` |
| Mute Breakpoints | 追加 | `mute on/off` |
| 全体の Pause、Resume | 既存 | `control PAUSE`、`control RESUME` |
| VM ごと再開 | 既存 | `control RESUME_VM` |
| スレッド一覧（状態とフレーム） | 追加 | `threads` |
| スレッド単位の Suspend、Resume | 追加 | `thread-suspend`、`thread-resume` |
| Step Over、Into、Out | 既存。0.1.5 でスレッド指定を追加 | `step`、`control STEP_*` |
| Run to Cursor | 追加 | `run-to` |
| フレームの選択 | 追加 | `frame` |
| スタック、変数 | 既存 | `stack`、`vars`、`snapshot` |
| 式の評価 | 既存。`--allow` が要る。0.1.5 で警告を追加 | `eval` |
| 停止時の情報をまとめて取る | 追加 | `snapshot`、`control WAIT_FOR_PAUSE --snapshot` |
| Stop と Detach | Stop は既存、Detach は追加 | `control STOP`、`detach` |
| attach 前の準備待ち | 追加 | `attach --wait-ready` |
| 値の書き換え（Set Value） | 未実装。次の段階。下の設計メモ | |
| Drop Frame（フレームを戻す） | 未実装。API（`createPopFrameCommand`）はある。戻した分の副作用は戻らないので、要るときに足す | |
| Force Return、Throw Exception | 未実装。JDI の `forceEarlyReturn` は Android の ART で使えないことがある | |
| Watches | 作らない。Android Studio の画面の状態で、CLI では `eval` を毎回呼べば同じ | |
| HotSwap（Apply Changes） | 対象外。ビルドと配備の操作で、デバッガの API ではない | |
| Mark Object、Memory View | 未実装。調査で要るなら足す | |

### suspend=thread で止めたとき

`--suspend thread` のブレークポイントで止まると、Android Studio のセッションは suspended にならないことがある。

- `sessions` の `threadSuspended` が `true` なら、どれかのスレッドが止まっている。`suspendedThreads` にスレッド名と suspend policy が出る。取れないときは `null`。
- `control RESUME` は、セッションが suspended ならこれまでどおり再開する。そうでなければ止まっているスレッドを Android Studio の Resume と同じ方法で再開し、`resumed` に再開したスレッドを返す。止まっているものが無ければ 409 `session is running`。
- `control RESUME_VM` は VM ごと再開する。RESUME で戻らないときの最後の手段。Android Studio の表示が止まったままになることがある。
- `control WAIT_FOR_PAUSE` はスレッド単位の停止も `paused` で返す。そのときは `threadLevel: true` と `suspendedThreads` が付く。
- `control STOP` は止める前のセッション id を返す。
- `stack`、`vars`、`eval` は、これまでどおりセッションが suspended のときだけ使える。スレッド単位の停止では `snapshot --thread`、`threads --frames`、`step --thread` を使う。

### timeout の単位

`--timeout` と MCP の `timeout` はどれもミリ秒。30 秒なら `30000`。`attach`、`attach-wait`、`restoration-debug --attach-timeout`、`start` は 1000 未満を受け付けない。`control` は 1000 未満だと警告を出してそのまま送る。プラグイン側も attach の 1000 未満を 400 で返す。attach がタイムアウトしたときは、途中までできたデバッグセッションを止めて 504 を返す。

### 止まった時点をまとめて見る（snapshot）

止まっているスレッドについて、Android Studio のデバッガに出る情報を 1 回で返す。

```
python3 cli/asdbg.py snapshot
python3 cli/asdbg.py snapshot --frame 1 --depth 3
python3 cli/asdbg.py snapshot --thread main --max-children 20 --max-string 200
python3 cli/asdbg.py control WAIT_FOR_PAUSE --timeout 60000 --snapshot
```

返すもの:

- `sessionId`
- `thread`: スレッド名、状態、`suspendCount`、`policy`、`threadLevel`（suspend=thread で止まったら true）
- `location`: 見ているフレームの `file`（プロジェクトからの相対パス。見つからなければ null）、`sourcePath`、`line`、`class`、`method`
- `breakpoint`: その行にあるブレークポイント。無ければ null
- `frames`: スタック全体（最大 200）。各フレームに `file`、`line`、`class`、`method`
- `variables.this` と `variables.locals`: 名前、型、値、`children`。`--depth` の深さまで開く（既定 2、最大 6）
- `nodes`、`truncated`、`truncatedReason`、`limits`、`elapsedMs`

値の読み方:

- JDI のミラーを読むだけで、アプリのメソッドは呼ばない。String、プリミティブ、ボクシング型、enum、配列、ArrayList、HashMap、LinkedHashMap、ConcurrentHashMap、HashSet はフィールドから中身を出す。
- それ以外のオブジェクトはフィールドを出す。`toString()` の結果がほしいときだけ `--to-string` を付ける。これはアプリでコードを動かすので、`eval` と同じく `X-ASdbg-Allow-Eval: 1` が要る。CLI は `--to-string` のときだけこのヘッダを付ける。
- 上限は `--max-children`（既定 50）、`--max-string`（既定 1000 文字）、`--max-nodes`（既定 2000）、`--snapshot-timeout`（既定 10000 ms）。どれかで切ったら `truncated: true` と理由が付く。
- 同じオブジェクトに戻る参照は `cycle: true` で止める。
- `stack` や `vars` と違い、suspend=thread で止まったときでも使える。

### snapshot の値の扱い

snapshot には会員情報、決済の値、端末の識別子などがそのまま入ることがある。名前が password、secret、token、apiKey、credential に当たる値は `***` にするが、それ以外は伏せない。

- ブリッジは `127.0.0.1` だけで待ち受け、token が無いと答えない。
- プラグインと CLI は値を外へ送らない。
- 結果をチャット、イシュー、外部のサービスに貼るときは、カード番号、POICHI token、端末 ID、シリアルを伏せる。

### 値の書き換え（次の段階の設計メモ）

今は読むだけ。書き換えは別の action `SET_VALUE` として足す予定。

- 入力は snapshot と同じ `frameIndex` と、`this.payment.amount` のような名前のパス、新しい値。
- パスは snapshot と同じ順にたどる。ローカル変数は `StackFrame.setValue`、フィールドは `ObjectReference.setValue`、配列は `ArrayReference.setValue`。
- 新しい値はプリミティブ、String（`vm.mirrorOf`）、null だけにする。型が合わなければ 400。
- 実行はデバッガのマネージャスレッドで、`X-ASdbg-Allow-Eval` と同じような明示ヘッダが無ければ断る。
- 書いたあと、そのノードを読み直して返す。Android Studio の Variables 表示は次の停止まで古いままのことがある。

`eval` は対象プロセスで式を実行する。`--allow` が無いと送らない。削除できるブレークポイントは、このブリッジが作ったものだけ。

## MCP

Cursor の MCP 設定に次を足す。

```
python3 /Users/shinma.takahashi/Develop/selfregi/android-debug-bridge/mcp-adapter/server.py
```

ツール: `bp_add`、`bp_list`、`bp_update`、`bp_remove`、`bp_clear`、`attach`、`start`、`sessions`、`mute`、`control`、`threads`、`snapshot`、`stack`、`variables`、`evaluate`。

- `control` の action は CLI と同じ。`SUSPEND_THREAD`、`RESUME_THREAD`、`STEP_*` は `thread`、`RUN_TO_LINE` は `file` と `line`、`SELECT_FRAME` は `frameIndex` と `thread` を取る。`DETACH` も使える。
- `attach` は `waitReady: true` で準備を待つ。

- `snapshot` は CLI の `snapshot` と同じ。引数は `frameIndex`、`depth`、`maxChildren`、`maxString`、`maxNodes`、`thread`、`snapshotTimeout`、`toString`。
- `control` に `action: "WAIT_FOR_PAUSE"` と `snapshot: true` を渡すと、止まったときに `snapshot` が付く。読めなかったときは `snapshotError` が付く。
- `timeout` と `snapshotTimeout` はミリ秒。

公式の Python MCP SDK は使っていない。stdio の JSON-RPC だけで足りる。
