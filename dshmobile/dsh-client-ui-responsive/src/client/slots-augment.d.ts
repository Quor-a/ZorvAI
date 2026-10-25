/**
 * Runtime contract type augmentations this package's typecheck consumes.
 *
 * Upstream client packages carry their own contracts: ui-layout declares
 * `shell.overlay` and `ctx.layout`, ui-conversation declares the session header
 * seats, ui-sidebar-right declares the right Sidebar's seats, and ui-session
 * declares the session standard props. What is left here is what this package
 * itself owns — the developer-options child seat its own section declares.
 * Context service types come from their actual declaration owners; local
 * approximations must not shadow slots or sessions during upstream adaptation.
 *
 * The file must stay a module (it is, through the import below): a script-form
 * `declare module` would define a NEW module instead of merging with cordis.
 */
import type {} from '@deepseek-ai/dsh-client-ui-slots'
import type {} from '@deepseek-ai/dsh-client-ui-renderer/client'
import type {} from '@deepseek-ai/dsh-api-session-controller/client'

declare module '@deepseek-ai/dsh-client-ui-slots' {
    interface SlotMap {
        /**
         * Android developer-options child seat. Declared by this plugin's own
         * `settings.section` entry (the ADB panel and the other shell
         * facilities mount here without opening their own navigation row), so
         * its contract lives beside the declaration.
         */
        'settings.dev.item': { kind: 'list'; scope: 'root' };
    }
}


