# Android Studio debug bridge

Android Studio 2026.2.1 の MCP Server は `http://127.0.0.1:64342/stream` で動く。ツール一覧に `xdebug_*` は無い。Debugger MCP Toolset は IntelliJ IDEA 同梱で、この Android Studio には入っていない。

このディレクトリのプラグインは、Android Studio が持っているデバッグセッションを `127.0.0.1` の API にする。別の jdb は張らない。

## 入れる

```
cd ide-plugin
./gradlew buildPlugin
```

できた zip を Android Studio の plugins に展開し、Android Studio を再起動する。起動後に `~/.android-debug-bridge/endpoint.json` と `token` ができる。

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

`eval` は対象プロセスで式を実行する。`--allow` が無いと送らない。削除できるブレークポイントは、このブリッジが作ったものだけ。

## MCP

Cursor の MCP 設定に次を足す。

```
python3 /Users/shinma.takahashi/Develop/selfregi/android-debug-bridge/mcp-adapter/server.py
```

公式の Python MCP SDK は使っていない。stdio の JSON-RPC だけで足りる。
