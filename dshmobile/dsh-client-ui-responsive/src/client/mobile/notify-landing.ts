/**
 * 通知落点：把「点通知」变成「打开对应会话」（0.14.1 批 4 / P0-1）。
 *
 * 为什么单独一个模块：落点逻辑必须在页面里执行（会话视图与切换能力只在这里），但它的**判据**
 * 不该埋在 `apply()` 的副作用里——那样只能靠设备实测，回归时没有任何离线信号。
 * 抽出来之后，`openSessionForNotify` 可以用一个假会话面直接单测（含「未加载的会话」与
 * 「会话不存在」两条分支）。
 *
 * 返回值的契约（壳侧 `MainActivity.deliverNotifyRoute` 依此决定是否给用户可见提示）：
 *   true  = 已确认切到该会话；false = 没切过去（会话不存在 / 服务抛错 / 不在场）。
 * 注意**不做事后无验证的成功声明**：对未加载的会话先 `open()` 再回头确认 `scope()`，
 * 否则「刚被打开」与「已被删除」会同样回 true，壳侧的失败提示就成了摆设。
 */

/** 会话服务的最小面（与 runtime `sessions` 服务同形）。 */
export interface SessionOpenFace {
  scope(id: unknown): unknown | undefined
  open(id: unknown): void
}

/**
 * 打开目标会话。
 * @param face - 会话服务面；不在场（undefined）即返回 false，不抛。
 * @param sessionId - 通知携带的会话 id。
 */
export function openSessionForNotify(face: SessionOpenFace | undefined, sessionId: unknown): boolean {
  if (face === undefined || face === null) return false
  if (typeof sessionId !== 'string' || sessionId === '') return false
  try {
    if (face.scope(sessionId) === undefined) {
      face.open(sessionId)
      return face.scope(sessionId) !== undefined
    }
    face.open(sessionId)
    return true
  } catch {
    return false
  }
}
