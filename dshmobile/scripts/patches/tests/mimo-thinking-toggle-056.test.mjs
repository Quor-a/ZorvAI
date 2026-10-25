import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { MIMO_PATCH_MARKER, patchDshThinkingProfile, patchPiAiMimoThinking } from '../mimo-thinking-toggle-056.mjs'
import { versionedFixture } from './lib/fixture.mjs'

const dshFixture = readFileSync(versionedFixture('dsh-llm-pi-ai', 'lib', 'index.js'), 'utf8')
const piFixture = readFileSync(versionedFixture('pi-ai', 'dist', 'api', 'openai-completions.js'), 'utf8')

test('patch marker and selector expose only closed plus one canonical enabled value', () => {
  const patched = patchDshThinkingProfile(dshFixture)
  assert.ok(patched.includes(MIMO_PATCH_MARKER))
  assert.match(patched, /"mimo": true/)
  assert.match(patched, /const efforts = \["off", "low"\]/)
  assert.match(patched, /level === "off" \? "关闭思考" : "开启思考"/)
  assert.match(patched, /defaultLevel === "off" \? "off" : "low"/)
  assert.match(patched, /isMimoToggleModel\(model\)\) return effort === "off" \? "off" : "low"/)
  assert.match(patched, /reasoning === "off" && !isMimoToggle \? void 0 : reasoning/)
  const resolverSource = patched.slice(patched.indexOf('function isMimoToggleModel'), patched.indexOf('\nfunction reasoningInfo', patched.indexOf('function resolveReasoningLevel')))
  const resolve = new Function(`${resolverSource}; return resolveReasoningLevel;`)()
  const legacyModel = { api: 'openai-completions', compat: { thinkingFormat: 'mimo' }, thinkingLevelMap: { high: 'high' } }
  assert.equal(resolve(legacyModel, 'high'), 'low')
  assert.equal(resolve(legacyModel, 'off'), 'off')
  assert.equal(patchDshThinkingProfile(patched), patched)
})

test('Completions serializer sends only the boolean thinking.type for MiMo', () => {
  const patched = patchPiAiMimoThinking(piFixture)
  assert.ok(patched.includes(MIMO_PATCH_MARKER))
  assert.match(patched, /options\.reasoning === "off" \? "off" : "low" : clampThinkingLevel\(model, options\.reasoning\)/)
  const streamSource = patched.match(/export const streamSimple = \(model, context, options\) => \{[\s\S]*?\n\};/)?.[0]
  assert.ok(streamSource)
  const streamSimple = new Function('buildBaseOptions', 'clampThinkingLevel', 'stream', 'getClientApiKey', 'getCompat', `${streamSource.replace('export const', 'const')}; return streamSimple;`)(
    () => ({}),
    (_model, effort) => effort === 'high' ? 'high' : effort,
    (_model, _context, options) => options,
    () => 'test-key',
    (model) => model.compat,
  )
  const legacyModel = { compat: { thinkingFormat: 'mimo' } }
  assert.equal(streamSimple(legacyModel, {}, { reasoning: 'high' }).reasoningEffort, 'low')
  assert.equal(streamSimple(legacyModel, {}, { reasoning: 'off' }).reasoningEffort, 'off')
  const branch = patched.match(/if \(compat\.thinkingFormat === "mimo"[\s\S]*?\n    }\n    else if/)
  assert.ok(branch)
  assert.match(branch[0], /params\.thinking = \{ type: options\?\.reasoningEffort === "off" \? "disabled" : "enabled" \}/)
  assert.doesNotMatch(branch[0], /reasoning_effort/)
  assert.equal(patchPiAiMimoThinking(patched), patched)
})

test('assistant reasoning_content survives multi-turn replay and missing turns receive the required empty field', () => {
  const patched = patchPiAiMimoThinking(piFixture)
  // The SSE parser retains the incoming field name on its thinking block.
  assert.match(patched, /const reasoningFields = \["reasoning_content", "reasoning", "reasoning_text"\]/)
  assert.match(patched, /const thinkingSignature = model\.provider === "opencode-go" && foundReasoningField === "reasoning"[\s\S]*?\? "reasoning_content"[\s\S]*?: foundReasoningField/)

  // Execute the serializer from the real patched artifact. Stub only imported
  // helpers; the assistant message construction and replay logic stay intact.
  const start = patched.indexOf('export function convertMessages(')
  const end = patched.indexOf('\nfunction convertTools(', start)
  assert.ok(start >= 0 && end > start)
  const convertSource = patched.slice(start, end).replace('export function', 'function')
  const convertMessages = new Function(
    'resolveTranscript', 'transformMessages', 'resolveTranscriptTools',
    'getSystemMessageText', 'renderSystemMessageUpdate', 'sanitizeSurrogates',
    'convertTools', 'isTextContentBlock', 'isThinkingContentBlock',
    'isToolCallBlock', 'parseOpenAIReasoningDetails',
    'parseLegacyEncryptedReasoningDetail', 'isOpenAICompletionsReasoningField',
    `${convertSource}; return convertMessages;`,
  )(
    (context) => context,
    (messages) => messages,
    () => ({ anchorsAdditions: false }),
    (message) => message.content,
    (message) => message.content,
    (text) => text,
    () => [],
    (block) => block.type === 'text',
    (block) => block.type === 'thinking',
    (block) => block.type === 'toolCall',
    () => undefined,
    () => undefined,
    (field) => ['reasoning', 'reasoning_content', 'reasoning_text'].includes(field),
  )
  const replay = convertMessages(
    { provider: 'mimo', reasoning: true },
    { messages: [{ role: 'assistant', content: [
      { type: 'thinking', thinking: 'first turn rationale', thinkingSignature: 'reasoning_content' },
      { type: 'text', text: 'answer' },
    ] }] },
    { supportsMidConvoSystemMessages: false },
    {},
  )
  assert.equal(replay[0].reasoning_content, 'first turn rationale')

  const missingReasoning = convertMessages(
    { provider: 'mimo', reasoning: true },
    { messages: [{ role: 'assistant', content: [{ type: 'text', text: 'answer' }] }] },
    { supportsMidConvoSystemMessages: false, requiresReasoningContentOnAssistantMessages: true },
    {},
  )
  assert.equal(missingReasoning[0].reasoning_content, '')
})
