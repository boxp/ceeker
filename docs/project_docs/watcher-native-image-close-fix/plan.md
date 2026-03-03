# watcher-native-image-close-fix Plan

## 背景
- `ceeker` の native-image バイナリ実行時に `No matching field found: close for class sun.nio.fs.LinuxWatchService` で起動失敗する。
- JVM 実行では再現せず、GraalVM native-image 実行で再現する。

## 計画
1. Java 21 + GraalVM (Docker) で例外を再現し、発生箇所を確定する。
2. `ceeker.tui.watcher` の WatchService interop を reflection 依存から型ヒント付き呼び出しへ変更する。
3. `make ci` と native-image 再ビルド・再実行で回帰確認する。

## 実施結果
- Docker 上の `native-image` 実行で同例外を再現。
- `src/ceeker/tui/watcher.clj` を修正し、`WatchService`/`WatchKey`/`WatchEvent`/`Path` の型ヒントを追加。
- `close/register/poll/context` の呼び出しを reflection 非依存化。
- `make ci` 通過。
- 修正後 native-image を再生成し、起動時例外が解消されたことを確認。
