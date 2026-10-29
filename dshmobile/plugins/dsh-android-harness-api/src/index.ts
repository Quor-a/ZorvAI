/**
 * dsh-android-harness-api — ZorvAI ⇄ DeepSeek Harness 互控 API 面
 *
 * 定位（2026-10-29 用户诉状「哪开发个插件出来给 zorvAI 一个 api 接口让 zorvAI
 * 任意调用他的插件，技能 skills 等等不就行了」的直接回应）：
 * 引擎里已装配的插件工具（zorvai_*、mcp__*、bash、web 搜索……）与技能库
 * （dsh-skill 注册表，含 #505 修复后的 dsh-plugin-store）此前只能被引擎内的
 * 模型经 LLM 工具调用面触达；ZorvAI 应用侧的 AI 没有任何入口列举/调用它们。
 * 本插件把引擎的 ToolRuntime 与 SkillRegistry 以 HTTP JSON 面暴露在引擎自己的
 * webServer 上（127.0.0.1，不出设备），ZorvAI 侧由此获得四类能力：
 *
 *   ① GET  /api/harness/tools       — 列举全部可见工具（name/description/parameters）
 *   ② POST /api/harness/tools/call  — 以引擎全管线执行任一工具（visibility、
 *                                     UNKNOWN_TOOL、PTC collapse、审批门全部保留）
 *   ③ GET  /api/harness/skills      — 列举技能目录（SkillSummary）
 *   ④ POST /api/harness/skills/load — 加载任一技能完整定义（含 content 正文）
 *
 * 安全：四条路由与 open-in-app / 商城 U2 同款——每个请求先问 composition 的
 * connection 服务 `requestRejection(req)`（Host/Origin 栅栏挡 DNS rebinding 与
 * 跨站调用，登录 cookie 挡未认证调用），拒绝即空体结束，不进任何业务逻辑。
 * 请求体 1MiB 上限（超限排空后 400）；工具调用默认 120s、上限 600s 超时，
 * 由调用方以 timeoutMs 指定；execute 绝不抛错——失败一律转错误 JSON。
 *
 * 边界：本插件只做「注册表投影 + 调用转发」，不持有任何工具/技能业务逻辑；
 * 全部执行发生在引擎进程内（与模型走同一 ToolRuntime，语义完全一致）。
 * 技能加载面的 500（validateDefinition 抛错）同时是 #505 market-skill-source
 * 修复效果的自诊断探针。
 *
 * 🔴 装配纪律（2026-10-30 真机事故，引擎启动即失败）：
 * 引擎启动要求**全部插件激活成功**——插件 fiber 的静态 inject 里任一服务不到位，
 * fiber 永久 pending，整台引擎被拖死（用户现场形态：「插件加载不了 + 起不来」）。
 * 因此静态 inject 只允许保留 apply 期**必须**的服务（webServer = 路由载体）；
 * tools / skills / connection 全部请求期 `ctx.get(name)` 懒读——cordis 对缺席
 * 服务返回 undefined 不抛错，且 strict 模式只认激活态实现，比静态 inject 安全。
 * 兄弟插件（session-delete / bridge / browser / undo / marketplace）无一静态
 * inject connection，即此纪律的设备实证。connection 缺席时本插件 fail-closed
 * （401，商城 U2 同语义）；tools/skills 缺席转可读 500，路由照常注册。
 */
import { Context } from '@deepseek-ai/cordis'
import type { ToolExecutionInput, ToolExecutionResult } from '@deepseek-ai/dsh-tools'
import type { SkillDefinition, SkillSummary } from '@deepseek-ai/dsh-skill'
import type { WebRoute } from '@deepseek-ai/dsh-host-webserver'
import type { IncomingMessage, ServerResponse } from 'node:http'

export const name = 'dsh-android-harness-api'
// 🔴 只 inject webServer——apply 期唯一必须的服务（路由载体）。
// tools/skills/connection 一律请求期 ctx.get() 懒读：静态 inject 任一请求期
// 服务 = 给整台引擎的启动埋永久 pending 的雷（2026-10-30 真机事故）。
export const inject = ['webServer'] as const

