# T-20260303-016: ceeker Homebrew対応（配布導線追加）

## 概要

macOS環境での導入体験を改善するため、ceekerをHomebrewで配布可能にする。

## 配布方針

### tap方式

- リポジトリ: `boxp/homebrew-tap`（将来的に別リポジトリで運用）
- 暫定的にceeker本体リポジトリ内に `Formula/ceeker.rb` を配置
- ユーザーは `brew tap boxp/tap` → `brew install ceeker` で導入可能

### バイナリ配布形式

- **tarball（.tar.gz）を優先** — Homebrew Formula が参照する形式
- raw binary も引き続きリリースに含める（後方互換性）

## 実装変更

### 1. release.yml — tarball生成の追加

リリースワークフローの `Prepare release assets` ステップで、各プラットフォームのバイナリを `.tar.gz` に圧縮してリリースアセットに追加。

```yaml
tar czf "release/${name}.tar.gz" -C "$dir" "$name"
```

checksums.txt には tarball の SHA256 も含まれる。

### 2. Formula/ceeker.rb

- `on_macos` / `on_linux` + `on_arm` / `on_intel` で分岐
- url は `https://github.com/boxp/ceeker/releases/download/v#{version}/ceeker-<platform>.tar.gz`
- sha256 はリリース時に手動更新（将来的にGitHub Actionsで自動化検討）
- `bin.install` でプラットフォーム別バイナリを `ceeker` にリネーム配置

### 3. README.md

インストール手順を拡充:
1. Homebrew（推奨）: `brew tap boxp/tap` + `brew install ceeker`
2. tarball から直接: `curl` + `tar xzf`
3. raw binary から直接: 従来の方法

## 対応プラットフォーム

| Platform | Architecture | ファイル名 |
|----------|-------------|-----------|
| macOS | ARM64 (Apple Silicon) | ceeker-darwin-arm64.tar.gz |
| Linux | AMD64 | ceeker-linux-amd64.tar.gz |
| Linux | ARM64 | ceeker-linux-arm64.tar.gz |

## SHA256運用

- リリースワークフローで `sha256sum` を自動生成し `checksums.txt` に記録
- Formula更新時は `checksums.txt` から対応するハッシュを取得して `ceeker.rb` を更新
- 初回リリース前は PLACEHOLDER を記載

## 今後の展開（非スコープ）

- `boxp/homebrew-tap` 独立リポジトリ化とGitHub Actions自動更新
- Apple notarization 対応
- `brew audit --strict` への完全準拠
