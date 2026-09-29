// Android compatibility patcher for the dsh package tree (run by
// patch-dsh-flock.sh after every install/update).
//
// Upstream dsh assumes glibc Linux; on Android/bionic six things break. Each
// patch is anchored on an exact source string and guarded by a marker, so
// re-running is a no-op and an upstream rewrite produces a WARNING rather
// than a silently corrupted file.
//
//   1. flock           node-addon-system ships no android build        -> stub
//   2. link()          SELinux denies link(2) for app data on 11+      -> rename
//   3. PLATFORM_CHAINS sandbox-local has no android entry (empty chain,
//                      bash fails closed without even probing)         -> add
//   4. syncDirectory   Android ancestors are traversable-but-unreadable,
//                      so open(O_RDONLY) on them throws EACCES and the
//                      whole attachment save fails                    -> tolerate
//   5. ripgrep         @vscode/ripgrep resolves a platform package that
//                      does not exist on npm (platform "android")     -> shim
//   6. shebangs        scripts carry the Termux prefix baked in         -> rewrite
//
// Usage: node patch-dsh-android.js <usr-prefix>

import { copyFileSync, existsSync, chmodSync, readFileSync, writeFileSync, readdirSync, statSync, mkdirSync, rmSync, lstatSync, symlinkSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { basename, dirname, join } from 'node:path';

const USR = process.argv[2];
if (!USR) {
  console.error('[phone-agent] patch-dsh-android: missing usr prefix');
  process.exit(2);
}

const DSH_NM = join(USR, 'lib/node_modules/@deepseek-ai/dsh/node_modules');
const DSH_MOD = join(DSH_NM, '@deepseek-ai');
// npm may hoist a dependency into any of these trees, and not every dsh
// dependency is scoped, so search every plausible node_modules root
const resolvePkgDir = (name) => {
  for (const base of [DSH_MOD, DSH_NM, join(USR, 'lib/node_modules/@deepseek-ai'), join(USR, 'lib/node_modules')]) {
    const dir = join(base, name);
    if (existsSync(dir)) return dir;
  }
  return null;
};

let patched = 0;
let warned = 0;

function patchFile(file, { marker, apply, label }) {
  if (!existsSync(file)) {
    console.log(`[phone-agent] skip ${label}: file not present`);
    return;
  }
  const src = readFileSync(file, 'utf8');
  if (src.includes(marker)) {
    console.log(`[phone-agent] ${label}: already patched`);
    return;
  }
  const out = apply(src);
  if (out === null || out === undefined) {
    console.log(`[phone-agent] WARN ${label}: anchor not found (upstream changed?)`);
    warned++;
    return;
  }
  writeFileSync(file, out);
  console.log(`[phone-agent] ${label}: patched`);
  patched++;
}

// --- 1. flock: stub the native binding ---------------------------------------
{
  const dir = resolvePkgDir('node-addon-system');
  const file = dir && join(dir, 'lib/flock.js');
  if (file && existsSync(file)) {
    const stub = `// PHONE_AGENT_ANDROID_STUB
// Android has no prebuilt @deepseek-ai/node-addon-system binding; the stock
// module throws ERR_FLOCK_UNSUPPORTED_PLATFORM. Session-log writes are already
// serialized by this app's single Node process, so acquisition is a no-op.
export async function tryLockExclusive(fd) {
  void fd
  return
}
`;
    if (readFileSync(file, 'utf8').includes('PHONE_AGENT_ANDROID_STUB')) {
      console.log('[phone-agent] flock: already patched');
    } else {
      writeFileSync(file, stub);
      console.log('[phone-agent] flock: patched (no-op stub)');
      patched++;
    }
  } else {
    console.log('[phone-agent] skip flock: node-addon-system not present');
  }
}

// --- 2. session persistence: link() -> rename() fallback ---------------------
{
  const dir = resolvePkgDir('dsh-session-persistence-jsonl');
  const file = dir && join(dir, 'lib/index.js');
  patchFile(file, {
    marker: 'PHONE_AGENT_ANDROID_LINK_PATCH',
    label: 'session persistence (link -> rename)',
    apply: (src) => {
      const importRe = /^import \{ link, ([^}]*)\} from "node:fs\/promises";$/m;
      const m = src.match(importRe);
      if (!m) return null;
      const names = m[1].split(',').map((s) => s.trim()).filter(Boolean);
      if (!names.includes('rename')) names.push('rename');
      const wrapper = [
        `import { link as linkNative, ${names.join(', ')} } from "node:fs/promises";`,
        'const link = async (f, t) => {',
        '  try {',
        '    await linkNative(f, t)',
        '  } catch (e) {',
        '    if (e && (e.code === "EACCES" || e.code === "EPERM" || e.code === "EMLINK" || e.code === "EXDEV")) {',
        '      await rename(f, t)',
        '    } else {',
        '      throw e',
        '    }',
        '  }',
        '};',
        '// PHONE_AGENT_ANDROID_LINK_PATCH',
      ].join('\n');
      return src.replace(importRe, wrapper);
    },
  });
}

