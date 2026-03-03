# T-20260303-008: Release workflow 失敗対応

## 失敗した Run
- https://github.com/boxp/ceeker/actions/runs/22625670163

## 根本原因

### 原因1: GraalVM native-image ビルド失敗
- `--initialize-at-build-time` を引数なしで指定しているため、全クラスがビルド時初期化対象
- `org.jline.nativ.Kernel32$CHAR_INFO` は Windows 用 JNI ネイティブクラスで、Linux/macOS 上ではネイティブライブラリが存在せず初期化に失敗
- エラー: `UnsatisfiedLinkError: 'void org.jline.nativ.Kernel32$CHAR_INFO.init()'`

### 原因2: macOS-13 ランナー廃止
- `macos-13` ランナーは GitHub Actions で廃止済み
- エラー: `The configuration 'macos-13-us-default' is not supported`
- Intel (x86_64) macOS ランナーは GitHub Actions で提供終了

## 修正内容

### 修正1: native-image に `--initialize-at-run-time=org.jline.nativ` を追加
- `org.jline.nativ` パッケージ配下のクラスをランタイム初期化に変更
- ビルド時初期化の対象から除外することで、Windows 固有 JNI クラスの初期化エラーを回避

### 修正2: matrix から `macos-13` / `darwin-amd64` を削除
- Intel macOS ランナーが利用不可のため、darwin-amd64 ターゲットを削除
- Apple Silicon (darwin-arm64) は `macos-14` ランナーで継続サポート

## 影響範囲
- `.github/workflows/release.yml` のみ
- リリースバイナリから `darwin-amd64` が除外される（ランナー廃止のため不可避）
