# ceeker

AI Coding Agent セッション・進捗モニタリングTUI

## セットアップ

README.md を参照。

## 開発ルール

- TDD原則: コード修正の前にテストを修正する
- KISS/YAGNI原則: 必要最小限の実装を優先し、不要な抽象化・フォールバック・握りつぶしを避ける
- lint/format の warning にも対応する
- `make ci` で全チェックを実行

## コマンド

- `make test` - テスト実行
- `make lint` - clj-kondo lint
- `make format-check` - cljfmt フォーマットチェック
- `make format` - cljfmt フォーマット修正
- `make ci` - format-check + lint + test
- `make run` - 実行
