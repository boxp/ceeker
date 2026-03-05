# T-20260304-017: card表示の選択ハイライトを1行目限定に調整

## 背景
card表示で選択中カード全体が `ansi-reverse` でハイライトされ、視認性が過剰になっていた。
table表示と統一感を持たせるため、1行目（session id / agent type / status）のみハイライトに変更する。

## 変更内容

### `src/ceeker/tui/view.clj` - `format-session-card` 関数

**Before**: `sel-start`/`sel-end` を全行（line1, line2, msg-lines, line-end）に適用
**After**: `sel-start`/`sel-end` を `card-line1` のみに適用し、他の行には空文字を渡す

具体的には:
- `line2`: `(card-line2 session "" "" content-width)` — ハイライトなし
- `msg-lines`: `(card-message-lines ... "" "")` — ハイライトなし
- `line-end`: `"  └─"` — 直接文字列（ハイライトなし）

## 影響範囲
- card表示の選択時表示のみ変更
- table表示は影響なし
- 未選択カードの表示は影響なし
