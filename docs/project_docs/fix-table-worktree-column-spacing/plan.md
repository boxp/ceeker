# fix/table-worktree-column-spacing

## Problem

ceeker table view で WORKTREE 列の値が長い場合、後続カラム (MESSAGE, UPDATED) が右にずれて見える。
また列間スペースが 1 文字で詰まり感がある。

## Root Cause

`format-session-columns` で WORKTREE 列に `truncate-by-width` が適用されていなかった。
`pad-to-width` は短い文字列を右パディングするが、長い文字列は切り詰めないため、
14 文字を超えるディレクトリ名がそのまま出力され後続列を押し出していた。

## Changes

### src/ceeker/tui/view.clj

1. **列幅定数の抽出**: `col-width-agent` (9), `col-width-status` (11), `col-width-worktree` (14), `col-width-message` (44) を `def ^:const` で定義
2. **列間ギャップの定義**: `col-gap` = `"  "` (2 spaces) を `def ^:private` で定義
3. **WORKTREE truncation 追加**: `format-session-columns` で worktree 値に `truncate-by-width` を適用してから `pad-to-width` する
4. **ギャップ統一**: `format-session-columns` と `column-headers` の列間セパレータを `" "` (1 space) から `col-gap` (2 spaces) に変更
5. **compact-threshold 調整**: テーブル行幅が ~98 列に拡大したため、`compact-threshold` を 80 → 100 に引き上げ

### test/ceeker/tui/view_test.clj

1. `test-long-worktree-truncated-in-table` - 長い worktree 名が ellipsis で切り詰められることを検証
2. `test-long-worktree-same-column-offset` - 短/長 worktree で MESSAGE 列の開始位置が一致することを検証
3. `test-column-gap-is-two-spaces` - ヘッダーの列間が 2 スペースであることを検証

## Trade-offs

- `compact-threshold` を 100 に引き上げたため、80-99 幅の端末ではカードビューが使われるようになる。従来はテーブルビューだったが、テーブルが実質はみ出していたため実用上は改善。
- WORKTREE 列幅は 14 のまま。13 文字 + ellipsis で大半のディレクトリ名には十分。

## Review Points

- 列幅計算: `truncate-by-width` → `pad-to-width` の順序が正しいか
- compact-threshold: 100 で十分か、動的計算にすべきか
- col-gap: ハードコード `"  "` で十分か