// ── 常量 ────────────────────────────────────────────────────────────────────
/** 工具列举路由（GET）。 */
const ROUTE_TOOLS = '/api/harness/tools'
/** 工具调用路由（POST，body: {name, arguments?, timeoutMs?}）。 */
const ROUTE_TOOLS_CALL = '/api/harness/tools/call'
/** 技能列举路由（GET）。 */
const ROUTE_SKILLS = '/api/harness/skills'
/** 技能加载路由（POST，body: {name}）。 */
const ROUTE_SKILLS_LOAD = '/api/harness/skills/load'
/** 请求体硬上限（字节）：工具实参 JSON 再大也够用；超限视为恶意。 */
const MAX_BODY_BYTES = 1024 * 1024
/** 工具调用默认超时（ms）：读屏/长任务给足 120s。 */
const DEFAULT_TIMEOUT_MS = 120_000
/** 工具调用超时上限（ms）：调用方可下调，不可上调超过此值。 */
const MAX_TIMEOUT_MS = 600_000
/** 扁平回执文本截断上限（字符）：防超大工具输出撑爆 HTTP 响应。 */
const MAX_TEXT_CHARS = 200_000

// ── 类型 ────────────────────────────────────────────────────────────────────
/**
 * connection 服务的最小本地形态（上游 open-in-app 同款：其包是浏览器侧，
 * 不引入依赖，只取 requestRejection 这一个方法）。
 */
interface ConnectionService {
  requestRejection(req: IncomingMessage): number | undefined
}

/** 工具调用请求体。arguments 原样透传（工具自校验 schema）。 */
interface ToolCallBody {
  name: string
  arguments?: unknown
  timeoutMs?: number
}

/** 技能加载请求体。 */
interface SkillLoadBody {
  name: string
}