// --- 3. sandbox-local: android runner chain ----------------------------------
{
  const dir = resolvePkgDir('dsh-sandbox-local');
  const file = dir && join(dir, 'lib/index.js');
  patchFile(file, {
    marker: 'PHONE_AGENT_ANDROID_CHAIN',
    label: 'sandbox platform chain (android)',
    apply: (src) => {
      // Two elements on purpose: a single-element chain is trusted WITHOUT a
      // probe, so a missing or broken shim would look like working sandboxing.
      const re = /(const PLATFORM_CHAINS = \{\n)(\tlinux: \["bwrap", "landlock"\],\n)/;
      if (!re.test(src)) return null;
      return src.replace(re, (m, head, linux) => `${head}${linux}\tandroid: ["bwrap", "landlock"], // PHONE_AGENT_ANDROID_CHAIN\n`);
    },
  });
}

// --- 4. attachment-local: tolerate unreadable ancestors ----------------------
{
  const dir = resolvePkgDir('dsh-attachment-local');
  const file = dir && join(dir, 'lib/index.js');
  patchFile(file, {
    marker: 'PHONE_AGENT_ANDROID_EACCES',
    label: 'attachment dir fsync (EACCES tolerance)',
    apply: (src) => {
      const anchor = '\tconst handle = await open(path, constants.O_RDONLY);';
      if (!src.includes(anchor)) return null;
      // App-private dirs sit under /data/user/0, which is traversable but not
      // readable (x without r) — open(O_RDONLY) on it is EACCES. Durability of
      // those platform-owned levels is not ours to assert, so skip them
      // instead of failing the whole attachment save.
      const replacement =
        '\tlet handle;\n' +
        '\ttry {\n' +
        '\t\thandle = await open(path, constants.O_RDONLY);\n' +
        '\t} catch (error) {\n' +
        '\t\tif (error?.code === "EACCES" || error?.code === "EPERM") return; /* PHONE_AGENT_ANDROID_EACCES */\n' +
        '\t\tthrow error;\n' +
        '\t}';
      return src.replace(anchor, replacement);
    },
  });
}

// --- 5. ripgrep shim ---------------------------------------------------------
{
  const candidates = [
    // Termux's own ripgrep: built for Android/bionic like the rest of the
    // runtime, so it is the most reliable source
    join(USR, 'bin/rg'),
    join(USR, '../pkg/rg'), // staged static musl build (npm @vscode linux-arm64)
    join(USR, '../pkg/vendor/aarch64-unknown-linux-musl/codex-path/rg'), // codex vendor tree
  ];
  const src = candidates.find((p) => existsSync(p));
  const dirs = [
    join(USR, 'lib/node_modules/@deepseek-ai/dsh/node_modules/@vscode/ripgrep-android-arm64'),
    // profile-hosted copies of @vscode/ripgrep resolve their platform package
    // next to themselves, so the shim has to exist there too when not symlinked
    join(process.env.HOME || '/data/user/0/com.phoneagent/files/home', '.dsh/profiles/node_modules/@vscode/ripgrep-android-arm64'),
  ];
  const works = (bin) => {
    if (!existsSync(bin)) return false;
    // a staged binary that cannot exec (e.g. a dynamically linked build whose
    // loader is missing) must be replaced, not trusted by existence
    const r = spawnSync(bin, ['--version'], { stdio: 'ignore', timeout: 10_000 });
    return r.status === 0;
  };
  let installed = 0;
  for (const dir of dirs) {
    const bin = join(dir, 'bin/rg');
    if (works(bin)) continue;
    if (!src) {
      console.log(`[phone-agent] ripgrep shim: no rg staged, cannot repair ${dir}`);
      continue;
    }
    mkdirSync(join(dir, 'bin'), { recursive: true });
    copyFileSync(src, bin);
    chmodSync(bin, 0o755);
    writeFileSync(join(dir, 'package.json'), '{"name":"@vscode/ripgrep-android-arm64","version":"1.18.0","main":"bin/rg"}\n');
    installed++;
    // verify what we just installed: a corrupted or wrong-flavour binary must
    // be reported here, not discovered later as "ripgrep launch failed"
    console.log(works(bin)
      ? `[phone-agent] ripgrep shim: installed into ${dir}`
      : `[phone-agent] WARN ripgrep shim: installed but NOT runnable in ${dir} (bad staged binary?)`);
  }
  if (installed > 0) patched += installed;
  else if (dirs.every((d) => works(join(d, 'bin/rg')))) console.log('[phone-agent] ripgrep shim: OK');
}

// --- 6. shebang prefix rewrite ----------------------------------------------
{
  const OLD = '/data/data/com.termux/files/usr';
  const ENV_OLD = '#!/usr/bin/env '; // Android has no /usr/bin/env
  let count = 0;
  const rewrite = (file) => {
    let head;
    try {
      head = readFileSync(file, 'utf8').slice(0, 4096);
    } catch {
      return;
    }
    if (!head.includes(OLD) && !head.startsWith(ENV_OLD)) return;
    try {
      if (statSync(file).size > 2_000_000) return; // skip big data files
      let text = readFileSync(file, 'utf8');
      if (text.includes('\0')) return; // binary
      const before = text;
      if (text.startsWith(ENV_OLD)) text = `#!${USR}/bin/env ` + text.slice(ENV_OLD.length);
      text = text.split(OLD).join(USR);
      if (text !== before) {
        writeFileSync(file, text);
        count++;
      }
    } catch {
      /* unreadable/undeletable: leave it */
    }
  };
  const targets = [join(USR, 'bin')];
  try {
    for (const name of readdirSync(join(USR, 'lib/node_modules'))) {
      targets.push(join(USR, 'lib/node_modules', name, 'bin'));
      targets.push(join(USR, 'lib/node_modules', name, 'libexec'));
    }
  } catch {
    /* no node_modules yet */
  }
  for (const dir of targets) {
    if (!existsSync(dir)) continue;
    let names;
    try {
      names = readdirSync(dir);
    } catch {
      continue;
    }
    for (const name of names) {
      const p = join(dir, name);
      try {
        if (statSync(p).isFile()) rewrite(p);
      } catch {
        /* dangling symlink */
      }
    }
  }
  console.log(`[phone-agent] shebangs: rewrote ${count} file(s) to the app prefix`);
  patched += count > 0 ? 1 : 0;
}

// --- 7. require-builtin: JS fallback -----------------------------------------
// 0.1.7 added node-addon-require-builtin, a native addon that forwards ids to
// Node's builtin require. No android build exists on npm, and its published
// form carries no sources to compile. The addon's whole job — per its own
// README — is `require(id)` plus `isAllowedInternalId() === true`, so a JS
// equivalent works as long as Node runs with --expose-internals (which the
// bin/dsh wrapper below guarantees).
{
  const dir = resolvePkgDir('node-addon-require-builtin');
  const file = dir && join(dir, 'lib/index.js');
  if (file && existsSync(file)) {
    const stub = `"use strict";
// PHONE_AGENT_ANDROID_STUB
// JS fallback for the missing android build; requires --expose-internals.
function requireBuiltin(moduleId) {
  return require(moduleId);
}
function isAllowedInternalId() {
  return true;
}
function getBindingInfo() {
  return {
    name: "node-addon-require-builtin",
    variant: "js-fallback",
    target: process.platform + "-" + process.arch,
  };
}
const api = { requireBuiltin, isAllowedInternalId, getBindingInfo };
module.exports = { ...api, default: api };
`;
    if (readFileSync(file, 'utf8').includes('PHONE_AGENT_ANDROID_STUB')) {
      console.log('[phone-agent] require-builtin: already patched');
    } else {
      writeFileSync(file, stub);
      console.log('[phone-agent] require-builtin: patched (JS fallback)');
      patched++;
    }
  } else {
    console.log('[phone-agent] skip require-builtin: not present');
  }
}

// --- 8. dsh launcher: force --expose-internals -------------------------------
// NODE_OPTIONS rejects the flag, so the wrapper is the only place that reaches
// every entry point (TUI, web, headless, subagents, user shells).
{
  const bin = join(USR, 'bin/dsh');
  const wrapper = `#!/system/bin/sh
# PHONE_AGENT_DSH_WRAPPER
# --expose-internals is required by the JS fallbacks for dsh's native addons
# (node-addon-require-builtin) and cannot be set through NODE_OPTIONS.
U="${USR}"
export LD_LIBRARY_PATH="$U/lib"
exec "$U/bin/node" --expose-internals "$U/lib/node_modules/@deepseek-ai/dsh/lib/bin.js" "$@"
`;
  let current = null;
  try {
    current = readFileSync(bin, 'utf8');
  } catch {
    /* missing or a symlink to the real entry: replace it */
  }
  if (current && current.includes('PHONE_AGENT_DSH_WRAPPER')) {
    console.log('[phone-agent] dsh launcher: already wrapped');
  } else if (existsSync(bin) || current !== null) {
    try {
      rmSync(bin, { force: true });
    } catch {
      /* ignore */
    }
    writeFileSync(bin, wrapper);
    chmodSync(bin, 0o755);
    console.log('[phone-agent] dsh launcher: wrapped (--expose-internals)');
    patched++;
  } else {
    console.log('[phone-agent] skip dsh launcher: bin/dsh not present');
  }
}

// --- 9. Termux shell path compiled into git --------------------------------
// git builds `git-upload-pack <url>` as a shell command and runs it through the
// shell it was configured with — for these packages
// /data/data/com.termux/files/usr/bin/sh, which cannot exist outside Termux, so
// every clone/fetch dies with "cannot exec ... unable to fork". No environment
// variable overrides it (SHELL and GIT_SHELL_PATH were both tried), so the
// string is rewritten in place. The replacement is shorter, which is safe: a C
// string ends at the first NUL, and only occurrences followed by NUL are
// touched so neighbouring strings in .rodata stay intact.
{
  const OLD = '/data/data/com.termux/files/usr/bin/sh';
  const appRoot = dirname(dirname(USR)); // /data/user/0/<pkg>
  const NEW = `${appRoot}/bin/sh`;
  if (Buffer.byteLength(NEW) > Buffer.byteLength(OLD)) {
    console.log(`[phone-agent] WARN git shell path: replacement too long (${NEW})`);
  } else {
    // the replacement must exist for git to spawn it
    const shim = join(appRoot, 'bin');
    try {
      mkdirSync(shim, { recursive: true });
      const link = join(shim, 'sh');
      if (!existsSync(link)) symlinkSync(join(USR, 'bin/sh'), link);
    } catch {
      /* already there or not creatable; the patch below still applies */
    }
    const targets = [];
    const scan = (dir) => {
      if (!existsSync(dir)) return;
      for (const name of readdirSync(dir)) {
        const p = join(dir, name);
        try {
          const st = lstatSync(p);
          if (st.isSymbolicLink()) continue; // patching the target is enough
          if (st.isFile() && st.size > 4096 && st.size < 64 * 1024 * 1024) targets.push(p);
        } catch {
          /* unreadable */
        }
      }
    };
    scan(join(USR, 'bin'));
    scan(join(USR, 'libexec/git-core'));
    let fixed = 0;
    for (const p of targets) {
      let buf;
      try {
        buf = readFileSync(p);
      } catch {
        continue;
      }
      const needle = Buffer.from(OLD + '\0');
      const repl = Buffer.concat([Buffer.from(NEW + '\0'), Buffer.alloc(needle.length - NEW.length - 1)]);
      let idx = buf.indexOf(needle);
      if (idx === -1) continue;
      let hits = 0;
      while (idx !== -1) {
        repl.copy(buf, idx);
        hits++;
        idx = buf.indexOf(needle, idx + needle.length);
      }
      try {
        writeFileSync(p, buf);
        fixed++;
        console.log(`[phone-agent] git shell path: patched ${hits} occurrence(s) in ${basename(p)}`);
      } catch {
        console.log(`[phone-agent] WARN git shell path: cannot write ${p}`);
      }
    }
    if (fixed > 0) patched += fixed;
    else console.log('[phone-agent] git shell path: nothing to patch');
  }
}

console.log(`[phone-agent] dsh android patch done (${patched} change(s), ${warned} warning(s))`);
if (warned > 0) process.exitCode = 1;
