/** Composer paperclip source chooser, built from DSH semantic surface and elevation tokens. */
export const ATTACHMENT_PICKER_MENU_CSS = `
/* Our own paperclip (D2/D4). Upstream 0.1.7-rc.1 ships no paperclip, so this plugin mounts its own
   button into the standard '[data-slot="conversation.input.left"]' anchor. The slot outlet renders
   that anchor with 'display: contents' (ui-renderer scoped-slots.tsx:1064-1091), so the button becomes
   a flex item of the upstream '.tools' row and inherits its gap. Chrome mirrors '.add'
   (InputBar.module.css:314-326): a round control on the selector fill with a primary glyph.

   Narrow-screen width bound (issue #54 / D9). '.row' is 'flex-wrap: wrap' and '.trailing' is
   'flex: none' (InputBar.module.css:251-302): whatever the left group gains must fit the row's spare
   width, or the trailing group wraps to a second line and the controls misalign vertically.

   Arithmetic at 360dp, from the files that own these numbers:
     row content width   302 = card 318 - row padding 16   (composer-row.css.ts:6-8)
     .tools baseline      88 = 28 add + 8 gap + 44 permission chip + 8 gap
     .row gap             12                                (InputBar.module.css:256)
     .trailing           182 = model pill capped at 104     (composer-row.css.ts:10-12,18-20)
     baseline total      282 = 88 + 12 + 182, so spare = 20
   A control appended to '.tools' costs one more '.tools' gap (8px in the '<=560px' container,
   InputBar.module.css:305-311) plus its own box: 8 + 28 = 36px > the 20px spare, so the trailing
   group wraps by 16px and the add button misaligns with the model pill (issue #54).

   Below 400px the box is 24px and 'margin-inline: -8px' recovers 16px of outer width, 8px of it
   exactly cancelling the seat gap the control introduced: '.tools' = 88 + 8 - 8 + 24 - 8 = 104, the
   row total = 104 + 12 + 182 = 298, so 4px of spare remain. Nothing overlaps another control: the
   start margin only closes the gap in front of this button, and nothing follows it inside '.tools'.
   The remaining assumption is the permission chip's 44px icon-only width at this width
   (PermissionSelect @container <=460px, PermissionSelect.module.css:94-98); a 'conversation.input.plan'
   chip mounted alongside would consume the spare and wrap. */
[data-dsh-attachment-picker-trigger] {
  display: grid;
  place-items: center;
  flex: none;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 999px;
  corner-shape: round;
  background: var(--dsw-specific-selector);
  color: var(--dsw-alias-label-primary);
  cursor: pointer;
}
[data-dsh-attachment-picker-trigger]:hover {
  background: var(--dsw-alias-interactive-bg-hover-solid);
}
[data-dsh-attachment-picker-trigger]:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px var(--dsw-alias-border-l3);
}
[data-dsh-attachment-picker-trigger]:disabled {
  opacity: 0.5;
  cursor: default;
}
@media (max-width: 400px) {
  [data-dsh-attachment-picker-trigger] {
    width: 24px;
    height: 24px;
    margin-inline: -8px;
  }
}
[data-dsh-attachment-picker-menu] {
  position: fixed;
  z-index: 2147483000;
  display: grid;
  gap: 2px;
  box-sizing: border-box;
  padding: 6px;
  border: 1px solid var(--dsw-alias-border-l4);
  border-radius: 12px;
  background: var(--dsw-specific-menu);
  backdrop-filter: var(--dsw-menu-backdrop-filter);
  box-shadow: var(--dsw-elevation-panel);
  color: var(--dsw-alias-label-primary);
  font: var(--dsw-font-markdown-base);
}
[data-dsh-attachment-picker-menu] [data-dsh-attachment-picker-item] {
  display: flex;
  min-height: 40px;
  width: 100%;
  align-items: center;
  gap: 10px;
  box-sizing: border-box;
  padding: 0 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: start;
}
[data-dsh-attachment-picker-menu] [data-dsh-attachment-picker-item] svg {
  flex: none;
  color: var(--dsw-alias-label-secondary);
}
[data-dsh-attachment-picker-menu] [data-dsh-attachment-picker-item]:hover,
[data-dsh-attachment-picker-menu] [data-dsh-attachment-picker-item]:focus-visible {
  outline: none;
  background: var(--dsw-alias-bg-layer-2);
}
[data-dsh-attachment-picker-menu] [data-dsh-attachment-picker-item]:active {
  background: var(--dsw-alias-bg-layer-1);
}
`
