# Quro AI — Open-Source License Compliance Remediation Report

**Date:** 2026-07-24
**Auditor:** gstack-security-officer (license-compliance / OWASP-adjacent review)
**Scope:** `D:\Calw OS-project\QuroAI` — LICENSE, NOTICE, `app/build.gradle.kts`, `gradle/libs.versions.toml`, and the 181 Kotlin/Java source files under `app/src/main/java/com/ai/assistance/quro`.
**Mode:** Remediation analysis + NOTICE correction. No batch header insertion executed. No build performed (to avoid conflicting with the concurrent build). LICENSE remains Apache-2.0 (unchanged, per instruction).

---

## 0. Executive Conclusion (read this first)

The NOTICE file contained **two factually incorrect license classifications**, both of which *overstated* the compliance risk:

1. **libVLC was labeled "GPL v2/v3 (STRONG COPYLEFT)."** This is **wrong**. VideoLAN licenses the libVLC *engine* (`libvlccore`) under **LGPL-2.1-or-later** (weak copyleft), which is **compatible with the Apache-2.0 main license** under dynamic linking. There is **no hard Apache-vs-GPL conflict** requiring removal or GPL re-licensing. The real, bounded obligation is LGPL-2.1 compliance (already largely satisfied) plus a module-set verification (some VLC *modules* can be GPLv3).
2. **org.json was labeled "JSON License" (the "Good, not Evil" clause).** This is **stale**. Upstream `org.json:json` removed that clause in version **20220924**; QuroAI pins **20240303**, which is **Public Domain**. The JSON License risk is therefore **not present** in the current dependency set.

**Net result:** The only actionable license work is (a) keep Apache-2.0, (b) correct the NOTICE (done — see §4), (c) verify the libVLC source offer + linked module set, and (d) optionally clean up the Apache headers on ~169 first-party files (plan in §3, not executed). No dependency removal or license-type change is required.

---

## 1. libVLC License Conflict Assessment

### 1.1 What the dependency actually is
- Dependency: `org.videolan.android:libvlc-all:3.6.5` (`app/build.gradle.kts:133`).
- Authoritative VideoLAN sources:
  - videolan.org/vlc/libvlc.html — *"libVLC is … under the LGPL2.1 license."*
  - Official Doxygen docs — *"libVLC and libvlccore are released under the LGPLv2 (or later) license. This allows embedding the engine in 3rd party applications, while letting them to be licensed under other licenses."*
  - VideoLAN FAQ — *"May I redistribute libVLC in my application? Yes … it is the GNU Lesser General Public License Version 2 (LGPL) … Beware that some modules are licensed under the GPLv2, in which case you must license your result under the GPLv2 as well. Check the modules in question before redistribution!"*

**Conclusion:** libVLC (the engine) = **LGPL-2.1** (weak copyleft). The full **VLC application** is GPLv2+, but QuroAI consumes the *library*, not the app.

### 1.2 Relationship to the Apache-2.0 main license
- Apache-2.0 (permissive) + LGPL-2.1 (weak copyleft) = **compatible**. Apache-2.0 imposes no copyleft on the combined work; LGPL-2.1 obligations attach only to the libVLC portion. The app's own code stays Apache-2.0. VideoLAN explicitly permits building non-open-source libVLC-based applications.

### 1.3 Distribution risk
- **LOW** for the LGPL engine, *provided* these LGPL-2.1 conditions hold:
  1. **Dynamic linking** — satisfied: libVLC loads as a native `.so` via JNI (`app/build.gradle.kts:57` `useLegacyPackaging = true` extracts the `.so` to disk; QuroToolsMediaPlayer loads it dynamically).
  2. **Corresponding source offer** — NOTICE (now corrected) claims libVLC 3.6.5 source is available in the repo/release artifacts. **Must be verified as actually true** (see §1.5).
  3. **Relink capability (LGPL §6)** — for Android, typically satisfied by shipping the `.so` and documenting how to obtain/rebuild it; should be documented in the repo.
  4. **Preserve LGPL notices / LICENSE** — satisfied by the NOTICE file.
- **MEDIUM residual risk:** the `-all` artifact bundles VLC *modules*; some modules may be GPLv3. If any GPL module is linked/used, that module triggers GPLv3 strong copyleft on the combined work (incompatible with keeping the app Apache-2.0). The AAR was **not present in the local Gradle cache**, so the linked module set could not be inspected; verification is required (§1.5).

### 1.4 Remediation options (as requested)

| Option | Description | Verdict |
|--------|-------------|---------|
| **(a) Remove libVLC; replace with Apache/MIT player** (e.g. AndroidX Media3/ExoPlayer) | Eliminates all LGPL/GPL obligation; fully Apache-2.0 stack. | **Not necessary.** Overkill — libVLC is LGPL-compatible. Significant rewrite of `QuroToolsMediaPlayer`. Media3 lacks some VLC codecs (DVD/niche protocols). |
| **(b) Switch whole project to GPL + host source** | Resolves any GPL-module concern. | **Not recommended / harmful.** Forces the entire (currently Apache-2.0) app to GPL; contradicts the instruction to keep Apache-2.0 unless explicitly confirmed; harms adoption. |
| **(c) Confirm dynamic linking + comply with LGPL (conflict 降级)** | Keep Apache-2.0; classify libVLC as LGPL-2.1; meet LGPL duties; verify module set. | **RECOMMENDED.** Correct path. The "conflict" the NOTICE described does not actually exist for libVLC's LGPL core. |

