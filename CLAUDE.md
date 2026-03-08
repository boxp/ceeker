# ceeker

AI Coding Agent セッション・進捗モニタリングTUI

## セットアップ

README.md を参照。

## 開発ルール

- TDD原則: コード修正の前にテストを修正する
- KISS/YAGNI原則: 必要最小限の実装を優先し、不要な抽象化・フォールバック・握りつぶしを避ける
- lint/format の warning にも対応する
- `make ci` で全チェックを実行

## core.async 非同期設計ルール

ceeker の非同期処理は core.async をベースとする。以下のルールに従うこと。

### チャネル設計
- シグナル用チャネル（stop channel 等）はバッファなし `(async/chan)` で作成する
- データフロー用チャネルはバッファサイズを明示する
- チャネルは作成した側が `async/close!` で明示的にクローズする
- クローズは `finally` ブロック内で行い、例外時もリソースリークを防ぐ

### go / go-loop / thread の使い分け
- **`async/thread`**: ブロッキング I/O（shell/sh, ファイル I/O, ネットワーク I/O）を含む処理に使用する。core.async の固定サイズスレッドプール外のスレッドで実行される
- **`go` / `go-loop`**: ノンブロッキングなチャネル操作のみの軽量コーディネーションに使用する
- `go` ブロック内でブロッキング操作を行ってはならない（スレッドプール枯渇の原因）

### ブロッキング I/O の扱い
- `go` ブロック内での `shell/sh`, `slurp`, `spit`, `.lock`, `.poll` 等は禁止
- ブロッキング I/O は `async/thread` 内、または同期コンテキスト（main スレッド、hook CLI）で実行する
- `async/thread` 内ではブロッキング版の `<!!`, `>!!`, `alts!!` を使用する

### エラー伝播
- `async/thread` 内の例外は catch して stderr に出力する（バックグラウンドワーカー）
- ワーカーは例外後もループを継続する（`run-pane-check!` パターン）
- チャネル経由でエラーを伝播する場合は `ex-info` をチャネルに put する

### タイムアウトとバックプレッシャー
- 定期実行には `async/timeout` + `async/alts!!` パターンを使用する
- stop channel を `:priority true` で優先監視し、シャットダウン応答性を確保する

### シャットダウン
- バックグラウンドワーカーは stop channel パターンで終了制御する
- ワーカー開始関数は stop channel を返す
- 呼び出し側は `async/close!` で停止シグナルを送る

### 許容される例外（core.async 外の並行プリミティブ）
- **ReentrantLock**: JVM スレッド間のファイルアクセス排他制御に使用可。チャネルでは代替不可
- **FileLock**: OS レベルのプロセス間排他制御に使用可。Java NIO API 必須
- **WatchService**: OS レベルのファイル変更監視に使用可。Java NIO API 必須
- **Thread（musl ビルド）**: GraalVM musl 静的ビルドのエントリポイントで `async/thread` + `<!!` を使用する

### テスト方針
- バックグラウンドワーカーのテストは `with-redefs` で I/O をモックし、`promise`/`deref` で完了を待機する
- インターバルは引数で注入可能にし、テストでは短い値を使用する
- stop channel の `close!` 後に追加実行がないことを検証する

## コマンド

- `make test` - テスト実行
- `make lint` - clj-kondo lint
- `make format-check` - cljfmt フォーマットチェック
- `make format` - cljfmt フォーマット修正
- `make ci` - format-check + lint + test
- `make run` - 実行
