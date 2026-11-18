/**
 * Simulate an Android/Bionic host that has no native prebuild for
 * `node-addon-require-builtin` (its optionalDependencies cover darwin, glibc
 * linux, musl linux and win32 only).
 *
 * Loaded with `node --require ./tools/block-native-addon.cjs` so every require
 * of the addon fails exactly as it does on Android, which shows whether the DSH
 * boot path tolerates a missing internal-module loader.
 */
const Module = require('node:module');
const originalLoad = Module._load;

Module._load = function blockNativeAddon(request, parent, isMain) {
  if (request === 'node-addon-require-builtin') {
    const error = new Error(
      'simulated android host: node-addon-require-builtin has no prebuild for linux-android arm64',
    );
    error.code = 'MODULE_NOT_FOUND';
    throw error;
  }
  return originalLoad.apply(this, arguments);
};
