# ceeker

AI Coding Agent セッション・進捗モニタリングTUI。

複数のAIコーディングエージェント（Claude Code / Codex）が並行動作する環境で、各セッションの状態を一覧表示し、tmuxペインへのジャンプを可能にする。

## 前提条件

- tmux

## インストール

[Releases](https://github.com/boxp/ceeker/releases) からプラットフォームに合ったバイナリをダウンロード:

```bash
# 例: Linux amd64
curl -L -o ceeker https://github.com/boxp/ceeker/releases/latest/download/ceeker-linux-amd64
chmod +x ceeker
sudo mv ceeker /usr/local/bin/
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

## ライセンス

MIT
