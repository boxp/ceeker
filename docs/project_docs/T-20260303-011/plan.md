# T-20260303-011: ceeker Phase 1.5 (Live Refresh + Filtering)

## Overview

ceeker MVPに対して2機能を追加する:
1. sessions.edn のファイル変更検知による自動反映
2. TUI上のセッション絞り込み機能

## Architecture

### 1. File Watcher (`src/ceeker/tui/watcher.clj`)

**方針**: `java.nio.file.WatchService` (inotify on Linux) を使用。

- `create-watcher` - WatchService を作成し、sessions.edn の親ディレクトリを ENTRY_MODIFY で監視
- `poll-change` - タイムアウト付きで変更イベントをポーリング (非ブロッキング)
- `close-watcher` - WatchService を閉じる

**フォールバック**: WatchService が利用不可の場合、ファイルの lastModified タイムスタンプを比較するポーリングにフォールバック。

**TUI統合**: 現在の `input/read-key` の1000msタイムアウトと組み合わせ:
- キー入力タイムアウト後に watcher を poll
- 変更あれば自動リフレッシュ (get-session-list 再取得)

### 2. Filter System (`src/ceeker/tui/filter.clj`)

**フィルタ状態**:
```clojure
{:agent-filter nil       ;; nil=全て, :claude-code, :codex
 :status-filter nil      ;; nil=全て, :running, :completed, :error, etc.
 :search-query nil}      ;; nil=なし, 文字列=session-id/cwd部分一致
```

**キーバインド追加**:
- `a` - agent種別フィルタ切替 (全て -> Claude -> Codex -> 全て)
- `s` - statusフィルタ切替 (全て -> running -> completed -> error -> 全て)
- `/` - 検索モード開始 (テキスト入力 -> Enter確定 -> Esc取消)

**フィルタ適用**: sort-sessions の前に filter-sessions を適用。

### 3. View Updates (`src/ceeker/tui/view.clj`)

- ヘッダーにアクティブフィルタ表示
- フッターにフィルタキーバインド追加
- 検索モード時の入力行表示

### 4. App Loop Updates (`src/ceeker/tui/app.clj`)

- loop の状態に filter-state と watcher を追加
- キー入力ハンドリングにフィルタ操作を追加
- watcher poll による自動リフレッシュ

## Files Changed

| File | Change |
|------|--------|
| `src/ceeker/tui/watcher.clj` | NEW - WatchService wrapper |
| `src/ceeker/tui/filter.clj` | NEW - フィルタロジック |
| `src/ceeker/tui/app.clj` | MOD - watcher/filter統合 |
| `src/ceeker/tui/view.clj` | MOD - フィルタ表示 |
| `src/ceeker/tui/input.clj` | MOD - 検索入力モード追加 |
| `test/ceeker/tui/watcher_test.clj` | NEW - watcher テスト |
| `test/ceeker/tui/filter_test.clj` | NEW - filter テスト |
| `README.md` | MOD - キーバインド・機能説明追記 |

## Test Plan

- `clojure -M:format-check` - フォーマット
- `clojure -M:lint` - lint
- `clojure -M:test` - 全テスト
- 手動: hook実行 -> sessions.edn更新 -> TUI自動反映確認