/** 工具调用响应体。ok=false 仅表示 API 面自身失败；工具级失败看 isError。 */
interface ToolCallResponse {
  ok: boolean
  name: string
  callId: string
  isError: boolean
  durationMs: number
  text: string
  content: ToolExecutionResult['content']
  value?: unknown
  error?: { message: string; info?: unknown }
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

// ── HTTP 面（响应 / 请求体 / 鉴权）──────────────────────────────────────────
/** JSON 响应（no-store：注册表与执行结果都是活事实，不缓存）。 */
function sendJson(res: ServerResponse, status: number, payload: unknown): void {
  res.statusCode = status
  res.setHeader('content-type', 'application/json; charset=utf-8')
  res.setHeader('cache-control', 'no-store')
  res.end(JSON.stringify(payload))
}

/** 拒绝响应：空体（上游 open-in-app 纪律；调用方按状态码分流 403/401）。 */
function sendRejection(res: ServerResponse, status: number): void {
  res.statusCode = status
  res.setHeader('cache-control', 'no-store')
  res.end()
}

/** 405 带 allow 头（单路由单方法）。 */
function sendMethodNotAllowed(res: ServerResponse, allow: string): void {
  res.statusCode = 405
  res.setHeader('allow', allow)
  res.end()
}

/**
 * 读取有界请求体（UTF-8 文本）；超上限返回 null 并排空流（防半读挂起）。
 * 与 open-in-app 的 readBoundedBody 同构。
 */
async function readBoundedBody(req: IncomingMessage): Promise<string | null> {
  const chunks: Buffer[] = []
  let size = 0
  for await (const chunk of req) {
    size += chunk.byteLength
    if (size > MAX_BODY_BYTES) {
      req.resume()
      return null
    }
    chunks.push(chunk as Buffer)
  }
  return Buffer.concat(chunks, size).toString('utf8')
}

/**
 * 请求期懒读服务（cordis ctx.get，strict 语义）：缺席/未激活返回 undefined，
 * 绝不抛错、绝不给 fiber 制造依赖。这是本插件不静态 inject 任何请求期服务
 * 的落地机制——见文件头「装配纪律」。
 */
function serviceOf<T>(ctx: Context, name: string): T | undefined {
  try {
    const get = Reflect.get(ctx, 'get') as ((n: string, strict?: boolean) => unknown) | undefined
    if (typeof get !== 'function') return undefined
    return get.call(ctx, name) as T | undefined
  } catch {
    return undefined
  }
}

/** connection 服务（懒读；其包是浏览器侧，本地最小类型声明即可）。 */
function connectionOf(ctx: Context): ConnectionService | undefined {
  return serviceOf<ConnectionService>(ctx, 'connection')
}

/** tools 服务（ToolRuntime）的最小本地形态（懒读取用）。 */
interface ToolsService {
  schemas(): Array<{
    name: string
    description: string
    parameters: Record<string, unknown>
    deferLoading?: boolean
  }>
  execute(input: {
    callId: unknown
    name: string
    arguments: unknown
    signal: AbortSignal
  }): Promise<ToolExecutionResult>
}

/** tools 服务（ToolRuntime，懒读）。 */
function toolsOf(ctx: Context): ToolsService | undefined {
  return serviceOf<ToolsService>(ctx, 'tools')
}

/** skills 服务（SkillRegistry）的最小本地形态（懒读取用）。 */
interface SkillsService {
  list(): Promise<SkillSummary[]>
  get(name: string): Promise<SkillDefinition | undefined>
}

/** skills 服务（SkillRegistry，懒读）。 */
function skillsOf(ctx: Context): SkillsService | undefined {
  return serviceOf<SkillsService>(ctx, 'skills')
}

/**
 * 信任栅栏：请求被拒时已结束响应并返回 true，调用方须立即 return。
 * 403 = Host/Origin 不合（含 DNS rebinding / 跨站）；401 = 未认证。
 * 栅栏服务缺席 = fail-closed 401（商城 U2 同语义）；只有栅栏在场且
 * requestRejection 可用时才由它裁决。
 */
function rejected(ctx: Context, req: IncomingMessage, res: ServerResponse): boolean {
  let rejection: number | undefined = 401
  try {
    const connection = connectionOf(ctx)
    if (connection && typeof connection.requestRejection === 'function') {
      rejection = connection.requestRejection(req)
    }
  } catch {
    rejection = 401
  }
  if (rejection === undefined) return false
  sendRejection(res, rejection)
  return true
}

// ── 内容扁平化 ──────────────────────────────────────────────────────────────
/** 把 content blocks 的文本块拼成回执文本（非文本块记一行占位）。 */
function flattenContent(content: ToolExecutionResult['content']): string {
  const parts: string[] = []
  for (const block of content) {
    if (isRecord(block) && block.type === 'text' && typeof block.text === 'string') {
      parts.push(block.text)
    } else if (isRecord(block) && typeof block.type === 'string') {
      parts.push('[' + block.type + ' block]')
    }
  }
  const text = parts.join('\n')
  return text.length > MAX_TEXT_CHARS ? text.slice(0, MAX_TEXT_CHARS) + '\n…（回执超长已截断）' : text
}

/** callId 生成。Branded<'ToolCallId'> 是 string 的幻影品牌，直接断言无语义损失。 */
function newCallId(): ToolExecutionInput['callId'] {
  const raw = 'harness-api-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
  return raw as ToolExecutionInput['callId']
}

// ── 路由处理 ────────────────────────────────────────────────────────────────
/** GET /api/harness/tools — 列举全局可见工具（模型面 schema 白名单字段）。 */
async function handleToolsList(ctx: Context, req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (rejected(ctx, req, res)) return
  if (req.method !== 'GET') {
    sendMethodNotAllowed(res, 'GET')
    return
  }
  let tools: ToolExecutionResponse['tools']
  try {
    const runtime = toolsOf(ctx)
    if (!runtime) {
      sendJson(res, 500, { ok: false, error: 'tools 服务未激活：引擎内无 ToolRuntime（装配异常）' })
      return
    }
    // schemas() 已做可见性投影 + 深拷贝，直接透传模型面字段。
    tools = runtime.schemas().map((s) => ({
      name: s.name,
      description: s.description,
      parameters: s.parameters,
      ...(s.deferLoading === true ? { deferLoading: true as const } : {}),
    }))
  } catch (e) {
    sendJson(res, 500, { ok: false, error: '工具注册表投影失败：' + errorText(e) })
    return
  }
  sendJson(res, 200, { ok: true, count: tools.length, tools })
}

/** POST /api/harness/tools/call — 以引擎全管线执行一个工具。 */
async function handleToolCall(ctx: Context, req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (rejected(ctx, req, res)) return
  if (req.method !== 'POST') {
    sendMethodNotAllowed(res, 'POST')
    return
  }
  const raw = await readBoundedBody(req)
  if (raw === null) {
    sendJson(res, 400, { ok: false, error: '请求体超过 ' + MAX_BODY_BYTES + ' 字节上限' })
    return
  }
  let body: ToolCallBody
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!isRecord(parsed) || typeof parsed.name !== 'string' || parsed.name === '') {
      sendJson(res, 400, { ok: false, error: '请求体必须是含非空 name 字符串的 JSON 对象' })
      return
    }
    // 上面的窄化已保证 name 是非空字符串；双断言仅作用于类型层。
    body = parsed as unknown as ToolCallBody
  } catch {
    sendJson(res, 400, { ok: false, error: '请求体不是合法 JSON' })
    return
  }
  // 超时：调用方可指定，夹在 [1, MAX_TIMEOUT_MS]；缺省 DEFAULT_TIMEOUT_MS。
  let timeoutMs = DEFAULT_TIMEOUT_MS
  if (typeof body.timeoutMs === 'number' && Number.isFinite(body.timeoutMs)) {
    timeoutMs = Math.min(Math.max(Math.floor(body.timeoutMs), 1), MAX_TIMEOUT_MS)
  }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  const startedAt = Date.now()
  const callId = newCallId()
  let result: ToolExecutionResult
  try {
    const runtime = toolsOf(ctx)
    if (!runtime) {
      sendJson(res, 500, { ok: false, error: 'tools 服务未激活：引擎内无 ToolRuntime（装配异常）' })
      return
    }
    // 全管线：visibility 判定、UNKNOWN_TOOL、PTC collapse、审批门（无 agent 时
    // fail-closed deny，不挂起）全部保留——与模型发起的调用走同一条路。
    result = await runtime.execute({
      callId,
      name: body.name,
      arguments: body.arguments ?? {},
      signal: controller.signal,
    })
  } catch (e) {
    // execute 契约上失败在带内返回；抛错只可能是装配级异常，转 500 不挂请求。
    sendJson(res, 500, { ok: false, error: '工具执行管线异常：' + errorText(e), name: body.name, callId })
    return
  } finally {
    clearTimeout(timer)
  }
  const response: ToolCallResponse = {
    ok: true,
    name: body.name,
    callId: String(callId),
    isError: result.isError,
    durationMs: Date.now() - startedAt,
    text: flattenContent(result.content),
    content: result.content,
  }
  if (result.isError) {
    response.error = { message: result.error.message, info: result.error.info }
  } else if ('value' in result) {
    response.value = result.value
  }
  sendJson(res, 200, response)
}

