# T-20260304-009: ceeker リリース配布を tarball のみに統一

## 背景

ceeker の GitHub Release では raw binary と tarball の両方をアップロードしていた。
配布形態を簡素化するため、tarball のみに統一する。

## 変更内容

### 1. `.github/workflows/release.yml`

- `Prepare release assets` ステップ: tarball 作成後に raw binary を `rm` で削除
- `Generate checksums` ステップ: `sha256sum ceeker-*` → `sha256sum *.tar.gz` に変更（tarball のみ対象）
- `Create GitHub Release` ステップ: `release/ceeker-*` → `release/*.tar.gz` に変更（tarball のみアップロード）

### 2. `README.md`

- 「raw binary から直接インストール」セクションを削除
- Homebrew と tarball の2つのインストール方法のみ記載

## ユーザー影響

- raw binary の直接ダウンロード URL (`ceeker-linux-amd64` 等) が新リリースから利用不可に
- 代替: tarball (`ceeker-linux-amd64.tar.gz`) または Homebrew 経由でインストール
- 既存バージョンの raw binary は過去の Release に残存

## ロールバック方針

- release.yml の raw binary 削除行(`rm "release/$name"`)を除去
- checksums と files の glob を元に戻す
- README に raw binary セクションを復元

## 検証方法

- release.yml の YAML 構文が正しいこと（actionlint で検証）
- リリースアセットが `*.tar.gz` と `checksums.txt` のみになることをワークフロー内容から確認
