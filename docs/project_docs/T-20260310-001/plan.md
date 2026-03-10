# T-20260310-001: Renovate 導入計画

## 目的
boxp/ceeker に Renovate を導入し、boxp/arch と同様の基本運用に揃える。

## 実装方針

### 1. renovate.json5
- boxp/arch の設定をベースに、ceeker 固有の不要設定を除外
- `config:recommended` + `suzuki-shunsuke/renovate-config#3.3.1` プリセット
- minor/patch: automerge 有効（プリセット経由）
- major: automerge 無効（明示的に設定）
- arch 固有の customManagers（Kubernetes, CRI-O, kube-vip）は不要なため除外
- arch 固有の packageRules（Cloudflare provider制限、Kubernetes手動レビュー、OpenClaw）は除外

### 2. GitHub Actions ワークフロー
- `.github/workflows/wc-enable-auto-merge.yaml`: arch と同一（GitHub App トークンで auto-merge 有効化）
- `.github/workflows/wc-renovate-config-validator.yaml`: arch と同一（renovate.json5 の構文検証）
- `.github/workflows/pull-request-target.yaml`: `pull_request_target` イベントで auto-merge と config 検証を実行

### 3. ci.yml 修正
- paths フィルターに `renovate.json5` を追加（renovate 設定変更時にもCIを実行）

## boxp/arch との差分
- customManagers: なし（ceeker は Kubernetes/Ansible 不使用）
- packageRules: additionalBranchPrefix のみ（arch 固有の制限ルールは不要）
- aquaproj プリセット: なし（ceeker は aqua 不使用）
- path-filter ワークフロー: なし（ceeker は CI が単純なため不要）

## 前提条件
- GitHub App シークレット（APP_ID, APP_PRIVATE_KEY）がリポジトリに設定されていること
- main ブランチの branch protection で required status checks が設定されていること
- Renovate GitHub App がリポジトリにインストールされていること
