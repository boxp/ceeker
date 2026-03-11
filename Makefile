.PHONY: test test-install-script lint format-check format ci run clean uber

test: test-install-script
	mkdir -p target/test-runtime
	XDG_RUNTIME_DIR=$(CURDIR)/target/test-runtime clojure -M:test

test-install-script:
	sh test/install_script_test.sh

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
