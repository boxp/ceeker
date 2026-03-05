# T-20260305-001: PR #32 を #29 へ統合 + homebrew-tap 自動更新導線の実装

## 目的
1. PR #32 (`fix/card-highlight-content-only`) を PR #29 (`feat/native-image-optimizations`) に統合し、レビュー導線を一本化
2. ceeker リリース時に `boxp/homebrew-tap` の Formula を自動更新する仕組みを追加

## 実装内容

### A. PR統合 (#32 -> #29)
- PR #32 のコミット `6d66c55` を `feat/native-image-optimizations` ブランチへ cherry-pick
- `test/ceeker/tui/view_test.clj` のコンフリクトを解消（テスト追加のみ）
- `src/ceeker/tui/view.clj` の変更は自動マージ

### B. homebrew-tap 自動更新 workflow
- `.github/workflows/release.yml` に `update-homebrew-tap` ジョブを追加
- `release` ジョブ完了後に実行
- 処理フロー:
  1. タグから version を抽出 (`vX.Y.Z` -> `X.Y.Z`)
  2. リリースの tarball (linux-amd64, linux-arm64, darwin-arm64) をダウンロード
  3. 各 tarball の SHA256 を算出
  4. `boxp/homebrew-tap` をクローンし `Formula/ceeker.rb` を生成
  5. ブランチ `update-ceeker-{VERSION}` を作成・push
  6. PR を自動作成

### 必要な Secrets
- `HOMEBREW_TAP_TOKEN`: `boxp/homebrew-tap` への push + PR 作成権限を持つ PAT

## 変更ファイル
- `.github/workflows/release.yml`: homebrew-tap 自動更新ジョブ追加
- `src/ceeker/tui/view.clj`: PR #32 からの cherry-pick (カードハイライト修正)
- `test/ceeker/tui/view_test.clj`: PR #32 からの cherry-pick (回帰テスト追加)
