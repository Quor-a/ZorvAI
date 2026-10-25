/* dsh-mobile safe ownership identity v2: publisher namespaces are not ownership proofs. */
export function safeModeFilterInserts(text, hardEntries = []) {
  const entries = Array.isArray(hardEntries) ? hardEntries : [];
  const owns = (id, name) => entries.some((entry) => entry && typeof entry.id === 'string' && entry.id.length > 0 && entry.id === id && entry.name === name);
  const lines = String(text).split('\n');
  const out = [];
  let i = 0;
  while (i < lines.length) {
    if (!/^- insert:\s*$/.test(lines[i])) { out.push(lines[i++]); continue; }
    let end = i + 1;
    while (end < lines.length && !/^-\s/.test(lines[end]) && lines[end] !== '-') end++;
    const body = lines.slice(i + 1, end);
    const rows = body.map((line, index) => {
      const match = /^(\s*)-\s+(id|name):\s*(.*)$/.exec(line);
      return match ? { index, indent: match[1].length, key: match[2], value: match[3].trim().replace(/^['"]|['"]$/g, '') } : null;
    }).filter(Boolean);
    const itemIndent = rows.length ? Math.min(...rows.map((row) => row.indent)) : null;
    if (itemIndent === null) { out.push(lines[i], ...body); i = end; continue; }
    const starts = rows.filter((row) => row.indent === itemIndent).map((row) => row.index);
    const kept = [];
    for (let n = 0; n < starts.length; n++) {
      const start = starts[n], stop = starts[n + 1] ?? body.length;
      const chunk = body.slice(start, stop);
      const head = rows.find((row) => row.index === start);
      const id = head.key === 'id' ? head.value : null;
      const name = head.key === 'name' ? head.value : chunk.map((line) => {
        const match = new RegExp('^\\s{' + (itemIndent + 2) + '}name:\\s*(.*)$').exec(line);
        return match ? match[1].trim().replace(/^['"]|['"]$/g, '') : null;
      }).find(Boolean) ?? null;
      // An entry without a module name cannot be proven Soft, so preserve its bytes.
      if (name !== null && !owns(id, name)) continue;
      kept.push(...chunk);
    }
    if (kept.length) out.push(lines[i], ...kept);
    i = end;
  }
  return out.join('\n');
}
