# T-20260305-005: ceeker 全体リファクタ（KISS/YAGNI・デッドコード削減・collection演算最適化）

## 1. Dead Code 削除

### 1-1. `display-mode-label` 重複排除 (app.clj + view.clj)
- `app.clj:115-121` と `view.clj:240-245` に同一の private `display-mode-label` が存在
- **対応**: `view.clj` の関数を public に昇格し、`app.clj` から参照。app.clj 側を削除

### 1-2. `render` 5-arity 未使用オーバーロード削除 (view.clj)
- `view.clj:325-326` の `[sessions sel fs sm? sb]` オーバーロードは全コード・テストで未使用
- **対応**: 5-arity を削除

## 2. KISS/YAGNI 簡素化

### 2-1. `next-in-cycle` 簡素化 (filter.clj)
- `keep-indexed` + fallback の冗長パターンを `.indexOf` に置換
- Before: `(or (first (keep-indexed ...)) -1)` → After: `(.indexOf cycle-vec current)`

### 2-2. `detect-codex-state` 構造統一 (capture.clj)
- Claude 検出と同じ `or` チェイン + ヘルパー関数パターンに統一
- `cond` + `:else nil` → `or` + 抽出関数 (detect-codex-running/waiting/idle)

### 2-3. `wait-for-input` 簡素化 (app.clj)
- `cond` with single-branch + `:else` → `or` に置換

## 3. Collection 演算置換

### 3-1. `str-display-width` → transduce (view.clj)
- `(reduce + 0 (map char-display-width plain))` → `(transduce (map char-display-width) + 0 (strip-ansi s))`
- 中間シーケンスの生成を回避

### 3-2. `supersede-old-sessions` → reduce-kv 最適化 (store.clj)
- 初期値 `{}` → `sessions` に変更、非該当エントリの不要な assoc を除去

### 3-3. `mark-stale-sessions` → update-vals (store.clj)
- `reduce-kv` + conditional assoc → `update-vals` (Clojure 1.11+)
- 条件がキーに依存しないため `update-vals` が適切

### 3-4. `apply-stale-pred` → reduce-kv 最適化 (store.clj)
- 初期値 `{}` → `sessions` に変更

### 3-5. `(set (map ...))` → `(into #{} (map ...) ...)` (pane.clj)
- 中間シーケンス回避のため transducer 形式に変換

### 3-6. `find-agent-in-tree` → reduce + reduced (pane.clj)
- `map` + 2 回の `some` → 単一パス `reduce` + `reduced` で短絡評価

## リスクと確認観点
- `update-vals` は Clojure 1.11+ 必要 → deps.edn で 1.12.0 使用中 ✅
- `render` 5-arity 削除はテスト・プロダクションコードどちらでも未使用を確認済み
- 全変更は振る舞い互換を維持
