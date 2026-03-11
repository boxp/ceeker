# pane-centric migration cleanup

## 目的
sessions.edn に旧 session-id key と新 pane-id key が混在する状態を
read-time normalization で自動的に解消し、1 pane = 1 row を保証する。

## Root Cause
pane-centric key への移行後も、旧形式（session-id key）の entry が
sessions.edn に残っているケースがある。同一 pane-id の entry が
session-id key と pane-id key の両方で存在し、多重記録が発生する。

## Canonicalization Strategy
- `normalize-sessions` 関数を `read-state-file` に組み込み
- 各 entry の `:pane-id` が non-empty なら pane-id を canonical key に
- `:pane-id` が空なら original key を維持（non-tmux session）
- 同一 canonical key に複数 entry がある場合、`:last-updated` が最新のものを採用

## Cleanup Behavior
- **Read-time**: `read-state-file` が normalize-sessions を呼び、
  返すデータは常に正規化済み
- **Write-time**: 全 write 関数は read-state-file 経由で読むため、
  write 後のファイルには正規化済みデータのみ残る
- 明示的な write-time cleanup は不要（read-time normalization で十分）

## 変更ファイル
- `src/ceeker/state/store.clj`: `normalize-sessions`, `pick-newer`,
  `parse-timestamp-ms` 追加、`read-state-file` に normalization 組み込み
- `test/ceeker/state/store_test.clj`: mixed state migration テスト 7件追加

## テスト
1. `test-normalize-mixed-keys` - session-id key + pane-id key 混在の正規化
2. `test-normalize-keeps-newer-entry` - newer timestamp 優先
3. `test-normalize-different-panes-separate` - 別 pane-id は別 entry
4. `test-normalize-empty-pane-id-keeps-original-key` - non-tmux session
5. `test-normalize-multiple-legacy-same-pane` - 3件の legacy entry 集約
6. `test-mixed-state-read-write-cleanup` - read→write でファイルが clean に
7. `test-update-after-normalize-no-legacy-remnants` - update 後に legacy key なし

## Residual Risk
- `normalize-sessions` は reduce-kv の iteration order に依存するが、
  `pick-newer` で timestamp 比較するため結果は deterministic
- 非常に古い entry で `:last-updated` が nil の場合は 0 として扱われ、
  新しい entry が優先される（意図通り）
