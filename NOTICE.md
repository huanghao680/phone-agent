# NOTICE — third-party components

This project bundles and redistributes the following third-party components.

## terminal-emulator / terminal-view (vendored source)

- Source: https://github.com/termux/termux-app at v0.118.1
  (modules `terminal-emulator`, `terminal-view`, including `src/main/jni/termux.c`)
- License: Apache License 2.0 — see NOTICE-termux-app.md for the upstream license text.
- Modified: Gradle build scripts rewritten for this project's toolchain;
  unused test sources removed; ABI filter reduced to arm64-v8a; added a
  null-renderer guard in `TerminalView.updateSize()` so early layout passes
  cannot NPE before the host app calls `setTextSize()`.

## Node.js runtime (nodejs + dependency packages)

- Source: Termux package repository builds of `nodejs` (v26.4.0-1) and its
  dependency closure (`npm`, `libicu`, `libopenssl`, `zlib`, `libc++`, …).
- The binaries are standard Android (Bionic) ELF executables built by the
  Termux project from unmodified upstream sources.
- License: MIT (Node.js), ISC (npm), plus per-package upstream licenses.
- Termux application source: https://github.com/termux/termux-packages (Apache-2.0/GPL
  for packaging scripts; packaged upstream components retain their own licenses).

## zcode-app-cli

- npm: https://www.npmjs.com/package/zcode-app-cli
- Repository: https://github.com/kingsword09/zcode-cli
- License: MIT.
- "ZCode" and the agent runtime are by Z.ai: https://github.com/zai-org/ZCode (Apache-2.0).

## @deepseek-ai/dsh (DeepSeek Harness)

- npm: https://www.npmjs.com/package/@deepseek-ai/dsh
- Repository: https://github.com/deepseek-ai/deepseek-harness
- License: MIT.

## Android libraries

- AndroidX / Material Components: Apache License 2.0 (Android Open Source Project).
- commons-compress (Apache-2.0), XZ for Java (public domain), libsu (Apache-2.0,
  https://github.com/topjohnwu/libsu).
