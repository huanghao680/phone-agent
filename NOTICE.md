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

## Bundled command-line toolchain (Termux builds, same source and terms as above)

Bundled so agents have a working shell environment on a stock Android system:

| Component | Version | License |
|---|---|---|
| bash | 5.3.x | GPL-3.0-or-later |
| coreutils / findutils / grep / sed / gawk / tar | current Termux builds | GPL-3.0-or-later (gawk: GPL-3.0) |
| curl (with libcurl, OpenSSL) | 8.22.0 | curl: MIT-like; OpenSSL: Apache-2.0 |
| jq | 1.8.2 | MIT (oniguruma: BSD-2-Clause) |
| ripgrep | 15.2.0 | MIT / Unlicense (pcre2: BSD-3-Clause) |
| git | 2.55.0 | GPL-2.0-only |
| python | 3.14.6 | PSF-2.0 |
| proot | 5.1.107.x | GPL-2.0-or-later (backs the sandbox shim's userland tier) |

The corresponding source is available from the Termux package repository
(https://github.com/termux/termux-packages) and each project's own repository.

## node-pty (android-arm64 prebuild)

- Upstream: https://github.com/microsoft/node-pty — MIT.
- Cross-compiled for android-arm64 by this project's CI (`scripts/fetch-native.sh`,
  Android NDK) and injected into dsh's dependency tree at install time.

## landlock-wrap (this project)

- Source: `scripts/landlock-wrap.c` in this repository, compiled by CI with the
  Android NDK. Apache-2.0, same as the rest of this project. Calls only the
  Landlock syscalls (kernel UAPI) — no third-party code.

## zcode-app-cli

- npm: https://www.npmjs.com/package/zcode-app-cli
- Repository: https://github.com/kingsword09/zcode-cli
- License: MIT.
- "ZCode" and the agent runtime are by Z.ai: https://github.com/zai-org/ZCode (Apache-2.0).

## ZCode web UI + server bundle

- Built by CI from the upstream monorepo (https://github.com/zai-org/ZCode,
  `packages/web` and `packages/server`) at the pinned tag — Apache-2.0.
- The staged bundle keeps its own `THIRD-PARTY-NOTICES.md` inside the APK
  (`assets/zcode-web/`, `assets/zcode-server/`).

## @deepseek-ai/dsh (DeepSeek Harness)

- npm: https://www.npmjs.com/package/@deepseek-ai/dsh
- Repository: https://github.com/deepseek-ai/deepseek-harness
- License: MIT.

## Codex CLI and Claude Code

- `@openai/codex` (platform tarball `-linux-arm64-musl`) — Apache-2.0.
- `@anthropic-ai/claude-code` (`-linux-arm64-musl` platform build) — see the
  package's own license terms; the Alpine musl loader (`ld-musl-aarch64.so.1`)
  is from Alpine Linux (musl: MIT).

## Android libraries

- AndroidX / Material Components: Apache License 2.0 (Android Open Source Project).
- commons-compress (Apache-2.0), XZ for Java (public domain), libsu (Apache-2.0,
  https://github.com/topjohnwu/libsu), zstd-jni (BSD-2-Clause,
  https://github.com/luben/zstd-jni — used to read dsh session logs).
- Miuix (https://github.com/compose-miuix-ui/miuix) — Apache-2.0.
