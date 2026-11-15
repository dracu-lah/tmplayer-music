// The site's whole script, bundled by Astro into one small file. Every part is an enhancement: without
// it the menu is a wrapping row of links, the support links open their pages, the card never shows
// and the UPI box shows its QR and ID. No timers run after the first paint except the card's one delay.

const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const $ = <T extends HTMLElement>(sel: string, root: ParentNode = document) => root.querySelector<T>(sel);
const $$ = <T extends HTMLElement>(sel: string, root: ParentNode = document) => Array.from(root.querySelectorAll<T>(sel));

// Phone menu: a button shows the links; Esc and a link click close it.
(() => {
  const btn = $<HTMLButtonElement>('[data-menu]');
  const header = $('[data-header]');
  if (!btn || !header) return;
  btn.hidden = false;
  const set = (open: boolean) => { header.classList.toggle('is-open', open); btn.setAttribute('aria-expanded', String(open)); };
  btn.addEventListener('click', () => set(!header.classList.contains('is-open')));
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && header.classList.contains('is-open')) { set(false); btn.focus(); } });
  $$('a', header).forEach((a) => a.addEventListener('click', () => set(false)));
})();

// Copy buttons: data-copy holds the text. The label says Copied for two seconds.
(() => {
  $$<HTMLButtonElement>('[data-copy]').forEach((b) => {
    b.hidden = false;
    b.addEventListener('click', async () => {
      const label = $('[data-copy-label]', b);
      const text = b.dataset.copy ?? '';
      try { await navigator.clipboard.writeText(text); } catch {
        const t = document.createElement('textarea'); t.value = text; document.body.append(t); t.select();
        try { document.execCommand('copy'); } catch { /* nothing more to try */ }
        t.remove();
      }
      if (label) { const was = label.textContent; label.textContent = b.dataset.copied ?? ''; setTimeout(() => { label.textContent = was; }, 2000); }
    });
  });
})();

// The floating support card: shown shortly after load, hidden by its x for this page view only.
(() => {
  const card = $('#bmc-float');
  if (!card) return;
  let gone = false;
  setTimeout(() => {
    if (gone) return;
    card.hidden = false;
    requestAnimationFrame(() => requestAnimationFrame(() => card.classList.add('is-on')));
  }, reduced ? 300 : 700);
  $('[data-bmc-hide]', card)?.addEventListener('click', () => {
    gone = true;
    card.classList.remove('is-on');
    setTimeout(() => { card.hidden = true; }, reduced ? 200 : 450);
    const main = $('#main');
    if (main) { main.setAttribute('tabindex', '-1'); main.focus({ preventScroll: true }); }
  });
})();

// Buy Me a Coffee in a dialog. The iframe gets its src when a support link is pointed at, focused
// or clicked, never with Save-Data. A middle or modified click, or no <dialog>, follows the link.
(() => {
  const dialog = $<HTMLDialogElement>('#bmc-dialog');
  if (!dialog || typeof dialog.showModal !== 'function') return;
  const frame = $<HTMLIFrameElement>('.bmc-dialog-frame', dialog);
  if (!frame) return;
  let opener: HTMLElement | null = null;
  const warm = () => {
    if (frame.getAttribute('src')) return;
    frame.addEventListener('load', () => dialog.classList.add('is-loaded'), { once: true });
    frame.setAttribute('src', frame.dataset.src ?? '');
  };
  const conn = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection;
  const saveData = !!conn?.saveData;
  if (!saveData) {
    ['pointerover', 'focusin', 'touchstart'].forEach((type) =>
      document.addEventListener(type, (e) => { if ((e.target as Element | null)?.closest?.('a[data-bmc]')) warm(); }, { capture: true, passive: true }));
  }
  document.addEventListener('click', (e) => {
    const link = (e.target as Element | null)?.closest?.<HTMLElement>('a[data-bmc]');
    if (!link || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    warm();
    opener = link;
    dialog.showModal();
  });
  $('[data-bmc-close]', dialog)?.addEventListener('click', () => dialog.close());
  dialog.addEventListener('click', (e) => { if (e.target === dialog) dialog.close(); });
  dialog.addEventListener('close', () => { opener?.focus({ preventScroll: true }); opener = null; });
})();

// Pay by UPI. On a touch device the link is followed (the UPI app opens) and the box opens too, for when
// the wrong app opened or none did. Elsewhere the link is held back and the box shows the QR and ID.
(() => {
  const dialog = $<HTMLDialogElement>('#upi-dialog');
  const links = $$('[data-upi]');
  if (!dialog || !links.length || typeof dialog.showModal !== 'function') return;
  const touch = window.matchMedia('(pointer: coarse)').matches;
  let opener: HTMLElement | null = null;
  const open = $<HTMLElement>('[data-upi-open]', dialog);
  if (open && touch) open.hidden = false;
  links.forEach((link) => link.addEventListener('click', (e) => {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    opener = link;
    if (!touch) e.preventDefault();
    if (!dialog.open) dialog.showModal();
  }));
  const close = () => { if (dialog.open) dialog.close(); };
  $('[data-upi-close]', dialog)?.addEventListener('click', close);
  dialog.addEventListener('click', (e) => { if (e.target === dialog) close(); });
  dialog.addEventListener('close', () => { opener?.focus(); opener = null; });
})();
