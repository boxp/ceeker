# T-20260304-006: ceeker UX改善（q終了 / インタラクティブ検索）

## 変更概要

### 1. `q`キー終了の堅牢化
- `next-loop-state`に明示的な`:quit`チェックを追加
- 従来は`:fs`キーの不在（nil）に依存した間接的な終了判定だったが、`:quit`フラグを最優先でチェックする設計に変更

### 2. `/`検索のインタラクティブ化
- `handle-search-key`を修正し、文字入力・Backspaceのたびに`f/set-search-query`を呼び出してフィルタをリアルタイム適用
- Enter: 検索モード終了、現在のフィルタを維持
- ESC: 検索モード終了、検索クエリをクリア（以前は変更前の状態を保持）
- Backspace: 最後の文字を削除し即座にフィルタ反映。空になった場合はフィルタクリア
- フッターヘルプテキストを`[Enter] Apply → [Enter] Done`、`[Esc] Cancel → [Esc] Clear`に更新

### 3. テスト追加
- インタラクティブ検索の動作テスト追加（文字入力即座反映、Backspace空バッファ時のクリア）
- `nav-key-result`のquit返却テスト
- `next-loop-state`のquit/idle処理テスト

## 変更ファイル
- `src/ceeker/tui/app.clj` - イベントループ、検索ハンドラ、quit処理
- `src/ceeker/tui/view.clj` - フッターヘルプテキスト
- `test/ceeker/tui/app_test.clj` - テスト追加・更新
