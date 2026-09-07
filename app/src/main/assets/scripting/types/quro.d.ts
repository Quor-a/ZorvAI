/**
 * ZorvAI SandboxPackage 运行时类型声明（全局环境）
 * ==================================================
 * 在 QuickJS 脚本沙箱（code_runner / ToolPkg / run_code js·ts 分支）中，
 * 每个 .js / .ts 文件都自动拥有以下全局变量。TS 文件由运行时自动转译为 JS 执行。
 *
 * 用法：把本目录加入 tsconfig 的 include，或在文件头写
 *   /// <reference path="../types/quro.d.ts" />
 */

/* ===================== Tools：宿主 API ===================== */

interface FsStat {
  name: string;
  isDir: boolean;
  size: number;
  lastModified: number;
}

interface FileEntry {
  name: string;
  isDir: boolean;
  size: number;
}

interface FetchResult {
  ok: boolean;
  status: number;
  body: string;
  headers: Record<string, string>;
}

interface FetchOptions {
  method?: "GET" | "POST" | "PUT" | "DELETE" | "PATCH";
  headers?: Record<string, string>;
  body?: string;
  timeoutMs?: number;
}

interface SystemInfo {
  device: string;
  model: string;
  brand: string;
  androidVersion: string;
  sdk: number;
  app: string;
  screen: string;
  totalMemMB: number;
  availMemMB: number;
}

interface SystemEnv {
  workspaceRoot: string;
  platform: string;
}

interface MediaState {
  /** MediaProjection 是否已授权注入（用户长按「看懂屏幕」后为 true）。 */
  attached: boolean;
  /** VirtualDisplay 抓帧循环是否正在运行。 */
  running: boolean;
  /** 控制器状态：Idle / Starting / Running / Stopped / Error。 */
  state: string;
  lastError: string | null;
}

interface MediaScreenshotResult {
  ok: boolean;
  /** 工作区相对路径（Tools.Files 可直接读）。 */
  path: string;
  /** 绝对路径（供附件 / 视觉分析复用）。 */
  file: string;
  width: number;
  height: number;
  bytes: number;
}

interface MediaScreenshotOptions {
  /** 最长边像素（320–4096，默认 1280）。 */
  maxEdge?: number;
  /** JPEG 质量 10–100（默认 80）。 */
  quality?: number;
  /** 文件名（仅英文/数字/横线/下划线/点；默认 shot_<时间戳>.jpg，存工作区 screenshots/）。 */
  filename?: string;
}

interface ToolsApi {
  Files: {
    /** 读文件文本（.ts 自动转译为 JS 后返回；限工作区内）。 */
    read(path: string): string | { error: string };
    /** 写文件（整体覆盖），返回 { bytes }。 */
    write(path: string, content: string): { bytes: number } | { error: string };
    append(path: string, content: string): { ok: boolean } | { error: string };
    /** 列目录：{name,isDir,size}[]（目录在前）。 */
    list(path: string): FileEntry[] | { error: string };
    exists(path: string): boolean;
    remove(path: string): { ok: boolean } | { error: string };
    mkdir(path: string): { ok: boolean } | { error: string };
    stat(path: string): FsStat | { error: string };
  };
  Net: {
    /** HTTP 请求（http/https，默认 15s 超时，响应上限 1MB）。 */
    fetch(url: string, options?: FetchOptions): FetchResult | { error: string };
    get(url: string, headers?: Record<string, string>): FetchResult | { error: string };
    post(url: string, body: unknown, headers?: Record<string, string>): FetchResult | { error: string };
  };
  System: {
    /** 设备/运行时信息。 */
    info(): SystemInfo | { error: string };
    /** 当前毫秒时间戳。 */
    now(): number;
    /** 工作区根目录与平台信息。 */
    env(): SystemEnv;
    /** 剪贴板：无参读、有参写。 */
    clipboard(text?: string): string | { ok: boolean } | { error: string };
    /** 系统通知（需通知权限）。 */
    notify(title: string, message: string): { ok: boolean } | { error: string };
  };
  calc: {
    /** 安全数学表达式求值："sin(0.5)^2 + sqrt(16)"（白名单函数，非 eval）。 */
    eval(expr: string): number | { error: string };
  };
  Media: {
    /** 屏幕捕获状态（MediaProjection 是否已授权并正在抓帧）。 */
    state(): MediaState | { error: string };
    /** 截取当前屏幕（需先在对话控制条长按「看懂屏幕」完成系统授权）。 */
    screenshot(options?: MediaScreenshotOptions): MediaScreenshotResult | { error: string };
  };
  Git: {
    /** 初始化本地仓库（已存在则幂等）。 */
    init(path?: string): { ok: boolean; repo: string; reinitialized?: boolean } | { error: string };
    /** 工作区状态。 */
    status(path?: string): GitStatus | { error: string };
    /** 暂存文件（["."] = 全部，含未跟踪）。 */
    add(path: string | undefined, files: string[]): { ok: boolean } | { error: string };
    /** 提交暂存区。 */
    commit(path: string | undefined, message: string, opts?: { name?: string; email?: string }): GitCommitResult | { error: string };
    /** 提交历史（倒序，默认 20 条）。 */
    log(path?: string, max?: number): { commits: GitCommit[]; count: number } | { error: string };
    /** 分支列表。 */
    branchList(path?: string): { branches: { name: string; current: boolean }[]; current: string } | { error: string };
    /** 建分支（checkout=true 时同时切换）。 */
    branchCreate(path: string | undefined, name: string, checkout?: boolean): { ok: boolean; branch: string; checkedOut: boolean } | { error: string };
    /** 切分支。 */
    checkout(path: string | undefined, name: string): { ok: boolean; branch: string } | { error: string };
  };
}

