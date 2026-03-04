# T-20260304-014: 同一cwd・別window時のジャンプ先誤り修正

## 根本原因

`ceeker.tui.app/tmux-jump!` が `:cwd` のみでペインを検索し、
`find-tmux-pane` の `(first ...)` で最初のマッチだけ返していた。

セッションデータには `:pane-id`（`$TMUX_PANE` 由来）が保存されているにもかかわらず、
ジャンプ時に使用されていなかった。

## ジャンプ解決キーの優先順位（修正後）

1. **pane-id** (`$TMUX_PANE`, e.g. `%5`): tmux pane ID で直接 `switch-client -t` を実行
2. **cwd fallback**: pane-id での切り替えが失敗した場合、従来の cwd 検索にフォールバック
3. **エラー**: いずれも失敗した場合はエラーメッセージを返す

## 修正内容

### `src/ceeker/tui/app.clj` - `tmux-jump!`

- セッションの `:pane-id` を最優先で使用
- `switch-tmux-pane!` に pane-id を直接渡す（tmux は `%N` 形式の pane ID をターゲットとして受け付ける）
- pane-id での切り替え失敗時は `:cwd` による従来の検索にフォールバック

### `test/ceeker/tui/app_test.clj`

追加テスト:
- `test-tmux-jump-prefers-pane-id`: pane-id 優先確認
- `test-tmux-jump-falls-back-to-cwd`: pane-id 空時の cwd フォールバック
- `test-tmux-jump-pane-id-fallback-on-failure`: pane-id 失敗時の cwd フォールバック
- `test-tmux-jump-same-cwd-different-panes`: 同一 cwd・別ペインの正確なジャンプ
- `test-tmux-jump-no-pane-id-no-cwd`: pane-id/cwd 両方なしの場合
