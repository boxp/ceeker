# T-20260305-002: homebrew-tap 自動更新ワークフローの追加

## 目的
ceeker リリース時に `boxp/homebrew-tap` の Formula を自動更新する仕組みを追加する。
PR #29 (native-image最適化) から分離した独立PR。

## 実装内容

### release.yml に `update-homebrew-tap` ジョブを追加
- `release` ジョブ完了後に実行
- 処理フロー:
  1. タグから version を抽出 (`vX.Y.Z` -> `X.Y.Z`)
  2. リリースの tarball (linux-amd64, linux-arm64, darwin-arm64) をダウンロード
  3. 各 tarball の SHA256 を算出
  4. `boxp/homebrew-tap` をクローンし `Formula/ceeker.rb` を生成
  5. ブランチ `update-ceeker-{VERSION}` を作成・push
  6. PR を自動作成（既存PR・ブランチがある場合はスキップ/再利用）

## 必要な Secrets
- `HOMEBREW_TAP_TOKEN`: `boxp/homebrew-tap` への push + PR 作成権限を持つ PAT

## 変更ファイル
- `.github/workflows/release.yml`: update-homebrew-tap ジョブ追加
