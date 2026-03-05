# T-20260305-007: ceeker→homebrew-tap repository_dispatch移行

## 目的
boxp/ceeker 側に強い PAT（homebrew-tap への push/PR 権限）を置かず、
`repository_dispatch` を使って権限境界を分離した安全な release 連携へ移行する。

## 旧方式との差分

### 旧方式（PR #33 の元の実装）
- ceeker の release workflow が直接 homebrew-tap をクローン
- Formula を生成して push、PR を作成
- `HOMEBREW_TAP_TOKEN` に push + PR 作成権限が必要

### 新方式（repository_dispatch）
- ceeker 側: release 完了後に `repository_dispatch` イベントを homebrew-tap へ送信
- homebrew-tap 側: dispatch イベントを受信して自身の `GITHUB_TOKEN` で Formula 更新 → PR 作成
- ceeker 側の `HOMEBREW_TAP_TOKEN` は dispatch 送信権限のみで十分

## 実装詳細

### ceeker 側変更（release.yml）
- `update-homebrew-tap` ジョブ → `notify-homebrew-tap` ジョブに置換
- tarball ダウンロード・SHA256 計算は維持
- 最後に `gh api repos/boxp/homebrew-tap/dispatches` で dispatch 送信
- payload: `version`, `tag`, `sha_darwin_arm64`, `sha_linux_amd64`, `sha_linux_arm64`, `sender`

### homebrew-tap 側変更（新規 update-formula.yml）
- `repository_dispatch` (type: `update-ceeker-formula`) でトリガー
- payload 検証（必須フィールドチェック）
- 冪等化（同一 version の open PR が存在すればスキップ）
- Formula 生成 → ブランチ作成 → PR 作成
- auto-merge 有効化（branch protection 設定時のみ動作）

## 必要 Secret/権限

### ceeker 側
- `HOMEBREW_TAP_TOKEN`: Fine-grained PAT with `Contents: Read and write` on `boxp/homebrew-tap`
  - dispatch イベント送信のみに使用（push/PR 作成権限不要）

### homebrew-tap 側
- 追加 Secret 不要（`GITHUB_TOKEN` のみ使用）

## ロールバック手順
1. ceeker 側: `feat/homebrew-tap-auto-update` ブランチの release.yml を旧方式に戻す
2. homebrew-tap 側: `.github/workflows/update-formula.yml` を削除
3. `HOMEBREW_TAP_TOKEN` を push + PR 権限のある PAT に戻す
