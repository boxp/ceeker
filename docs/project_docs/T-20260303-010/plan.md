# T-20260303-010: Codex notify仕様準拠にceeker hook処理を修正

## 背景

Codex CLI (`@openai/codex`) の notify hook は以下の仕様で動作する:

1. `config.toml` 設定: `notify = ["ceeker", "hook", "codex"]`
2. 呼び出し形式: コマンド末尾にJSON payloadを引数として追加
   - 例: `ceeker hook codex '{"type":"agent-turn-complete","thread-id":"...","cwd":"..."}'`
3. フィールド名: **kebab-case** (`thread-id`, `last-assistant-message`)
4. stdin: `/dev/null` (fire-and-forget)

## 問題点

現在の ceeker 実装は以下を前提としていた:

- `ceeker hook codex <event-type> [<payload>]` 形式 (event-typeが独立引数)
- フィールド名: snake_case (`session_id`, `message`)
- stdin からのペイロード受信

Codex 実呼び出しでは JSON が event-type 位置に来るため:
- event-type に JSON 文字列が入り、マッチしない
- payload が空 (stdin=/dev/null) で session-id/cwd が取得できない
- 結果: sessions.lock のみ作成され sessions.edn が正しく更新されない

## 修正内容

### 1. core.clj
- `json-string?` ヘルパー追加: 第2引数が `{` で始まるかを判定
- `handle-hook-command`: JSON が event-type 位置に来た場合、
  event-type=nil, payload=その JSON として handler に渡す

### 2. handler.clj
- `codex-type->event`: Codex の `type` フィールドを内部イベント名に変換
  (`agent-turn-complete` → `notification`)
- `extract-codex-identity`: `thread-id` (kebab-case) もフォールバックとしてサポート
- `codex-event-fields`: `last-assistant-message` もメッセージソースに追加
- `resolve-codex-event`: event-type が nil の場合、payload の `:type` から自動判定
- `handle-hook!`: codex の場合に `resolve-codex-event` を経由

### 3. README.md
- Codex 設定例を `notify = ["ceeker", "hook", "codex"]` に修正
- Hook CLI 例に Codex notify 形式を追加

### 4. テスト追加
- `test-codex-notify-real-payload`: 実 Codex ペイロードでの E2E テスト
- `test-codex-notify-no-message`: last-assistant-message が null の場合
- `test-codex-legacy-explicit-event`: レガシー形式の後方互換テスト
- `payload-from-cli-codex-notify-format`: CLI引数パースの境界テスト

## 後方互換性

- `ceeker hook codex notification '{"session_id":"...","message":"..."}'` 形式は
  引き続き動作する (event-type が明示的に渡される場合は従来通り処理)
