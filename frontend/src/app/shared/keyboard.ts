/** True when a key event comes from a text field, so single-key shortcuts must not fire. */
export function isTyping(event: KeyboardEvent): boolean {
  const target = event.target as HTMLElement | null;
  return !!target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName));
}

/** Single-key shortcuts are ignored with modifiers, while typing, or when a dialog is open. */
export function ignoreShortcut(event: KeyboardEvent): boolean {
  return event.ctrlKey || event.metaKey || event.altKey || isTyping(event) || !!document.querySelector('dialog[open]');
}
