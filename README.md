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
[hooks]
notify = "ceeker hook codex notification"
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
