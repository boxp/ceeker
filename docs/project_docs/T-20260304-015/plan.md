# T-20260304-015: native-image抽出パターン8件をceekerへ全適用

## 実装概要

PR #25 で抽出した native-image + Clojure 実装パターン8件を ceeker に適用し、
nativeビルドの安定性・起動性能・運用性を向上させる。

## 実装パターン一覧

### P0（低リスク・高効果）

1. **graal-build-time ライブラリ導入**
   - `deps.edn` の `:build` alias に `com.github.clj-easy/graal-build-time 1.0.5` 追加
   - `:paths` に `"resources"` を追加
   - Clojure全クラスのビルド時初期化を自動化

2. **Direct Linking 有効化**
   - `build.clj` の `compile-clj` に `:java-opts` を追加
   - `-Dclojure.compiler.direct-linking=true` でvar間接参照を排除
   - `-Dclojure.spec.skip-macros=true` でspec macroをスキップ

3. **native-image.properties 自動検出化**
   - `resources/META-INF/native-image/com.github.boxp/ceeker/native-image.properties` を新規作成
   - `--no-fallback`, `-H:+ReportExceptionStackTraces`, `--initialize-at-run-time=org.jline.nativ` を含む
   - CI/release.yml のフラグを簡素化（JAR内設定を自動検出）

### P1（中効果・低リスク）

4. **ServiceLoader 除外**
   - `native-image.properties` に `javax.sound.*`, `java.net.ContentHandlerFactory` の除外を追加
   - バイナリサイズ削減

5. **JLine native exclusion**
   - `deps.edn` の `org.jline/jline` に `:exclusions [org.jline/jline-native]` 追加
   - バイナリサイズ約1MB削減

6. **reflect-config.json 明示管理**
   - `resources/META-INF/native-image/com.github.boxp/ceeker/reflect-config.json` 新規作成
   - 最小限のエントリ（`java.lang.Class`のみ）

7. **バージョンリソース埋め込み**
   - `resources/CEEKER_VERSION` ファイル追加
   - `native-image.properties` に `-H:IncludeResources=CEEKER_VERSION` 追加
   - `core.clj` に `version` def と `--version` CLIオプション追加

### P2（将来検討）

8. **musl 静的リンク対応**
   - `core.clj` に `musl?` def と `run` マクロ追加
   - `CEEKER_STATIC=true CEEKER_MUSL=true` 環境変数で有効化
   - Alpine Docker配布時にスタックサイズ制限を回避

## CI変更

- `ci.yml` / `release.yml` の `native-image` コマンドフラグを簡素化
- `ci.yml` のpathsに `resources/**` を追加
- `build.clj` の `copy-dir` に `"resources"` 追加

## ロールバック方針

- 各パターンは独立しており、個別にrevertが可能
- native-image.properties を削除し、CI yamlのフラグを復元すれば元に戻せる