### 1.5 Required follow-up actions (Option c)
1. **Verify source offer:** confirm `libvlc-all:3.6.5` corresponding source is actually committed/released (NOTICE claims it). If not, publish it.
2. **Verify module set:** inspect the published AAR (or build output) to confirm which VLC modules are linked. If any GPLv3 module is present and used, either (i) switch to `org.videolan.android:libvlc:3.6.5` (core engine only, LGPL-2.1) — safest, or (ii) comply with GPL for those modules (host their source). If no GPL module is linked, no further action beyond the NOTICE correction is needed.
3. **Document relink mechanism:** add a short note in the repo (e.g., README or NOTICE) on how to obtain/rebuild libVLC so users can exercise LGPL §6.
4. **Align code comments:** `app/build.gradle.kts:131` comment says "GPLv2/v3" — update to "LGPL-2.1" for accuracy (non-blocking; not edited here to avoid touching build files during the concurrent build).

**Clear conclusion:** NOTICE's "GPL v2/v3 (STRONG COPYLEFT)" claim is **factually incorrect**. libVLC is **LGPL-2.1**, compatible with Apache-2.0. Choose **Option (c)**. Do **not** remove libVLC (a) and do **not** re-license to GPL (b).

---

## 2. JSON License Risk

### 2.1 The claimed risk
NOTICE (old) listed `org.json` as "JSON License" with the clause *"The Software shall be used for Good, not Evil."* The JSON License (MIT + that clause) is **non-free** (FSF: conflicts with freedom 0) and **not open source** (OSI OSD clause 6 — no discrimination by field of endeavor). If true, it would be a compliance problem for an Apache-2.0 distribution.

### 2.2 Verification — the risk is NOT present in the pinned version
- Dependency: `org.json:json:20240303` (`gradle/libs.versions.toml:12`, `app/build.gradle.kts:105,141`).
- Upstream history (stleary/JSON-java, confirmed via raw LICENSE at tag `20240303` and the project README): the "Good, not Evil" clause was **removed in version 20220924 (2022)**; the project is now **Public Domain**.
- Since `20240303` (Mar 2024) > `20220924`, the pinned artifact is **Public Domain** — verified: the LICENSE file at that tag contains only `Public Domain.`

**Conclusion:** There is **no JSON License exposure** in the current dependency tree. QuroAI uses **no** library carrying the JSON License. The NOTICE statement was **stale/incorrect** and has been corrected to "Public Domain."

### 2.3 Recommendations
1. **NOTICE corrected** (§4) — org.json now listed as Public Domain with the version note.
2. **Optional hardening:** "Public Domain" is not an OSI-approved license and some corporate legal teams dislike it. Two clean alternatives:
   - **(Preferred, zero-code-change):** Remove the explicit `org.json:json` dependency and rely on the **Android platform's bundled `org.json`** (AOSP, Apache-2.0). 76 source files `import org.json.*`; the platform provides `JSONObject`/`JSONArray`/etc. at compile time. *Caveat:* verify no `org.json:json`-only API (e.g., `JSONPointerException`, recent `JSONObject.wrap` overloads) is used before removing.
   - **(Alternative):** Replace with `com.google.code.gson:gson` (Apache-2.0) if a named, OSI-approved license is preferred.
3. **No removal is legally required** — Public Domain imposes no restriction.

---

## 3. License Header Completion Plan (NOT executed)

Per instructions, **no batch header insertion is performed in this step.** This section is the implementation plan for a later, isolated step.

