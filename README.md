# ceeker

AI Coding Agent セッション・進捗モニタリングTUI。

複数のAIコーディングエージェント（Claude Code / Codex）が並行動作する環境で、各セッションの状態を一覧表示し、tmuxペインへのジャンプを可能にする。

## 前提条件

- tmux

## インストール

### Homebrew（macOS / Linux）

```bash
brew tap boxp/tap
brew install ceeker
```

アップデート:

```bash
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
- **セッション絞り込み**: エージェント種別・ステータス・テキスト検索で表示を絞り込み

**キーバインド:**

| キー | 動作 |
|------|------|
| `j` / `↓` | 下へ移動 |
| `k` / `↑` | 上へ移動 |
| `Enter` | 選択セッションのtmuxペインへジャンプ |
| `r` | 手動リフレッシュ |
| `v` | 表示切替 (Auto→Table→Card) |
| `a` | エージェント種別フィルタ切替（全て → Claude → Codex → 全て） |
| `s` | ステータスフィルタ切替（全て → running → completed → error → waiting → idle → 全て） |
| `/` | テキスト検索（session-id / cwd 部分一致） |
| `c` | フィルタ全クリア |
| `q` | 終了 |

### Hook CLI

```bash
# Claude Code hookイベントを処理（stdin で JSON payload を受け取る）
ceeker hook claude Notification <<< '{"session_id":"abc","cwd":"/tmp","hook_event_name":"Notification","title":"Working..."}'

# Codex notify hookイベントを処理（Codex が JSON を最後の引数として渡す）
ceeker hook codex '{"type":"agent-turn-complete","thread-id":"xyz","cwd":"/tmp","last-assistant-message":"Done."}'
# レガシー形式も引き続きサポート
ceeker hook codex notification '{"session_id":"xyz","message":"Testing..."}'
```

## Hook設定

### Claude Code

`.claude/settings.json` に以下を追加（[公式仕様](https://docs.anthropic.com/en/docs/claude-code/hooks)準拠の3レベルネスト形式）:

```json
{
  "hooks": {
    "SessionStart": [
      { "hooks": [{ "type": "command", "command": "ceeker hook claude SessionStart" }] }
    ],
    "Notification": [
      { "hooks": [{ "type": "command", "command": "ceeker hook claude Notification" }] }
    ],
    "PreToolUse": [
      { "hooks": [{ "type": "command", "command": "ceeker hook claude PreToolUse" }] }
    ],
    "PostToolUse": [
      { "hooks": [{ "type": "command", "command": "ceeker hook claude PostToolUse" }] }
    ],
    "Stop": [
      { "hooks": [{ "type": "command", "command": "ceeker hook claude Stop" }] }
    ]
  }
}
```

Claude Code は hook コマンドの stdin に JSON payload を渡します。payload には `session_id`, `cwd`, `hook_event_name` 等の共通フィールドが含まれます（[Hooks reference](https://docs.anthropic.com/en/docs/claude-code/hooks) 参照）。

### Codex

`~/.codex/config.toml` に以下を追加:

```toml
notify = ["ceeker", "hook", "codex"]
```

Codex は `notify` コマンドの最後の引数として JSON ペイロードを追加します（stdin ではなく argv 経由）。

## セッション自動整理

tmuxペインが終了すると、対応するセッションは自動的に `Closed` 状態に遷移します。

**チェックタイミング:**

- TUI起動時に全セッションを一括チェック
- TUI表示中は約10秒ごとに定期チェック
- hookイベント受信時にもチェック実行

**仕組み:**

`tmux list-panes -a` を1回実行して全ペインのcwdとPIDを取得し、`running` 状態のセッションと照合します。以下の条件でセッションは `closed` に遷移します:

1. **ペイン不在**: セッションのcwdに一致するtmuxペインが存在しない
2. **プロセスツリー探索**: cwdが一致するペインが存在しても、そのペインのプロセスツリー内に対象エージェント（claude/codex）のプロセスが見つからない場合

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

## CI

GitHub Actions で以下のジョブが PR / main push 時に実行されます:

- **lint**: clj-kondo lint + cljfmt format-check
- **test**: Clojure ユニットテスト
- **native-e2e**: GraalVM native-image ビルド + E2E テスト

### native-e2e

native-image でビルドしたバイナリに対して E2E テストを実行し、JVM では再現しない native-image 固有の不具合を検出します。

テスト内容:
- `--help` 出力確認
- hook コマンド (Claude / Codex) のセッション記録
- TUI 起動・終了 (`q` キー)
- TUI 検索モード (`/` → `Esc` → `q`)

TUI テストは tmux を利用してターミナルを模擬します。

## ライセンス

MIT
