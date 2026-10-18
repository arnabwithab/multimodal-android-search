# Multimodal Search for Android — Makefile (single entry point, thin wrappers only)
# JAVA_HOME falls back to the first candidate shipping bin/javac (default java-21 here is JRE-only and breaks fresh compiles)
_JAVAC_HOME := $(shell dirname $$(dirname $$(readlink -f $$(which javac 2>/dev/null))) 2>/dev/null)
export JAVA_HOME ?= $(firstword $(foreach d,/usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64 $(_JAVAC_HOME),$(if $(wildcard $(d)/bin/javac),$(d),)))
GRADLE ?= ./gradlew
# Android SDK: ~/android-sdk is the bootstrap location (see `make setup`); env overrides win.
export ANDROID_HOME ?= $(HOME)/android-sdk
export ANDROID_SDK_ROOT ?= $(HOME)/android-sdk
export PATH := $(ANDROID_SDK_ROOT)/platform-tools:$(ANDROID_SDK_ROOT)/cmdline-tools/latest/bin:$(PATH)
ADB ?= $(ANDROID_SDK_ROOT)/platform-tools/adb
DEVICE ?= 8316b289
APP_ID ?= com.filesearch.android

.PHONY: setup dev test test-device eval bench-device style build clean

setup: ## idempotent env check (JDK + gradle wrapper + Android SDK)
	@java -version 2>&1 | head -n 1
	@if [ -x ./gradlew ]; then ./gradlew --version | head -n 6; else echo "gradlew missing — run: gradle wrapper"; exit 1; fi
	@if [ -x "$(ANDROID_SDK_ROOT)/cmdline-tools/latest/bin/sdkmanager" ]; then \
		yes | "$(ANDROID_SDK_ROOT)/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true; \
		"$(ANDROID_SDK_ROOT)/cmdline-tools/latest/bin/sdkmanager" --install "platform-tools" "platforms;android-36" "build-tools;36.0.0" 2>&1 | tail -n 2; \
	else \
		echo "no cmdline-tools at $(ANDROID_SDK_ROOT) — bootstrapping..."; \
		mkdir -p "$(ANDROID_SDK_ROOT)/cmdline-tools" /tmp/opencode; \
		curl -fSL -o /tmp/opencode/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip; \
		unzip -q -o /tmp/opencode/cmdtools.zip -d "$(ANDROID_SDK_ROOT)/cmdline-tools"; \
		mv "$(ANDROID_SDK_ROOT)/cmdline-tools/cmdline-tools" "$(ANDROID_SDK_ROOT)/cmdline-tools/latest"; \
		yes | "$(ANDROID_SDK_ROOT)/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true; \
		"$(ANDROID_SDK_ROOT)/cmdline-tools/latest/bin/sdkmanager" --install "platform-tools" "platforms;android-36" "build-tools;36.0.0" 2>&1 | tail -n 2; \
	fi
	@test -d "$(ANDROID_SDK_ROOT)/platforms/android-36" || { echo "setup FAILED: platforms;android-36 missing"; exit 1; }
	@echo "setup ok (sdk=$(ANDROID_SDK_ROOT))"

dev: ## install debug APK on attached device (adb) + launch
	$(GRADLE) :android:assembleDebug --console=plain
	@if [ -n "$(DEVICE)" ]; then D="-s $(DEVICE)"; else D=""; fi; \
	$(ADB) $$D install -r android/build/outputs/apk/debug/android-debug.apk && \
	$(ADB) $$D shell am start -n $(APP_ID)/.MainActivity

test: ## JVM unit tests (Linux-safe, CI gate)
	$(GRADLE) :app:test --console=plain

test-device: ## instrumented tests on attached device/emulator (requires SDK)
	@if ls android/src/androidTest -R 2>/dev/null | grep -q '\.kt$$'; then \
		$(GRADLE) :android:connectedAndroidTest --console=plain; \
	else \
		echo "test-device: no instrumented tests yet (android/src/androidTest empty) — add a smoke test there, then this runs connectedAndroidTest."; \
	fi

eval: ## ranking evals on Linux
	python3 evals/run.py

bench-device: ## NPU/GPU/CPU latency + RSS on attached device
	bash scripts/bench-device.sh

style: ## ktlint + detekt (format + lint in one)
	$(GRADLE) :app:ktlintCheck :app:detekt --console=plain

build: ## release artefact
	$(GRADLE) :app:assemble --console=plain || $(GRADLE) :app:build -x test --console=plain

clean: ## removes build outputs
	$(GRADLE) :app:clean :android:clean --console=plain || true
	rm -rf app/build android/build .gradle/build
