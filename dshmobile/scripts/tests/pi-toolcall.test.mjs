#!/usr/bin/env node
/**
 * pi-toolcall-G2 回归测试（issue #124）。
 *
 * 直接驱动引擎树里的 pi-ai 实现（出口映射 convertMessages + 流式累加器 stream），
 * 不联网、不花额度。目标是两条独立缺陷路径都不再复现：
 *   A 出口：空名 tool_call 及其 tool result 必须被丢弃，arguments 不得为空；
 *   B 累加器：缺 index 且缺 id 的续块必须合并进唯一在途调用，不得新建空名块。
 *
 * 目标文件来源（按优先级）：
 *   1. `--pi-ai <path>` / 环境变量 `DSH_PI_AI_FILE`
 *   2. 默认 `.deploy-tmp/snapshot-013/x86_64/stage/root/...`（本地构建产物）
 * 找不到目标文件时以「跳过」结束（退出码 0）并打印原因——构建门禁在补丁施加后调用，
 * 此时文件必定在场，跳过只发生在裸 clone 上。
 *
 * 用法：node scripts/tests/pi-toolcall.test.mjs [--pi-ai <path>]
 */
import { existsSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
import assert from 'node:assert/strict'
import test from 'node:test'

const DEFAULT_PI = '.deploy-tmp/snapshot-013/x86_64/stage/root/usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@earendil-works/pi-ai/dist/api/openai-completions.js'

const argv = process.argv.slice(2)
const flagIndex = argv.indexOf('--pi-ai')
const target = resolve(flagIndex >= 0 && argv[flagIndex + 1] ? argv[flagIndex + 1] : (process.env.DSH_PI_AI_FILE ?? DEFAULT_PI))

if (!existsSync(target)) {
  console.log(`[skip] pi-ai 目标文件不在场：${target}\n       （裸 clone 无快照 stage 时属正常；构建链在补丁施加后调用本测试）`)
  process.exit(0)
}

const pi = await import(pathToFileURL(target).href)

const model = {
  provider: 'test-route', id: 'test-model', name: 'Test Model', api: 'openai-completions',
  reasoning: false, input: ['text'], contextWindow: 1000, maxTokens: 100, baseUrl: 'https://example.invalid/v1',
}

function outbound(messages) {
  return pi.convertMessages(model, { messages }, {}, {})
}

function sse(chunks) {
  const body = chunks.map((c) => `data: ${JSON.stringify(c)}\n\n`).join('') + 'data: [DONE]\n\n'
  return new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream' } })
}

const baseChunk = { id: 'chatcmpl-test', object: 'chat.completion.chunk', created: 1, model: 'test-model' }

async function streamWith(chunks) {
  const fetchStub = async () => sse(chunks)
  const s = pi.stream(model, { messages: [{ role: 'user', content: [{ type: 'text', text: 'go' }] }] }, { apiKey: 'test', fetch: fetchStub })
  return s.result()
}

test('出口：空名 tool_call 与其 tool result 一并丢弃（issue #124 主症状）', () => {
  const params = outbound([
    { role: 'user', content: [{ type: 'text', text: 'run' }] },
    { role: 'assistant', content: [{ type: 'toolCall', id: 'call_x', name: '', arguments: {} }] },
    { role: 'toolResult', toolCallId: 'call_x', content: [{ type: 'text', text: 'output' }] },
  ])
  const assistant = params.find((m) => m.role === 'assistant')
  assert.equal(assistant, undefined, '无内容且工具调用被丢弃的 assistant 消息不应发出')
  assert.equal(params.some((m) => m.role === 'tool'), false, '孤立的 tool result 不应发出')
})

test('出口：空白名（空格）同样丢弃', () => {
  const params = outbound([
    { role: 'user', content: [{ type: 'text', text: 'run' }] },
    { role: 'assistant', content: [{ type: 'toolCall', id: 'call_ws', name: '   ', arguments: { a: 1 } }] },
    { role: 'toolResult', toolCallId: 'call_ws', content: [{ type: 'text', text: 'output' }] },
  ])
  assert.equal(params.some((m) => m.role === 'assistant' && m.tool_calls), false)
})

test('出口：arguments 缺失时序列化为 {} 而不是省略字段', () => {
  const params = outbound([
    { role: 'user', content: [{ type: 'text', text: 'run' }] },
    { role: 'assistant', content: [{ type: 'toolCall', id: 'call_y', name: 'bash', arguments: undefined }] },
    { role: 'toolResult', toolCallId: 'call_y', content: [{ type: 'text', text: 'ok' }] },
  ])
  const assistant = params.find((m) => m.role === 'assistant')
  assert.ok(assistant?.tool_calls?.length === 1)
  assert.equal(assistant.tool_calls[0].function.name, 'bash')
  assert.equal(typeof assistant.tool_calls[0].function.arguments, 'string')
  assert.ok(assistant.tool_calls[0].function.arguments.length > 0, 'arguments 必须是非空字符串')
})

test('出口：正常 tool_call 原样保留（对照组）', () => {
  const params = outbound([
    { role: 'user', content: [{ type: 'text', text: 'run' }] },
    { role: 'assistant', content: [{ type: 'toolCall', id: 'call_z', name: 'bash', arguments: { command: 'echo hi' } }] },
    { role: 'toolResult', toolCallId: 'call_z', content: [{ type: 'text', text: 'hi' }] },
  ])
  const assistant = params.find((m) => m.role === 'assistant')
  assert.equal(assistant.tool_calls[0].function.name, 'bash')
  assert.equal(assistant.tool_calls[0].function.arguments, '{"command":"echo hi"}')
  assert.equal(params.filter((m) => m.role === 'tool').length, 1)
})

test('出口：混合场景只丢损坏项，正常项与其结果保留', () => {
  const params = outbound([
    { role: 'user', content: [{ type: 'text', text: 'run' }] },
    { role: 'assistant', content: [
      { type: 'toolCall', id: 'call_bad', name: '', arguments: {} },
      { type: 'toolCall', id: 'call_good', name: 'bash', arguments: { command: 'ls' } },
    ] },
    { role: 'toolResult', toolCallId: 'call_bad', content: [{ type: 'text', text: 'bad output' }] },
    { role: 'toolResult', toolCallId: 'call_good', content: [{ type: 'text', text: 'good output' }] },
  ])
  const assistant = params.find((m) => m.role === 'assistant')
  assert.deepEqual(assistant.tool_calls.map((c) => c.function.name), ['bash'])
  const tools = params.filter((m) => m.role === 'tool')
  assert.equal(tools.length, 1)
  assert.equal(tools[0].tool_call_id, 'call_good')
})

test('累加器：缺 index 且缺 id 的续块合并进唯一在途调用', async () => {
  const final = await streamWith([
    { ...baseChunk, choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: 'call_b', type: 'function', function: { name: 'get_weather', arguments: '' } }] } }] },
    { ...baseChunk, choices: [{ index: 0, delta: { tool_calls: [{ function: { arguments: '{"city":"BJ"}' } }] } }] },
    { ...baseChunk, choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }] },
  ])
  const calls = final.content.filter((b) => b.type === 'toolCall')
  assert.equal(calls.length, 1, '不得裂成两个工具调用')
  assert.equal(calls[0].name, 'get_weather')
  assert.deepEqual(calls[0].arguments, { city: 'BJ' })
  assert.equal(calls[0].id, 'call_b')
})

