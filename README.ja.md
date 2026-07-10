# ceeker

> [English](./README.md)

tmuxペインを横断してAIコーディングエージェントのセッション・進捗をモニタリングするTUI。

複数のAIコーディングエージェント（Claude Code / Codex / pi）が並行動作する環境で、各セッションの状態を一覧表示し、tmuxペインへのジャンプを可能にする。

![ceeker screenshot](./assets/ceeker-screenshot.png)

## なぜ ceeker？

- **Windows（WSL）、Linux、macOS で動作**
- **Claude Code、Codex、pi に対応**
- **`Enter` キーひとつで対象の Claude Code / Codex / pi ペインにジャンプ**
- **複数エージェントセッションをまとめて監視**

## 前提条件

- tmux

## インストール

### ワンライナーインストール

```bash
curl -fsSL https://raw.githubusercontent.com/boxp/ceeker/main/install.sh | sh
```

対応プラットフォーム: `darwin-arm64`, `linux-amd64`, `linux-arm64`

このインストーラは GitHub Releases から対応 tarball を取得し、`checksums.txt` で検証したうえで、既定では `~/.local/bin` に `ceeker` を配置します。

配置先を変える例:

```bash
curl -fsSL https://raw.githubusercontent.com/boxp/ceeker/main/install.sh | sh -s -- -b ~/.local/bin
```

特定バージョンを入れる例:

```bash
curl -fsSL https://raw.githubusercontent.com/boxp/ceeker/main/install.sh | sh -s -- -v 0.1.0
```

未対応プラットフォームでは、以下の Homebrew または tarball 手順を使ってください。

### Homebrew（macOS / Linux）

```bash
brew tap boxp/tap
brew install ceeker
```

アップデート:

```bash
brew update
brew upgrade ceeker
```

### tarball から直接インストール

