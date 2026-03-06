# T-20260306-014: Claude hook受信時にlast messageを更新しない

## 背景
ceekerがClaude Code hooks（メトリクス用途）を受信したとき、MESSAGE列（last message）がhook由来のノイズ（"using: Bash", "used: Edit"等）で上書きされてしまう問題。

## 方針
`handle-hook!` でClaude Code hookイベント処理時、storeに書き込むsession dataから `:last-message` を `dissoc` する。
`merge` がキーの無いフィールドを上書きしないため、既存の `:last-message` が保持される。

## 変更内容

### `src/ceeker/hook/handler.clj`
- `handle-hook!` 関数に `store-data` バインディングを追加
- `agent-type` が `"claude"` の場合、`session-data` から `:last-message` を `dissoc` してからstoreに渡す
- Codexイベントは従来通り `:last-message` を更新

### `test/ceeker/hook/handler_test.clj`
- `test-claude-hook-does-not-update-last-message`: 既存セッションに複数のClaude hookイベントを送信し、`:last-message` が保持されることを確認
- `test-codex-hook-still-updates-last-message`: Codexイベントでは従来通り `:last-message` が更新されることを確認
- `test-claude-new-session-has-no-last-message`: 新規Claude セッションでは `:last-message` がnilになることを確認

## 互換性
- 後方互換あり。既存のセッションデータ構造は変更なし
- Codex hookの挙動は変更なし
