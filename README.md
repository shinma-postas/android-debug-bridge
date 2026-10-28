# Android Studio debug bridge（asdbg）

asdbg は、Android Studio が持っているデバッグセッションを、シェルや AI エージェントから操作するための道具です。Android Studio のプラグインが `127.0.0.1` に小さな HTTP API を開きます。CLI（`cli/asdbg.py`）と MCP サーバ（`mcp-adapter/server.py`）は、その API を呼びます。別の jdb は張りません。Android Studio の画面で見ているのと同じセッション、同じブレークポイントを扱います。

できることの例です。

- ブレークポイントの追加、変更、削除（行、メソッド、フィールド、例外）
- 動いているアプリへの attach と、Run Configuration の Debug 起動
- 停止待ち、ステップ、Run to Cursor、スレッド単位の停止と再開
- 止まった時点のスタックと変数をまとめて取る（snapshot）
- 止まっている間に値を書き換える（Set Value）
- 止まったことをファイル、HTTP、コマンドで知らせる（pause hooks）

## 目次

1. [対応する Android Studio のバージョン](#対応する-android-studio-のバージョン)
2. [前提条件](#前提条件)
3. [エージェントに任せてセットアップする](#エージェントに任せてセットアップする)
4. [手でセットアップする](#手でセットアップする)
5. [動いているか確かめる](#動いているか確かめる)
6. [主な使い方](#主な使い方)
7. [MCP](#mcp)
8. [エージェント用のスキル](#エージェント用のスキル)
9. [困ったとき](#困ったとき)
10. [セキュリティ](#セキュリティ)
11. [開発](#開発)
12. [詳しい仕様](#詳しい仕様)

## 対応する Android Studio のバージョン

セットアップを始める前に、まず Android Studio のバージョンが対応範囲に入っているかを確かめてください。範囲外の Android Studio にはプラグインが入らないか、入っても読み込まれません。

| 項目 | 内容 |
|---|---|
| ビルド番号 | `262.9437` 以降、`262.*` まで |
| リリース名 | Android Studio Rabbit（2026.2 系）。Rabbit 1（2026.2.1）以降の 2026.2.x |
| 動作を確かめた版 | Android Studio Rabbit 1 2026.2.1（`AI-262.9437.185.2621.16467767`） |
| 対応しない版 | Quail（2026.1.x、ビルド 261.*）より前と、2026.3 系（ビルド 263.*）以降 |

範囲は `ide-plugin/build.gradle.kts` の `ideaVersion` にある `sinceBuild` と `untilBuild` で決まります（`plugin.xml` の `idea-version` もビルド時にこの値になります）。Rabbit 2（2026.2.2、ビルド 262.10968 など）も範囲に入りますが、まだ動作は確かめていません。

バージョンの確かめ方は次のどちらかです。

- Android Studio の画面で見る。macOS は Android Studio > About Android Studio、Linux と Windows は Help > About。`Build #AI-262.9437.185...` の `AI-` の後ろがビルド番号です。
- `product-info.json` の `buildNumber` を見る。Android Studio を起動していなくても読めます。

  ```bash
  # macOS
  grep '"buildNumber"' "/Applications/Android Studio.app/Contents/Resources/product-info.json"
  # Linux（Android Studio を置いたディレクトリ）
  grep '"buildNumber"' /opt/android-studio/product-info.json
  ```

  ```powershell
  # Windows
  Select-String '"buildNumber"' "C:\Program Files\Android\Android Studio\product-info.json"
  ```

`buildNumber` が `262.` で始まり、2 つ目の数字が `9437` 以上なら対応しています。`bash scripts/setup.sh check` も最初にこの確認を行い、範囲外ならほかを調べずに止まります。

対応範囲を変えるときは、`build.gradle.kts` の `sinceBuild` と `untilBuild`、この節の表、下の「エージェントに任せてセットアップする」のプロンプトの手順 1 をそろえて直してください。

## 前提条件

| 項目 | 内容 |
|---|---|
| OS | macOS または Linux。`scripts/setup.sh` はこの 2 つで動きます。Windows は「[Windows で入れる](#windows-で入れる)」の手順を使います。 |
| Android Studio | Rabbit（2026.2 系、ビルド番号 262.9437 以降、262.* まで）。詳しくは「[対応する Android Studio のバージョン](#対応する-android-studio-のバージョン)」を見てください。 |
| JDK | ビルドに JDK 21 以上が要ります。Android Studio に入っている JBR を使うので、別に入れる必要はありません。 |
| Python | 3.8 以上。CLI と MCP サーバは標準ライブラリだけで動きます。pip は使いません。 |
| コマンド | `git`、`python3`、`unzip`。`adb` は `attach-wait` と `restoration-debug` を使うときだけ要ります。`attach` そのものは Android Studio の adb を使います。 |
| ネットワーク | 初回のビルドで Gradle が Maven Central と JetBrains のリポジトリから依存を取ります。 |
| リポジトリ | このリポジトリを clone できる権限が要ります。 |

Android Studio が標準の場所に無いときは、環境変数 `ANDROID_STUDIO_PATH` に場所を入れます。macOS では `.app` のパス、Linux では `bin` と `lib` があるディレクトリです。

## エージェントに任せてセットアップする

次のコードブロックを、そのまま Cursor や Claude Code などのエージェントに貼ってください。エージェントは最初に Android Studio のバージョンを確かめ、範囲外ならそこで止まって知らせます。途中で 1 回だけ、Android Studio の再起動を頼んできます。最後に `SETUP COMPLETE` と出れば終わりです。

```text
asdbg（Android Studio debug bridge）をこのマシンにセットアップしてください。最後の確認が通るまで、次の手順を順に進めてください。

守ること:
- ~/.android-debug-bridge/token の中身は読まない、表示しない、ログやチャットに書かない。確認は scripts/setup.sh verify に任せる。
- 既存のプラグイン、MCP 設定、スキル、~/.local/bin/asdbg は上書きしない。scripts/setup.sh が ~/.android-debug-bridge/backups/ に退避してから入れ替えるので、手で消したり上書きしたりしない。
- 端末（adb）、デバッグセッション、ブレークポイントには触らない。
- Android Studio の終了や起動は自分でしない。人間に頼む。
- コマンドが失敗したら、そこで止まって出力をそのまま人間に見せ、どうするか聞く。sudo は使わない。

手順:
1. ほかの作業より先に、Android Studio のバージョンが対応範囲に入っているかを確かめる。対応範囲はビルド番号 262.9437 以降、262.* まで（Android Studio Rabbit、2026.2.1 以降の 2026.2.x）。
   - product-info.json の buildNumber を読む。Android Studio の起動や終了はしない。
     - macOS: `grep '"buildNumber"' "/Applications/Android Studio.app/Contents/Resources/product-info.json"`（無ければ "$HOME/Applications/Android Studio.app" も見る）
     - Linux: Android Studio を置いたディレクトリの product-info.json（/opt/android-studio、/usr/local/android-studio、$HOME/android-studio、/snap/android-studio/current/android-studio など）
     - Windows: `Select-String '"buildNumber"' "C:\Program Files\Android\Android Studio\product-info.json"`
   - 見つからなければ、人間に Android Studio の場所を聞くか、About 画面（macOS は Android Studio > About Android Studio、それ以外は Help > About）の `Build #AI-...` を教えてもらう。
   - buildNumber が `262.` で始まり、2 つ目の数字が 9437 以上なら次へ進む。
   - それ以外なら、ここで止まる。clone、ビルド、インストールはしない。見つかったビルド番号と対応範囲を人間に伝え、対応する Android Studio を入れるかどうかを聞く。
2. OS を `uname -s` で確かめる。Darwin か Linux なら次へ進む。それ以外（Windows など）なら、README の「Windows で入れる」の手順を人間と一緒に進め、この手順はそこで終える。
3. リポジトリを用意する。人間に置き場所の希望を聞き、無ければ "$HOME/android-debug-bridge" にする。この場所を ASDBG_DIR とする。
   - ASDBG_DIR に既に clone があれば `git -C "$ASDBG_DIR" status --short` が空であることを確かめてから `git -C "$ASDBG_DIR" pull --ff-only` する。空でなければ止まって人間に聞く。
   - 無ければ `git clone https://github.com/shinma-postas/android-debug-bridge.git "$ASDBG_DIR"` する。権限が無くて失敗したら、人間にアクセス権を頼む。
   - 以降のコマンドはすべて `cd "$ASDBG_DIR"` してから実行する。
4. `bash scripts/setup.sh check` を実行する。Android Studio の場所とビルド番号を最初に調べ、続けて JBR、python3、plugins フォルダを調べる。
   - Android Studio が見つからないと言われたら、人間に場所を聞いて `bash scripts/setup.sh check --studio "<場所>"` で再実行する。以降のコマンドにも同じ `--studio` を付ける。
   - バージョンが範囲外と言われたら止まって人間に伝える（手順 1 で見た Android Studio と別のものを見ていないかも確かめる）。
5. `bash scripts/setup.sh build` を実行する。プラグインのビルドとテストを行う。数分かかることがある。
6. `bash scripts/setup.sh install` を実行する。古い asdbg プラグインがあれば退避してから入れる。退避先のパスを控える。
7. 人間に次のとおり伝えて、返事が来るまで待つ。先へ進まない。
   「Android Studio を完全に終了してから起動し直し、デバッグしたいプロジェクトを開いてください。終わったら『再起動した』と返事してください。」
8. `bash scripts/setup.sh cli` を実行する。~/.local/bin/asdbg ができる。PATH に無いと警告が出たら、その旨を最後の報告に書く（シェルの設定ファイルは勝手に直さない）。
9. 人間に 2 つ聞く。どちらも分からなければ空のままでよい。
   - Android Studio で開いているプロジェクトのルートの絶対パス（PROJECT）
   - デバッグするアプリの applicationId（PACKAGE。例 com.example.app）
10. MCP を登録する。自分が動いているクライアントに合わせて選ぶ。両方使うなら両方実行する。空の値の引数は付けない。
   - Cursor: `bash scripts/setup.sh mcp --cursor --project "<PROJECT>" --package "<PACKAGE>"`
   - Claude Code: `bash scripts/setup.sh mcp --claude --project "<PROJECT>" --package "<PACKAGE>"`
   - Cursor の場合は、人間に「Cursor の設定の MCP で android-studio-debug を再読み込みしてください」と頼む。
11. スキルを置く。
   - Cursor: `bash scripts/setup.sh skills --dest "$HOME/.cursor/skills"`
   - Claude Code: `bash scripts/setup.sh skills --dest "$HOME/.claude/skills"`
12. `bash scripts/setup.sh verify` を実行する。
   - 最後の行が `SETUP COMPLETE` なら完了。
   - `plugin version` の確認で失敗したら、Android Studio が古いプラグインのまま動いている。手順 7 に戻って再起動を頼み、もう一度 verify する。
   - `endpoint.json` や `GET /health` で失敗したら、Android Studio が起動しているか、プロジェクトを開いているかを人間に確かめてもらい、もう一度 verify する。
13. 人間に短く報告する。入れたプラグインのバージョン、退避したもののパス、MCP とスキルの登録先、PATH の警告の有無、verify の結果。token の値は書かない。
```

`scripts/setup.sh` は何度実行しても安全です。同じものが入っていれば何も変えません。違うものがあれば `~/.android-debug-bridge/backups/<名前>-<日時>` に移してから入れ替えます。

## 手でセットアップする

### macOS と Linux

リポジトリのルートで順に実行します。

```bash
bash scripts/setup.sh check      # Android Studio のバージョンを最初に確かめ、続けて前提条件を確かめる
bash scripts/setup.sh build      # ビルドとテスト
bash scripts/setup.sh install    # plugins に入れる（既存は退避）
# ここで Android Studio を完全に終了して起動し直し、プロジェクトを開く
bash scripts/setup.sh cli        # ~/.local/bin/asdbg を作る
bash scripts/setup.sh mcp --cursor --project /path/to/project --package com.example.app
bash scripts/setup.sh skills --dest ~/.cursor/skills
bash scripts/setup.sh verify     # SETUP COMPLETE と出れば終わり
```

スクリプトを使わない場合の中身は次のとおりです。

1. ビルドします。`JAVA_HOME` には Android Studio の JBR を入れます。macOS なら `"/Applications/Android Studio.app/Contents/jbr/Contents/Home"`、Linux なら `<Android Studio>/jbr` です。

   ```bash
   cd ide-plugin
   JAVA_HOME="<JBR>" ./gradlew buildPlugin test
   ```

   `ide-plugin/build/distributions/android-debug-bridge-<version>.zip` ができます。Android Studio の場所が標準でなければ、`-PandroidStudioPath=<場所>` を付けるか、`ANDROID_STUDIO_PATH` を入れます。

2. zip を plugins フォルダに展開します。前のバージョンの `android-debug-bridge` フォルダがあれば、先に plugins の外へ移します。plugins フォルダの名前は Android Studio の `product-info.json` の `dataDirectoryName` で決まります。

   | OS | plugins フォルダ |
   |---|---|
   | macOS | `~/Library/Application Support/Google/<dataDirectoryName>/plugins` |
   | Linux | `~/.local/share/Google/<dataDirectoryName>` |
   | Windows | `%APPDATA%\Google\<dataDirectoryName>\plugins` |

   Android Studio の Settings > Plugins > 歯車 > Install Plugin from Disk で zip を選んでも同じです。

3. Android Studio を再起動します。起動すると `~/.android-debug-bridge/endpoint.json` と `token` ができます。

4. CLI はそのまま `python3 cli/asdbg.py` で動きます。短く `asdbg` で呼びたいときは `ln -s "$PWD/cli/asdbg.py" ~/.local/bin/asdbg` します。

5. MCP とスキルは「[MCP](#mcp)」と「[エージェント用のスキル](#エージェント用のスキル)」を見てください。

### Windows で入れる

`scripts/setup.sh` は Windows では動きません。PowerShell で次を行います。

1. ビルドします。

   ```powershell
   cd ide-plugin
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   .\gradlew.bat buildPlugin test
   ```

   Android Studio が別の場所なら `$env:ANDROID_STUDIO_PATH` に入れます。

2. Android Studio の Settings > Plugins > 歯車 > Install Plugin from Disk で `ide-plugin\build\distributions\android-debug-bridge-<version>.zip` を選び、再起動します。
3. CLI は `python cli\asdbg.py doctor` で確かめます。MCP の設定では `command` を `python`、`args` を `server.py` の絶対パスにします。

## 動いているか確かめる

Android Studio を起動してプロジェクトを開いてから、次を実行します。

```bash
asdbg doctor                     # または python3 cli/asdbg.py doctor
bash scripts/setup.sh verify     # self-check、MCP の応答、doctor をまとめて行う
```

`asdbg doctor` は読み取りだけを行います。ブレークポイントやセッションには触りません。token の値も表示しません。次を確かめます。

- `endpoint.json` があり、待ち受けが `127.0.0.1` であること
- token ファイルがあり、権限が 600 であること
- `GET /health` が答え、動いているプラグインのバージョン（`pluginVersion`）
- token が受け付けられること

すべて通ると `"ok": true` を返し、終了コードは 0 です。

Android Studio の `idea.log`（Help > Show Log）に `asdbg listening` の行があれば、プラグインは起動しています。

## 主な使い方

CLI は、Android Studio で開いているプロジェクトのルートを `ANDROID_STUDIO_PROJECT` で受け取ります。無ければカレントディレクトリを使います。attach するアプリは `--package` か `ASDBG_PACKAGE` で渡します。決まった既定値はありません。

```bash
export ANDROID_STUDIO_PROJECT="/path/to/project"
export ASDBG_PACKAGE="com.example.app"

asdbg bp-add app/src/main/java/com/example/app/MainActivity.kt 42
asdbg bp-list
asdbg attach --serial <端末のシリアル> --timeout 60000   # 動いているアプリに付ける
asdbg control WAIT_FOR_PAUSE --timeout 60000 --snapshot  # 止まるまで待つ
asdbg stack
asdbg vars
asdbg step over
asdbg control RESUME
asdbg detach
```

- 行番号は 1 から数えます。ファイルはプロジェクトルートからの相対パスです。
- `--timeout` はすべてミリ秒です。30 秒なら `30000` です。
- `start <configuration>` は Run Configuration を Debug で起動します。多くの場合アプリを入れ直すので、動いているアプリには `attach` を使います。
- サブコマンドの一覧は `asdbg tools`、各コマンドの引数は `asdbg <command> -h` で見られます。
- アプリを kill して起動し直す試験では、`attach-wait --serial <serial> --after-kill` で古いセッションを止めてから付け直せます。2 段階で止める位置を変えたいときは `restoration-debug --serial <serial> --file <file> --bp1-line <n> --bp2-line <m> --once` を使います。

### 止まったことを知らせる（pause hooks）

止まるたびに、止まった場所（file、line、class、method、thread など）を JSON で知らせます。変数の値は入れません。初期状態では止めてあります。

```bash
asdbg webhook enable                       # イベントファイルに書き始める
asdbg watch --once --timeout 600000        # 次に止まったら 1 行出して終わる
asdbg webhook set --command "/usr/bin/say paused"   # 止まるたびにコマンドを実行する
asdbg webhook set --url http://127.0.0.1:8787/asdbg --header-env Authorization=MY_HOOK_TOKEN
asdbg webhook test                         # 設定した全部の経路へ見本を送る
asdbg webhook show
```

設定は `~/.android-debug-bridge/webhook.json` に保存されます。キーの意味は「[止まったことを知らせる（pause hooks）](#止まったことを知らせるpause-hooks)」の詳しい仕様にあります。

### 値を書き換える（set-value）

止まっている間に、ローカル変数やフィールドの値を書き換えられます。

```bash
asdbg set response.result.code --int 200
asdbg set this.state --enum DONE --resume
asdbg resume-with --json '[{"path":"a.code","value":{"int":"200"}},{"path":"a.ok","value":{"boolean":true}}]'
```

これはアプリの動きを本当に変えます。「[セキュリティ](#セキュリティ)」も読んでください。

## MCP

MCP サーバは `mcp-adapter/server.py` です。stdio の JSON-RPC で動き、Python の標準ライブラリだけを使います。サーバ名は `android-studio-debug` にします。

Cursor では `~/.cursor/mcp.json` に次を足します。`bash scripts/setup.sh mcp --cursor` でも同じものを書きます（既存のファイルは退避します）。

```json
{
  "mcpServers": {
    "android-studio-debug": {
      "command": "python3",
      "args": ["/absolute/path/to/android-debug-bridge/mcp-adapter/server.py"],
      "env": {
        "ANDROID_STUDIO_PROJECT": "/path/to/project",
        "ASDBG_PACKAGE": "com.example.app"
      }
    }
  }
}
```

Cursor ではこのサーバが `user-android-studio-debug` という名前で見えます。

Claude Code では次を実行します。`bash scripts/setup.sh mcp --claude` でも同じです。

```bash
claude mcp add --scope user -e ANDROID_STUDIO_PROJECT=/path/to/project -e ASDBG_PACKAGE=com.example.app \
  android-studio-debug -- python3 /absolute/path/to/android-debug-bridge/mcp-adapter/server.py
```

`env` はどちらも省けます。省いたときは、ツールの `projectPath` と `package` 引数で毎回渡します。

ツール: `bp_add`、`bp_list`、`bp_update`、`bp_remove`、`bp_clear`、`attach`、`start`、`sessions`、`mute`、`control`、`threads`、`snapshot`、`stack`、`variables`、`evaluate`、`set_value`、`webhook_get`、`webhook_set`、`webhook_test`、`wait_pause_event`。

- `control` の action は CLI と同じです。`SUSPEND_THREAD`、`RESUME_THREAD`、`STEP_*` は `thread`、`RUN_TO_LINE` は `file` と `line`、`SELECT_FRAME` は `frameIndex` と `thread` を取ります。`DETACH` も使えます。
- `control` に `action: "WAIT_FOR_PAUSE"` と `snapshot: true` を渡すと、止まったときに snapshot が付きます。読めなかったときは `snapshotError` が付きます。
- `snapshot` の引数は `frameIndex`、`depth`、`maxChildren`、`maxString`、`maxNodes`、`thread`、`snapshotTimeout`、`toString` です。
- `attach` は `serial` が必須です。`package` が無ければサーバの `ASDBG_PACKAGE` を使い、それも無ければエラーを返します。`waitReady: true` で Android Studio の準備を待ちます。
- `webhook_get`、`webhook_set`、`webhook_test` は CLI の `webhook show`、`set`、`test` と同じです。`webhook_set` は `webhook.json` と同じキーを取ります。
- `wait_pause_event` は `pause-events.jsonl` に次の行が来るまで待って返します。`timeout` はミリ秒（既定 60000、最大 600000）です。
- `timeout` と `snapshotTimeout` はミリ秒です。

## エージェント用のスキル

`skills/` に、エージェント向けの手順書を 2 つ置いています。[agentskills.io](https://agentskills.io) の形式です。

| スキル | 使う場面 |
|---|---|
| `skills/android-studio-debug/SKILL.md` | シェルから `asdbg` CLI でデバッグするとき |
| `skills/android-studio-debug-mcp/SKILL.md` | MCP の `android-studio-debug` ツールでデバッグするとき |

`bash scripts/setup.sh skills --dest <スキルのフォルダ>` でコピーします。Cursor なら `~/.cursor/skills`、Claude Code なら `~/.claude/skills` です。同じ名前のスキルが既にあれば退避してから置きます。

## 困ったとき

### ブレークポイントで止まらない、または止まったあと固まる

Android Studio の Settings > Build, Execution, Deployment > Debugger > Data Views > Java で、次の 2 つを外すと直ることがあります。

- Enable alternative view for Collections classes
- Enable 'toString()' object view

どちらも、止まったときに Android Studio がアプリの中でメソッドを呼びます。アプリによっては、その呼び出しが止まったスレッドのロックを待って戻らなくなります。

あわせて、ブレークポイントの Suspend を Thread にすると、ほかのスレッドが動き続けるので固まりにくくなります。`asdbg bp-add <file> <line> --suspend thread` か、既存のものなら `asdbg bp-update <id> --suspend thread` です。Thread で止めたときは「[suspend=thread で止めたとき](#suspendthread-で止めたとき)」を読んでください。

### そのほか

| 症状 | 確かめること |
|---|---|
| `bridge endpoint is missing` | プラグインを入れたあと Android Studio を再起動したか。`asdbg doctor` で確かめる。 |
| `plugin version` が合わない | Android Studio が古いプラグインのまま動いている。完全に終了して起動し直す。 |
| `set X-Project-Path` で 400 | `ANDROID_STUDIO_PROJECT`（MCP なら `projectPath`）を、Android Studio で開いているプロジェクトのルートにする。 |
| `package is required` | `--package` か `ASDBG_PACKAGE`（MCP なら `package`）を渡す。 |
| attach で 404 | アプリのプロセスが動いていない。`--wait-for-process` を付けるか、アプリを起動してから attach する。 |
| ブレークポイントが `pending` のまま | そのコードをまだ通っていない。インデックス中なら `attach --wait-ready` を使う。 |
| `session is running` | 止まっていない。`control WAIT_FOR_PAUSE` で止まるのを待つ。 |
| Gradle が Android Studio を見つけない | `-PandroidStudioPath=<場所>` か `ANDROID_STUDIO_PATH` を入れる。 |
| Gradle が Java のバージョンで失敗する | `JAVA_HOME` を Android Studio の JBR にする。 |
| Cursor で MCP が `error` のまま | `mcp.json` の `server.py` のパスが絶対パスか確かめ、MCP を再読み込みする。 |

## セキュリティ

- ブリッジは `127.0.0.1` だけで待ち受けます。ほかのマシンからはつながりません。
- `/health` 以外の API には token が要ります。token は Android Studio が初回起動時に 32 バイトの乱数から作り、`~/.android-debug-bridge/token` に権限 600 で保存します。マシンごとに別の値になります。token をチャット、イシュー、リポジトリに書かないでください。
- `endpoint.json` には URL と token ファイルのパスだけが入ります。token の値は入りません。
- `eval` と `snapshot --to-string` はアプリの中でコードを動かします。CLI は `--allow` を付けたときだけ送ります。
- `set`、`resume-with`、`set_value` はアプリの状態を本当に書き換えます。お金、外部システム、端末の設定が絡む処理では、試験の意図した範囲だけに使ってください。本番の端末や本番のデータでは使わないでください。
- snapshot、変数、書き換えの結果には個人情報や識別子が入ることがあります。名前が password、secret、token、apiKey、credential に当たる値は `***` にしますが、それ以外は伏せません。外に貼るときは、カード番号、token、端末 ID、シリアルを末尾 4 桁まで伏せてください。
- pause hooks の URL やヘッダに秘密を書くときは `headersFromEnv` を使い、値は環境変数に置きます。`webhook show` ではヘッダの値と URL のパスが `***` になります。
- 社内の接続先、パスワード、端末の情報はこのリポジトリに書きません。必要なものは各チームの Wiki を参照してください。

## 開発

```bash
cd ide-plugin
JAVA_HOME="<Android Studio の JBR>" ./gradlew buildPlugin test
python3 ../cli/asdbg.py --self-check
python3 ../mcp-adapter/server.py --self-check
```

| パス | 中身 |
|---|---|
| `ide-plugin/` | Android Studio のプラグイン（Kotlin） |
| `cli/asdbg.py` | CLI |
| `mcp-adapter/server.py` | MCP サーバ |
| `scripts/setup.sh` | セットアップ用スクリプト |
| `skills/` | エージェント用のスキル |

バージョンは `ide-plugin/build.gradle.kts` の `version`、`PauseHooks.kt` の `ASDBG_VERSION`、`server.py` の `serverInfo.version` の 3 か所にあります。上げるときはそろえてください。

Android Studio 2026.2 に同梱の MCP Server には、デバッガを操作するツール（IntelliJ IDEA の `xdebug_*`）がありません。asdbg はその穴を埋めるために作りました。

## 詳しい仕様

### ブレークポイントが効いているか

`bp-list` と `bp-add` の各ブレークポイントに `verified` が付く。

- `verified`: 動いているセッションでクラスに入った。Android Studio の赤丸にチェックが付いた状態。
- `pending`: セッションはあるが、まだクラスが読み込まれていない。そのコードを通る前はこうなる。
- `invalid`: 置けなかった。`verifyError` に Android Studio の理由が入る（行にコードが無いなど）。
- `unknown`: デバッグセッションが無い、または状態が読めなかった。

`verifySession` は判定に使ったセッション名。`sessions` にはセッションごとの `muted` と、プロジェクトがインデックス中かを示す `projectIndexing` が出る。

### ミュート

```
asdbg mute on
asdbg mute off --session <id>
```

Debug ウィンドウの Mute Breakpoints と同じ。ミュートはセッションごとなので、`--session` が無ければ全セッションに掛ける。ミュート中はブレークポイントが `verified` でも止まらない。

### attach の前に Android Studio の準備を待つ

Android Studio の起動直後はプロジェクトが読み込み途中だったり、インデックス中（dumb mode）だったりする。この間に attach すると、ブレークポイントが効かないことがある。

- `attach` は、プロジェクトが読み込み途中かインデックス中なら `warnings` を返す。CLI は標準エラーに出す。
- `attach --wait-ready` はインデックスが終わるまで待ってから attach する。待てるのは `--timeout` から 1000 ms 引いた分まで。待った時間は `waitedReadyMs` に出る。

### 操作の一覧

```
asdbg threads --frames 5
asdbg thread-suspend "Binder:1234_2"
asdbg thread-resume "Binder:1234_2"
asdbg step over --thread main
asdbg run-to app/src/main/java/com/example/Main.kt 60
asdbg frame 2 --thread main
asdbg bp-update <id> --condition "count > 3" --pass-count 2 --suspend thread
asdbg bp-update <id> --log-expression "amount" --suspend none
asdbg bp-disable <id>
asdbg detach
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
| 値の書き換え（Set Value） | 0.1.6 で追加。止まっている間に値を書く | `set`、`resume-with`、`control SET_VALUE` |
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
asdbg snapshot
asdbg snapshot --frame 1 --depth 3
asdbg snapshot --thread main --max-children 20 --max-string 200
asdbg control WAIT_FOR_PAUSE --timeout 60000 --snapshot
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

snapshot には個人情報、金額、端末の識別子などがそのまま入ることがある。名前が password、secret、token、apiKey、credential に当たる値は `***` にするが、それ以外は伏せない。

- ブリッジは `127.0.0.1` だけで待ち受け、token が無いと答えない。
- プラグインと CLI は値を外へ送らない。
- 結果をチャット、イシュー、外部のサービスに貼るときは、カード番号、認証トークン、端末 ID、シリアルを伏せる（末尾 4 桁まで）。

### 値の書き換え（Set Value）

止まっている間にフレームの値を書き換えられる。プリミティブ、String、null、enum 定数はメソッドを呼ばないので、`suspend=thread` の停止でも使える。`eval` と違って式は実行しないので `--allow` ヘッダは要らない。

対象:

- フレームのローカル変数（例 `amount`）
- `this` のフィールド（例 `this.payment.amount`）
- フィールドや配列要素をたどったパス（例 `response.result.code`、`arr[2]`、`grid[0][1].cell`）
- 完全修飾の静的フィールド（例 `com.example.Foo.FIELD`）

値（1 つだけ指定する）:

- プリミティブ `--int` / `--long` / `--short` / `--byte` / `--double` / `--float` / `--boolean` / `--char`（型チェックつき）
- `--string`（`vm.mirrorOf` でミラーを作る）
- `--null`（オブジェクト参照のフィールドだけ）
- `--enum PAID`（宣言型の enum 定数を名前で解決）
- 既存オブジェクトを id で代入するのは未対応（`--object-id` は予約。400 を返す）

使い方:

```
# 1 つ書く
asdbg set response.result.code --int 200

# 書いてそのまま再開する
asdbg set this.payment.amount --long 0 --resume

# 別スレッド・別フレームを指定
asdbg set flag --boolean true --thread main --frame 1

# 複数まとめて書いてから再開する
asdbg resume-with --json '[{"path":"a.code","value":{"int":"200"}},{"path":"a.paid","value":{"boolean":true}}]'
```

`control SET_VALUE`（および MCP の `set_value`）も同じことをする。body は 1 件なら `path` と `value`、複数なら `sets=[{path,value}]`、どちらも `resume=true` で設定後に再開できる。`frameIndex` は省略すると現在のフレーム（0）。

動き:

- ローカル変数は `StackFrame.setValue`、フィールドは `ObjectReference.setValue`、配列は `ArrayReference.setValue`、静的は `ClassType.setValue`。すべてデバッガのマネージャスレッドで実行する。
- 書いたあとに読み直して、古い値と新しい値を返す。名前が secret 系なら `***` にする。
- Android Studio の Variables 表示は次の停止まで古いままのことがある。

エラー（4xx）:

- 型が合わない → 400
- 配列の範囲外 → 400
- 見つからないパス（最適化で消えた・スコープ外・未定義のフィールド）→ 404 / 409
- スレッドが止まっていない、指定したフレームが無い → 409
- `final` フィールド → JDI が書けてしまうことがあるので止めず、`warning` を付けて返す

リスク:

- これは動いているアプリの状態を書き換える。`response.result.code` のような値を変えると、後続の処理が実機で本当に分岐する。お金や外部システムが絡む処理では特に、試験の意図した範囲だけに使う。
- 書き込む値と返る値には個人情報や金額が入りうる。外に貼らない。

`eval` は対象プロセスで式を実行する。`--allow` が無いと送らない。削除できるブレークポイントは、このブリッジが作ったものだけ。

### 止まったことを知らせる（pause hooks）

デバッガが止まるたびに、止まった場所を外へ知らせる。ブレークポイント、ステップの終わり、例外・メソッド・フィールドのブレークポイント、手動の Pause のどれでも動く。`suspend=thread` の停止も拾う。初期状態では止めてある。

知らせ方は 3 つあり、どれも同じ JSON を使う。

1. イベントファイル: `~/.android-debug-bridge/pause-events.jsonl` に 1 行ずつ足す。1 MB（`eventsMaxBytes`）を超えると `pause-events.jsonl.1` へ回す。権限は 600。
2. HTTP POST: `url` に JSON を POST する。別スレッドで送り、タイムアウトは 3 秒（`timeoutMs`）。失敗はログに残すだけで再送しない。リダイレクトは追わない。
3. コマンド: `onPauseCommand` に書いた argv を実行する。標準入力に JSON、環境変数に `ASDBG_EVENT`、`ASDBG_KIND`、`ASDBG_FILE`、`ASDBG_LINE`、`ASDBG_THREAD`、`ASDBG_SESSION`、`ASDBG_TIMESTAMP` を入れる。シェルは通さない。`commandTimeoutMs`（初期 10 秒）を過ぎたら止める。出力は `~/.android-debug-bridge/on-pause.log` に出る。

JSON の例:

```
{"event":"paused","kind":"breakpoint","source":"jdi","sessionId":"a1b2c3","sessionName":"app","project":"my-app","file":"app/src/main/java/com/example/MainActivity.kt","line":42,"class":"com.example.MainActivity","method":"onCreate","thread":"main","threadLevel":true,"policy":"thread","contextPolicy":"thread","breakpointPolicy":"thread","breakpointId":"5f0c...","timestamp":"2026-10-26T16:40:00.123+09:00","pluginVersion":"0.1.9","seq":1}
```

- `kind` は `breakpoint`、`step`、`exception`、`method`、`field`、`pause`、`run_to_line`、`other` のどれか。
- `source` は `jdi`（Java のデバッガから直接）か `session`（それ以外の停止。手動 Pause など）。同じ停止は 1 回だけ送る。
- `line` は 1 始まり。`file` はプロジェクトからの相対パス。
- `threadLevel` は `suspend=thread` の停止なら `true`。
- 変数の値、eval の結果、ログの本文、ブリッジの token は入れない。

設定ファイル `~/.android-debug-bridge/webhook.json`（権限 600）:

```
{
  "enabled": true,
  "url": "http://127.0.0.1:8787/asdbg",
  "headers": {},
  "headersFromEnv": {"Authorization": "ASDBG_HOOK_AUTH"},
  "debounceMs": 0,
  "eventsFile": true,
  "eventsMaxBytes": 1000000,
  "onPauseCommand": ["/usr/bin/say", "止まりました"],
  "commandTimeoutMs": 10000,
  "timeoutMs": 3000
}
```

- `enabled` が `false` なら何もしない。止まるたびの負荷もない。
- `url` は http か https だけ。空にすると POST しない。
- `headersFromEnv` はヘッダの値を Android Studio の環境変数から読む。Dock から起動した Android Studio にはシェルの環境変数が入らないことがある。
- `debounceMs` を入れると、同じセッション・スレッド・場所の停止がその時間内に続いたとき 2 回目以降を送らない。
- ヘッダの値と URL のパスは `webhook show` で `***` になる。ログにも出さない。
- 設定はブリッジ経由で変えると即時に効く。ファイルを手で直したときは Android Studio の再起動で読み直す。

CLI:

```
asdbg webhook show
asdbg webhook enable
asdbg webhook disable
asdbg webhook set --url http://127.0.0.1:8787/asdbg --header-env Authorization=ASDBG_HOOK_AUTH
asdbg webhook set --command "/usr/bin/say 止まりました" --command-timeout 5000
asdbg webhook set --events-file off --debounce 500
asdbg webhook set --clear-url --clear-command
asdbg webhook test
```

- `--header 'Name: value'` と `--header-env Name=ENV` は、渡したもので全部を置き換える。
- `--command` はシェルの書き方で分けて argv にするだけで、シェルでは実行しない。
- `webhook test` は止めていなくても、設定した全部の経路へ見本の JSON を送る（`enabled` が `false` でも送る）。

次の停止を待つ（`watch`）:

```
# 次に止まったら JSON を 1 行出して終わる
asdbg watch --once --timeout 600000

# 止まるたびにコマンドを実行する（標準入力に JSON、ASDBG_* の環境変数つき）
asdbg watch --exec "python3 next_step.py"

# イベントファイルを使わず、ブリッジの WAIT_FOR_PAUSE で待つ
asdbg watch --once --bridge --timeout 60000
```

- `watch` は `pause-events.jsonl` の今の末尾から読む。ファイルが回っても追う。
- 時間内に何も来なければ `{"event": "timeout"}` を出して終了コード 2 で終わる。
- hooks が止まっている、または `eventsFile` が `off` のときは警告を出す。
- `ASDBG_HOME` を指定すると、CLI と MCP はそのディレクトリを `~/.android-debug-bridge` の代わりに読む（試験用）。

エージェントから使うときは、`watch --once` か MCP の `wait_pause_event` で待ち、返ってきた `file` と `line` を見て次の操作（`snapshot`、`set` など）をする。