### 3.1 Scope (verified by grep)
- Total Kotlin/Java source files: **181** (`find … -name "*.kt" -o -name "*.java"`).
- Files **with** an Apache-2.0 header: **8** — all under `com/ai/assistance/quro/core/mcp/**` (vendored `droid-mcp`, correctly attributed). These are **done; skip**.
- Vendored third-party files (must **retain upstream attribution, NOT receive Quro's header**): **4** under `com/k2fsa/sherpa/ncnn/**` (k2-fsa Sherpa-NCNN, Apache-2.0 / BSD-3-Clause). One (`WaveReader.kt`) already carries an upstream header; ensure the other three retain/restore k2-fsa attribution.
- **First-party Quro-authored files needing the Apache-2.0 header: ~169** (181 − 8 − 4).

### 3.2 Recommended header format (standard Apache-2.0)
```kotlin
/*
 * Copyright 2025-2026 Quro AI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
```
- **Placement:** as a block comment `/* … */` at the **very top of the file, before the `package` statement** (Kotlin requires `package` to be first; the license comment goes above it, as in the existing `DroidMcp.kt`).
- Do **not** add headers to generated files (`R`, `BuildConfig`) — these live outside `src/main/java` and are regenerated.

### 3.3 Safe batch approach (idempotent, no duplication)
1. Enumerate all `.kt`/`.java` under `app/src/main/java`.
2. **Exclude** vendored trees: `com/ai/assistance/quro/core/mcp/**` (done) and `com/k2fsa/**` (upstream).
3. For each remaining file, inspect the first 20 lines; **skip if** it already contains `Licensed under the Apache License`, `Copyright`, or an existing `/*` header (prevents double headers).
4. Prepend the §3.2 block **immediately before** the `package` line.
5. Implement via a guarded script (Python) or a Gradle header/license plugin (e.g., `license-gradle-plugin` with a `header` task) configured to be **idempotent** (dry-run first).
6. After insertion, run a build/compile to confirm no `package`-position regressions — but **only in a dedicated step**, not now.

### 3.4 Why not execute now
The task explicitly forbids batch header insertion and building in this pass (to avoid colliding with the concurrent build). The plan above is ready to run as a separate, isolated step.

---

## 4. NOTICE Corrections Applied (this session)

The following edits were made to `D:\Calw OS-project\QuroAI\NOTICE`:

- **libVLC block (was "GPL v2/v3 STRONG COPYLEFT"):** corrected to **LGPL-2.1 (weak copyleft)**, with the compatibility statement, the source-offer note, and the module-set caveat (some VLC modules may be GPLv2/v3 — verify).
- **org.json block (was "JSON License / Good, not Evil"):** corrected to **Public Domain**, noting the clause was removed upstream in 20220924 and that the Android platform also bundles `org.json` under AOSP Apache-2.0.

**Unchanged (verified correct):** AndroidX/Jetpack/Compose (Apache-2.0), OkHttp (Apache-2.0), Kotlin/Coroutines (Apache-2.0), Shizuku (Apache-2.0), android-image-cropper (Apache-2.0), Apache Commons Compress (Apache-2.0), QuickJS (MIT), Sherpa-NCNN (Apache-2.0/BSD-3), GeckoView (MPL-2.0 — correct; note the *build.gradle comment* wrongly says "Apache-2.0", see §5).

**LICENSE:** left as Apache-2.0 (unchanged, per instruction).

---

## 5. Minor Findings

1. **Misleading build.gradle comments (non-blocking):** `app/build.gradle.kts:131` labels libVLC "GPLv2/v3" (should be LGPL-2.1); `app/build.gradle.kts:135` labels GeckoView "Apache-2.0" (actually MPL-2.0; the NOTICE is correct, the comment is not). Recommend aligning comments. Not edited here to avoid touching build files during the concurrent build.
2. **Omitted dependency in NOTICE (minor):** `androidx.concurrent:concurrent-futures:1.2.0` (Apache-2.0, `app/build.gradle.kts:123`) is not listed in NOTICE. Optional to add for completeness.
3. **Vendored droid-mcp not enumerated in NOTICE:** the 8 `core/mcp` files are vendored from `stixez/droid-mcp` (Apache-2.0). They carry correct headers in-source; optionally add a one-line NOTICE attribution.

---

## 6. Verification & Confidence Notes (CSO discipline)

| Finding | Source of verification | Confidence |
|---------|------------------------|-----------|
| libVLC engine = LGPL-2.1 | VideoLAN official site + Doxygen docs + FAQ (fetched) | 10/10 |
| Some VLC modules may be GPLv3 | VideoLAN FAQ (fetched) | 10/10 (caveat existence); module-set-in-this-build = unverified (AAR not cached) |
| `org.json:json:20240303` = Public Domain | Raw LICENSE at tag `20240303` (fetched) + JSON-java README | 10/10 |
| 8 files have Apache header; 4 vendored k2fsa; ~169 need headers | Grep of source tree | 10/10 |
| No other JSON-License dependency present | Version catalog + source grep (76 `org.json` imports, all satisfied by public-domain/platform) | 9/10 |

No finding is speculative; each was actively confirmed against an authoritative source or the repository contents. No LLM/external API calls were used for analysis; all verification used local file inspection and public web sources.

---

## 7. Remediation Roadmap (prioritized)

- **P0 (done):** Correct NOTICE libVLC → LGPL-2.1; org.json → Public Domain.
- **P1:** Verify libVLC 3.6.5 source is actually offered (repo/release). Verify linked VLC module set for any GPLv3 module; if found, switch to `org.videolan.android:libvlc:3.6.5` or comply with GPL.
- **P2:** Document LGPL §6 relink mechanism in repo; align `build.gradle.kts` comments (libVLC/GeckoView).
- **P3:** (Optional) Remove explicit `org.json:json` dep in favor of platform AOSP `org.json`, or migrate to Gson (Apache-2.0). Add `concurrent-futures` to NOTICE.
- **P3:** (Separate isolated step) Insert Apache-2.0 headers on ~169 first-party files per §3 (idempotent, no duplication).
- **Do NOT:** change LICENSE from Apache-2.0, remove libVLC, or re-license to GPL unless explicitly confirmed by the user.
