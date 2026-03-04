# T-20260304-013: table表示での不自然な改行を解消

## 問題

card表示向けに導入されたメッセージ折り返し処理（PR #21）により、`card-message-lines` では改行文字を正規化するようになったが、
table表示の `format-session-line` にはその対応がなく、メッセージ内の `\n` / `\r\n` がそのままテーブル行に含まれて不自然な改行が発生していた。

さらに、table表示では `truncate`（文字数ベース）を使用しており、CJK全角文字の表示幅を考慮していなかった。

## 根本原因

- `format-session-line` が `(:last-message session)` を改行正規化なしで `truncate` に渡していた
- `truncate` は `count`（文字数）ベースのため、全角文字が2カラム消費することを考慮していなかった

## 修正内容

### 1. `normalize-message` 関数の追加
- `\r?\n` をスペースに置換する共通関数を新設
- table表示・card表示の両方で利用可能

### 2. `pad-to-width` 関数の追加
- 表示幅ベースでスペースパディングする関数
- `format` の `%-40s` はバイト数ベースのため、CJK混在時にカラムがずれる問題を解消

### 3. `format-session-line` の修正
- メッセージを `normalize-message` → `truncate-by-width` → `pad-to-width` のパイプラインで処理
- `format` テンプレートの `%-40s` を `%s` に変更（パディングは `pad-to-width` で行う）

### 4. テスト追加
- `test-normalize-message`: 改行正規化のユニットテスト
- `test-pad-to-width`: パディングのユニットテスト
- `test-format-session-line-no-newlines`: table行に改行が含まれないことを検証
- `test-format-session-line-crlf`: CRLF対応
- `test-format-session-line-cjk-message`: CJKメッセージの切り詰め
- `test-format-session-line-short-message`: 短いメッセージのパススルー
- `test-format-session-line-nil-message`: nil対応

## 非影響範囲

- card表示 (`format-session-card`, `card-message-lines`) は変更なし
- 既存テストに変更なし