test('累加器：带 index 的续块仍按 index 归位（对照组）', async () => {
  const final = await streamWith([
    { ...baseChunk, choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: 'call_a', type: 'function', function: { name: 'get_weather', arguments: '' } }] } }] },
    { ...baseChunk, choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { arguments: '{"city":"SH"}' } }] } }] },
    { ...baseChunk, choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }] },
  ])
  const calls = final.content.filter((b) => b.type === 'toolCall')
  assert.equal(calls.length, 1)
  assert.equal(calls[0].name, 'get_weather')
  assert.deepEqual(calls[0].arguments, { city: 'SH' })
})

test('累加器：并行两个调用时不做误合并（保守规则）', async () => {
  const final = await streamWith([
    { ...baseChunk, choices: [{ index: 0, delta: { tool_calls: [
      { index: 0, id: 'call_1', type: 'function', function: { name: 'tool_a', arguments: '{}' } },
      { index: 1, id: 'call_2', type: 'function', function: { name: 'tool_b', arguments: '{}' } },
    ] } }] },
    { ...baseChunk, choices: [{ index: 0, delta: { tool_calls: [{ function: { arguments: '{"x":1}' } }] } }] },
    { ...baseChunk, choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }] },
  ])
  const calls = final.content.filter((b) => b.type === 'toolCall')
  assert.ok(calls.length >= 2, '两个在途调用时不得把无 index 续块并入任意一个')
  assert.deepEqual(calls.map((c) => c.name).slice(0, 2), ['tool_a', 'tool_b'])
})
