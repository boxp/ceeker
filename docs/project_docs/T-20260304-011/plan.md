# T-20260304-011: ceeker q終了不発バグ修正

## 原因分析

### 根本原因: JVMプロセスが終了しない

`q`キー押下時、TUIループ自体は正しく終了する（画面クリア、WatchService/Terminal閉鎖）。
しかし **JVMプロセスが終了しない**。

理由:
1. `clojure.java.shell/sh` が内部で `future` を使用し、Clojureの `Agent/soloExecutor` スレッドプール（non-daemon threads）を起動する
2. `pane/close-stale-sessions!` が tick=0 で即座に `shell/sh` を呼び出すため、TUI起動直後にnon-daemonスレッドプールが存在する
3. `-main` の TUIパスでは `System/exit` を呼ばずにreturnするだけなので、non-daemonスレッドがJVMを存命させる

### TUIループの終了フロー（正常動作）

```
q押下 → read-key → \q
  → process-key → handle-normal-key → nav-key-result → {:quit true}
  → next-loop-state → nil
  → when-let body skipped → loop ends
  → start-tui! finally block → cleanup
  → -main returns → BUT JVM stays alive ← ここが問題
```

## 修正内容

### 1. `core.clj` (-main)
- TUI終了後に `(System/exit 0)` を呼び出し、JVMを確実に終了させる
- `--help` や `errors` パスでは既に `System/exit` が呼ばれていた（一貫性の確保）

### 2. `app.clj` (start-tui!)
- finally ブロックで `(shutdown-agents)` を呼び出し、Clojureのスレッドプールを停止
- `System/exit` の前にgracefulなクリーンアップを行う層として機能

### 3. テスト追加 (app_test.clj)
- `test-handle-normal-key-q-propagates-quit`: handle-normal-key → next-loop-state のエンドツーエンドテスト
- `test-process-key-q-in-normal-mode`: 通常モードで process-key が quit を返すことを確認
- `test-process-key-q-in-search-mode`: 検索モードでは q が終了ではなくバッファに入ることを確認

## 既知制約
- 検索モード中は `q` が検索文字として扱われる（設計通り、ESCで検索終了してからqを押す）
- `shutdown-agents` はグローバル操作だが、TUI終了時点で保留中のagent操作は存在しない
