.PHONY: test lint format-check format ci run clean

test:
	clojure -M:test

lint:
	clojure -M:lint

format-check:
	clojure -M:format-check

format:
	clojure -M:format-fix

ci: format-check lint test

run:
	clojure -M:run

clean:
	rm -rf .cpcache target
