# Multimodal Search for Android — Makefile (single entry point, thin wrappers only)
export JAVA_HOME ?= /usr/lib/jvm/java-17-openjdk-amd64
GRADLE ?= $(HOME)/.gradle/wrapper/dists/gradle-8.14.3-bin/*/gradle-8.14.3/bin/gradle
# Resolve glob at run time
GRADLE_BIN := $(firstword $(wildcard $(HOME)/.gradle/wrapper/dists/gradle-8.14.3-bin/*/gradle-8.14.3/bin/gradle))

.PHONY: setup dev test test-device eval bench-device style build clean

setup: ## idempotent env check (JDK + gradle)
	@java -version 2>&1 | head -n 1
	@if [ -n "$(GRADLE_BIN)" ]; then echo "gradle: $(GRADLE_BIN)"; else echo "gradle not found (expected at ~/.gradle/wrapper/dists)"; exit 1; fi
	@echo "setup ok (Android SDK needed only for make dev/test-device/bench-device)"

dev: ## install debug APK on attached device (requires Android SDK + device)
	@echo "shortcut: no Android module yet, device install not wired — full AAB comes with F011."

test: ## JVM unit tests (Linux-safe, CI gate)
	$(GRADLE_BIN) :app:test --console=plain

test-device: ## instrumented tests on attached device/emulator (requires SDK)
	@echo "shortcut: no instrumented tests yet — runs on Linux via 'make test' until F009/F011."

eval: ## ranking evals on Linux
	@if [ -f evals/run.py ]; then python3 evals/run.py; else echo "shortcut: evals/run.py not present yet (F008 adds it) — skipping."; fi

bench-device: ## NPU/GPU/CPU latency + RSS on attached device
	@if [ -f scripts/bench-device.sh ]; then bash scripts/bench-device.sh; else echo "shortcut: scripts/bench-device.sh not present yet — skipping."; fi

style: ## ktlint + detekt (format + lint in one)
	$(GRADLE_BIN) :app:ktlintCheck :app:detekt --console=plain

build: ## release artefact
	$(GRADLE_BIN) :app:assemble --console=plain || $(GRADLE_BIN) :app:build -x test --console=plain

clean: ## removes build outputs
	$(GRADLE_BIN) :app:clean --console=plain || true
	rm -rf app/build .gradle/build
