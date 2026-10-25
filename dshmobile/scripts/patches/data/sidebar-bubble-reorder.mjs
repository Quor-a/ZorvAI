/** Additive controller operation for the mobile bubble navigation; upstream stays read-only. */
export const SIDEBAR_REORDER_MARKER = 'dsh-mobile sidebar bubble reorder (B1)'
export const SIDEBAR_REORDER_METHOD = `// dsh-mobile sidebar bubble reorder (B1)
reorderIn(sessionId, tabId, beforeId) {
	if (this.mounted.getSnapshot() !== sessionId) return false;
	const store = this.adopted.get(sessionId)?.store;
	const layout = store?.getSnapshot().bySession[sessionId]?.layout;
	if (!layout?.expanded || layout.tabs[tabId] === void 0) return false;
	const pane = Object.values(layout.nodes).find(node => node.kind === "pane" && node.tabs.includes(tabId));
	if (!pane || pane.host !== "dock" || (beforeId !== void 0 && !pane.tabs.includes(beforeId))) return false;
	const index = beforeId === void 0 ? pane.tabs.length : pane.tabs.indexOf(beforeId);
	const at = pane.tabs.indexOf(tabId);
	if (index === at || index === at + 1) return false;
	const active = layout.nodes[layout.activePaneId]?.activeTabId;
	store.actions.placeTab(sessionId, tabId, pane.id, index);
	if (active !== void 0 && active !== tabId) store.actions.focusTab(sessionId, active);
	return true;
}`

const FOCUS = /^([ \t]*)focus\(tabId\) \{\r?\n\1\tconst \{ sessionId, actions \} = this\.require\(\);\r?\n\1\tif \(this\.mountedSurface\(\)\?\.layout\.tabs\[tabId\] === void 0\) return;\r?\n\1\tactions\.focusTab\(sessionId, tabId\);\r?\n\1\}/gm
const MOBILE_FOCUS = /^(      )focus\(tabId2\) \{\r?\n\1  var _a2;\r?\n\1  const \{ sessionId, actions \} = this\.require\(\);\r?\n\1  if \(\(\(_a2 = this\.mountedSurface\(\)\) == null \? void 0 : _a2\.layout\.tabs\[tabId2\]\) === void 0\) return;\r?\n\1  actions\.focusTab\(sessionId, tabId2\);\r?\n\1\}/gm
const indented = indent => SIDEBAR_REORDER_METHOD.split('\n').map(line => indent + line).join('\n')

export function hasSidebarReorder(source) {
  const marker = /^([ \t]*)\/\/ dsh-mobile sidebar bubble reorder \(B1\)$/m.exec(source)
  return marker !== null && source.includes(indented(marker[1]))
}

/** Reject unknown or partially changed artifacts before any write. */
export function patchSidebarReorder(source) {
  if (hasSidebarReorder(source)) return source
  if (source.includes(SIDEBAR_REORDER_MARKER) || source.includes('reorderIn(sessionId')) throw new Error('B1: incompatible existing reorder operation')
  const matches = [...source.matchAll(FOCUS), ...source.matchAll(MOBILE_FOCUS)]
  if (matches.length !== 1) throw new Error('B1: expected one released SidebarRight focus operation, found ' + matches.length)
  const match = matches[0]
  const at = match.index + match[0].length
  return source.slice(0, at) + '\n' + indented(match[1]) + source.slice(at)
}