/** Tools.Git.status() 返回。 */
interface GitStatus {
  branch: string;
  clean: boolean;
  staged: string[];
  modified: string[];
  removed: string[];
  untracked: string[];
}

/** Tools.Git.commit() 返回。 */
interface GitCommitResult {
  ok: boolean;
  commit: string;
  message: string;
  branch: string;
}

/** Tools.Git.log() 单条提交。 */
interface GitCommit {
  id: string;
  message: string;
  author: string;
  time: number;
}

declare const Tools: ToolsApi;

/* ===================== _：Lodash-lite ===================== */

interface Lodash {
  each<T>(coll: T[] | Record<string, T>, iter: (v: T, k: string | number, c: unknown) => void): void;
  forEach: Lodash["each"];
  map<T, R>(coll: T[] | null | undefined, iter: (v: T, i: number, c: T[]) => R): R[];
  map<T, R>(coll: Record<string, T>, iter: (v: T, k: string, c: Record<string, T>) => R): Record<string, R>;
  collect: Lodash["map"];
  reduce<T, R>(coll: T[] | null | undefined, iter: (acc: R, v: T, k: number) => R, init: R): R;
  reduce<T>(coll: T[], iter: (acc: T, v: T, k: number) => T): T | undefined;
  foldl: Lodash["reduce"];
  filter<T>(coll: T[] | null | undefined, pred: (v: T, i: number, c: T[]) => boolean): T[];
  select: Lodash["filter"];
  find<T>(coll: T[] | null | undefined, pred: (v: T, i: number, c: T[]) => boolean): T | undefined;
  detect: Lodash["find"];
  findIndex<T>(arr: T[] | null | undefined, pred: (v: T, i: number) => boolean): number;
  some<T>(coll: T[] | null | undefined, pred: (v: T, i: number) => boolean): boolean;
  any: Lodash["some"];
  every<T>(coll: T[] | null | undefined, pred: (v: T, i: number) => boolean): boolean;
  all: Lodash["every"];
  groupBy<T>(coll: T[] | null | undefined, iter: ((v: T) => string) | string): Record<string, T[]>;
  countBy<T>(coll: T[] | null | undefined, iter: ((v: T) => string) | string): Record<string, number>;
  sortBy<T>(coll: T[] | null | undefined, iter: ((v: T) => unknown) | string): T[];
  indexBy<T>(coll: T[] | null | undefined, key: string): Record<string, T>;
  keyBy<T>(coll: T[] | null | undefined, key: string | ((v: T) => string)): Record<string, T>;
  uniq<T>(arr: T[] | null | undefined): T[];
  unique: Lodash["uniq"];
  uniqBy<T>(arr: T[] | null | undefined, iter: ((v: T) => string) | string): T[];
  flatten<T>(arr: T[][] | null | undefined): T[];
  flattenDeep<T>(arr: unknown[] | null | undefined): T[];
  chunk<T>(arr: T[] | null | undefined, n: number): T[][];
  zip(...arrays: unknown[][]): unknown[][];
  range(start: number, end?: number, step?: number): number[];
  shuffle<T>(arr: T[] | null | undefined): T[];
  sample<T>(arr: T[] | null | undefined): T | undefined;
  clone<T>(v: T): T;
  cloneDeep<T>(v: T): T;
  merge<T>(dst: T, ...srcs: object[]): T;
  pick<T extends object>(obj: T, keys: (keyof T)[]): Partial<T>;
  omit<T extends object>(obj: T, keys: (keyof T)[]): Partial<T>;
  keys(o: object | null | undefined): string[];
  values<T>(o: Record<string, T> | null | undefined): T[];
  entries<T>(o: Record<string, T> | null | undefined): [string, T][];
  toPairs: Lodash["entries"];
  fromPairs<V>(ps: [string, V][]): Record<string, V>;
  fromEntries: Lodash["fromPairs"];
  invert(o: Record<string, string> | null | undefined): Record<string, string>;
  get(obj: unknown, path: string, def?: unknown): unknown;
  set(obj: object, path: string, val: unknown): object;
  has(obj: unknown, path: string): boolean;
  property(path: string): (o: unknown) => unknown;
  min(arr: number[] | null | undefined): number;
  max(arr: number[] | null | undefined): number;
  sum(arr: number[] | null | undefined): number;
  mean(arr: number[] | null | undefined): number;
  random(lower?: number, upper?: number, floating?: boolean): number;
  capitalize(s: string): string;
  camelCase(s: string): string;
  kebabCase(s: string): string;
  snakeCase(s: string): string;
  padStart(s: string, len: number, ch?: string): string;
  padEnd(s: string, len: number, ch?: string): string;
  truncate(s: string, n: number): string;
  startsWith(s: string, prefix: string): boolean;
  endsWith(s: string, suffix: string): boolean;
  template(tpl: string): (data: Record<string, unknown>) => string;
  debounce<F extends (...args: unknown[]) => void>(fn: F, wait: number): F & { cancel(): void };
  throttle<F extends (...args: unknown[]) => void>(fn: F, wait: number): F;
  once<F extends (...args: unknown[]) => unknown>(fn: F): F;
  isEmpty(v: unknown): boolean;
  isArray(v: unknown): v is unknown[];
  isObject(v: unknown): v is Record<string, unknown>;
  isString(v: unknown): v is string;
  isNumber(v: unknown): v is number;
  isBoolean(v: unknown): v is boolean;
  isFunction(v: unknown): v is (...args: unknown[]) => unknown;
  isNull(v: unknown): v is null;
  isNil(v: unknown): v is null | undefined;
  isUndefined(v: unknown): v is undefined;
  identity<T>(v: T): T;
  noop(): void;
  times<R>(n: number, iter?: (i: number) => R): R[] | number[];
  partition<T>(coll: T[] | null | undefined, pred: (v: T, i: number) => boolean): [T[], T[]];
  orderBy<T>(coll: T[] | null | undefined, iters: (((v: T) => unknown) | string)[], orders?: ("asc" | "desc")[]): T[];
}

