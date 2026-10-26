/**
 * Runtime contract type augmentations this package's typecheck consumes.
 *
 * Upstream client packages carry their own contracts: ui-layout declares
 * `shell.overlay` and `ctx.layout`, ui-conversation declares the session header
 * seats, ui-sidebar-right declares the right Sidebar's seats, and ui-session
 * declares the session standard props. What is left here is the set of
 * type-only imports that pull those contract owners into this package's
 * program — their `declare module '@deepseek-ai/cordis'` augmentations are
 * what put the services (`slots`, `sessions`, …) on `Context`. **Do not drop
 * an import here**: deleting e.g. the renderer line makes every `ctx.slots`
 * in index.ts stop typechecking (TS2339), which reads like a cordis bug but
 * is just the augmentation leaving the program.
 *
 * The `settings.dev.item` child seat this file used to declare retired with
 * #418: the developer-options section moved into the ZorvAI shell's cleanup
 * screen and the WebUI registration is gone, so the SlotMap augmentation has
 * no key left to declare and the block is removed with it.
 *
 * The file must stay a module (it is, through the imports below): a script-form
 * `declare module` would define a NEW module instead of merging with cordis.
 */
import type {} from '@deepseek-ai/dsh-client-ui-slots'
import type {} from '@deepseek-ai/dsh-client-ui-renderer/client'
import type {} from '@deepseek-ai/dsh-api-session-controller/client'
