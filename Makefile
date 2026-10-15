# Multimodal Search for Android — Makefile (single entry point, thin wrappers only)
# JAVA_HOME falls back to the first candidate shipping bin/javac (default java-21 here is JRE-only and breaks fresh compiles)
_JAVAC_HOME := $(shell dirname $$(dirname $$(readlink -f $$(which javac 2>/dev/null))) 2>/dev/null)
export JAVA_HOME ?= $(firstword $(foreach d,/usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64 $(_JAVAC_HOME),$(if $(wildcard $(d)/bin/javac),$(d),)))
GRADLE ?= ./gradlew

.PHONY: setup dev test test-device eval bench-device style build clean

setup: ## idempotent env check (JDK + gradle wrapper)
	@java -version 2>&1 | head -n 1
	@if [ -x ./gradlew ]; then ./gradlew --version | head -n 6; else echo "gradlew missing — run: gradle wrapper"; exit 1; fi
	@echo "setup ok (Android SDK needed only for make dev/test-device/bench-device)"

dev: ## install debug APK on attached device (requires Android SDK + device)
	@echo "shortcut: no Android module yet, device install not wired — full AAB comes with F011."

test: ## JVM unit tests (Linux-safe, CI gate)
	$(GRADLE) :app:test --console=plain

test-device: ## instrumented tests on attached device/emulator (requires SDK)
	@echo "shortcut: no instrumented tests yet — runs on Linux via 'make test' until F009/F011."

eval: ## ranking evals on Linux
	python3 evals/run.py

bench-device: ## NPU/GPU/CPU latency + RSS on attached device
	@if [ -f scripts/bench-device.sh ]; then bash scripts/bench-device.sh; else echo "shortcut: scripts/bench-device.sh not present yet — skipping."; fi

style: ## ktlint + detekt (format + lint in one)
	$(GRADLE) :app:ktlintCheck :app:detekt --console=plain

build: ## release artefact
	$(GRADLE) :app:assemble --console=plain || $(GRADLE) :app:build -x test --console=plain

clean: ## removes build outputs
	$(GRADLE) :app:clean --console=plain || true
	rm -rf app/build .gradle/build
