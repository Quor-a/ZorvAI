/**
 * dsh-android-zorvai-tools — ZorvAI 宿主能力内置插件通道（互控多通道之一）
 *
 * 定位（2026-10-26 用户裁定「mCP 是一种，内置插件也是一种，多通道应该做出来」）：
 * ZorvAI（Android 应用，com.ai.assistance.quro）与 DeepSeek Harness 引擎之间的互控，
 * 同一批 ZorvAI 工具走两条通道暴露给引擎里的模型：
 *   ① MCP 通道（app 侧运行时写入）：工具名 mcp__zorvai__*（HarnessBridge 经
 *      @deepseek-ai/dsh-mcp-client 挂本地 MCP server，/mcp JSON-RPC 面）；
 *   ② 内置插件通道（本插件，随快照装配）：工具名 zorvai_*，execute 走壳侧
 *      127.0.0.1 纯 HTTP 面 POST /tool/<name>（与 /mcp 同源同分发器）。
 * 两条通道调用同一批工具；一条失败换另一条即可，语义等价。
 *
 * 零配置发现：apply 时读 <dshHome>/zorvai-tools.json（壳侧 HarnessBridge.enable 写的
 * 端点清单，含 127.0.0.1 本地服务器地址与工具定义）。清单缺席（桥未启用/从未启用）时
 * 只注册元工具 zorvai_bridge，引擎照常启动——不因宿主桥缺席而炸 boot。
 * 每次 execute 前重读清单：壳侧重部署换随机端口后无需重启引擎。
 *
 * 工具面预算：动态工具一律 deferLoading:true（引擎渐进披露，初始可见集不膨胀）；
 * 元工具 zorvai_bridge 常驻可见，供模型自诊断桥状态并 refresh 补注册。
 *
 * 边界：本插件只做「调用转发 + 状态自诊断」，不持有任何 ZorvAI 业务逻辑；
 * 全部执行发生在 ZorvAI 应用进程内的本地服务器上（127.0.0.1，不出设备）。
 */
import { Context } from '@deepseek-ai/cordis'
import { defineTool, type DefineToolOptions, type ParameterSchemaSpec } from '@deepseek-ai/dsh-tools'
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { request } from 'node:http'

export const name = 'dsh-android-zorvai-tools'
// 只依赖 tools 注册面；dshHomePath 经 ctx.get 可选读取（cordis 代理对未 inject 的
// 非注册属性**读取**会抛错，故一律走 ctx.get 而不是 ctx.dshHomePath）。
export const inject = ['tools'] as const

// ── 常量 ────────────────────────────────────────────────────────────────────
/** 内置插件通道的工具名前缀（与 MCP 通道的 mcp__zorvai__ 前缀区分）。 */
const TOOL_PREFIX = 'zorvai_'
/** 元工具名（常驻可见；literal 形态，供源码级注册完整性门禁比对）。 */
const META_TOOL_NAME = 'zorvai_bridge'
/** 壳侧 HarnessBridge.enable 写的端点清单文件名（落在 <dshHome> 根下）。 */
const MANIFEST_FILE = 'zorvai-tools.json'
/** 单次桥调用超时（ms）。ZorvAI 侧读屏/应用管理等操作可能较慢，给足 65s。 */
const BRIDGE_TIMEOUT_MS = 65_000
/** 回执文本硬上限（字符）：防壳侧异常payload撑爆会话转录。 */
const MAX_TEXT_CHARS = 1_000_000

// ── 类型 ────────────────────────────────────────────────────────────────────
interface ManifestToolDef {
  name: string
  description?: string
  parameters?: unknown
}
interface BridgeManifest {
  version?: number
  alias?: string
  baseUrl?: string
  toolPath?: string
  updatedAt?: string
  tools?: ManifestToolDef[]
}
/** 桥调用结果（execute 的返回值必须是 lossless JSON：无 undefined 成员）。 */
interface BridgeResult {
  ok: boolean
  text: string
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}
function isScalar(v: unknown): boolean {
  return v === null || typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean'
}