[Releases](https://github.com/boxp/ceeker/releases) からプラットフォームに合った tarball をダウンロード:

```bash
# 例: macOS ARM64
curl -L -o ceeker.tar.gz https://github.com/boxp/ceeker/releases/latest/download/ceeker-darwin-arm64.tar.gz
tar xzf ceeker.tar.gz
chmod +x ceeker-darwin-arm64
sudo mv ceeker-darwin-arm64 /usr/local/bin/ceeker
```

```bash
# 例: Linux amd64
curl -L -o ceeker.tar.gz https://github.com/boxp/ceeker/releases/latest/download/ceeker-linux-amd64.tar.gz
tar xzf ceeker.tar.gz
chmod +x ceeker-linux-amd64
sudo mv ceeker-linux-amd64 /usr/local/bin/ceeker
```

## 使い方

### TUI起動

```bash
ceeker
```

セッション一覧が表示されます。

**機能:**

- **自動反映**: `sessions.edn` のファイル変更を inotify（Linux）/ WatchService で検知し、TUIを自動更新
- **session履歴ファイルwatcher**: hookなしでも Claude Code / Codex / pi の JSONL 履歴ファイルからセッションを自動検知
- **セッション絞り込み**: エージェント種別・ステータス・テキスト検索で表示を絞り込み

**キーバインド:**

| キー | 動作 |
|------|------|
| `j` / `↓` | 下へ移動 |
| `k` / `↑` | 上へ移動 |
| `Enter` | 選択セッションのtmuxペインへジャンプ |
| `r` | 手動リフレッシュ |
| `v` | 表示切替 (Auto→Table→Card) |
| `a` | エージェント種別フィルタ切替（全て → Claude → Codex → Pi → 全て） |
| `s` | ステータスフィルタ切替（全て → running → completed → error → waiting → idle → 全て） |
| `/` | テキスト検索（session-id / cwd 部分一致） |
| `c` | フィルタ全クリア |
| `q` | 終了 |

### Session List JSON

`--list-sessions` を付けると、ceeker は TUI を起動せず現在の session list を JSON で標準出力します。LLM や外部ツール連携向けのモードで、各 session には tmux pane を特定できる `pane_id` も含まれます。

```bash
ceeker --list-sessions
```

出力例:

```json
[
  {
    "session_id": "sess-123",
    "agent_type": "codex",
    "agent_status": "running",
    "cwd": "/path/to/worktree",
    "pane_id": "%42",
    "last_message": "planning changes",
    "last_updated": "2026-04-02T12:34:56Z"
  }
]
```

出力前に ceeker は直近の session 履歴ファイルをスキャンし、その後 1 回だけ同期的に pane 生存確認と capture ベースの状態更新を行います。tmux 更新に失敗した場合でも、保存済みの session list はそのまま返します。

### ジャンプ後に自動終了

`--exit-on-jump` を指定すると、ジャンプ成功後に ceeker が自動的に終了します。ポップアップで一時的に起動し、セッション選択→ジャンプ→自動クローズする運用に便利です。

```bash
ceeker --exit-on-jump
```

### 起動時の表示モード

`--view` を使うと、起動直後のレイアウトを指定できます。指定可能な値は `auto`、`table`、`card` です。

```bash
ceeker --view table
ceeker --view card
```

## 便利な tmux 設定

tmux 内のどこからでもポップアップで ceeker を開けます。`--exit-on-jump` と組み合わせると、ペイン選択後にポップアップが自動で閉じます。

```tmux
# prefix + C-k で Claude Code / Codex / pi の状態をポップアップ表示
bind-key C-k display-popup -h 80% -w 80% -d "#{pane_current_path}" -E "ceeker --exit-on-jump"
```

## セットアップ

通常の tmux 利用では、agent 側の設定は不要です。ceeker をインストール後、
tmux 内で Claude Code / Codex / pi を起動し、次を実行するだけです:

```bash
ceeker
```

ceeker は次の session 履歴 JSONL ファイルを監視して、セッションを自動検知します:

- Claude Code: `~/.claude/projects/<cwd-slug>/<session-id>.jsonl`
- Codex: `~/.codex/sessions/YYYY/MM/DD/rollout-<timestamp>-<uuid>.jsonl`
- pi: `~/.pi/agent/sessions/<cwd-slug>/<timestamp>_<uuid>.jsonl`

watcher は Claude Code と pi の assistant entry、および Codex の
`task_complete.last_agent_message` から最新の assistant message も読み取ります。
したがって、セッション検知、状態更新、`last-message` は hook や Codex
`notify` に依存しません。

ceeker は cwd、agent process、利用可能な場合は process の `TMUX_PANE` を使って
セッションと pane を対応付けます。同じ cwd で同種 agent を複数 pane に起動し、
pane の自動解決が曖昧な場合、セッションは一覧に出てもジャンプ先が付かないことが
あります。その場合は、以下の任意設定で pane ID を明示できます。

### agent の任意設定

通常の tmux 利用では追加しないでください。用途は次の場合だけです:

- tmux 外で動くセッションを登録する
- pane の自動解決が曖昧な場合に紐付け精度を上げる

#### Claude Code（任意）

上記の用途がある場合だけ、[hooks 公式リファレンス](https://code.claude.com/docs/en/hooks)
に従い、`.claude/settings.json` に async の `SessionStart` / `Stop` だけを追加します:

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "ceeker hook claude SessionStart",
            "async": true
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "ceeker hook claude Stop",
            "async": true
          }
        ]
      }
    ]
  }
}
```

Claude Code は hook payload を stdin へ渡します。`SessionStart` でセッションを
登録し、`Stop` で終了状態と `last_assistant_message` を記録します。

#### Codex notify（任意）

同じ例外用途では、Codex
[`notify`](https://developers.openai.com/codex/config-advanced#notifications) から
turn 完了更新を ceeker へ送れます。ユーザーレベルの `~/.codex/config.toml`
に次を追加します:

```toml
notify = ["ceeker", "hook", "codex"]
```

Codex は対応する通知（現在は `agent-turn-complete`）ごとに JSON payload を
引数として1件追加します。この設定は任意で、`last-message` の取得には不要です。

[Codex lifecycle hooks](https://developers.openai.com/codex/hooks) は ceeker の
日常利用では推奨しません。Codex の command hooks は同期実行され、`async` を
指定した handler は現在スキップされます。通常の監視経路は session-file watcher
で完結するため、ceeker のためだけに Codex hooks を有効化しないでください。
既存の `ceeker hook codex ...` 設定は後方互換として引き続き受理します。hooks と
`notify` を両方有効にすると、ceeker に重複した更新が届きます。

#### pi

pi 側に ceeker 専用設定は不要です。custom extension との互換用に
`ceeker hook pi <event>` は引き続き受理しますが、自動監視には pi の session
ファイルを使います。

## セッション自動整理

tmuxペインが終了すると、対応するセッションは自動的に `Closed` 状態に遷移します。

**チェックタイミング:**

- TUI起動時に全セッションを一括チェック
- TUI表示中は約10秒ごとに定期チェック
- hookイベント受信時にもチェック実行

**仕組み:**

`tmux list-panes -a` を1回実行して全ペインのcwdとPIDを取得し、`running` 状態のセッションと照合します。以下の条件でセッションは `closed` に遷移します:

1. **ペイン不在**: セッションのcwdに一致するtmuxペインが存在しない
2. **プロセスツリー探索**: cwdが一致するペインが存在しても、そのペインのプロセスツリー内に対象エージェント（claude/codex/pi）のプロセスが見つからない場合

tmuxが利用できない場合はチェックをスキップします。

### セッション重複防止（Supersede-per-Key）

同一tmuxペインでエージェントをclose/resumeしたとき、旧セッションが `Running` のまま残って増殖する問題を防止します。

**動作:**

- hookイベント受信時、`$TMUX_PANE` 環境変数からペインIDを取得
- 新セッション登録時、同一キー `(pane-id, agent-type, cwd)` を持つ既存の `running` セッションを自動的に `closed`（superseded）に遷移
- `$TMUX_PANE` が利用できない場合（tmux外など）はsupersede判定をスキップ

**例:**

1. ペイン `%42` で Claude Code を起動 → session-A が `running` に
2. Claude Code を close → session-A はそのまま `running`（Stop hookが届かなかった場合）
3. 同じペイン `%42` で resume → session-B 登録時に session-A が自動で `closed` に

## 縦長ペイン時の表示仕様

ターミナル幅が80カラム未満の場合、自動的にコンパクトカード表示に切り替わります。

### 表示モード

| モード | 説明 |
|--------|------|
| Auto | 幅80未満でカード、80以上でテーブル（デフォルト） |
| Table | 常にテーブル表示 |
| Card | 常にカード表示 |

`v` キーで Auto → Table → Card の順に切り替え可能です。

### カード表示例

```
  ceeker — 2 session(s)
  ────────────────────────────────
  ┌ abc123 [Claude] ● Running
  │ 12:34:56  my-project
  │ Working on feature...
  └─
  ┌ xyz789 [Codex] ○ Done
  │ 12:30:00  backend
  │ Completed refactoring
  └─
  ────────────────────────────────
  [j/k] Navigate  [Enter] Jump to tmux  [r] Refresh  [v] View:Auto  [q] Quit
