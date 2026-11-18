#!/usr/bin/env node
/**
 * Pack the Android payload into one deflated zip that the app extracts on first
 * launch.
 *
 * One archive beats 64k individual APK assets: the installer reads a single
 * asset stream, and the Unix mode bits travel with the archive so `bin/node`
 * comes out executable without a separate manifest.
 *
 *   node tools/pack-payload.mjs <payload dir> <out.zip> [--base <dir>]
 *
 * Layout inside the archive (all paths relative to <payload dir>):
 *   runtime/bin/node              mode 0755
 *   runtime/lib/*.so
 *   dsh/…                         the pruned install anchor
 *   tools/android-*.cjs
 *   profile/profiles/web/…        seed DSH_HOME
 *   links.json                    soname symlinks to create after extraction
 */
import { createWriteStream, existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { crc32, deflateRawSync } from 'node:zlib';

const [payloadDir, outPath] = process.argv.slice(2);
if (!payloadDir || !outPath) {
  console.error('usage: pack-payload.mjs <payload dir> <out.zip>');
  process.exit(1);
}

/** soname links the dynamic linker needs but a zip cannot carry. */
const LINKS = {
  'libicudata.so.79': 'libicudata.so.79.1',
  'libicui18n.so.79': 'libicui18n.so.79.1',
  'libicuuc.so.79': 'libicuuc.so.79.1',
  'libsqlite3.so': 'libsqlite3.so.3.54.1',
  'libz.so.1': 'libz.so.1.4',
};

writeFileSync(join(payloadDir, 'links.json'), `${JSON.stringify(LINKS, null, 2)}\n`);

const entries = [];
function walk(dir, base) {
  for (const item of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, item.name);
    if (item.isDirectory()) walk(full, base);
    else if (item.isFile()) {
      const rel = relative(base, full).split(sep).join('/');
      const mode = rel === 'runtime/bin/node' ? 0o100755 : 0o100644;
      entries.push({ rel, full, mode });
    }
  }
}
walk(payloadDir, payloadDir);
entries.sort((left, right) => (left.rel < right.rel ? -1 : left.rel > right.rel ? 1 : 0));

const out = createWriteStream(outPath);
const write = (chunk) => new Promise((resolve) => (out.write(chunk) ? resolve() : out.once('drain', resolve)));

const central = [];
let offset = 0;
let raw = 0;

for (const entry of entries) {
  const data = readFileSync(entry.full);
  raw += data.length;
  const deflated = deflateRawSync(data, { level: 6 });
  const useDeflate = deflated.length < data.length;
  const body = useDeflate ? deflated : data;
  const name = Buffer.from(entry.rel, 'utf8');
  const checksum = crc32(data) >>> 0;

  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(20, 4);
  local.writeUInt16LE(0, 6);
  local.writeUInt16LE(useDeflate ? 8 : 0, 8);
  local.writeUInt16LE(0, 10); // mod time
  local.writeUInt16LE(0x21, 12); // mod date (1980-01-01)
  local.writeUInt32LE(checksum, 14);
  local.writeUInt32LE(body.length, 18);
  local.writeUInt32LE(data.length, 22);
  local.writeUInt16LE(name.length, 26);
  local.writeUInt16LE(0, 28);

  await write(local);
  await write(name);
  await write(body);

  central.push({ entry, name, checksum, compressed: body.length, size: data.length, method: useDeflate ? 8 : 0, offset });
  offset += local.length + name.length + body.length;
}

const directoryOffset = offset;
const directoryChunks = [];
let directorySize = 0;
for (const item of central) {
  const record = Buffer.alloc(46);
  record.writeUInt32LE(0x02014b50, 0);
  record.writeUInt16LE(0x031e, 4); // made by: Unix, zip 3.0
  record.writeUInt16LE(20, 6);
  record.writeUInt16LE(0, 8);
  record.writeUInt16LE(item.method, 10);
  record.writeUInt16LE(0, 12);
  record.writeUInt16LE(0x21, 14);
  record.writeUInt32LE(item.checksum, 16);
  record.writeUInt32LE(item.compressed, 20);
  record.writeUInt32LE(item.size, 24);
  record.writeUInt16LE(item.name.length, 28);
  record.writeUInt16LE(0, 30);
  record.writeUInt16LE(0, 32);
  record.writeUInt16LE(0, 34);
  record.writeUInt16LE(0, 36);
  record.writeUInt32LE(item.entry.mode * 0x10000, 38);
  record.writeUInt32LE(item.offset, 42);
  directoryChunks.push(record, item.name);
  directorySize += record.length + item.name.length;
}

const end = Buffer.alloc(22);
end.writeUInt32LE(0x06054b50, 0);
end.writeUInt16LE(0, 4);
end.writeUInt16LE(0, 6);
end.writeUInt16LE(central.length, 8);
end.writeUInt16LE(central.length, 10);
end.writeUInt32LE(directorySize, 12);
end.writeUInt32LE(directoryOffset, 16);
end.writeUInt16LE(0, 20);

for (const chunk of directoryChunks) await write(chunk);
await write(end);
await new Promise((resolve) => out.end(resolve));

const packed = central.reduce((sum, item) => sum + item.compressed + item.name.length + 30, 0);
console.log(`entries   : ${central.length}`);
console.log(`raw       : ${(raw / 1024 / 1024).toFixed(1)} MB`);
console.log(`archive   : ${((packed + directorySize + 22) / 1024 / 1024).toFixed(1)} MB`);
console.log(`written   : ${outPath}`);