// ── output.schema（值面 DSL：object 必须显式 additionalProperties；逐属性 required:true）──
// as const：让 defineTool 的 const 泛型推断出字面量类型（execute 返回值据此精确匹配）。
const BRIDGED_OUTPUT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    ok: { type: 'boolean', required: true },
    text: { type: 'string', required: true },
  },
} as const

const META_OUTPUT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    ok: { type: 'boolean', required: true },
    connected: { type: 'boolean', required: true },
    baseUrl: { type: 'string', required: true },
    toolCount: { type: 'integer', required: true },
    tools: { type: 'array', required: true, items: { type: 'string' } },
    manifestPath: { type: 'string', required: true },
    text: { type: 'string', required: true },
  },
} as const

// ── 端点清单（manifest）读写 ────────────────────────────────────────────────
/**
 * 清单路径：优先引擎提供的 dshHomePath(...)（<filesDir>/home/.dsh/zorvai-tools.json）；
 * 缺席（单测夹具/非启动器宿主）时退到 DSH_HOME 环境变量或 ~/.dsh，与 dsh-session-delete
 * 的口径一致。任何一步异常都只影响「拿不到路径」，由调用方按「桥未连接」处理。
 */
function manifestPathFor(ctx: Context): string {
  const homePath = ctx.get('dshHomePath') as ((...segments: string[]) => string) | undefined
  if (typeof homePath === 'function') {
    try {
      return homePath(MANIFEST_FILE)
    } catch {
      /* 落到环境变量/家目录分支 */
    }
  }
  const envHome = process.env.DSH_HOME
  const base = typeof envHome === 'string' && envHome !== '' ? envHome : join(homedir(), '.dsh')
  return join(base, MANIFEST_FILE)
}

/** 读端点清单；任何不合格（缺席/损坏/baseUrl 或 tools 缺失）都返回 undefined = 桥未连接。 */
function readManifest(manifestPath: string): BridgeManifest | undefined {
  let raw: string
  try {
    raw = readFileSync(manifestPath, 'utf8')
  } catch {
    return undefined
  }
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!isRecord(parsed)) return undefined
    if (typeof parsed.baseUrl !== 'string' || parsed.baseUrl === '') return undefined
    if (!Array.isArray(parsed.tools)) return undefined
    return parsed as BridgeManifest
  } catch {
    return undefined
  }
}

// ── 参数面转写（ZorvAI JSON Schema → dsh-tools 属性表 DSL）──────────────────
/**
 * dsh-tools 的 parameters 是**属性表**（property-map）：每个属性一个值面节点，
 * required 必须是逐属性 `required: true`（根 required 数组不被接受）；未知键忽略；
 * 畸形 schema 会抛 authorError——故整棵转换逐属性 try/catch，单个坏属性跳过。
 */
function toParameterSpec(schema: unknown): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  if (!isRecord(schema)) return out
  const props = schema.properties
  if (!isRecord(props)) return out
  const required = new Set<string>()
  if (Array.isArray(schema.required)) {
    for (const r of schema.required) if (typeof r === 'string') required.add(r)
  }
  for (const [key, raw] of Object.entries(props)) {
    try {
      const spec = toValueSpec(raw, required.has(key))
      if (spec !== undefined) out[key] = spec
    } catch {
      /* 单个属性畸形：跳过该属性，不影响其它属性与 boot */
    }
  }
  return out
}

