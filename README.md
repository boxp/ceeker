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

**キーバインド:**

| キー | 動作 |
|------|------|
| `j` / `↓` | 下へ移動 |
| `k` / `↑` | 上へ移動 |
| `Enter` | 選択セッションのtmuxペインへジャンプ |
| `r` | 手動リフレッシュ |
| `v` | 表示切替 (Auto→Table→Card) |
| `q` | 終了 |

### Hook CLI

```bash
# Claude Code hookイベントを処理
ceeker hook claude Notification <<< '{"session_id":"abc","title":"Working..."}'
# もしくは引数で JSON を渡す
ceeker hook claude Notification '{"session_id":"abc","title":"Working..."}'

# Codex notify hookイベントを処理（Codex が JSON を最後の引数として渡す）
ceeker hook codex '{"type":"agent-turn-complete","thread-id":"xyz","cwd":"/tmp","last-assistant-message":"Done."}'
# レガシー形式も引き続きサポート
ceeker hook codex notification '{"session_id":"xyz","message":"Testing..."}'
```

## Hook設定

### Claude Code

`.claude/settings.json` に以下を追加:

```json
{
  "hooks": {
    "Notification": [
      {
        "type": "command",
        "command": "ceeker hook claude Notification"
      }
    ],
    "Stop": [
      {
        "type": "command",
        "command": "ceeker hook claude Stop"
      }
    ]
  }
}
```

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
  ──────────────────────────────────────────────────────────────────────────────
   SESSION      AGENT     STATUS      WORKTREE     MESSAGE                                  UPDATED
  ──────────────────────────────────────────────────────────────────────────────
   abc123       [Claude]  ● Running   my-project   Working on feature...                    12:34:56
   xyz789       [Codex]   ○ Done      backend      Completed refactoring                    12:30:00
  ──────────────────────────────────────────────────────────────────────────────
  [j/k] Navigate  [Enter] Jump to tmux  [r] Refresh  [v] View:Auto  [q] Quit
```

## State Store

セッション状態は `$XDG_RUNTIME_DIR/ceeker/sessions.edn` に永続化されます（フォールバック: `/tmp/ceeker-<user>/sessions.edn`）。

- TUI未起動でもhookイベントは保存されます
- 複数TUI / 複数hookプロセスの同時実行に対応（ファイルロック使用）

## 開発

### 前提条件（開発者向け）

- Java 21+
- [Clojure CLI](https://clojure.org/guides/install_clojure) 1.12+
- [GraalVM](https://www.graalvm.org/) (native image build用)

```bash
git clone https://github.com/boxp/ceeker.git
cd ceeker

# テスト
clojure -M:test

# lint
clojure -M:lint

# フォーマットチェック
clojure -M:format-check

# フォーマット修正
clojure -M:format-fix

# 開発時のTUI起動
clojure -M:run
```

## ライセンス

MIT License - 詳細は [LICENSE](LICENSE) を参照。
