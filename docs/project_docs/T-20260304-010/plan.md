# T-20260304-010: native-image + Clojure 主要実装の読解と ceeker 適用パターン抽出

## 調査概要

native-image + Clojure 系の著名 CLI 実装を読解し、ceeker に活かせる実装パターンを抽出した。

### 調査対象リポジトリ

| リポジトリ | 概要 | native-image方式 |
|---|---|---|
| [babashka/babashka](https://github.com/babashka/babashka) | Clojure スクリプティングランタイム | GraalVM native-image (大規模) |
| [clj-kondo/clj-kondo](https://github.com/clj-kondo/clj-kondo) | Clojure リンター | GraalVM native-image (中規模) |
| [borkdude/jet](https://github.com/borkdude/jet) | JSON/EDN/Transit変換CLI | GraalVM native-image (小規模) |
| [babashka/nbb](https://github.com/babashka/nbb) | Node.js上のClojureScript | SCI on Node.js (native-image不使用) |
| [babashka/neil](https://github.com/babashka/neil) | Clojure依存管理CLI | babashkaスクリプト (native-image不使用) |

---

## 1. 調査対象の読解結果

### 1.1 babashka (bb)

**ソースコード参照**: `babashka/babashka` リポジトリ

#### native-image 設定

**ファイル**: `resources/META-INF/native-image/babashka/babashka/native-image.properties`

```properties
ImageName=bb
Args=-H:+ReportExceptionStackTraces \
     -Dborkdude.dynaload.aot=true \
     -Dstdout.encoding=UTF-8 \
     -H:IncludeResources=BABASHKA_VERSION \
     -H:IncludeResources=META-INF/babashka/.* \
     -H:IncludeResources=src/babashka/.* \
     -H:IncludeResources=SCI_VERSION \
     --enable-url-protocols=http,https,jar,unix \
     --enable-all-security-services \
     -H:+JNI \
     --initialize-at-build-time=com.fasterxml.jackson \
     --initialize-at-build-time=java.sql.SQLException \
     --initialize-at-build-time=org.yaml.snakeyaml \
     --initialize-at-run-time=org.postgresql.sspi.SSPIClient \
     --initialize-at-run-time=org.httpkit.client.ClientSslEngineFactory$SSLHolder \
     --initialize-at-run-time=sun.security.ssl.SSLContextImpl,sun.security.ssl.SSLAlgorithmConstraints \
     --features=babashka.impl.CharsetsFeature,clj_easy.graal_build_time.InitClojureClasses
```

**主要パターン**:

1. **`graal-build-time` による全Clojureクラスのビルド時初期化**
   - `--features=clj_easy.graal_build_time.InitClojureClasses` でClojureの `*__init.class` を自動検出・ビルド時初期化
   - 手動で `--initialize-at-build-time=clojure,...` を列挙する必要がなくなる

2. **カスタム GraalVM Feature クラス**
   - `babashka.impl.CharsetsFeature` (Java): 必要な文字セットだけを選択的に追加
   - `AddAllCharsets` を使うと全173文字セット（+5MB）が含まれるため、必要最小限のみ追加

   ```java
   // impl-graal-features/src-java/babashka/impl/CharsetsFeature.java
   public class CharsetsFeature implements Feature {
       private static final String[] EXTRA_CHARSETS = {"IBM437"};
       @Override
       public void beforeAnalysis(BeforeAnalysisAccess access) {
           for (String name : EXTRA_CHARSETS) {
               LocalizationFeature.addCharset(Charset.forName(name));
           }
       }
   }
   ```

3. **環境変数によるフィーチャーフラグ制御**
   - `BABASHKA_FEATURE_YAML`, `BABASHKA_FEATURE_XML` 等で機能の有効/無効を制御
   - `BABASHKA_LEAN=true` で最小構成ビルド
   - `-E` フラグで環境変数をnative-imageに伝播

4. **`borkdude/dynaload` による動的ロード制御**
   - `-Dborkdude.dynaload.aot=true` でvar参照をAOT時に解決
   - Clojureの動的var参照をnative-image互換にする

5. **コンパイルスクリプト** (`script/compile`)
   - 2段階: `script/uberjar` → `script/compile`
   - `--no-fallback`: JVMフォールバック禁止（完全ネイティブ保証）
   - `-march=compatibility`: 古いCPU互換
   - `-O1`: ビルド時間とランタイム速度のバランス
   - musl静的リンク: `--static --libc=musl -H:CCompilerOption=-Wl,-z,stack-size=2097152`
   - glibc動的リンク: `-H:+StaticExecutableWithDynamicLibC`

6. **`*warn-on-reflection* true` の全ファイル適用**
   - `src/babashka/main.clj`, `src/babashka/impl/*.clj` 等の全ソースファイルで設定

7. **JLine3統合**（ceeker同様）
   - `org.jline/jline-terminal-ffm`, `org.jline/jline-terminal`, `org.jline/jline-reader` を使用
   - `org.jline/jline-native` をexclude（バイナリサイズ1MB削減）

8. **ServiceLoader除外**
   - 不要なサービス（javax.sound, java.net.ContentHandlerFactory等）を明示的に除外
   - バイナリサイズ削減

#### ビルドパイプライン

```
deps.edn (依存定義) → Leiningen profiles (feature有効/無効) → uberjar → native-image
```

---

### 1.2 clj-kondo

**ソースコード参照**: `clj-kondo/clj-kondo` リポジトリ

#### native-image 設定

**ファイル**: `resources/META-INF/native-image/clj-kondo/clj-kondo/native-image.properties`

```properties
ImageName=clj-kondo
Args=-J-Dborkdude.dynaload.aot=true \
     --initialize-at-build-time=com.fasterxml.jackson \
     --initialize-at-build-time=com.github.javaparser \
     -H:IncludeResources=clj_kondo/impl/cache/built_in/.* \
     -H:Log=registerResource: \
     --no-server
```

**主要パターン**:

1. **最小限のreflect-config.json**（約20エントリ）
   - `resources/META-INF/native-image/clj-kondo/clj-kondo/reflect-config.json`
   - 3カテゴリに分類:
     - Clojure/JVM基本: `java.lang.Class`, `java.lang.Exception`, `System.nanoTime`
     - SCI要件: `StringWriter`, `StringReader`, `LineNumberingPushbackReader`
     - JavaParser依存: `com.github.javaparser.*` のフィールドアクセス

2. **AOTコンパイル + Direct Linking**
   - `project.clj` uberjarプロファイル:
   ```clojure
   :uberjar {:dependencies [[com.github.clj-easy/graal-build-time "0.1.0"]]
             :jvm-opts ["-Dclojure.compiler.direct-linking=true"
                        "-Dclojure.spec.skip-macros=true"]
             :main clj-kondo.main
             :aot [clj-kondo.main]}
   ```
   - `direct-linking=true`: var間接参照を回避し、起動高速化・リフレクション要件削減

3. **Built-inキャッシュのリソース埋め込み**
   - `script/built-in`: clj-kondo自身で標準ライブラリを解析し、Transit JSON形式で事前計算
   - `io/resource` でnative-image内からも読み込み可能
   - 2層キャッシュ: ディスク → ビルトイン

4. **pprint互換パッチ**（`aaaa-this-has-to-be-first/because-patches.clj`）
   - 名前空間名をアルファベット順で最初にする（AOTの読み込み順に依存）
   - `CLJ_KONDO_NATIVE=true` 環境変数で条件付き適用
   - `clojure.pprint/write` のvar動的解決を静的参照に置き換え

5. **musl静的リンクのスタック問題回避**
   - `src/clj_kondo/main.clj`:
   ```clojure
   (def musl?
     (and (= "true" (System/getenv "CLJ_KONDO_STATIC"))
          (= "true" (System/getenv "CLJ_KONDO_MUSL"))))
   (defmacro run [expr]
     (if musl?
       `(let [v# (volatile! nil)
              f# (fn [] (vreset! v# ~expr))]
          (doto (Thread. nil f# "main") (.start) (.join))
          @v#)
       `(do ~expr)))
   ```
   - `musl?` はマクロ展開時（AOTコンパイル時）に評価
   - musl環境ではメインスレッドのスタックサイズ制限を回避するため別スレッドで実行

6. **SCI（Small Clojure Interpreter）でのhook実行**
   - `src/clj_kondo/impl/hooks.clj`: hooks機能でSCIを使い動的コード評価
   - SCIが使えるJavaクラスを事前登録 → reflect-config.jsonと対応

7. **ライブラリのインライン化**
   - rewrite-clj, tools.reader をフォーク・リネームしてプロジェクト内に取り込み
   - native-image互換性のための独自修正を可能にする

8. **PODプロトコル**（bencode over stdin/stdout）
   - `src/pod/borkdude/clj_kondo.clj`: babashkaからIPC経由で呼び出し可能
   - 同一バイナリがCLIモードとPODモードの両方で動作

---

### 1.3 jet

**ソースコード参照**: `borkdude/jet` リポジトリ

#### native-image 設定

**ファイル**: `resources/META-INF/native-image/borkdude/jet/native-image.properties`

```properties
ImageName=jet
Args=-J-Dclojure.spec.skip-macros=true \
     -J-Dclojure.compiler.direct-linking=true \
     -H:IncludeResources=JET_VERSION \
     --initialize-at-build-time=com.fasterxml.jackson \
     --initialize-at-build-time=org.yaml.snakeyaml.DumperOptions$FlowStyle
```

**主要パターン**:

1. **最小限のreflect-config.json**（2エントリのみ）
   - `java.lang.Class` と `clojure.lang.Util` のみ
   - 小規模CLIでは reflect-config.json をほぼ不要にできる

2. **muslスタック回避の同一パターン**
   - clj-kondoと完全に同じマクロパターン

3. **ネイティブ検出とパッチ適用**
   ```clojure
   (when (System/getProperty "jet.native")
     (require 'jet.patches))
   ```
   - Specter互換性パッチを条件付きロード

4. **5環境並行ビルド**
   - CircleCI: Linux amd64/aarch64, macOS amd64
   - Cirrus CI: macOS aarch64
   - AppVeyor: Windows amd64

---

### 1.4 nbb (Node.js babashka)

**ソースコード参照**: `babashka/nbb` リポジトリ

**方式**: GraalVM native-imageではなく、SCI on Node.js

1. **shadow-cljs による ESM出力**
   - `shadow-cljs.edn`: 約23個のESモジュールに分割
   - `:target :esm`, `:runtime :node`, `:js-options {:js-provider :import}`
2. **起動 ~170ms**、Node.js必須
3. **npm配布**: `"bin": {"nbb": "cli.js"}` で CLI 提供
4. **GraalVM不要**: Node.jsエコシステムとの統合が利点

---

### 1.5 neil

**ソースコード参照**: `babashka/neil` リポジトリ

**方式**: babashkaスクリプト（単一ファイル結合配布）

1. **prelude**: `#!/usr/bin/env bb` + `babashka.deps/add-deps` で依存注入
2. **ソース結合スクリプト**: `dev/babashka/neil/gen_script.clj` で複数ソースを1ファイルに結合
3. **babashka必須**: babashka自体がnative-imageバイナリなので起動は高速（~30-50ms）

---

## 2. ceeker 適用パターン

### パターン 1: graal-build-time ライブラリ導入

| 項目 | 内容 |
|---|---|
| **目的** | Clojure全クラスのビルド時初期化を自動化し、起動時間を短縮 |
| **適用箇所** | `deps.edn` (依存追加) + `build.clj` (uberjar設定) |
| **期待効果** | `--initialize-at-build-time` の手動列挙が不要になる。名前空間追加時の設定漏れを防止 |
| **副作用/トレードオフ** | ビルド依存が1つ増える。ただし`graal-build-time`は広く使われており安定 |
| **実装難易度** | **S** (Small) |
| **参照元** | babashka: `native-image.properties` L38 / clj-kondo: `project.clj` uberjar profile / jet: `project.clj` uberjar profile |

**具体的変更**:

```clojure
;; deps.edn: :paths に "resources" を追加（パターン3/7/8の前提条件でもある）
{:paths ["src" "resources"]
 ;; ...
}
```

```clojure
;; deps.edn の :build alias に graal-build-time を追加
:build {:deps {io.github.clojure/tools.build {:mvn/version "0.10.6"}
               com.github.clj-easy/graal-build-time {:mvn/version "0.1.4"}}
        :ns-default build}
```

`graal-build-time` は GraalVM Feature API (`org.graalvm.nativeimage.hosted.Feature`) を実装しており、uberjar に含まれると native-image が自動的に `InitClojureClasses` Feature を検出・実行する。これにより、Clojure の全 `*__init.class` がビルド時初期化に登録される。

> **前提**: `resources` ディレクトリを classpath に含める必要がある。現状 ceeker の `deps.edn` は `:paths ["src"]` のみなので、`"resources"` を追加する変更が必須。この変更はパターン3/7/8の前提にもなる。

---

### パターン 2: Direct Linking 有効化

| 項目 | 内容 |
|---|---|
| **目的** | Clojureのvar間接参照を排除し、起動速度とイメージサイズを改善 |
| **適用箇所** | `build.clj` (AOTコンパイル時の JVM opts) |
| **期待効果** | 関数呼び出しの間接参照（var deref）が静的リンクに置換される。起動時のvar初期化コスト削減。リフレクション要件も減少 |
| **副作用/トレードオフ** | `alter-var-root` による動的なvar置換が効かなくなる。ceeker は動的なvar書き換えを使っていないため問題なし |
| **実装難易度** | **S** (Small) |
| **参照元** | clj-kondo: `project.clj` `:jvm-opts ["-Dclojure.compiler.direct-linking=true"]` / jet: `native-image.properties` `-J-Dclojure.compiler.direct-linking=true` |

**具体的変更**:

```clojure
;; build.clj
(b/compile-clj {:basis @basis
                :ns-compile '[ceeker.core]
                :class-dir class-dir
                :java-opts ["-Dclojure.compiler.direct-linking=true"
                            "-Dclojure.spec.skip-macros=true"]})
```

---

### パターン 3: native-image.properties による設定の自動検出化

| 項目 | 内容 |
|---|---|
| **目的** | native-image設定をJAR内に含め、コンパイルスクリプトの簡素化と設定の一元管理を実現 |
| **適用箇所** | `resources/META-INF/native-image/com.github.boxp/ceeker/native-image.properties` (新規作成) + `.github/workflows/release.yml` (フラグ削減) |
| **期待効果** | native-imageがJAR内の設定を自動検出。release.yml のフラグが減り保守性向上。設定変更がJAR再ビルドだけで完結 |
| **副作用/トレードオフ** | 設定ファイルとCLIフラグが重複するとコンフリクト。移行時は既存CLIフラグを段階的に削除する |
| **実装難易度** | **S** (Small) |
| **参照元** | babashka: `resources/META-INF/native-image/babashka/babashka/native-image.properties` / clj-kondo: 同構造 / jet: 同構造 |

**具体的変更**:

```properties
# resources/META-INF/native-image/com.github.boxp/ceeker/native-image.properties
ImageName=ceeker
Args=--no-fallback \
     -H:+ReportExceptionStackTraces \
     --initialize-at-run-time=org.jline.nativ
```

```yaml
# .github/workflows/release.yml (簡素化後)
- name: Build native image
  run: |
    native-image \
      -jar target/ceeker.jar \
      -o ceeker-${{ matrix.platform }}
```

> **前提**: パターン1で `deps.edn` に `"resources"` を `:paths` に追加済みであること。native-image は uberjar 内の `META-INF/native-image/{groupId}/{artifactId}/native-image.properties` を自動検出する。

---

### パターン 4: ServiceLoader 除外によるバイナリサイズ削減

| 項目 | 内容 |
|---|---|
| **目的** | 不要なServiceLoaderサービスを除外し、バイナリサイズを削減 |
| **適用箇所** | `native-image.properties` |
| **期待効果** | javax.sound等の未使用サービスを除外。バイナリサイズ数MB削減の可能性 |
| **副作用/トレードオフ** | 除外したサービスが実行時に必要になった場合エラー。ceekerはTUIアプリのためサウンド系は不要 |
| **実装難易度** | **S** (Small) |
| **参照元** | babashka: `native-image.properties` L28-L37 |

**具体的変更**:

```properties
# native-image.properties に追加
-H:ServiceLoaderFeatureExcludeServices=javax.sound.sampled.spi.AudioFileReader \
-H:ServiceLoaderFeatureExcludeServices=javax.sound.midi.spi.MidiFileReader \
-H:ServiceLoaderFeatureExcludeServices=javax.sound.sampled.spi.MixerProvider \
-H:ServiceLoaderFeatureExcludeServices=javax.sound.sampled.spi.FormatConversionProvider \
-H:ServiceLoaderFeatureExcludeServices=javax.sound.sampled.spi.AudioFileWriter \
-H:ServiceLoaderFeatureExcludeServices=javax.sound.midi.spi.MidiDeviceProvider \
-H:ServiceLoaderFeatureExcludeServices=javax.sound.midi.spi.SoundbankReader \
-H:ServiceLoaderFeatureExcludeServices=javax.sound.midi.spi.MidiFileWriter \
-H:ServiceLoaderFeatureExcludeServices=java.net.ContentHandlerFactory
```

---

### パターン 5: JLine native exclusion によるバイナリサイズ削減

| 項目 | 内容 |
|---|---|
| **目的** | JLine3のネイティブライブラリ（jline-native）を除外し、バイナリサイズを約1MB削減 |
| **適用箇所** | `deps.edn` |
| **期待効果** | バイナリサイズ約1MB削減。GraalVM native-imageではjline-nativeは不要（ネイティブコードは別途リンク） |
| **副作用/トレードオフ** | JVM上での開発時実行には影響ない（jline-nativeはオプショナル）。FFM (Foreign Function & Memory) API が使える環境では`jline-terminal-ffm`の方が推奨 |
| **実装難易度** | **S** (Small) |
| **参照元** | babashka: `deps.edn` L57-L59 (`org.jline/jline-terminal {:exclusions [org.jline/jline-native]}`) |

**具体的変更**:

```clojure
;; deps.edn: jline の exclusions 追加
org.jline/jline {:mvn/version "3.26.3"
                 :exclusions [org.jline/jline-native]}
```

---

### パターン 6: musl 静的リンクのスタックサイズ回避

| 項目 | 内容 |
|---|---|
| **目的** | Alpine Linux等のmusl環境で安定動作するための静的リンクバイナリ対応 |
| **適用箇所** | `src/ceeker/core.clj` + `script/compile`（将来的なコンパイルスクリプト導入時） |
| **期待効果** | Dockerコンテナ（Alpine）での配布が可能に。muslのスタックサイズ制限問題を回避 |
| **副作用/トレードオフ** | メインの処理が別スレッドで実行される。スレッド作成のオーバーヘッド（微小）。muslビルドのCI追加が必要 |
| **実装難易度** | **M** (Medium) |
| **参照元** | clj-kondo: `src/clj_kondo/main.clj` `musl?` マクロ / jet: `src/jet/main.clj` 同パターン / babashka: `script/compile` L58-L67 |

**具体的変更**:

```clojure
;; src/ceeker/core.clj
(def ^:private musl?
  (and (= "true" (System/getenv "CEEKER_STATIC"))
       (= "true" (System/getenv "CEEKER_MUSL"))))

(defmacro ^:private run [expr]
  (if musl?
    `(let [v# (volatile! nil)
           f# (fn [] (vreset! v# ~expr))]
       (doto (Thread. nil f# "ceeker-main")
         (.start)
         (.join))
       @v#)
    `(do ~expr)))

(defn -main [& args]
  (run (let [{:keys [options arguments summary errors]}
             (cli/parse-opts args cli-options :in-order true)]
         ;; ... existing logic
         )))
```

---

### パターン 7: reflect-config.json の明示管理

| 項目 | 内容 |
|---|---|
| **目的** | 型ヒント漏れによるランタイムリフレクションエラーを防止するセーフティネット |
| **適用箇所** | `resources/META-INF/native-image/com.github.boxp/ceeker/reflect-config.json` (新規作成) |
| **期待効果** | 現在ceekerは型ヒントが十分だが、将来の依存追加時のリフレクション問題を事前に防止。GraalVM agentで自動生成も可能 |
| **副作用/トレードオフ** | 維持コスト（依存変更時に更新が必要になる場合がある）。ただしceekerの依存は少ないため低リスク |
| **実装難易度** | **S** (Small) |
| **参照元** | clj-kondo: `reflect-config.json` (約20エントリ) / jet: `reflect-config.json` (2エントリ) |

**具体的変更**:

```json
[
  {
    "name": "java.lang.Class",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true,
    "allDeclaredMethods": true,
    "allPublicMethods": true
  }
]
```

> ceekerは既に十分な型ヒントを持つため、最小限のエントリで十分。

---

### パターン 8: バージョンファイルのリソース埋め込み

| 項目 | 内容 |
|---|---|
| **目的** | バイナリにバージョン情報を埋め込み、`ceeker --version` を実装可能にする |
| **適用箇所** | `resources/CEEKER_VERSION` (新規) + `native-image.properties` + `src/ceeker/core.clj` |
| **期待効果** | ビルド時にバージョンが確定しバイナリに含まれる。CI/CDでのバージョン管理が一元化される |
| **副作用/トレードオフ** | バージョンファイルの更新をリリースフローに組み込む必要あり |
| **実装難易度** | **S** (Small) |
| **参照元** | babashka: `resources/BABASHKA_VERSION` + `-H:IncludeResources=BABASHKA_VERSION` / clj-kondo: `resources/CLJ_KONDO_VERSION` |

**具体的変更**:

```properties
# native-image.properties に追加
-H:IncludeResources=CEEKER_VERSION
```

```clojure
;; src/ceeker/core.clj
(def version
  (str/trim (slurp (io/resource "CEEKER_VERSION"))))
```

---

## 3. 優先度付き導入ロードマップ

### P0: 即座に導入すべき（低リスク・高効果）

| # | パターン | 理由 |
|---|---|---|
| 1 | **graal-build-time 導入** | 全調査対象が採用。設定漏れ防止。変更量最小 |
| 2 | **Direct Linking 有効化** | 1行の追加で起動速度・バイナリサイズ改善。全調査対象が採用 |
| 3 | **native-image.properties 導入** | release.yml の簡素化。設定の一元管理。全調査対象が採用 |

> **まず最初にこの3項目を入れるべき**。いずれもdeps.edn/build.clj/新規ファイル追加のみで、既存コードの変更が不要。

### P1: 次のリリースで導入（中効果・低リスク）

| # | パターン | 理由 |
|---|---|---|
| 4 | **ServiceLoader除外** | バイナリサイズ削減。コピー&ペーストで導入可能 |
| 5 | **JLine native exclusion** | バイナリ1MB削減。babashkaで実証済み |
| 7 | **reflect-config.json 明示管理** | セーフティネット。最小限のエントリで開始 |
| 8 | **バージョンリソース埋め込み** | `--version` 対応。ユーザビリティ向上 |

### P2: 将来検討（特定ユースケース時）

| # | パターン | 理由 |
|---|---|---|
| 6 | **musl静的リンク対応** | Alpine Docker配布が必要になった時点で導入 |

---

## 4. リスク・保留事項

### リスク

| リスク | 対策 |
|---|---|
| `graal-build-time` のバージョン互換性 | GraalVM 21/25 両方で動作確認されている（babashka/clj-kondo実績） |
| Direct Linking で `alter-var-root` が効かない | ceekerは動的var書き換えを使っていないため問題なし |
| JLine native exclusion でJVM開発時に問題 | JVM実行時は jline-native がなくても動作する（graceful fallback） |

### 保留事項

| 項目 | 理由 |
|---|---|
| SCI 統合 | ceekerは現時点でスクリプト評価機能を持たない。将来的にプラグイン機能が必要になった場合に検討 |
| POD プロトコル | babashka連携が必要になった場合に検討 |
| ライブラリのインライン化 | ceekerの依存は少なく、現時点では不要 |
| pprint パッチ | ceekerは`clojure.pprint`を直接使用していないため不要 |
| カスタムFeatureクラス | CharsetsFeature のような追加文字セット対応。現時点のceekerには不要 |

---

## 5. 参照元一覧

### babashka (v1.12.216, commit `859a5205`)
- [`resources/META-INF/native-image/babashka/babashka/native-image.properties`](https://github.com/babashka/babashka/blob/v1.12.216/resources/META-INF/native-image/babashka/babashka/native-image.properties) - native-image全設定
- [`impl-graal-features/src-java/babashka/impl/CharsetsFeature.java`](https://github.com/babashka/babashka/blob/v1.12.216/impl-graal-features/src-java/babashka/impl/CharsetsFeature.java) - カスタムFeatureクラス
- [`script/compile`](https://github.com/babashka/babashka/blob/v1.12.216/script/compile) - native-imageコンパイルスクリプト
- [`script/uberjar`](https://github.com/babashka/babashka/blob/v1.12.216/script/uberjar) - uberjar生成スクリプト（フィーチャーフラグ制御）
- [`deps.edn`](https://github.com/babashka/babashka/blob/v1.12.216/deps.edn) - 依存関係（JLine exclusion等）
- [`src/babashka/main.clj`](https://github.com/babashka/babashka/blob/v1.12.216/src/babashka/main.clj) - エントリポイント（`:gen-class`, `*warn-on-reflection*`）

### clj-kondo (v2024.11.14, commit `1eef5c59`)
- [`resources/META-INF/native-image/clj-kondo/clj-kondo/native-image.properties`](https://github.com/clj-kondo/clj-kondo/blob/v2024.11.14/resources/META-INF/native-image/clj-kondo/clj-kondo/native-image.properties) - native-image設定
- [`resources/META-INF/native-image/clj-kondo/clj-kondo/reflect-config.json`](https://github.com/clj-kondo/clj-kondo/blob/v2024.11.14/resources/META-INF/native-image/clj-kondo/clj-kondo/reflect-config.json) - リフレクション設定
- [`project.clj`](https://github.com/clj-kondo/clj-kondo/blob/v2024.11.14/project.clj) - ビルド設定（graal-build-time, direct-linking, AOT）
- [`script/compile`](https://github.com/clj-kondo/clj-kondo/blob/v2024.11.14/script/compile) - コンパイルスクリプト（静的リンク対応）
- [`src/clj_kondo/main.clj`](https://github.com/clj-kondo/clj-kondo/blob/v2024.11.14/src/clj_kondo/main.clj) - muslスタック回避マクロ
- [`src/aaaa_this_has_to_be_first/because_patches.clj`](https://github.com/clj-kondo/clj-kondo/blob/v2024.11.14/src/aaaa_this_has_to_be_first/because_patches.clj) - pprint互換パッチ
- [`src/clj_kondo/impl/hooks.clj`](https://github.com/clj-kondo/clj-kondo/blob/v2024.11.14/src/clj_kondo/impl/hooks.clj) - SCI統合（クラス登録）
- [`src/clj_kondo/impl/cache.clj`](https://github.com/clj-kondo/clj-kondo/blob/v2024.11.14/src/clj_kondo/impl/cache.clj) - リソースバンドルとキャッシュ
- [`src/pod/borkdude/clj_kondo.clj`](https://github.com/clj-kondo/clj-kondo/blob/v2024.11.14/src/pod/borkdude/clj_kondo.clj) - PODプロトコル

### jet (v0.7.27, commit `04cac359`)
- [`resources/META-INF/native-image/borkdude/jet/native-image.properties`](https://github.com/borkdude/jet/blob/v0.7.27/resources/META-INF/native-image/borkdude/jet/native-image.properties) - 最小設定例
- [`resources/META-INF/native-image/borkdude/jet/reflect-config.json`](https://github.com/borkdude/jet/blob/v0.7.27/resources/META-INF/native-image/borkdude/jet/reflect-config.json) - 最小リフレクション設定（2エントリ）
- [`project.clj`](https://github.com/borkdude/jet/blob/v0.7.27/project.clj) - ビルド設定
- [`script/compile`](https://github.com/borkdude/jet/blob/v0.7.27/script/compile) - コンパイルスクリプト
- [`src/jet/main.clj`](https://github.com/borkdude/jet/blob/v0.7.27/src/jet/main.clj) - muslスタック回避

### nbb (v1.4.206, commit `f6de710a`)
- [`shadow-cljs.edn`](https://github.com/babashka/nbb/blob/v1.4.206/shadow-cljs.edn) - SCI on Node.js のビルド設定
- [`src/nbb/core.cljs`](https://github.com/babashka/nbb/blob/v1.4.206/src/nbb/core.cljs) - SCIインタプリタ初期化

### neil (v0.3.69, commit `fa793214`)
- [`prelude`](https://github.com/babashka/neil/blob/v0.3.69/prelude) - babashkaスクリプトの依存注入
- [`dev/babashka/neil/gen_script.clj`](https://github.com/babashka/neil/blob/v0.3.69/dev/babashka/neil/gen_script.clj) - ソース結合スクリプト
