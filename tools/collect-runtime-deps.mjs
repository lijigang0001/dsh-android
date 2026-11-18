#!/usr/bin/env node
/**
 * Work out the exact package set an Android build has to ship.
 *
 * Input is `dsh --profile web --dump-config`, which lists every plugin the
 * composed web profile mounts. From those entry names we walk each package's
 * own dependencies inside the installed tree and print the closure with sizes,
 * so pruning is driven by "what the profile actually mounts" instead of "delete
 * directories that look Windows-y".
 *
 *   node tools/collect-runtime-deps.mjs captures/dump-config.txt <dsh tree> <out manifest>
 */
import { readFileSync, writeFileSync, statSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';

const [dumpPath, treeRoot, outPath] = process.argv.slice(2);
if (!dumpPath || !treeRoot || !outPath) {
  console.error('usage: collect-runtime-deps.mjs <dump-config.txt> <dsh tree> <out.json>');
  process.exit(1);
}

const dump = readFileSync(dumpPath, 'utf8');

/** Plugin entry names from the composed profile. */
const entries = new Set();
for (const line of dump.split(/\r?\n/u)) {
  const match = /^\s*-?\s*name:\s*'([^']+)'|^\s*-?\s*name:\s*"([^"]+)"/u.exec(line);
  if (match) entries.add(match[1] ?? match[2]);
}
/** '@deepseek-ai/dsh-web-app/client.js' -> '@deepseek-ai/dsh-web-app' */
function packageOf(specifier) {
  if (specifier.startsWith('@')) return specifier.split('/').slice(0, 2).join('/');
  return specifier.split('/')[0];
}

const rootModules = join(treeRoot, 'node_modules');

function packageDirFrom(baseDir, name) {
  let dir = baseDir;
  for (;;) {
    const candidate = join(dir, 'node_modules', name);
    if (existsSync(join(candidate, 'package.json'))) return candidate;
    const parent = dirname(dir);
    if (parent === dir) return undefined;
    dir = parent;
  }
}

const visited = new Map();
const missing = new Set();

function visit(dir, name) {
  if (visited.has(dir)) return;
  const manifestPath = join(dir, 'package.json');
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  } catch {
    return;
  }
  visited.set(dir, { name: manifest.name ?? name, version: manifest.version ?? '', dir });
  const deps = {
    ...manifest.dependencies,
    ...manifest.optionalDependencies,
    ...manifest.peerDependencies,
  };
  for (const dep of Object.keys(deps)) {
    if (dep === 'node-addon-require-builtin' || dep === 'node-addon-native-custom-loader') continue;
    const found = packageDirFrom(dir, dep);
    if (found) visit(found, dep);
    else missing.add(dep);
  }
}

// The CLI package itself is the entry point.
visit(treeRoot, 'dsh');

for (const entry of entries) {
  const name = packageOf(entry);
  const dir = packageDirFrom(rootModules, name) ?? packageDirFrom(treeRoot, name);
  if (dir) visit(dir, name);
  else missing.add(name);
}

function dirSize(dir) {
  let total = 0;
  let files = 0;
  const stack = [dir];
  while (stack.length) {
    const current = stack.pop();
    for (const item of readdirSync(current, { withFileTypes: true })) {
      const full = join(current, item.name);
      // Nested node_modules are separate packages, each visited on its own;
      // counting them here would double the payload.
      if (item.isDirectory()) {
        if (item.name !== 'node_modules') stack.push(full);
      } else if (item.isFile()) {
        total += statSync(full).size;
        files += 1;
      }
    }
  }
  return { total, files };
}

const packages = [...visited.values()]
  .map((entry) => {
    const { total, files } = dirSize(entry.dir);
    return { ...entry, bytes: total, files, rel: relative(treeRoot, entry.dir).split(sep).join('/') };
  })
  .sort((left, right) => right.bytes - left.bytes);

const totalBytes = packages.reduce((sum, entry) => sum + entry.bytes, 0);
const totalFiles = packages.reduce((sum, entry) => sum + entry.files, 0);

const report = {
  entries: entries.size,
  packages: packages.length,
  totalMB: Number((totalBytes / 1024 / 1024).toFixed(1)),
  files: totalFiles,
  missing: [...missing].sort(),
  top: packages.slice(0, 20).map((entry) => `${(entry.bytes / 1024 / 1024).toFixed(1)} MB  ${entry.name}`),
  dirs: packages.map((entry) => entry.rel),
};

writeFileSync(outPath, `${JSON.stringify(report, null, 2)}\n`);
console.log(`profile entries : ${report.entries}`);
console.log(`packages        : ${report.packages}`);
console.log(`payload         : ${report.totalMB} MB in ${report.files} files`);
if (report.missing.length) console.log(`unresolved      : ${report.missing.join(', ')}`);
console.log('top:');
for (const line of report.top) console.log(`  ${line}`);
