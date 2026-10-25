import assert from 'node:assert/strict'
import test from 'node:test'
import { isReviewedNativePath } from './check-android-native-runtime-packages.mjs'

test('recognizes the pinned Linux native payloads found in the deploy tree', () => {
  for (const arch of ['arm64', 'x64']) {
    for (const path of [
      `@trycua+cua-driver-linux-${arch}-gnu@0.28.0/node_modules/@trycua/cua-driver-linux-${arch}-gnu/cua_driver_node_runtime.node`,
      `@ubjs+node-linux-${arch}-gnu@0.31.0-3/node_modules/@ubjs/node-linux-${arch}-gnu/uniffi-runtime-napi.linux-${arch}-gnu.node`,
      `node-addon-require-builtin-linux-${arch}-gnu@0.1.6/node_modules/node-addon-require-builtin-linux-${arch}-gnu/prebuilt/linux-${arch}-gnu-napi-v9.node`,
      `sherpa-onnx-linux-${arch}@1.13.8/node_modules/sherpa-onnx-linux-${arch}/sherpa-onnx.node`,
    ]) assert.equal(isReviewedNativePath(path), true, path)
  }
})

test('rejects a new version, mismatched architecture and unknown native payload', () => {
  for (const path of [
    '@trycua+cua-driver-linux-arm64-gnu@0.29.0/node_modules/@trycua/cua-driver-linux-arm64-gnu/cua_driver_node_runtime.node',
    '@ubjs+node-linux-arm64-gnu@0.31.0-3/node_modules/@ubjs/node-linux-x64-gnu/uniffi-runtime-napi.linux-x64-gnu.node',
    'unknown-native@1.0.0/node_modules/unknown-native/native.node',
  ]) assert.equal(isReviewedNativePath(path), false, path)
})
