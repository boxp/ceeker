# T-20260312-002: Codex hooks 実運用条件の follow-up ドキュメント更新

## 背景
PR #61 マージ後の実機確認により、Codex hooks の実運用に追加の前提条件が判明した。

## 判明した実運用条件
1. `hooks.json` を配置しただけでは hooks が発火しない — feature flag `codex_hooks` の有効化が必須
2. `"async": true` を設定すると `⚠ skipping async hook ... async hooks are not supported yet` でスキップされる — `"async": false` が必須

## 変更内容
- **README.md / README.ja.md**: Codex hooks セクションを restructure
  - Feature flag 有効化手順をステップ1として追加
  - 設定例の `"async": true` → `"async": false` に変更
  - `config.toml` 不要の記述を修正（feature flag 用の設定は必要）
  - async の制限を明確に記述（Note → Current limitation に格上げ）
  - トラブルシューティングテーブルを追加

## 非スコープ
- Codex hooks 機能の実装変更
- Claude Code hooks に関する変更（Claude Code 側は async: true が正常動作するため）