/** 单个 JSON Schema 节点 → dsh-tools 值面节点。无法表达的类型退到 'json'（任意 JSON 值）。 */
function toValueSpec(raw: unknown, isRequired: boolean): Record<string, unknown> | undefined {
  if (!isRecord(raw)) return undefined
  const spec: Record<string, unknown> = {}
  const t = raw.type
  if (t === 'string' || t === 'number' || t === 'integer' || t === 'boolean' || t === 'null') {
    spec.type = t
    // enum/const 只对基础类型合法（'json' 节点带 enum 会被 schema 编译器拒）。
    if (Array.isArray(raw.enum) && raw.enum.length > 0 && raw.enum.every(isScalar)) {
      spec.enum = [...raw.enum]
    }
    if (isScalar(raw.const)) spec.const = raw.const
  } else if (t === 'object') {
    spec.type = 'object'
    spec.additionalProperties = true
    if (isRecord(raw.properties)) {
      const nested = toParameterSpec(raw)
      if (Object.keys(nested).length > 0) spec.properties = nested
    }
  } else if (t === 'array') {
    spec.type = 'array'
    const items = toValueSpec(raw.items, false)
    if (items !== undefined) spec.items = items
  } else if (Array.isArray(t)) {
    // type 数组（如 ['string','null']）：取第一个基础类型，取不到退 'json'。
    const first = t.find((x) => x === 'string' || x === 'number' || x === 'integer' || x === 'boolean' || x === 'null')
    spec.type = first !== undefined ? first : 'json'
  } else {
    spec.type = 'json'
  }
  // 注释键（annotation）：description/title/default 对所有节点合法。
  if (typeof raw.description === 'string' && raw.description !== '') spec.description = raw.description
  if (typeof raw.title === 'string' && raw.title !== '') spec.title = raw.title
  if (isScalar(raw.default)) spec.default = raw.default
  if (isRequired) spec.required = true
  return spec
}

// ── 桥调用（node:http，127.0.0.1 本地纯 HTTP 面）────────────────────────────
/**
 * POST <baseUrl><toolPath>/<toolName>，body 为模型实参 JSON。
 * 壳侧约定返回 `{"ok":boolean,"text":string}`（content-type application/json）；
 * 非 JSON 响应按纯文本回执处理。任何网络/超时/解析失败都转成 `{ok:false,text}`——
 * execute 绝不抛错（引擎与门禁都要求「返回错误对象」而不是异常）。
 */
function postToolCall(baseUrl: string, toolPath: string, toolName: string, args: Record<string, unknown>): Promise<BridgeResult> {
  return new Promise((resolve) => {
    let url: URL
    try {
      url = new URL(baseUrl.replace(/\/+$/, '') + toolPath + '/' + encodeURIComponent(toolName))
    } catch {
      resolve({ ok: false, text: '⛔ ZorvAI 桥端点 URL 不合法：' + baseUrl })
      return
    }
    const body = JSON.stringify(args ?? {})
    const req = request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname + url.search,
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'content-length': String(Buffer.byteLength(body)),
        },
        timeout: BRIDGE_TIMEOUT_MS,
      },
      (res) => {
        res.setEncoding('utf8')
        let data = ''
        res.on('data', (chunk: string) => {
          data += chunk
        })
        res.on('end', () => resolve(parseBridgeResponse(data, res.statusCode ?? 0)))
      },
    )
    req.on('timeout', () => {
      req.destroy()
      resolve({ ok: false, text: '⛔ ZorvAI 桥调用超时（' + BRIDGE_TIMEOUT_MS + 'ms）：' + toolName })
    })
    req.on('error', (e: Error) => {
      resolve({ ok: false, text: '⛔ ZorvAI 桥调用失败：' + toolName + ' — ' + (e?.message ?? String(e)) })
    })
    req.end(body)
  })
}

function parseBridgeResponse(body: string, statusCode: number): BridgeResult {
  let parsed: unknown
  try {
    parsed = JSON.parse(body)
  } catch {
    parsed = undefined
  }
  if (isRecord(parsed) && typeof parsed.text === 'string') {
    const ok = typeof parsed.ok === 'boolean' ? parsed.ok : statusCode >= 200 && statusCode < 300
    return { ok, text: capText(parsed.text) }
  }
  const ok = statusCode >= 200 && statusCode < 300
  const text = body.trim() === '' ? '(空响应，HTTP ' + statusCode + ')' : body
  return { ok, text: capText(text) }
}