declare const _: Lodash;

/* ===================== dataUtils：数据工具 ===================== */

interface Stats {
  count: number;
  sum: number;
  mean: number;
  min: number;
  max: number;
  median: number;
  stdev: number;
}

interface ColumnSummary {
  missing: number;
  unique: number;
  type: "number" | "string";
  samples?: unknown[];
  stats?: Stats;
}

interface DataUtils {
  jsonParse(s: string): unknown;
  jsonStringify(v: unknown, pretty?: boolean): string;
  /** CSV 文本 → 对象数组（首行为表头；支持引号转义与自定义分隔符）。 */
  csvParse(text: string, delimiter?: string): Record<string, string>[];
  /** 对象数组 → CSV 文本。 */
  csvStringify(rows: Record<string, unknown>[], delimiter?: string): string;
  /** 数值统计：count/sum/mean/min/max/median/stdev。 */
  stats(nums: number[]): Stats;
  /** 表格摘要：每列类型/缺失/唯一值（数值列附统计）。 */
  summarize(rows: Record<string, unknown>[]): Record<string, ColumnSummary>;
}

declare const dataUtils: DataUtils;

/* ===================== CommonJS ===================== */

interface NodeModule {
  exports: unknown;
}

/** require：内置模块（"lodash"/"datautils"/"tools"）+ 工作区相对路径（./xxx、../xxx）。 */
declare function require(id: string): unknown;

declare var module: NodeModule;
declare var exports: unknown;
declare var __filename: string;
declare var __dirname: string;

/* ===================== 宿主通道（低层，一般用 Tools.* 即可） ===================== */

/** 把值 JSON 序列化后回传宿主：path="__log"（console 内部用）或 "__result"（显式回传返回值）。 */
declare function hostSetData(path: string, value: unknown): void;

/** 调宿主 API（api 如 "fs.read" / "net.fetch"），同步返回结果 JSON。 */
declare function hostCallApi(api: string, paramsJson: string): unknown;
