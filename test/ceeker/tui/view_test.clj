(ns ceeker.tui.view-test
  (:require [ceeker.tui.view :as view]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; -- char-display-width tests --

(deftest test-char-display-width-ascii
  (testing "ASCII characters have width 1"
    (is (= 1 (#'view/char-display-width \a)))
    (is (= 1 (#'view/char-display-width \Z)))
    (is (= 1 (#'view/char-display-width \space)))
    (is (= 1 (#'view/char-display-width \0)))))

(deftest test-char-display-width-cjk
  (testing "CJK characters have width 2"
    (is (= 2 (#'view/char-display-width \u4E00)))   ; 一
    (is (= 2 (#'view/char-display-width \u3042)))   ; あ
    (is (= 2 (#'view/char-display-width \u30A2)))   ; ア
    (is (= 2 (#'view/char-display-width \u5168)))   ; 全
    (is (= 2 (#'view/char-display-width \uFF01))))) ; ！ (fullwidth)

;; -- str-display-width tests --

(deftest test-str-display-width
  (testing "ASCII-only string"
    (is (= 5 (#'view/str-display-width "hello"))))
  (testing "CJK-only string"
    (is (= 6 (#'view/str-display-width "日本語"))))
  (testing "mixed ASCII and CJK"
    (is (= 7 (#'view/str-display-width "ab日本c"))))
  (testing "empty string"
    (is (= 0 (#'view/str-display-width ""))))
  (testing "nil returns 0"
    (is (= 0 (#'view/str-display-width nil)))))

;; -- substr-by-width tests --

(deftest test-substr-by-width-ascii
  (testing "ASCII within limit"
    (is (= "hello" (#'view/substr-by-width "hello" 10))))
  (testing "ASCII exceeding limit"
    (is (= "hel" (#'view/substr-by-width "hello" 3)))))

(deftest test-substr-by-width-cjk
  (testing "CJK within limit"
    (is (= "日本" (#'view/substr-by-width "日本" 4))))
  (testing "CJK at exact limit"
    (is (= "日本" (#'view/substr-by-width "日本語" 4))))
  (testing "CJK one column short of next char"
    (is (= "日" (#'view/substr-by-width "日本語" 3)))))

(deftest test-substr-by-width-mixed
  (testing "mixed text truncation"
    (is (= "ab日" (#'view/substr-by-width "ab日本" 4))))
  (testing "nil returns empty"
    (is (= "" (#'view/substr-by-width nil 10)))))

(deftest test-substr-by-width-boundary
  (testing "max-width 0 returns empty"
    (is (= "" (#'view/substr-by-width "hello" 0))))
  (testing "negative max-width returns empty"
    (is (= "" (#'view/substr-by-width "hello" -1))))
  (testing "max-width 1 with CJK returns empty"
    (is (= "" (#'view/substr-by-width "日本" 1)))))

;; -- truncate-by-width tests --

(deftest test-truncate-by-width-no-truncation
  (testing "short string unchanged"
    (is (= "hello" (#'view/truncate-by-width "hello" 10))))
  (testing "CJK fits exactly"
    (is (= "日本" (#'view/truncate-by-width "日本" 4)))))

(deftest test-truncate-by-width-with-truncation
  (testing "ASCII truncated with ellipsis"
    (is (= "hel…" (#'view/truncate-by-width "hello world" 4))))
  (testing "CJK truncated with ellipsis"
    (is (= "日…" (#'view/truncate-by-width "日本語テスト" 4))))
  (testing "nil returns empty"
    (is (= "" (#'view/truncate-by-width nil 10)))))

(deftest test-truncate-by-width-boundary
  (testing "max-width 0 returns empty"
    (is (= "" (#'view/truncate-by-width "hello" 0))))
  (testing "negative max-width returns empty"
    (is (= "" (#'view/truncate-by-width "hello" -1)))))

;; -- wrap-by-width tests --

(deftest test-wrap-by-width-no-wrap
  (testing "short ASCII fits in one line"
    (is (= ["hello"] (#'view/wrap-by-width "hello" 10))))
  (testing "empty string"
    (is (= [""] (#'view/wrap-by-width "" 10))))
  (testing "nil string"
    (is (= [""] (#'view/wrap-by-width nil 10)))))

(deftest test-wrap-by-width-ascii
  (testing "ASCII wraps at width boundary"
    (is (= ["abcde" "fghij"]
           (#'view/wrap-by-width "abcdefghij" 5)))))

(deftest test-wrap-by-width-cjk
  (testing "CJK wraps respecting display width"
    (is (= ["日本" "語テ" "スト"]
           (#'view/wrap-by-width "日本語テスト" 4)))))

(deftest test-wrap-by-width-mixed
  (testing "mixed text wraps correctly"
    (is (= ["ab日" "本cd"]
           (#'view/wrap-by-width "ab日本cd" 4)))))

(deftest test-wrap-by-width-boundary
  (testing "max-width 1 with ASCII"
    (is (= ["a" "b" "c"]
           (#'view/wrap-by-width "abc" 1))))
  (testing "max-width 0 clamps to 1"
    (is (= ["a" "b"]
           (#'view/wrap-by-width "ab" 0))))
  (testing "CJK char wider than max-width placed on own line"
    (let [lines (#'view/wrap-by-width "日" 1)]
      (is (= 1 (count lines)))
      (is (= "日" (first lines))))))

;; -- card-message-lines tests --

(deftest test-card-message-lines-single-line
  (testing "short message produces single card line"
    (let [lines (#'view/card-message-lines "hello" 20 "" "")]
      (is (= 1 (count lines)))
      (is (str/starts-with? (first lines) "  │ "))
      (is (str/includes? (first lines) "hello")))))

(deftest test-card-message-lines-multiline-wrap
  (testing "long message wraps into multiple card lines"
    (let [lines (#'view/card-message-lines
                 "abcdefghijklmnopqrst" 10 "" "")]
      (is (= 2 (count lines)))
      (is (every? #(str/starts-with? % "  │ ") lines)))))

(deftest test-card-message-lines-cjk-wrap
  (testing "CJK message wraps by display width"
    (let [lines (#'view/card-message-lines
                 "日本語のテスト文字列です" 10 "" "")]
      (is (> (count lines) 1))
      (is (every? #(str/starts-with? % "  │ ") lines)))))

(deftest test-card-message-lines-cjk-width-check
  (testing "CJK message lines stay within content-width"
    (let [cw 12
          msg "日本語のテスト文字列ですがとても長い"
          lines (#'view/card-message-lines msg cw "" "")]
      (doseq [line lines]
        (let [content (subs line 4)]
          (is (<= (#'view/str-display-width content) cw)
              (str "Line overflows: " (pr-str content))))))))

(deftest test-card-message-lines-max-lines
  (testing "message is limited to max-card-message-lines"
    (let [long-msg (apply str (repeat 200 "a"))
          lines (#'view/card-message-lines long-msg 10 "" "")]
      (is (= view/max-card-message-lines (count lines)))))
  (testing "truncated last line ends with ellipsis"
    (let [long-msg (apply str (repeat 200 "a"))
          lines (#'view/card-message-lines long-msg 10 "" "")
          last-line (last lines)]
      (is (str/ends-with? last-line "…")))))

(deftest test-card-message-lines-newline-normalized
  (testing "newlines in message are replaced with spaces"
    (let [lines (#'view/card-message-lines
                 "line1\nline2" 30 "" "")]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "line1 line2")))))

(deftest test-card-message-lines-nil-message
  (testing "nil message produces single empty card line"
    (let [lines (#'view/card-message-lines nil 20 "" "")]
      (is (= 1 (count lines)))
      (is (str/starts-with? (first lines) "  │ ")))))

;; -- format-session-card integration tests --

(defn- make-session [msg]
  {:session-id "test123"
   :agent-type :claude-code
   :agent-status :running
   :cwd "/home/user/project"
   :last-message msg
   :last-updated "2025-01-01T12:34:56.000Z"})

(defn- strip-ansi [s]
  (str/replace s #"\033\[[0-9;]*m" ""))

(deftest test-format-session-card-single-line
  (testing "card with short message has correct structure"
    (let [card (#'view/format-session-card
                (make-session "short msg") false 0 60)
          lines (str/split card #"\n")]
      (is (str/includes? (first lines) "┌"))
      (is (str/includes? (last lines) "└"))
      (is (some #(str/includes? % "short msg") lines)))))

(deftest test-format-session-card-cjk-long-message
  (testing "CJK long message stays within card width"
    (let [width 40
          msg "日本語の長いメッセージがカード表示で枠を貫通しないことを確認するテスト"
          card (#'view/format-session-card
                (make-session msg) false 0 width)
          lines (str/split card #"\n")
          content-width (- width 4)]
      (is (str/includes? (first lines) "┌"))
      (is (str/includes? (last lines) "└"))
      (doseq [line lines]
        (let [plain (strip-ansi line)]
          (when (str/includes? plain "│")
            (let [after-border (subs plain 4)]
              (is (<= (#'view/str-display-width after-border)
                      content-width)
                  (str "Line overflows card: "
                       (pr-str after-border))))))))))

(deftest test-format-session-card-multiline-message
  (testing "message with newlines renders correctly"
    (let [msg "first line\nsecond line\nthird line"
          card (#'view/format-session-card
                (make-session msg) false 0 60)
          lines (str/split card #"\n")
          plain-lines (mapv strip-ansi lines)]
      (is (str/includes? (first plain-lines) "┌"))
      (is (str/includes? (last plain-lines) "└"))
      (is (every? #(or (str/includes? % "┌")
                       (str/includes? % "│")
                       (str/includes? % "└"))
                  plain-lines))
      (is (some #(str/includes? % "first line second line")
                plain-lines)))))

;; -- normalize-message tests --

(deftest test-normalize-message
  (testing "newlines replaced with spaces"
    (is (= "line1 line2" (#'view/normalize-message "line1\nline2"))))
  (testing "carriage-return-newline replaced"
    (is (= "a b c" (#'view/normalize-message "a\r\nb\r\nc"))))
  (testing "nil returns empty string"
    (is (= "" (#'view/normalize-message nil))))
  (testing "no newlines unchanged"
    (is (= "hello world" (#'view/normalize-message "hello world")))))

;; -- pad-to-width tests --

(deftest test-pad-to-width
  (testing "pads short ASCII string"
    (let [result (#'view/pad-to-width "hi" 5)]
      (is (= 5 (#'view/str-display-width result)))
      (is (str/starts-with? result "hi"))))
  (testing "pads CJK string accounting for display width"
    (let [result (#'view/pad-to-width "日本" 6)]
      (is (= 6 (#'view/str-display-width result)))
      (is (str/starts-with? result "日本"))))
  (testing "string already at target width unchanged"
    (is (= "hello" (#'view/pad-to-width "hello" 5))))
  (testing "string wider than target unchanged"
    (is (= "hello world" (#'view/pad-to-width "hello world" 5)))))

;; -- format-session-line tests (table display) --

(deftest test-format-session-line-no-newlines
  (testing "table row with newlines in message renders as single line"
    (let [session (make-session "line1\nline2\nline3")
          line (#'view/format-session-line session false 0)
          plain (strip-ansi line)]
      (is (not (str/includes? plain "\n"))
          "Table row must not contain newline characters")
      (is (str/includes? plain "line1 line2 line3")))))

(deftest test-format-session-line-crlf
  (testing "table row with CRLF in message renders as single line"
    (let [session (make-session "first\r\nsecond")
          line (#'view/format-session-line session false 0)
          plain (strip-ansi line)]
      (is (not (str/includes? plain "\n")))
      (is (str/includes? plain "first second")))))

(deftest test-format-session-line-cjk-message
  (testing "table row with CJK message truncates by display width"
    (let [session (make-session "日本語の長いメッセージがテーブル表示で適切に切り詰められることを確認")
          line (#'view/format-session-line session false 0)
          plain (strip-ansi line)]
      (is (not (str/includes? plain "\n")))
      (is (str/includes? plain "…")
          "Long CJK message should be truncated with ellipsis"))))

(deftest test-format-session-line-short-message
  (testing "table row with short message has no truncation"
    (let [session (make-session "short msg")
          line (#'view/format-session-line session false 0)
          plain (strip-ansi line)]
      (is (not (str/includes? plain "\n")))
      (is (str/includes? plain "short msg")))))

(deftest test-format-session-line-nil-message
  (testing "table row with nil message renders without error"
    (let [session (make-session nil)
          line (#'view/format-session-line session false 0)
          plain (strip-ansi line)]
      (is (not (str/includes? plain "\n"))))))
