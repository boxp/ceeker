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
