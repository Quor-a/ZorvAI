    // dsh-mobile safe ownership identity v2: no namespace or live-patch fallback can prove ownership.
    const filesRoot = dirname(dirname(DSH_HOME));
    const hardManifest = JSON.parse(await fs.readFile(join(filesRoot, ".plugin-hard-manifest.json"), "utf8"));
    const installedFingerprint = (await fs.readFile(join(filesRoot, ".snapshot-fingerprint"), "utf8")).trim();
    if (hardManifest.schema !== 2 || hardManifest.complete !== true || hardManifest.fingerprint !== installedFingerprint ||
      !Array.isArray(hardManifest.entries) || hardManifest.entries.length === 0 || hardManifest.entries.some((entry) =>
        !entry || typeof entry.id !== "string" || entry.id.length === 0 || typeof entry.name !== "string" || entry.name.length === 0) ||
      !Array.isArray(hardManifest.profileEntries) || hardManifest.profileEntries.length === 0 || hardManifest.profileEntries.some((entry) =>
        !entry || typeof entry.id !== "string" || typeof entry.name !== "string" ||
        !hardManifest.entries.some((hard) => hard.id === entry.id && hard.name === entry.name))) {
      throw new Error("Safe Mode ownership manifest is missing, invalid, or for another snapshot; configuration was not changed");
    }
    const hardEntries = hardManifest.entries;
    const owns = (id, name) => hardEntries.some((entry) => entry.id === id && entry.name === name);
    const dshMobileSafeFilterInserts = (text) => {
      const lines = String(text).split("\n"), out = [];
      let i = 0;
      while (i < lines.length) {
        if (!/^- insert:\s*$/.test(lines[i])) { out.push(lines[i++]); continue; }
        let end = i + 1;
        while (end < lines.length && !/^-\s/.test(lines[end]) && lines[end] !== "-") end++;
        const body = lines.slice(i + 1, end);
        const rows = body.map((line, index) => {
          const match = /^(\s*)-\s+(id|name):\s*(.*)$/.exec(line);
          return match ? { index, indent: match[1].length, key: match[2], value: match[3].trim().replace(/^['\"]|['\"]$/g, "") } : null;
        }).filter(Boolean);
        const indent = rows.length ? Math.min(...rows.map((row) => row.indent)) : null;
        if (indent === null) { out.push(lines[i], ...body); i = end; continue; }
        const starts = rows.filter((row) => row.indent === indent).map((row) => row.index);
        const kept = [];
        for (let n = 0; n < starts.length; n++) {
          const start = starts[n], stop = starts[n + 1] ?? body.length;
          const chunk = body.slice(start, stop), head = rows.find((row) => row.index === start);
          const id = head.key === "id" ? head.value : null;
          const name = head.key === "name" ? head.value : chunk.map((line) => {
            const match = new RegExp("^\\s{" + (indent + 2) + "}name:\\s*(.*)$").exec(line);
            return match ? match[1].trim().replace(/^['\"]|['\"]$/g, "") : null;
          }).find(Boolean) ?? null;
          if (name !== null && !owns(id, name)) continue;
          kept.push(...chunk);
        }
        if (kept.length) out.push(lines[i], ...kept);
        i = end;
      }
      return out.join("\n");
    };
    const dshMobileSafePatchText = await fs.readFile(patch, "utf8");
    const minimal = dshMobileSafeFilterInserts(dshMobileSafePatchText);
    await fs.writeFile(patch, minimal, "utf8");
