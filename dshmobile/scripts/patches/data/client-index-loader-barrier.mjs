export const CLIENT_INDEX_LOADER_BARRIER_MARKER = 'dsh-mobile client index Loader barrier (G2)'

export function planClientIndexLoaderBarrier(source) {
	if (source.includes(CLIENT_INDEX_LOADER_BARRIER_MARKER)) return source
	const before = `	const renderIndex = async () => {
		return ctx.webServer.renderIndex(await readFile(distIndex, "utf8")).replace(/<head(?:\\s[^>]*)?>/i, (open) => \`\${open}<base href="./">\`);
	};`
	const after = `	const renderIndex = async () => {
		/* ${CLIENT_INDEX_LOADER_BARRIER_MARKER}: clientModules defers its startup flush
		 * onto this same Loader's settle continuation. Registering this await after the
		 * registry barrier lets that flush finish before renderIndex snapshots the graph.
		 * Keep the barrier on HTML only; static assets do not need the client graph. */
		await ctx.get("loader").await();
		return ctx.webServer.renderIndex(await readFile(distIndex, "utf8")).replace(/<head(?:\\s[^>]*)?>/i, (open) => \`\${open}<base href="./">\`);
	};`
	if (source.split(before).length !== 2) throw new Error('client-index Loader barrier anchor mismatch: renderIndex')
	return source.replace(before, after)
}
