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

### suspend=thread で止めたとき

`--suspend thread` のブレークポイントで止まると、Android Studio のセッションは suspended にならないことがある。

- `sessions` の `threadSuspended` が `true` なら、どれかのスレッドが止まっている。`suspendedThreads` にスレッド名と suspend policy が出る。取れないときは `null`。
- `control RESUME` は、セッションが suspended ならこれまでどおり再開する。そうでなければ止まっているスレッドを Android Studio の Resume と同じ方法で再開し、`resumed` に再開したスレッドを返す。止まっているものが無ければ 409 `session is running`。
- `control RESUME_VM` は VM ごと再開する。RESUME で戻らないときの最後の手段。Android Studio の表示が止まったままになることがある。
- `control WAIT_FOR_PAUSE` はスレッド単位の停止も `paused` で返す。そのときは `threadLevel: true` と `suspendedThreads` が付く。
- `control STOP` は止める前のセッション id を返す。
- `stack`、`vars`、`eval` は、これまでどおりセッションが suspended のときだけ使える。

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

ツール: `bp_add`、`bp_list`、`bp_remove`、`bp_clear`、`attach`、`start`、`sessions`、`control`、`snapshot`、`stack`、`variables`、`evaluate`。

- `snapshot` は CLI の `snapshot` と同じ。引数は `frameIndex`、`depth`、`maxChildren`、`maxString`、`maxNodes`、`thread`、`snapshotTimeout`、`toString`。
- `control` に `action: "WAIT_FOR_PAUSE"` と `snapshot: true` を渡すと、止まったときに `snapshot` が付く。読めなかったときは `snapshotError` が付く。
- `timeout` と `snapshotTimeout` はミリ秒。

公式の Python MCP SDK は使っていない。stdio の JSON-RPC だけで足りる。
