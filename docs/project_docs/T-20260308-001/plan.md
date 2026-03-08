# T-20260308-001: ceeker 非同期処理の core.async 統一

## 棚卸し結果

### 現在の非同期処理パターン

| パターン | ファイル | 用途 | core.async 統一対象 |
|---|---|---|---|
| `async/thread` + `async/chan` + `async/alts!!` | `tui/app.clj` | pane checker バックグラウンドワーカー | 既に core.async |
| `Thread.` + `.start` + `.join` + `volatile!` | `core.clj` | musl/GraalVM ビルド用 run マクロ | **統一対象** |
| `ReentrantLock` | `state/store.clj` | JVM スレッド間排他制御 | 対象外（JVM プリミティブ必須） |
| `FileLock` | `state/store.clj` | OS レベルプロセス間排他制御 | 対象外（OS プリミティブ必須） |
| `WatchService` | `tui/watcher.clj` | ファイル変更監視（inotify） | 対象外（Java NIO API） |
| `shell/sh` | 各所 | tmux コマンド同期実行 | 対象外（意図的な同期呼び出し） |

### 判断根拠

- **ReentrantLock**: `async/thread` と TUI メインスレッド間で `sessions.edn` への同時アクセスを防ぐため必須。チャネルでは代替不可。
- **FileLock**: 複数プロセス（ceeker hook + ceeker TUI）間のファイルロック。OS レベル API のため代替不可。
- **WatchService**: OS の inotify API を利用するファイル監視。Java NIO 固有の機能。
- **shell/sh**: tmux CLI 呼び出し。短時間で完了し、結果を同期的に必要とするため非同期化不要。

## 実装計画

### Step 1: core.async 設計ルール策定
`CLAUDE.md` に非同期設計ルールを追記する。

### Step 2: `core.clj` の `run` マクロを core.async に統一
- `Thread.` + `volatile!` + `.start` + `.join` パターンを `async/<!! (async/thread ...)` に置換
- core.async は既に deps.edn に含まれ、app.clj で GraalVM 環境でも使用実績あり

**Before:**
```clojure
(defmacro ^:private run [expr]
  (if musl?
    `(let [v# (volatile! nil)
           ex# (volatile! nil)
           f# (fn [] (try (vreset! v# ~expr)
                          (catch Throwable t# (vreset! ex# t#))))]
       (doto (Thread. nil f# "ceeker-main")
         (.start) (.join))
       (when-let [t# @ex#] (throw t#))
       @v#)
    `(do ~expr)))
```

**After:**
```clojure
(defmacro ^:private run [expr]
  (if musl?
    `(let [r# (async/<!!
               (async/thread
                 (try [nil ~expr]
                      (catch Throwable t# [t# nil]))))]
       (when-let [t# (first r#)]
         (throw t#))
       (second r#))
    `(do ~expr)))
```

注意: `async/thread` は例外をチャネルに載せないため、try/catch で
タプルに包んで例外伝播を明示的に保持する。

### Step 3: テスト実行
`make ci` で回帰がないことを確認。

### Step 4: PR 作成
設計ルール・実装変更・テスト結果を含む PR を作成。

## リスク

- `async/thread` は core.async のスレッドプールからスレッドを取得するため、musl 環境での挙動が raw Thread と異なる可能性がある。ただし `app.clj` で既に `async/thread` を GraalVM ビルドで使用しており、実績がある。
- ロールバック: `core.clj` の 1 マクロ変更のみのため、revert が容易。
