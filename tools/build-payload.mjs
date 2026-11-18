#!/usr/bin/env node
/**
 * Materialise the on-phone DSH payload: the pruned dependency closure computed by
 * collect-runtime-deps.mjs, laid out exactly like an npm install anchor so
 * `node dsh/lib/bin.js --profile web` resolves plugins as usual.
 *
 * Prune rules target what cannot work on Android (no bionic prebuilds) and what
 * no runtime ever reads (sourcemaps, docs, test fixtures). Every removal is
 * printed so a boot failure can be traced back to a decision here.
 *
 *   node tools/build-payload.mjs <deps.json> <dsh tree> <out dir>
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, copyFileSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';

const [depsPath, treeRoot, outRoot] = process.argv.slice(2);
if (!depsPath || !treeRoot || !outRoot) {
  console.error('usage: build-payload.mjs <deps.json> <dsh tree> <out dir>');
  process.exit(1);
}

const deps = JSON.parse(readFileSync(depsPath, 'utf8'));

/** Packages that cannot load on linux-android (bionic) or are another OS's build. */
const DROP_PACKAGES = [
  '@deepseek-ai/libreoffice-kit-win32-x64',
  '@deepseek-ai/libreoffice-kit-darwin-arm64',
  '@deepseek-ai/libreoffice-kit-darwin-x64',
  '@deepseek-ai/libreoffice-kit-win32-arm64',
  '@deepseek-ai/libreoffice-kit-wasm',
  '@deepseek-ai/libreoffice-kit-linux-x64',
  '@deepseek-ai/libreoffice-kit-linux-arm64',
  '@img/sharp-linux-x64',
  '@img/sharp-linux-arm64',
  '@img/sharp-linuxmusl-x64',
  '@img/sharp-linuxmusl-arm64',
  '@img/sharp-libvips-linux-x64',
  '@img/sharp-libvips-linux-arm64',
  '@img/sharp-libvips-linuxmusl-x64',
  '@img/sharp-libvips-linuxmusl-arm64',
  '@vscode/ripgrep-linux-x64',
  '@vscode/ripgrep-linux-arm64',
  '@koromix/koffi-linux-x64',
  '@koromix/koffi-linux-arm64',
  'sherpa-onnx-win-x64',
  'sherpa-onnx-win-ia32',
  'sherpa-onnx-darwin-arm64',
  'sherpa-onnx-darwin-x64',
  '@img/sharp-win32-x64',
  '@img/sharp-win32-arm64',
  '@img/sharp-win32-ia32',
  '@vscode/ripgrep-win32-x64',
  '@vscode/ripgrep-win32-arm64',
  '@vscode/ripgrep-win32-ia32',
  '@koromix/koffi-win32-x64',
  'node-pty',
];

/** File names / directory names no runtime path reads. */
const DROP_FILES = /\.(map|md|markdown|txt)$/iu;
const DROP_FILE_EXCEPTIONS = /^(LICENSE|NOTICE|package\.json|README)/u;
/**
 * Directory names that are safe to drop. Kept deliberately tiny: `yaml`'s
 * runtime requires `../doc/directives.js`, so a package's `doc/` directory is
 * not documentation in general.
 */
const DROP_DIRS = new Set(['.github', '.vscode', '.idea']);

const dropped = new Map();
function note(reason) {
  dropped.set(reason, (dropped.get(reason) ?? 0) + 1);
}

let copiedFiles = 0;
let copiedBytes = 0;
const skippedPackages = [];

function copyDir(from, to, relBase) {
  if (!existsSync(from)) return;
  mkdirSync(to, { recursive: true });
  for (const item of readdirSync(from, { withFileTypes: true })) {
    const source = join(from, item.name);
    const target = join(to, item.name);
    if (item.isDirectory()) {
      if (item.name === 'node_modules') continue; // nested packages are separate closure entries
      if (DROP_DIRS.has(item.name)) {
        note(`dir:${item.name}`);
        continue;
      }
      copyDir(source, target, relBase);
      continue;
    }
    if (!item.isFile()) continue;
    if (DROP_FILES.test(item.name) && !DROP_FILE_EXCEPTIONS.test(item.name)) {
      note('sourcemap/doc');
      continue;
    }
    const size = statSync(source).size;
    mkdirSync(dirname(target), { recursive: true });
    copyFileSync(source, target);
    copiedFiles += 1;
    copiedBytes += size;
  }
}

function packageNameOf(rel) {
  const parts = rel.split('/');
  const at = parts.lastIndexOf('node_modules');
  if (at < 0) return undefined;
  const first = parts[at + 1];
  if (first === undefined) return undefined;
  return first.startsWith('@') ? `${first}/${parts[at + 2]}` : first;
}

for (const rel of deps.dirs) {
  const name = packageNameOf(rel);
  if (name !== undefined && DROP_PACKAGES.some((drop) => name === drop || name.startsWith(`${drop}/`))) {
    skippedPackages.push(name);
    continue;
  }
  const from = join(treeRoot, rel.split('/').join(sep));
  const to = join(outRoot, rel.split('/').join(sep));
  copyDir(from, to, rel);
}

console.log(`payload dir     : ${outRoot}`);
console.log(`packages kept   : ${deps.dirs.length - skippedPackages.length} / ${deps.dirs.length}`);
console.log(`packages dropped: ${[...new Set(skippedPackages)].sort().join(', ') || '(none)'}`);
console.log(`files copied    : ${copiedFiles}`);
console.log(`payload size    : ${(copiedBytes / 1024 / 1024).toFixed(1)} MB`);
console.log('pruned:');
for (const [reason, count] of [...dropped.entries()].sort((left, right) => right[1] - left[1])) {
  console.log(`  ${String(count).padStart(7)}  ${reason}`);
}
