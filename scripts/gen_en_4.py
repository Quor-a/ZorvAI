# -*- coding: utf-8 -*-
"""把 remaining_en.txt 里的剩余中文 UI 串（按序）映射为下方英文，写 i18n_en_4.json。
读取 remaining_en.txt 的精确中文字面值作为 key，避免手抖写错 key 导致 do_replace 静默跳过。"""
import json, os
HERE = os.path.dirname(os.path.abspath(__file__))

# 按 remaining_en.txt 的顺序，逐条英文翻译（与文件行序严格对应）
EN = [
"Terminal session foreground keep-alive service",           # 1
"Max execution seconds",                                     # 2
"Search current directory…",                                 # 3
"(Native Mini-program \"",                                   # 4
"Peer ip:port",                                              # 5
"Statistics",                                                # 6
"A plugin is a standalone APK; install it to add capabilities to the AI: AI tools / ACI capabilities / slash commands / its own UI, with no host code changes.",  # 7
"Select or create a file in the left file tree to start coding…",  # 8
"Partial results",                                           # 9
"Not ready (install rootfs on the Terminal page first)",      # 10
"Software source management",                                 # 11
"This statement may change with app version updates; the latest version on this page prevails.",  # 12
"Pull failed",                                               # 13
"Open with WPS",                                             # 14
"ROOT access permission",                                    # 15
"Paste: first tap the input box in the target app, then tap \"Paste\" (via Accessibility SET_TEXT / PASTE). ZorvAI accessibility service must be enabled.",  # 16
"Health check interval (minutes)",                           # 17
"Unhealthy",                                                 # 18
"▶️ Full JS/TS script runner (SandboxPackage): runs \"workspace script files\" or \"inline code snippets\" inside the QuickJS sandbox,",  # 19
"Popup title (optional)",                                    # 20
"APK-level plugin framework controller (ZorvAI's plugin system has a single entry point: this tool).",  # 21
"Bobo",                                                      # 22
"Target directory does not exist",                            # 23
"Please wait for rendering to finish",                        # 24
"CMS modules & engine management: view/invoke CMS modules, deploy/check CMS engine status, fix CMS deployment, AI self-writes CMS module scripts",  # 25
"Built-in always-on",                                        # 26
"Please enter your answer",                                   # 27
"Initiating…",                                               # 28
"Current provider",                                          # 29
"Enable \"Exact Alarm\" in system settings, then return and tap test again",  # 30
"Save soul card",                                            # 31
"Moved",                                                     # 32
"Search MNN models",                                         # 33
"Read workspace file",                                       # 34
"AI model",                                                  # 35
"Shizuku authorized ✓ ready to use",                         # 36
"Visual analysis of the current screen screenshot (real visual understanding): uses a vision model to identify content; usable whenever the screen needs to be seen",  # 37
"Leave blank to use the main model's key",                    # 38
"Give up",                                                   # 39
"Enter \"interactive games with AI\" mode: guess the number / tic-tac-toe vs AI / blackjack / memory flip / rock-paper-scissors,",  # 40
"Custom API key (optional)",                                 # 41
"MANAGE_EXTERNAL_STORAGE declared",                          # 42
"This toolkit has no openable UI surface",                    # 43
"Generate real Office files (docx/xlsx/pptx/pdf), downloadable/shareable",  # 44
"ROOT access",                                               # 45
"Manually enter Bot Token",                                  # 46
"Not detected yet; tap to refresh.",                         # 47
"Select time",                                               # 48
"Sent to chat preview",                                      # 49
"Battery optimization exemption",                            # 50
"Replace / crop image",                                      # 51
"Office editor",                                             # 52
"Cannot read this document; please select a .docx/.xlsx/.pptx/.pdf file",  # 53
"e.g. getprop ro.build.version.release",                     # 54
"Invoke a third-party app's ACI capability",                 # 55
"Stop listening",                                            # 56
"No matching plugin",                                        # 57
"Persistent system prompt (if off, injected only when a trigger word matches)",  # 58
"Summary",                                                   # 59
"Data sources & priority",                                   # 60
"Apply for API key",                                         # 61
"Enter a test message…",                                     # 62
"How to add skills (instructions)",                          # 63
"Enter a shell command to run in the sandbox",               # 64
"e.g. Which style do you want?",                             # 65
"Build log",                                                 # 66
"While running AI tasks, show a fluid cloud capsule / live update notification in the status bar (create/update/end).",  # 67
"List all scheduled tasks / automation reminders. No parameters. Returns the task list (id/title/time/repeat type/status).",  # 68
"Tap to select a preset voice / a created clone voice",       # 69
"Open preview (rich text / editable)",                       # 70
"Build APK",                                                 # 71
"Use the AI model configured in chat for transcription (opens in Phase 2)",  # 72
"Plugin runtime",                                            # 73
"Describe the interface you want, and the AI generates complete HTML",  # 74
"☁ Cloud port",                                             # 75
"Files & media",                                             # 76
"No module selected",                                        # 77
"Code editing",                                              # 78
"This model runs offline locally on the device, without any cloud service; no Base URL / API Key needed.",  # 79
"Render channel",                                            # 80
"  Enabled",                                                 # 81
"Mini-program (native engine)·",                             # 82
"Open in system browser",                                    # 83
"Via the Zorv AI agent keyboard, type text directly into the currently focused input box (e.g. a WPS document).",  # 84
"Voice ball bound to chat",                                  # 85
"Pinned to home screen (if supported)",                      # 86
"No description yet",                                        # 87
"Voice capability center",                                   # 88
"Deleted project",                                           # 89
"Connection command",                                        # 90
"This plugin does not yet provide an interactive UI",         # 91
"Weekdays (multi-select)",                                   # 92
"Offline model download center",                             # 93
"Enabled · before each generation a 4-choice pops up (GenUI / A2UI / Markdown / HTML); generation starts after selection",  # 94
"New file name",                                             # 95
"(No capability: may not be bound yet, or the app has not declared any)",  # 96
"📁 Workspace file write: write text to a relative-path file in the workspace (QuroWorkspace).",  # 97
"Switch back to cloud model",                                # 98
"UI as reply",                                               # 99
"Layered, controlled, auditable: L1 Accessibility → L2 Shizuku → L3 Device Admin → L4 ROOT, escalating step by step. Any privilege escalation must pass four stages: intent → policy check → user confirmation → audit.",  # 100
"Import icon PNG",                                           # 101
"Tap \"Import\" to select an audio file, or paste an audio URI/URL",  # 102
"This is an old \"native mini-program\" card; the embedded engine has been removed. For native mini-programs, use the Tool Center now",  # 103
"Edit skill",                                                # 104
"Voice name",                                                # 105
"View, edit, delete",                                        # 106
"Card description (optional)",                               # 107
"Add an external MCP server here (e.g. another AI client, a cloud tool gateway); once added, the AI can call its exposed tools via mcp_call.",  # 108
"Conversation tree does not exist",                          # 109
"Call it in chat by alias with mcp_call; deployed instances are listed below.",  # 110
"No toolkit yet, and the plugin directory is empty. Tap \"Install sample package\" or \"AI generate plugin\" above.",  # 111
"e.g. Select an action",                                     # 112
"Export JSON",                                               # 113
"Recent audit log",                                          # 114
"When enabled, the device tries to start the foreground voice-ball service after boot (notification bar only, no active recording). If the vendor ROM restricts auto-start, allow Zorv AI in system \"Battery / Auto-start management\".",  # 115
"✅ Parameters configured",                                  # 116
"Flowchart",                                                 # 117
"Storage",                                                   # 118
"Option A, Option B, Option C",                              # 119
"Select document type",                                      # 120
"Auto-read",                                                 # 121
"Full toolset",                                              # 122
"Max tokens per local-model reply. Phone CPU takes tens to hundreds of ms per token; 256–1024 recommended.",  # 123
"Reset",                                                     # 124
"Renamed",                                                   # 125
"Toolchain status",                                          # 126
"Downloading…",                                              # 127
"Module runtime state",                                      # 128
"Start the service in the Shizuku app first, then return here and tap \"Request authorization\"",  # 129
"Apply custom UA",                                           # 130
"Sign out",                                                  # 131
"📁 Import audio file",                                      # 132
"Memory mode",                                               # 133
"Scripted UI automation: execute a sequence of UI actions (dump current UI / tap by text·id·description·coordinates / input text / swipe / scroll / wait).",  # 134
"Default 5555, aligned with adb tcpip",                      # 135
"Plugin",                                                    # 136
"Enter the software name first",                             # 137
"Tap the button below to start the health check",            # 138
"Branch overview",                                           # 139
"e.g. 127.0.0.1",                                            # 140
"One-click deploy to terminal",                              # 141
"Save only",                                                 # 142
"Package name or app name",                                  # 143
"Upgrade",                                                   # 144
"Download instructions",                                     # 145
"⚠ Has dependencies to resolve",                             # 146
"No other chat dialog yet.",                                 # 147
"Avatar",                                                    # 148
"Edit preset",                                               # 149
"Save and continue adding",                                  # 150
"Generate new signature",                                    # 151
"Open document",                                             # 152
"Voice description (e.g. gentle girlish voice)",             # 153
"Open homepage in browser",                                  # 154
"Configure visual question",                                 # 155
"Get WeChat login QR code",                                  # 156
"Local diagnostics",                                         # 157
"Type:",                                                     # 158
"Re-instantiate the plugin entry; changes take effect immediately",  # 159
"Validate whether a dynamic UI DSL (JSON) can be parsed correctly. Self-check before output avoids render failure.",  # 160
"e.g. My DeepSeek",                                          # 161
"Extension points / AI tools / version info",                # 162
"Save and preview voice",                                    # 163
"Set; a reminder notification will pop up in about 10 seconds",  # 164
"Uninstall failed",                                          # 165
"Fields (comma-separated)",                                  # 166
"Move to…",                                                  # 167
"No output yet. Install/search/list command output appears here after execution (truncated to 4000 chars).",  # 168
"Document:",                                                 # 169
"Freeze or unfreeze a specified app (frozen apps don't reside in memory or receive push).",  # 170
"Options are in the floating window on screen; just tap it",  # 171
"Text-to-speech config. Reading and the floating voice ball both follow this setting; the voice engine is taken over by the phone's system default TTS engine (local) or the selected cloud provider (cloud model service).",  # 172
"This provider needs no key/params to use (e.g. Edge TTS).",  # 173
"Call CMS capability module (old interface; cms_toolbox recommended)",  # 174
"Log in to GitHub",                                          # 175
"Tasker trigger missing workflow / workflow_id / prompt",     # 176
"Unknown type",                                              # 177
"Forbidden (any escalation is denied)",                      # 178
"Select date and time first",                                # 179
"No history session yet; you can use \"Auto-create session\"",  # 180
"Probing permissions…",                                      # 181
"Favorite this page",                                        # 182
"Allow CapOS in the Root manager",                           # 183
"Bio (optional)",                                            # 184
"Model type",                                                # 185
"Java source",                                               # 186
"About Zorv AI",                                             # 187
"Real-time generation",                                      # 188
"Yansheng",                                                  # 189
"Deploy to terminal",                                        # 190
"Unified config for voice ball, auto-read, chat voice button and emotion / voice-tone capabilities. Each switch is independent.",  # 191
"Preview voice text",                                        # 192
"Export knowledge base (ZIP)",                               # 193
"Add to home screen",                                        # 194
"🖼️ Image content recognition: analyze the image file provided by the user, return a detailed description.",  # 195
"All install commands sent to terminal",                     # 196
"Query CMS v2 modules' and recent tasks' execution status (deploy state / running / terminal), letting the AI confirm \"deploy/call succeeded\".",  # 197
"Paste Mermaid source to view / edit (AI can write directly to a named project with the visual tool)",  # 198
"The connection list does not auto-refresh",                 # 199
"Folder name",                                               # 200
"Install sample package",                                    # 201
"Control the ACI HTTP mock server (used when the real ACI API is not done yet).",  # 202
"Disable image loading",                                     # 203
"When on, local models can call tools via function calling. Off is pure chat mode, saving context window.",  # 204
"Steps in last 7 days (by source):",                         # 205
"Repeat",                                                    # 206
"Copy to…",                                                  # 207
"(No instruction, won't inject even if enabled)",            # 208
"Please select authorization level:",                        # 209
"Render to chat",                                            # 210
"Cannot render GenUI content",                               # 211
"User",                                                      # 212
"Proxy address",                                             # 213
"Checking provider health status...",                        # 214
"Connection failed",                                         # 215
"Cannot auto-open the Shizuku manager; please manually open the Shizuku app and authorize this app",  # 216
"Go to Settings→Apps→Zorv AI→Notifications/Permissions to enable \"Exact Alarm\"",  # 217
"Call a capability exposed by a third-party app via ACI (Agent Capability Interface) (e.g. send message / check unread / create group / open web / run web JS / initiate HTTP request / shared workspace read-write workspace_write·workspace_read·workspace_list·workspace_delete).",  # 218
"Add custom tag",                                            # 219
"Update",                                                    # 220
"Chat settings",                                             # 221
"Custom UA string",                                          # 222
"Official github.com",                                       # 223
"Edit provider",                                             # 224
"Allow permanently",                                         # 225
"Zorv AI doesn't store email; sending is done via your chosen email app.",  # 226
"Device admin permission",                                   # 227
"Launch app",                                                # 228
"The data format returned by the AI is incorrect; please retry or adjust the description.",  # 229
"Evaluate arithmetic expression",                            # 230
"Blank / 0 means auto. After changing, re-tap \"Load\" to take effect.",  # 231
"Generate real files in the background (docx/xlsx/pptx/pdf are binary, md/txt/csv/html are plain text), openable with the in-app viewer or WPS / Office.",  # 232
"Runtime permissions pop the system authorization dialog; ROOT / Shizuku / Device Admin require your active authorization in the system UI. All permission usage is recorded in the audit log.",  # 233
"Clear and continue",                                        # 234
"Terminal · Node runtime",                                  # 235
"Create a folder in the default workspace",                  # 236
"Type",                                                      # 237
"Layout failed, shown as plain text",                        # 238
"Query the deploy readiness, health, version, launched shared services (NODE/PYTHON/SSH/JAVA/RUST/GO runtimes), deploy progress and logs of the \"CMS Engine (system resource package)\".",  # 239
"List currently connected external MCP servers (alias and address). View available servers with this tool before calling external tools.",  # 240
"Private database",                                          # 241
"Say something...",                                          # 242
"AI thinking panel",                                         # 243
"Use GitHub as a search engine inside the chat: directly search GitHub's repositories / code / Issues / users.",  # 244
"No scheduled task yet",                                     # 245
"Title shown in the chat",                                   # 246
"Paste",                                                     # 247
"Please copy the content below and send it to the developer to locate the real cause of \"settings crash / cannot chat\".",  # 248
"Export all skills (JSON)",                                  # 249
"Terminal environment",                                      # 250
"Failed to read file or content is empty",                   # 251
"Deploy a local MCP server: submit a set of tool definitions (JSON array), the app starts an MCP endpoint locally,",  # 252
"Models running offline on the phone must be downloaded first. All built-in are streaming transducers (encoder/decoder/joiner trio), sorted by phone adaptation; the first item is recommended. Auto-unzip and deploy after download, ready to use immediately.",  # 253
"Shizuku not running, opening and waiting for service to start…",  # 254
"Package management (Linux sandbox)",                        # 255
"Select preset UA or custom",                                # 256
"✓ Login succeeded!",                                         # 257
"Memory details",                                            # 258
"entry.sh script",                                           # 259
"⚠️ No params configured, please enter config first",          # 260
"Project address",                                           # 261
"Voice name (free to fill)",                                 # 262
"e.g. alloy / custom gateway voice ID / or select clone voice above",  # 263
"DataOrigin grouping · this app's source is traceable",       # 264
"Skill instruction (inject system prompt)",                  # 265
"Ask render channel each round",                             # 266
"Developer",                                                 # 267
"👁 Eye screenshot (page pixels)",                           # 268
"No thinking process yet",                                   # 269
"☁ Cloud port LLM",                                          # 270
"Java project → DEX → APK",                                  # 271
"Multi-step workflow orchestration engine: chain registered tools into a pipeline, supports variables, loops, conditions, error handling and timeouts; use it to orchestrate other tools to fill capability gaps.",  # 272
"Compute backend",                                           # 273
"Plugin desktop",                                            # 274
"Send SMS",                                                  # 275
"Select local document…",                                    # 276
"Control device volume",                                     # 277
"Get current date and time",                                 # 278
"Terminal environment ready ✓",                              # 279
"The following params only affect the local offline model, independent of cloud model settings.",  # 280
"No registered tool yet.",                                   # 281
"Each project uses a different package name, so installs won't conflict (over-install also needs same signature).",  # 282
"Open the web browser and navigate to the specified URL",     # 283
"Enable failover",                                           # 284
"Local Git version control integration: run git commands on the device-storage repo inside the proot container.",  # 285
"Direct official gateway",                                   # 286
"Verify built-in skill signature (signature troubleshooting)",  # 287
"Already has privileged channel; can execute ADB shell directly in the terminal",  # 288
"Enter token budget (0=unlimited). Long chats auto-discard oldest rounds, always keep identity/persona/tool guidance, avoid window overflow causing lost context or broken tool calls. Xiaomi MiMo recommends 16000–32000.",  # 289
"When \"Local Model (on-device)\" is selected, this test uses the phone's offline Sherpa-NCNN recognition; other engines use native SpeechRecognizer.",  # 290
"Local recognition",                                         # 291
"Select question method",                                    # 292
"ZorvAI sandbox terminal",                                   # 293
"Recognized text shows here",                                # 294
"Terminal · Static HTTP service",                            # 295
"Cannot share this file (path not allowed)",                 # 296
"Save changes",                                              # 297
"HTML app",                                                  # 298
"Target has a same-name file",                               # 299
"Enter…",                                                    # 300
"Enter message…",                                            # 301
"Provider health",                                           # 302
"e.g. 192.168.1.10:5555",                                    # 303
"Current context cannot pop system dialog; opened Shizuku app authorization",  # 304
"No favorites yet; tap \"More → Favorite this page\" at top-right to add.",  # 305
"Add new",                                                   # 306
"Supports Markdown/HTML",                                    # 307
"Register and start by package name",                        # 308
"Web automation script",                                     # 309
"Current branch",                                            # 310
"Share diagnostic info",                                     # 311
"Execute command in terminal",                               # 312
"Folder operations",                                         # 313
"👁️ Screen visual analysis (real visual understanding): capture the current screen, feed the screenshot as an image directly to the current multimodal chat model to \"see\" it,",  # 314
"e.g. gentle girlish voice, 20-30 yo female",                # 315
"Collapse entry.sh",                                         # 316
"Linux rootfs cache (usr/tmp) usage; after clearing, the terminal environment must be re-initialized.",  # 317
"Proactively initiate screen capture (MediaProjection) system authorization: let AI see the screen/screenshot without the user manually holding the switch",  # 318
"(No suggestion)",                                           # 319
"View command",                                              # 320
"Standalone",                                                # 321
"Cannot share this file (path not allowed)",                 # 322
"Start editing document content...",                         # 323
"Search knowledge base",                                     # 324
"Install the Linux environment on the Terminal page first, then deploy the dev environment.",  # 325
"Pet management",                                            # 326
"Search GGUF models",                                        # 327
"Search plugins / package names / AI tools",                 # 328
"\" and all its contents?",                                  # 329
"Installed",                                                 # 330
"Manage terminal sessions with tmux inside the proot container (multi-pane / background tasks / keyboard send).",  # 331
"Disabled",                                                  # 332
"Saved notes to Download/Quro/",                             # 333
"Soul editing",                                              # 334
"Failover strategy",                                         # 335
"Send the Enter key via the Zorv AI agent keyboard (submit/newline). Preconditions same as ai_type_text: enabled and switched to 'Zorv AI Keyboard', and the target app's input box is focused.",  # 336
"Toolchain",                                                 # 337
"Provisioner environment script",                            # 338
"Lock screen display",                                       # 339
"One-click send all install commands",                       # 340
"Open-source AI assistant · original build.",                # 341
"On sends all ~50 tools (full capability); if your API relay is sensitive to tool count (>25 may silently drop the tools field and break calls), turn off to send only the core 14.",  # 342
"Voice service",                                             # 343
"No valid cms.io/v2 module recognized",                      # 344
"Enter custom answer",                                       # 345
"Execution failed",                                          # 346
"Move down",                                                 # 347
"Multi-provider management",                                 # 348
"List available ACI capabilities",                           # 349
"No soul card yet",                                          # 350
"Plugin engine not initialized",                             # 351
"Required for SiliconFlow clone: the text content corresponding to the reference audio",  # 352
"Tags",                                                      # 353
"Custom tag",                                                # 354
"Clear all",                                                 # 355
"Project name",                                              # 356
"Cannot open this file",                                     # 357
"Format",                                                    # 358
"Close sandbox terminal",                                    # 359
"(No chart content)",                                        # 360
"Apply for ACI API key",                                     # 361
"Local offline mode",                                        # 362
"No plugin tools yet. Install a few plugins first.",         # 363
"List CMS capability modules (old interface; cms_toolbox recommended)",  # 364
"No model obtained; please check address / key.",            # 365
"The AI can also directly call aiwps_create in chat to generate real .docx/.xlsx/.pptx documents.",  # 366
"Permission & confirmation mechanism; sensitive operations (e.g. install/uninstall apps, ROOT commands) are still constrained by system permissions and runtime confirmation.",  # 367
"Manage virtual displays and background automation; supports starting/stopping virtual displays, creating and running automation tasks, screenshots, etc.",  # 368
"Execute commands as Root via Shizuku (device must be rooted and Shizuku running in root mode).",  # 369
"Local test",                                                # 370
"Generation failed",                                         # 371
"Quick actions",                                             # 372
"Rename branch",                                             # 373
"⚠️ The currently deployed model is an old version (SenseVoice / ONNX); this device's engine has no matching implementation, recognition will stay unresponsive.",  # 374
"Save config",                                               # 375
"Last run crashed (captured)",                               # 376
"Permissions & capabilities",                                # 377
"Switch to this branch",                                     # 378
"Document name",                                             # 379
"Off",                                                       # 380
"Voice engine: taken over by the phone's system default TTS engine (Settings → Language & input → Text-to-speech). No need to select here.",  # 381
"🔊 Offline TTS",                                            # 382
"Leave blank to use the main model's URL",                   # 383
"Speak",                                                     # 384
"Zorv AI test alarm",                                        # 385
"100% open-source local music player: plays local audio files in the background (keeps playing without a foreground window).",  # 386
"Meeting minutes action list",                               # 387
"View add instructions",                                     # 388
"Copy log",                                                  # 389
"Enabling the voice ball requires the \"floating window\" permission",  # 390
"Music player",                                              # 391
"File is large; in-app preview may be slow. If unresponsive for a long time, tap \"Other app\" to open.",  # 392
"Can escalate privileges",                                   # 393
"Full screen",                                               # 394
"Created",                                                   # 395
"Query",                                                     # 396
"Scan the code to get the connection command (copy after scanning with another phone/PC camera), enabling phone/PC control",  # 397
"Supplier name",                                             # 398
"Source name",                                               # 399
"List workspace contents",                                   # 400
"Save dependency template",                                  # 401
"Generating…",                                               # 402
"Copied layout content",                                     # 403
"Each tag is an independent switch: on = allow the AI to freely combine this style during speech synthesis; off = disabled. All off by default, enable as needed; it won't force \"use all\".",  # 404
"Use the toolbar to quickly insert formatting; docx splits by newline into paragraphs; xlsx splits by newline into rows; md/txt/html written as-is",  # 405
"How to add a CMS v2 module",                                # 406
"Only off",                                                  # 407
"Enable ZorvAI accessibility service in system settings first",  # 408
"QR code expired",                                           # 409
"Already default",                                           # 410
"Zorv AI scheduled-task due reminder",                       # 411
"ACT associated launch",                                     # 412
"Start check",                                               # 413
"Default model name (optional)",                             # 414
"No module yet. Tap \"Add module\" above to create a capability module (cms.io/v2).",  # 415
"Document modified; save changes?",                          # 416
"No tool call yet",                                          # 417
"Not installed",                                             # 418
"Invoke",                                                    # 419
"Terminal · Python runtime",                                 # 420
"Shizuku still not ready: start the service in that app first (ADB wireless debugging/pairing), then tap this button to authorize",  # 421
"Connected to ZorvAI persona card / memory / tag system, sharing the same identity as the main chat; no separate setup needed here",  # 422
"Persona heartbeat",                                         # 423
"Collapse script",                                           # 424
"New knowledge document",                                    # 425
"Import CMS engine",                                         # 426
"List installed",                                            # 427
"KaleidoBox not initialized",                                # 428
"Environment status",                                        # 429
"No root/Shizuku: use system wireless debugging pairing instead",  # 430
"Isolated sandbox",                                          # 431
"Form description",                                          # 432
"Check for updates",                                         # 433
"Enable voice cloning",                                      # 434
"No data",                                                   # 435
"Unified KV cache",                                          # 436
"Command",                                                   # 437
"Start entering document content...",                         # 438
"Scroll the scrollable container (list/page) on the current screen.",  # 439
"Go to voice service settings ›",                            # 440
"Extension point",                                           # 441
"(No steps)",                                                 # 442
"Tip: if the current network can't reach official GitHub, device flow / PAT may both fail. Log in with PAT on a GitHub-reachable network first for safety.",  # 443
"Cloud model config · speech synthesis",                     # 444
"Identity & model",                                          # 445
"Workspace root (QuroWorkspace)",                            # 446
"Environment not ready: install rootfs on the Terminal page first",  # 447
"Adapter not initialized; please restart the App",           # 448
"Preset options (one per line, optional)",                   # 449
"Web page body",                                             # 450
"Describe the question interface you want",                  # 451
"Multi-format document creation (md/txt/csv/json/html, 17 types)",  # 452
"After changing the name, tap \"Save changes\" to update the preset display name. For other fields, delete and recreate.",  # 453
"Direct official domain, zero public endpoints",             # 454
"Import module",                                             # 455
"Answer with UI",                                            # 456
"Document content",                                          # 457
"Instruction / reminder content (optional)",                 # 458
"Dynamic UI components",                                      # 459
"Battery status",                                            # 460
"Query 12306 ticket availability (train / departure-arrival time / duration / seats per class).",  # 461
"Custom UA",                                                 # 462
"New version detected, downloading APK directly…",           # 463
"LSPosed module",                                            # 464
"Tag management",                                            # 465
"Read GitHub repo content: file source / directory list / Issue / PR file changes / PR diff.",  # 466
"Target session",                                            # 467
"100% open-source local music player (playlist): plays multiple local audio files continuously in the background.",  # 468
"Read text file content",                                    # 469
"Create new branch",                                         # 470
"Confirm content",                                           # 471
"Select any folder on the phone as the workspace",           # 472
"View package name",                                         # 473
"TTS speech synthesis",                                      # 474
]

assert len(EN) == 474, (len(EN), "expected 474")

# 读取 remaining_en.txt 的精确中文字面值（顺序与 EN 严格对应）
src = []
for line in open(os.path.join(HERE, 'remaining_en.txt'), encoding='utf-8').read().splitlines():
    line = line.strip()
    if not line:
        continue
    src.append(line.split('\t', 1)[1] if '\t' in line else line)

assert len(src) == len(EN), (len(src), len(EN))

out = {}
for i, t in enumerate(src):
    out[t] = EN[i]

json.dump(out, open(os.path.join(HERE, 'i18n_en_4.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=0)
print('wrote i18n_en_4.json with', len(out), 'entries')

# 校验所有 key 都在 i18n_strings.json
data = json.load(open(os.path.join(HERE, 'i18n_strings.json'), encoding='utf-8'))
indata = set(e['text'] for e in data)
missing = [t for t in out if t not in indata]
print('missing from i18n_strings.json:', len(missing))
for m in missing[:20]:
    print('  ', repr(m))