/** GET /api/harness/skills — 列举技能目录（invocation-neutral 摘要）。 */
async function handleSkillsList(ctx: Context, req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (rejected(ctx, req, res)) return
  if (req.method !== 'GET') {
    sendMethodNotAllowed(res, 'GET')
    return
  }
  let skills: SkillSummary[]
  try {
    const registry = skillsOf(ctx)
    if (!registry) {
      sendJson(res, 500, { ok: false, error: 'skills 服务未激活：引擎内无 SkillRegistry（装配异常）' })
      return
    }
    skills = await registry.list()
  } catch (e) {
    sendJson(res, 500, { ok: false, error: '技能目录读取失败：' + errorText(e) })
    return
  }
  sendJson(res, 200, { ok: true, count: skills.length, skills })
}

/** POST /api/harness/skills/load — 加载一个技能的完整定义（含正文）。 */
async function handleSkillLoad(ctx: Context, req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (rejected(ctx, req, res)) return
  if (req.method !== 'POST') {
    sendMethodNotAllowed(res, 'POST')
    return
  }
  const raw = await readBoundedBody(req)
  if (raw === null) {
    sendJson(res, 400, { ok: false, error: '请求体超过 ' + MAX_BODY_BYTES + ' 字节上限' })
    return
  }
  let body: SkillLoadBody
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!isRecord(parsed) || typeof parsed.name !== 'string' || parsed.name === '') {
      sendJson(res, 400, { ok: false, error: '请求体必须是含非空 name 字符串的 JSON 对象' })
      return
    }
    // 上面的窄化已保证 name 是非空字符串；双断言仅作用于类型层。
    body = parsed as unknown as SkillLoadBody
  } catch {
    sendJson(res, 400, { ok: false, error: '请求体不是合法 JSON' })
    return
  }
  let skill: SkillDefinition | undefined
  try {
    const registry = skillsOf(ctx)
    if (!registry) {
      sendJson(res, 500, { ok: false, error: 'skills 服务未激活：引擎内无 SkillRegistry（装配异常）' })
      return
    }
    // get() 的 validateDefinition 对坏定义会抛错（#505 的 source 缺失即此路径）；
    // 抛错转 500 带原文——同时是该修复效果的自诊断面。
    skill = await registry.get(body.name)
  } catch (e) {
    sendJson(res, 500, { ok: false, error: '技能定义校验失败：' + errorText(e), name: body.name })
    return
  }
  if (skill === undefined) {
    sendJson(res, 404, { ok: false, error: '技能不存在：' + body.name, name: body.name })
    return
  }
  sendJson(res, 200, { ok: true, name: body.name, skill })
}

