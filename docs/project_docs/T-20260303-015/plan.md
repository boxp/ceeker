# T-20260303-015: 停滞タスクのコンフリクト解消と再実行

## 背景

以下2つのPRがmainとのコンフリクトにより停滞していた:
- PR #8 (`feat/tmux-pane-liveness-check`) - T-20260303-012
- PR #10 (`feat/responsive-compact-layout`) - T-20260303-014

両ブランチは `7304bed` (PR #4マージ時点) から分岐しており、
その後mainにマージされたPR #5, #6, #9の変更と競合していた。

## 停滞原因

- 両ブランチがmainの `7304bed` から分岐
- PR #5 (live refresh + filtering) が `tui/app.clj` と `tui/view.clj` を大幅に変更
- PR #6 (Claude hooks仕様修正) が `hook/handler.clj` を変更
- PR #9 (key handling fixes) がさらに `tui/app.clj` を変更
- 結果として両ブランチともCONFLICTING状態でCIが実行不可に

## 解決方針

新ブランチ `feat/T-20260303-015-resolve-conflicts` を最新mainから作成し、
両ブランチの実装を手動で統合。

## 統合ファイル一覧

### T-012 (tmux pane liveness) から:
- `src/ceeker/tmux/pane.clj` (新規)
- `src/ceeker/state/store.clj` (stale session関数追加)
- `src/ceeker/hook/handler.clj` (hook時のpaneチェック呼び出し)
- `src/ceeker/tui/app.clj` (定期paneチェック)
- `src/ceeker/tui/view.clj` (`:closed`ステータスバッジ)
- `test/ceeker/tmux/pane_test.clj` (新規)
- `test/ceeker/state/store_test.clj` (staleテスト追加)

### T-014 (responsive compact layout) から:
- `src/ceeker/tui/view.clj` (compact card表示)
- `src/ceeker/tui/app.clj` (terminal width, display-mode切替)
- `test/ceeker/core_test.clj` (view renderテスト追加)

### 統合が必要だったファイル:
- `src/ceeker/tui/view.clj` - filter対応 + closed badge + compact layout
- `src/ceeker/tui/app.clj` - watcher/filter + pane liveness + display mode
- `README.md` - 両機能のドキュメント

## コンフリクト解消の詳細

### app.clj
- mainの `tui-loop` (watcher/filter対応) をベースに維持
- T-012の `maybe-check-panes!` + tick カウンタを `tui-loop` に組み込み
- T-014の `get-terminal-width`, `next-display-mode`, `v` キーハンドリングを追加
- key result mapに `:dm` (display-mode) フィールドを追加

### view.clj
- mainのfilter対応 `render` をベースに維持
- T-012の `:closed` ステータスバッジを追加
- T-014の `format-session-card`, `use-compact?`, `compact-threshold` を追加
- `render` のarity: 2, 4, 5, 7 に対応
- `separator-line` を幅対応に変更
- `footer-line` にdisplay-mode表示を追加

### handler.clj
- mainのフォーマットを維持
- T-012の `pane/close-stale-sessions!` 呼び出しを追加
