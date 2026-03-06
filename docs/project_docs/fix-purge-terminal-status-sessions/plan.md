# fix: purge terminal status sessions

## 問題

`:completed` や `:error` ステータスのセッションが永遠にパージされない。

### 原因

`expired-closed?` が `:closed` ステータスのみチェックしていたため、
`:completed`/`:error` のセッションはどのクリーンアップパスにも引っかからなかった。

- `close-sessions-by-pred!` → `apply-stale-pred` は `capturable-statuses` (`:running`, `:idle`, `:waiting`) のみ対象
- `purge-expired-closed-sessions!` → `expired-closed?` は `:closed` のみ対象

## 修正

- `terminal-statuses` セット (`#{:closed :completed :error}`) を導入
- `expired-closed?` を `expired-terminal?` にリネームし、`terminal-statuses` に含まれるステータス全てをパージ対象に

## 変更ファイル

- `src/ceeker/state/store.clj` - `expired-terminal?` + `terminal-statuses`
- `test/ceeker/state/store_test.clj` - `:completed`/`:error` のパージテスト追加
