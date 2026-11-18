/**
 * Pure-JS stand-in for `node-addon-require-builtin` on hosts without that
 * addon's prebuild (linux-android arm64, i.e. Termux and embedded runtimes).
 *
 * The real package dlopen()s a Node-API binding that forwards ids to Node's
 * builtin require. Node already exposes the same capability in-process when it
 * starts with `--expose-internals`: `require('internal/modules/esm/loader')`
 * resolves. So this shim implements the same three exports on top of plain
 * require and is installed by `android-addon-preload.cjs`.
 *
 * Usage: node --expose-internals --require tools/android-addon-preload.cjs <dsh bin.js> …
 */
const nodeRequire = require;

function requireBuiltin(id) {
  return nodeRequire(id);
}

function isAllowedInternalId() {
  return true;
}

function getBindingInfo() {
  return Object.freeze({
    mode: 'js-shim',
    product: 'node-addon-require-builtin',
    backend: 'js',
    abi: 'napi',
    bindingPath: __filename,
    bindingSource: 'js-shim',
    localBindingPath: '',
    optionalPackageName: '',
    optionalBinaryRelativePath: '',
    platformPackageSuffix: `${process.platform}-${process.arch}`,
  });
}

const api = { requireBuiltin, isAllowedInternalId, getBindingInfo };
api.default = api;
module.exports = api;
