# T-20260306-018: ceeker 非同期処理を core.async ベースへ整理

## 背景

PR #47 (`perf/async-startup-cleanup`) で導入された `future` + `atom` による
バックグラウンド pane check を `core.async` ベースへ置換し、
並行処理の入口を明確化する。

## 設計変更点

### 1. 依存関係の追加

`deps.edn` に `org.clojure/core.async` を追加。

### 2. app.clj — async/thread ベースの単一ワーカー

**Before (future + atom)**:
- `(atom nil)` で background future を追跡
- tick カウンタで 20tick ごとにチェック判定
- `realized?` で前回完了を確認、未完了ならスキップ

**After (core.async thread + channel)**:
- `start-pane-checker!` を新設:
  - 停止用チャネル `stop-ch` を返す
  - 内部で `async/thread` ベースのループを起動
  - `(async/alt!! stop-ch ... (async/timeout interval) ...)` で
    停止シグナルまたはタイムアウトを待機
  - タイムアウト時に `run-pane-check!` を逐次実行
  - pane check は `shell/sh` やファイルI/Oを含むブロッキング処理のため、
    `go` ブロック（固定サイズスレッドプール）ではなく
    `async/thread`（専用スレッド）を使用する
- `tui-loop` から tick カウンタと `bg-check` atom を除去
- `maybe-check-panes!` を削除

### 3. ライフサイクル管理

- `start-tui!` で `start-pane-checker!` を呼び出し、`stop-ch` を取得
- `start-tui!` の `finally` ブロックで `(async/close! stop-ch)` を実行
- これによりワーカーリークを防止

### 4. store.clj — 変更なし

`ReentrantLock` + `FileLock` はプロセス間ロックであり、
core.async では代替できないため維持する。

### 5. 競合回避の担保

- **プロセス内**: thread ループは逐次実行なので pane check の同時実行はない
- **プロセス間**: `with-file-lock` による NIO FileLock を維持
- **JVM内スレッド間**: `jvm-lock` (ReentrantLock) を維持
  - thread ワーカーと TUI render スレッドが同時に state ファイルに
    アクセスする可能性があるが、jvm-lock + FileLock で保護される

### 6. テスト更新

テスト項目:
- `start-pane-checker!` が即座に返ること（非ブロッキング）
- 同時実行が発生しないこと（逐次ワーカーなので自動保証されるが検証）
- pane check 例外後も次周期で再実行されること
- `stop-ch` を close した後はチェックが追加実行されないこと

### 7. GraalVM native-image 互換性

core.async は `async/thread` 経由で標準 JVM スレッドを使用する。
GraalVM native-image との互換性は CI の既存 `native-e2e` ジョブで検証する。
（native-e2e は native-image ビルド + tmux 上での動作テストを含む）

## 変更ファイル

| ファイル | 変更内容 |
|----------|----------|
| `deps.edn` | core.async 依存追加 |
| `src/ceeker/tui/app.clj` | future+atom → thread+channel |
| `test/ceeker/tui/app_test.clj` | pane check テスト書き換え |

## 非変更ファイル

- `src/ceeker/state/store.clj` — ロック機構はそのまま
- `src/ceeker/tmux/pane.clj` — 呼び出し側のみ変更
- `src/ceeker/hook/handler.clj` — 無関係
- `.github/workflows/ci.yml` — 既存 native-e2e で検証

## 既存機能要件の維持

- 起動応答をブロックしない → thread は別スレッドで非ブロッキング
- clean 失敗で起動を落とさない → thread 内で例外キャッチ
- OverlappingFileLockException 回避 → jvm-lock + FileLock 維持
