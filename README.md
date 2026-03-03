# ceeker

AI Coding Agent セッション・進捗モニタリングTUI。

複数のAIコーディングエージェント（Claude Code / Codex）が並行動作する環境で、各セッションの状態を一覧表示し、tmuxペインへのジャンプを可能にする。

## 前提条件

- Java 21+
- [Clojure CLI](https://clojure.org/guides/install_clojure) 1.12+
- tmux

## セットアップ

```bash
git clone https://github.com/boxp/ceeker.git
cd ceeker
```

## 使い方

### TUI起動

```bash
clojure -M:run
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
# Claude Code hookイベントを処理
ceeker hook claude Notification <<< '{"session_id":"abc","title":"Working..."}'

# Codex hookイベントを処理
ceeker hook codex notification <<< '{"session_id":"xyz","message":"Testing..."}'
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
        "command": "clojure -M:run hook claude Notification"
      }
    ],
    "Stop": [
      {
        "type": "command",
        "command": "clojure -M:run hook claude Stop"
      }
    ]
  }
}
```

### Codex

`~/.codex/config.toml` に以下を追加:

```toml
[hooks]
notify = "clojure -M:run hook codex notification"
```

## State Store

セッション状態は `$XDG_RUNTIME_DIR/ceeker/sessions.edn` に永続化されます（フォールバック: `/tmp/ceeker-<user>/sessions.edn`）。

- TUI未起動でもhookイベントは保存されます
- 複数TUI / 複数hookプロセスの同時実行に対応（ファイルロック使用）

## 開発

```bash
# テスト
clojure -M:test

# lint
clojure -M:lint

# フォーマットチェック
clojure -M:format-check

# フォーマット修正
clojure -M:format-fix

# 全CI実行
make ci
```

## ライセンス

MIT License - 詳細は [LICENSE](LICENSE) を参照。