function capText(text: string): string {
  return text.length > MAX_TEXT_CHARS ? text.slice(0, MAX_TEXT_CHARS) + '\n…（回执超长已截断）' : text
}

/** 动态工具 execute：每次重读清单（壳侧重部署换端口后无需重启引擎）。 */
async function callBridgedTool(manifestPath: string, origName: string, args: Record<string, unknown>): Promise<BridgeResult> {
  const manifest = readManifest(manifestPath)
  if (manifest === undefined) {
    return {
      ok: false,
      text: '⛔ ZorvAI 桥未连接：端点清单缺席（' + manifestPath + '）。ZorvAI 侧 harness 桥可能未启用；'
        + '启用后调用 ' + META_TOOL_NAME + '（refresh=true）刷新工具清单，无需重启引擎。',
    }
  }
  const baseUrl = manifest.baseUrl as string
  const toolPath = typeof manifest.toolPath === 'string' && manifest.toolPath !== '' ? manifest.toolPath : '/tool'
  return postToolCall(baseUrl, toolPath, origName, args)
}

// ── 动态工具构造 ────────────────────────────────────────────────────────────
/**
 * 动态工具的 defineTool 入参类型。
 * S 用 `Record<string, never>` 占位而不是 ParameterSchemaSpec：后者的字符串索引签名会让
 * InferArgs/InferValue 的类型推断递归爆 depth（TS2321）；运行期参数由 parameters 字段
 * 如实携带（双断言仅作用于类型层，字节零损失）。
 */
type BridgedToolOptions = DefineToolOptions<Record<string, never>, typeof BRIDGED_OUTPUT_SCHEMA>

/**
 * 由清单里的一个工具定义造 defineTool 入参。
 * 注意形态：调用点写 `defineTool(buildBridgedTool(...))`（实参是函数返回值，不是对象
 * 字面量）——源码级 defineTool({name}) 比对门禁只认字面量/常量/映射表三种 name 形态，
 * 动态工具经 helper 构造才能让「源码声明集 vs 运行时注册集」差集为 0。
 */
function buildBridgedTool(origName: string, toolName: string, def: ManifestToolDef, manifestPath: string): BridgedToolOptions {
  const desc = typeof def.description === 'string' && def.description !== '' ? def.description : 'ZorvAI 工具 ' + origName
  return {
    name: toolName,
    description: '【ZorvAI 宿主能力·内置插件通道】' + desc,
    parameters: toParameterSpec(def.parameters) as unknown as Record<string, never>,
    output: {
      schema: BRIDGED_OUTPUT_SCHEMA,
      render: (_args, value) => [{ type: 'text', text: textOrFallback(value) }],
    },
    deferLoading: true,
    execute: async (args) => callBridgedTool(manifestPath, origName, args as Record<string, unknown>),
  }
}

function textOrFallback(value: Record<string, unknown>): string {
  const t = typeof value?.text === 'string' ? value.text : ''
  return t.trim() === '' ? '(no output)' : t
}

/**
 * 按当前清单补注册动态工具（幂等：已注册的跳过；单个工具畸形只跳过它自己）。
 * @returns 新注册数量。
 */
function syncTools(ctx: Context, manifestPath: string, registered: Set<string>): number {
  const manifest = readManifest(manifestPath)
  if (manifest === undefined) return 0
  const defs = Array.isArray(manifest.tools) ? manifest.tools : []
  let added = 0
  for (const def of defs) {
    if (!isRecord(def)) continue
    const origName = def.name
    if (typeof origName !== 'string' || !/^[A-Za-z][A-Za-z0-9_]*$/.test(origName)) continue
    const toolName = TOOL_PREFIX + origName
    if (registered.has(toolName)) continue
    try {
      // 显式泛型实参：入参是 BridgedToolOptions 类型的变量，让 defineTool 自己推断 S/O 会走
      // 函数型成员的反向推断，把 InferObject 递归爆 depth（TS2321）；显式给出即只做一次
      // 结构校验，不再推断。
      ctx.tools.register(defineTool<Record<string, never>, typeof BRIDGED_OUTPUT_SCHEMA>(buildBridgedTool(origName, toolName, def as ManifestToolDef, manifestPath)))
      registered.add(toolName)
      added += 1
    } catch {
      /* 单个工具畸形（如 schema 编译抛错）：跳过，不炸 boot */
    }
  }
  return added
}