```

### テーブル表示例（通常幅）

```
  ceeker — 2 session(s)
  ────────────────────────────────────────────────────────────────────────────────
   SESSION      AGENT     STATUS      WORKTREE     MESSAGE                                  UPDATED
  ────────────────────────────────────────────────────────────────────────────────
   abc123       [Claude]  ● Running   my-project   Working on feature...                    12:34:56
   xyz789       [Codex]   ○ Done      backend      Completed refactoring                    12:30:00
  ────────────────────────────────────────────────────────────────────────────────
  [j/k] Navigate  [Enter] Jump to tmux  [r] Refresh  [v] View:Auto  [q] Quit
```

## 開発

```bash
# テスト
make test

# lint
make lint

# フォーマット
make format
```

ceeker repo には、Claude Code と Codex 向けの repo-local hook 設定を同梱しています。

- `.claude/settings.json`: `Write|Edit|MultiEdit|Bash` の `PostToolUse` 後に `scripts/agent-hooks/lint_format_check_hook.clj` を非同期実行
- `.codex/hooks.json`: `PostToolUse` 後に同じスクリプトを実行

この hook は babashka (`bb`) で動作し、`make format-check` と `make lint` を順に実行して結果を agent に返します。Claude Code は repo を開くだけで有効です。Codex は feature flag が必要なので、未設定なら `~/.codex/config.toml` に `[features] codex_hooks = true` を追加するか `codex features enable codex_hooks` を実行してください。

補足: Codex の `PostToolUse` は 2026-04-14 時点の公式仕様では `Bash` のみが発火対象です。そのため ceeker の Codex hook も、ファイル変更の可能性がある Bash 実行に対してのみ `format-check` / `lint` を走らせます。

## CI

GitHub Actions で以下のジョブが PR / main push 時に実行されます:

- **lint**: clj-kondo lint + cljfmt format-check
- **test**: Clojure ユニットテスト
- **native-e2e**: GraalVM native-image ビルド + E2E テスト

### native-e2e

native-image でビルドしたバイナリに対して E2E テストを実行し、JVM では再現しない native-image 固有の不具合を検出します。

テスト内容:
- `--help` 出力確認
- hook コマンド (Claude / Codex / pi) のセッション記録
- TUI 起動・終了 (`q` キー)
- TUI 検索モード (`/` → `Esc` → `q`)

TUI テストは tmux を利用してターミナルを模擬します。

## ライセンス

MIT
