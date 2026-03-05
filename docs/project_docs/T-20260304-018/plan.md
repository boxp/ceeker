# T-20260304-018: release tag から CEEKER_VERSION を自動生成

## 背景

`build.clj` の `CEEKER_VERSION` 環境変数と release tag が二重管理になっている。
現状、release workflow では `CEEKER_VERSION` を設定しておらず、常にフォールバック値 `0.1.0` が使われている。

## 現状分析

- `build.clj:5`: `(def version (or (System/getenv "CEEKER_VERSION") "0.1.0"))`
  - uberjar ビルド時に version を参照
- `.github/workflows/release.yml`: tag `v*` push でトリガー
  - `CEEKER_VERSION` 環境変数を設定していない
- release workflow の build job で `clojure -T:build uber` を実行

## 実装計画

### 1. release.yml の修正

build job に以下を追加:
- tag format バリデーション (`vX.Y.Z` 形式のみ許可)
- `GITHUB_REF_NAME` から `v` プレフィックスを除去して `CEEKER_VERSION` を生成
- `CEEKER_VERSION` を環境変数として uberjar ビルドステップに渡す

```yaml
- name: Validate tag format
  run: |
    if [[ ! "${GITHUB_REF_NAME}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      echo "::error::Tag '${GITHUB_REF_NAME}' does not match expected format 'vX.Y.Z'"
      exit 1
    fi

- name: Build uberjar
  env:
    CEEKER_VERSION: ${{ github.ref_name }}
  run: |
    export CEEKER_VERSION="${CEEKER_VERSION#v}"
    clojure -T:build uber
```

### 2. 影響範囲

- `build.clj`: 変更不要（既に `CEEKER_VERSION` 環境変数を参照する設計）
- `ci.yml`: 変更不要（CI では version embedding は不要）
- ソースコード: version 参照箇所なし

### 3. 非スコープ

- リリース方式の変更（tarball only 方針は維持）
- build.clj のフォールバック値の変更（開発時のデフォルトとして残す）
