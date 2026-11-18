#!/usr/bin/env node
/**
 * Push the working tree to GitHub through the Git Data API.
 *
 * `git push` over HTTPS to github.com is reset from this network while
 * api.github.com answers fine, so the commit is assembled with REST calls
 * instead: one blob per file, then a tree, a commit and a ref update. The
 * resulting ref update is what fires the `push` workflow in Actions.
 *
 *   node tools/github-push.mjs <owner> <repo> <branch> <files list> <message>
 *
 * The file list is newline separated (produce it with `git ls-files`). The token
 * comes from $GITHUB_TOKEN or from a file path in $GITHUB_TOKEN_FILE.
 */
import { readFileSync } from 'node:fs';
import { basename } from 'node:path';

const [owner, repo, branch, listPath, message] = process.argv.slice(2);
const api = 'https://api.github.com';
const token =
  process.env.GITHUB_TOKEN ??
  (process.env.GITHUB_TOKEN_FILE ? readFileSync(process.env.GITHUB_TOKEN_FILE, 'utf8').trim() : '');
if (!owner || !repo || !branch || !listPath || !message || !token) {
  console.error('usage: github-push.mjs <owner> <repo> <branch> <files> <message> (token via env)');
  process.exit(1);
}

const headers = {
  authorization: `token ${token}`,
  accept: 'application/vnd.github+json',
  'user-agent': 'dsh-android-build',
  'content-type': 'application/json',
};

async function call(method, path, body) {
  const response = await fetch(`${api}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`${method} ${path} -> ${response.status}: ${text.slice(0, 400)}`);
  return text ? JSON.parse(text) : {};
}

const files = readFileSync(listPath, 'utf8')
  .split(/\r?\n/u)
  .map((line) => line.trim())
  .filter(Boolean);
console.log(`files: ${files.length}`);

const tree = [];
let done = 0;
async function upload(file) {
  const content = readFileSync(file);
  const blob = await call('POST', `/repos/${owner}/${repo}/git/blobs`, {
    content: content.toString('base64'),
    encoding: 'base64',
  });
  tree.push({ path: file.split('\\').join('/'), mode: '100644', type: 'blob', sha: blob.sha });
  done += 1;
  if (done % 20 === 0) console.log(`  uploaded ${done}/${files.length}`);
}

const queue = [...files];
const workers = Array.from({ length: 6 }, async () => {
  while (queue.length) {
    const next = queue.shift();
    if (next !== undefined) await upload(next);
  }
});
await Promise.all(workers);

const newTree = await call('POST', `/repos/${owner}/${repo}/git/trees`, { tree });
console.log(`tree: ${newTree.sha}`);

let parent;
try {
  const ref = await call('GET', `/repos/${owner}/${repo}/git/ref/heads/${branch}`);
  parent = ref.object.sha;
} catch {
  parent = undefined; // empty repository: first commit
}

const commit = await call('POST', `/repos/${owner}/${repo}/git/commits`, {
  message,
  tree: newTree.sha,
  parents: parent ? [parent] : [],
});
console.log(`commit: ${commit.sha}`);

if (parent) {
  await call('PATCH', `/repos/${owner}/${repo}/git/refs/heads/${branch}`, { sha: commit.sha, force: true });
} else {
  await call('POST', `/repos/${owner}/${repo}/git/refs`, { ref: `refs/heads/${branch}`, sha: commit.sha });
}
console.log(`https://github.com/${owner}/${repo}/tree/${branch}`);
console.log(`https://github.com/${owner}/${repo}/actions`);
void basename;
