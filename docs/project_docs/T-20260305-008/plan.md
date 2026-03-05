# T-20260305-008: ceeker hotfix - a/s キーでクラッシュする不具合修正

## 原因分析

PR #35 (refactor: KISS/YAGNI simplification) で `next-in-cycle` 関数が
Java interop `.indexOf` を使用する実装にリファクタされた。

```clojure
;; 変更後 (PR #35) — クラッシュする
(let [idx (.indexOf cycle-vec current)]
  ...)
```

`PersistentVector` は `java.util.List` を実装しているため JVM 上では動作するが、
GraalVM native-image ではリフレクション設定が不足しており
`No matching method indexOf` でクラッシュする。

## 修正内容

### 1. `src/ceeker/tui/filter.clj` — `next-in-cycle` 関数

`.indexOf` (Java interop) を `reduce-kv` (純粋 Clojure) に置換。
native-image でリフレクション不要。

### 2. `test/ceeker/tui/filter_test.clj` — ユニットテスト追加

- `test-toggle-agent-filter-full-cycle`: agent フィルタの完全サイクルテスト
- `test-toggle-status-filter-full-cycle`: status フィルタの完全サイクルテスト

### 3. `test/ceeker/tui/app_test.clj` — ユニットテスト追加

- `test-filter-key-a-toggles-agent`: `a` キーで agent フィルタが切り替わること
- `test-filter-key-s-toggles-status`: `s` キーで status フィルタが切り替わること
- `test-filter-key-a-full-cycle-no-crash`: `a` キー完全サイクルでクラッシュしないこと
- `test-filter-key-s-full-cycle-no-crash`: `s` キー完全サイクルでクラッシュしないこと

### 4. `e2e/native-e2e.sh` — E2E テスト追加

- `test_tui_filter_keys`: native-image バイナリで `a` → `a` → `s` → `s` → `q` を
  tmux 経由で入力し、クラッシュせずに正常終了することを検証。

## 非スコープ

- UI 仕様変更なし
- 新機能追加なし
- reflect-config.json 追加なし（純粋 Clojure で解決）
