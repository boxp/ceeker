.PHONY: test lint format-check format ci run clean uber

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

uber:
	clojure -T:build uber

clean:
	rm -rf .cpcache target
