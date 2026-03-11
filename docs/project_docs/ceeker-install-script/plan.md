# ceeker ワンライナー導入計画

## Summary

GitHub Releases の既存ネイティブ tarball を使う `install.sh` をリポジトリ直下に追加し、`curl -fsSL ... | sh` だけで `ceeker` を導入できる状態にする。  
既定の配置先は `~/.local/bin`、対応プラットフォームは現行 Release と一致する `darwin-arm64` / `linux-amd64` / `linux-arm64` に限定し、未対応環境は明示エラーにする。  
実装開始時の最初の変更として、今回の計画を `docs/project_docs/ceeker-install-script/plan.md` に保存し、同じ PR に含める。

## Key Changes

- `install.sh` を追加する
  - POSIX `sh` ベースで実装する
  - 既定動作は latest 版を `${HOME}/.local/bin/ceeker` にインストールする
  - `-b` / `--bin-dir` と `-v` / `--version` を受け付ける
  - 環境変数 `BIN_DIR` / `VERSION` でも上書き可能にし、優先順位は `CLI引数 > 環境変数 > デフォルト` に固定する
  - `uname -s` / `uname -m` から `darwin-arm64` / `linux-amd64` / `linux-arm64` に正規化する
  - ダウンロード URL は以下に固定する
    - latest: `https://github.com/boxp/ceeker/releases/latest/download/ceeker-${platform}.tar.gz`
    - 明示版: `https://github.com/boxp/ceeker/releases/download/v${VERSION}/ceeker-${platform}.tar.gz`
  - 同じ release から `checksums.txt` も取得し、tarball の SHA256 を検証してから展開する
  - 検証コマンドは `sha256sum` を優先し、未導入環境では `shasum -a 256` にフォールバックする
  - `curl`、`tar`、`sha256sum` または `shasum` のいずれかの存在確認を行い、足りなければ即時エラーにする
  - 一時ディレクトリへ展開し、展開された単一バイナリを `ceeker` にリネームして配置する
  - 配置先ディレクトリがなければ作成する
  - インストール後に `ceeker --version` を実行して成功確認する
  - `PATH` に配置先が含まれない場合は、その旨を案内する
  - 未対応 OS/arch、ダウンロード失敗、チェックサム不一致、展開失敗時は non-zero exit で理由を表示する

- 配布物の整合性ポリシーを固定する
  - README の推奨ワンライナーは `main` 上の `install.sh` を取得する
  - その代わり `install.sh` は release asset のファイル名規約と `checksums.txt` のみを契約面とし、latest / `vX.Y.Z` の両方で動く後方互換を維持する
  - installer 固有の release asset は追加しない
  - この方針を README に短く明記し、インストーラ変更時は既存 release 命名規約を壊さない前提で運用する

- README を英日両方更新する
  - `README.md` と `README.ja.md` に「One-liner install」節を追加する
  - 推奨ワンライナーは `curl -fsSL https://raw.githubusercontent.com/boxp/ceeker/main/install.sh | sh`
  - カスタム配置先とバージョン固定の例も載せる
    - `curl ... | sh -s -- -b ~/.local/bin`
    - `curl ... | sh -s -- -v 0.1.0`
  - サポート対象を明記し、非対応環境は Homebrew または手動導入へ誘導する
  - 既存の Homebrew / tarball 手順は残し、ワンライナーを最短導線として先頭に置く

- TDD と CI 導線を明確化する
  - まず失敗するテストを追加し、その後に `install.sh` を実装する
  - スクリプトテストは新規 `test/install_script_test.sh` に切り出し、POSIX `sh` で実行できる自前テストにする
  - テストでは `curl` / `tar` / `uname` / `mktemp` / `ceeker --version` / ハッシュ計算コマンドをモックできるよう、`install.sh` を外部コマンド呼び出し境界ごとに差し替え可能な構成にする
  - `Makefile` に `test-install-script` を追加し、`test` ターゲットから Clojure テストの前後どちらかで必ず実行する
  - `ci` は既存どおり `format-check lint test` を維持し、`test` に shell テストが含まれる状態にする
  - `.github/workflows/ci.yml` の path filter に `README.md`、`README.ja.md`、`install.sh`、`test/install_script_test.sh` を追加し、この変更が PR で必ず CI 実行対象になるようにする

## Public Interfaces

- 新規公開ファイル: `install.sh`
- 新規 Make ターゲット: `test-install-script`
- 利用者向けインターフェース
  - `sh install.sh`
  - `sh install.sh -b <dir>`
  - `sh install.sh -v <version>`
  - `BIN_DIR=<dir> VERSION=<version> sh install.sh`
- README に公開するワンライナー
  - `curl -fsSL https://raw.githubusercontent.com/boxp/ceeker/main/install.sh | sh`

## Test Plan

- `test/install_script_test.sh` で以下を固定する
  - `linux-amd64` / `linux-arm64` / `darwin-arm64` の判定
  - 未対応環境での失敗
  - latest URL と固定バージョン URL の切り替え
  - `-b` / `-v` と `BIN_DIR` / `VERSION` の優先順位
  - `checksums.txt` の取得と SHA256 検証成功
  - チェックサム不一致時の失敗
  - 配置先ディレクトリ作成
  - `PATH` 未設定時の案内
  - `ceeker --version` 成功で正常終了すること
- `make ci` を実行し、既存 Clojure 側の format/lint/test と追加 shell テストが通ることを確認する
- 追加で一時ディレクトリ向けのスモーク実行を行い、実際に配置された `ceeker --version` まで通ることを確認する

## Assumptions

- 配布元は既存 GitHub Releases を使い、新しい配布基盤は追加しない
- macOS Intel は今回の対象外とし、Release/CI 拡張は別タスクに分離する
- `install.sh` はリポジトリ直下に置き、`raw.githubusercontent.com` から取得する
- 既定配置先は `~/.local/bin` を採用する
- `main` 上の installer は、公開済み release の tarball 命名規約と `checksums.txt` に対して後方互換を維持する
