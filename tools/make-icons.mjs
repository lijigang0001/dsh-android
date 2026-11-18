#!/usr/bin/env node
/**
 * Render the launcher icon set from icon-source.html with headless Chromium.
 *
 * Writes legacy mipmaps (square, brand-blue background), adaptive-icon
 * foregrounds (transparent, mark inside the 66% safe zone) and a 512px
 * store-style icon. PNG sizes are read back from each file's IHDR chunk so a
 * silently ignored --window-size cannot pass unnoticed.
 */
import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

const root = process.cwd();
const shell = 'C:\\Users\\lenovo\\AppData\\Local\\ms-playwright\\chromium_headless_shell-1234\\chrome-headless-shell-win64\\chrome-headless-shell.exe';
const resMain = join(root, 'dsh-mobile', 'app', 'src', 'main', 'res');
const work = join(root, 'captures', 'icons');

const sourceHtml = readFileSync(join(root, 'dsh-mobile', 'icon-source.html'), 'utf8');

const densities = [
  ['mdpi', 1, 48],
  ['hdpi', 1.5, 72],
  ['xhdpi', 2, 96],
  ['xxhdpi', 3, 144],
  ['xxxhdpi', 4, 192],
];

function writeVariant(name, foreground) {
  const html = foreground
    ? sourceHtml.replace('<body>', '<body class="foreground">')
    : sourceHtml;
  const file = join(work, name);
  writeFileSync(file, html);
  return file;
}

function pngSize(buffer) {
  if (buffer.length < 24) return undefined;
  if (buffer.toString('ascii', 12, 16) !== 'IHDR') return undefined;
  return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) };
}

function shoot(pageFile, size, outFile, transparent) {
  return new Promise((resolve, reject) => {
    const args = [
      '--no-sandbox',
      '--disable-gpu',
      '--hide-scrollbars',
      '--no-first-run',
      '--allow-file-access-from-files',
      `--user-data-dir=${join(work, 'profile')}`,
      `--window-size=${size},${size}`,
      `--screenshot=${outFile}`,
      ...(transparent ? ['--default-background-color=00000000'] : []),
      `file:///${pageFile.replaceAll('\\', '/')}`,
    ];
    const child = spawn(shell, args, { stdio: 'ignore', windowsHide: true });
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error(`timed out rendering ${outFile}`));
    }, 30000);
    child.on('exit', () => {
      clearTimeout(timer);
      try {
        const size2 = pngSize(readFileSync(outFile));
        resolve(size2);
      } catch (error) {
        reject(new Error(`no screenshot at ${outFile}: ${error.message}`));
      }
    });
  });
}

async function main() {
  mkdirSync(work, { recursive: true });
  const plain = writeVariant('icon.html', false);
  const foreground = writeVariant('icon-foreground.html', true);

  const report = [];
  for (const [name, , size] of densities) {
    const dir = join(resMain, `mipmap-${name}`);
    mkdirSync(dir, { recursive: true });
    const legacy = join(dir, 'ic_launcher.png');
    const round = join(dir, 'ic_launcher_round.png');
    const adaptive = join(dir, 'ic_launcher_foreground.png');
    const a = await shoot(plain, size, legacy, false);
    const b = await shoot(plain, size, round, false);
    const fgSize = Math.round(size * 2.25);
    const c = await shoot(foreground, fgSize, adaptive, true);
    report.push({ density: name, legacy: `${a.width}x${a.height}`, round: `${b.width}x${b.height}`, foreground: `${c.width}x${c.height} (from ${fgSize})` });
  }

  const storeDir = join(work, 'out');
  mkdirSync(storeDir, { recursive: true });
  const store = await shoot(plain, 512, join(storeDir, 'dsh-mobile-icon-512.png'), false);
  report.push({ density: 'store', legacy: `${store.width}x${store.height}`, round: '', foreground: '' });

  writeFileSync(join(work, 'report.json'), JSON.stringify(report, null, 2));
  console.table(report);
}

main().catch((error) => {
  console.error('make-icons failed:', error.message);
  process.exit(1);
});
