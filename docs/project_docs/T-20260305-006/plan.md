# T-20260305-006: ceeker README多言語化

## 目的
ceeker のドキュメントを英語優先で提供しつつ、日本語ユーザーも迷わず参照できる構成にする。

## 実装内容
1. `README.md` を英語版（デフォルト表示）に変換
2. `README.ja.md` を新設し、日本語版ドキュメントを提供
3. 両READMEの先頭に相互リンクを設置
   - README.md: `> [日本語版 (Japanese)](./README.ja.md)`
   - README.ja.md: `> [English](./README.md)`
4. 全セクション（Install / Usage / Options / Hook Configuration / Session Cleanup / Display Modes / Development / CI / License）を両言語で整合

## 変更ファイル
- `README.md` — 日本語→英語に翻訳、日本語版リンク追加
- `README.ja.md` — 新規作成（元の日本語README内容を保持、英語版リンク追加）

## 非スコープ
- 機能追加・CLI仕様変更なし