function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** 工具列举响应体（局部声明，避免把 dsh-llm 的 ToolSchema 拖进公共面）。 */
interface ToolExecutionResponse {
  tools: Array<{
    name: string
    description: string
    parameters: Record<string, unknown>
    deferLoading?: true
  }>
}

// ── 插件入口 ────────────────────────────────────────────────────────────────
export function apply(ctx: Context, _config: Record<string, unknown> = {}) {
  // 每条路由经 ctx.effect 注册：插件卸载时 disposer 自动摘除，
  // 与 open-in-app 同款生命周期纪律（重复 (kind,path) 会抛错——装配级事故，
  // 不静默吞）。
  //
  // 鉴权 guard 内联在**每个注册块内**（不是只藏在 handler 函数里）：api-route-auth
  // 门禁按注册块判定 guard 在场；同时这是 fail-closed 纪律——守卫写在装配点，
  // 任何人改路由都绕不过它。handler 内的二次判定是兜底（见各 handle* 首行）。
  ctx.effect(
    () =>
      ctx.webServer.register({
        kind: 'exact',
        path: ROUTE_TOOLS,
        handler: (req, res) => {
          if (rejected(ctx, req, res)) return
          return handleToolsList(ctx, req, res)
        },
      } satisfies WebRoute),
    'harness-api: GET ' + ROUTE_TOOLS,
  )
  ctx.effect(
    () =>
      ctx.webServer.register({
        kind: 'exact',
        path: ROUTE_TOOLS_CALL,
        handler: (req, res) => {
          if (rejected(ctx, req, res)) return
          return handleToolCall(ctx, req, res)
        },
      } satisfies WebRoute),
    'harness-api: POST ' + ROUTE_TOOLS_CALL,
  )
  ctx.effect(
    () =>
      ctx.webServer.register({
        kind: 'exact',
        path: ROUTE_SKILLS,
        handler: (req, res) => {
          if (rejected(ctx, req, res)) return
          return handleSkillsList(ctx, req, res)
        },
      } satisfies WebRoute),
    'harness-api: GET ' + ROUTE_SKILLS,
  )
  ctx.effect(
    () =>
      ctx.webServer.register({
        kind: 'exact',
        path: ROUTE_SKILLS_LOAD,
        handler: (req, res) => {
          if (rejected(ctx, req, res)) return
          return handleSkillLoad(ctx, req, res)
        },
      } satisfies WebRoute),
    'harness-api: POST ' + ROUTE_SKILLS_LOAD,
  )
}