// ── 插件入口 ────────────────────────────────────────────────────────────────
export function apply(ctx: Context, _config: Record<string, unknown> = {}) {
  const manifestPath = manifestPathFor(ctx)
  const registered = new Set<string>()

  // 元工具：常驻可见（不带 deferLoading）。桥未启用时它是在场唯一可见的 ZorvAI 工具，
  // 供模型自诊断桥状态；refresh=true 时补注册清单里新出现的工具（无需重启引擎）。
  // 名字用字面量（不是常量引用）：源码级注册完整性门禁的字面量分支直接采集，无需依赖
  // nameConsts 常量表解析——声明集与注册集的差集判据在最简形态下成立。
  ctx.tools.register(
    defineTool({
      name: 'zorvai_bridge',
      description:
        'ZorvAI 互控桥状态自诊断（内置插件通道）。报告 ZorvAI 宿主桥的连接状态、端点地址与已注册的 '
        + TOOL_PREFIX + '* 工具清单；refresh=true 时强制重读端点清单并补注册新出现的工具。'
        + 'ZorvAI 桥未启用时，它是在场唯一可见的 ZorvAI 工具。',
      parameters: {
        refresh: {
          type: 'boolean',
          description: 'true = 强制重读端点清单（' + MANIFEST_FILE + '）并补注册新工具；默认 false，仅报告当前状态',
        },
      },
      output: {
        schema: META_OUTPUT_SCHEMA,
        render: (_args, value) => [{ type: 'text', text: textOrFallback(value) }],
      },
      execute: async (args) => {
        const refresh = args?.refresh === true
        if (refresh) {
          try {
            syncTools(ctx, manifestPath, registered)
          } catch {
            /* 刷新失败不改变报告本身 */
          }
        }
        const manifest = readManifest(manifestPath)
        const connected = manifest !== undefined
        const baseUrl = connected ? String(manifest.baseUrl) : ''
        const names = [...registered].filter((n) => n !== META_TOOL_NAME).sort()
        const updatedNote =
          connected && typeof manifest.updatedAt === 'string' && manifest.updatedAt !== ''
            ? '（清单更新于 ' + manifest.updatedAt + '）'
            : ''
        const lines: string[] = []
        if (connected) {
          lines.push('ZorvAI 桥已连接：' + baseUrl + updatedNote)
          lines.push('已注册 ZorvAI 工具 ' + names.length + ' 个（' + TOOL_PREFIX + ' 前缀，渐进披露）')
          if (names.length > 0) lines.push('工具清单：' + names.join(', '))
          lines.push('提示：这些工具与 MCP 通道（mcp__zorvai__*）是同一批 ZorvAI 能力，任选其一，一条失败换另一条重试。')
        } else {
          lines.push('ZorvAI 桥未连接：端点清单缺席（' + manifestPath + '）。')
          lines.push('ZorvAI 侧 harness 桥未启用或已被关闭；启用后调用本工具（refresh=true）即可补注册工具，无需重启引擎。')
        }
        return {
          ok: true,
          connected,
          baseUrl,
          toolCount: names.length,
          tools: names,
          manifestPath,
          text: lines.join('\n'),
        }
      },
    }),
  )
  registered.add(META_TOOL_NAME)

  // 端点清单在场（桥已启用）→ 注册全部 ZorvAI 工具（deferLoading 渐进披露）。
  // 启动期读不到清单只留元工具，引擎照常起——宿主桥缺席不炸 boot。
  try {
    syncTools(ctx, manifestPath, registered)
  } catch {
    /* 同上：清单异常不影响引擎启动 */
  }
}
