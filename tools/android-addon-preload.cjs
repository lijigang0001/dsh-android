/**
 * Preload that redirects `node-addon-require-builtin` to the pure-JS shim, so a
 * host without the addon's native prebuild (linux-android arm64) can still boot
 * DSH. Pair it with `--expose-internals`, which is what makes the shim's
 * require('internal/…') calls resolve.
 */
const Module = require('node:module');
const shim = require('./android-native-addon-shim.cjs');

const originalLoad = Module._load;
Module._load = function loadWithAndroidShim(request, parent, isMain) {
  if (request === 'node-addon-require-builtin') return shim;
  return originalLoad.apply(this, arguments);
};
