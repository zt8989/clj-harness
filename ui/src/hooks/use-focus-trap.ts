// THE FOCUS TRAP: a panel drawn OVER the page keeps the keyboard to itself.
//
// WHY IT IS NEEDED AT ALL, and why it goes with `aria-modal="true"` rather than instead of it.
// `aria-modal` is a CLAIM -- it tells assistive technology that everything outside this dialog is
// inert and that the person need not look there -- and a claim nothing makes true is worse than no
// claim at all: the reader tabs once and lands on a control they cannot see, under the panel. So
// the two are one decision with two halves, and this is the half that makes the other honest.
//
// WHAT IT DOES, in three parts:
//
//   1. FOCUS GOES IN when the panel mounts: the first thing a person can Tab to inside it, or the
//      container itself (which is also why a caller gives it `tabIndex={-1}`) when there is nothing
//      to focus yet.
//   2. TAB STAYS IN, WRAPPING AT BOTH ENDS -- Shift+Tab off the first is the last and Tab off the
//      last is the first. It WRAPS rather than swallowing the key, because a trap that eats Tab is
//      a keyboard nobody can explain; and an element in the middle is left to the browser, which
//      already knows how to walk a list.
//   3. FOCUS COMES BACK when the panel unmounts, to whatever had it when the panel opened -- the
//      control that opened it, in practice. That is half the contract: a dialog that forgets sends
//      the next Tab to the top of the document.
//
// ESCAPE IS NOT HERE. Closing is the page's answer, because the page is what knows what is open and
// what is over what (`app.tsx`'s own key handler); a second listener for the same key would be a
// second answer to one question.
//
// IT TOUCHES NO DOM OUTSIDE AN EFFECT, so a component that traps can still be rendered to a string
// (the suites do exactly that -- `test/suites/right-pane.tsx`).
//
// THE OTHER INSTANCE OF THIS RULE is `components/assistant-ui/elements/image.tsx`, which traps its
// zoom overlay inline. It is NOT a caller of this hook on purpose: that file is upstream's,
// translated in place (its own header says so, and each local edit is marked), and moving its trap
// into this repo's vocabulary would be a divergence with nothing on screen to show for it.
import { useEffect, type RefObject } from "react";

/// "Something a person can Tab to inside the panel", the same list the repo's other trap uses:
/// a link with somewhere to go, an enabled button, and anything given an explicit tab stop.
/// `[tabindex="-1"]` is deliberately NOT in it -- that is how a CONTAINER takes focus without
/// becoming a stop of its own, which is exactly what the panel itself is for.
const FOCUSABLE = 'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])';

/// Keep the keyboard inside CONTAINER for as long as the caller is mounted.
export function useFocusTrap(container: RefObject<HTMLElement | null>): void {
  useEffect(() => {
    const root = container.current;
    if (root === null) return;

    /// WHAT HAD FOCUS BEFORE. Captured at mount, given back at unmount.
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;

    const stops = (): HTMLElement[] =>
      Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE));

    // 1. FOCUS GOES IN -- and the container is the fallback, which is what its `tabIndex={-1}` is
    // for: a panel with nothing focusable in it must not leave the keyboard out on the page.
    (stops()[0] ?? root).focus();

    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== "Tab") return;
      const list = stops();
      const head = list[0];
      const tail = list[list.length - 1];
      // NOTHING TO CYCLE: the container holds it, and the browser's own Tab would walk out.
      if (head === undefined || tail === undefined) {
        event.preventDefault();
        root.focus();
        return;
      }
      const here = document.activeElement as HTMLElement | null;
      // FOCUS ESCAPED ANYWAY (a click on the page behind, a browser that moved it): bring it back
      // rather than letting the next Tab carry on out there.
      if (here === null || !root.contains(here)) {
        event.preventDefault();
        head.focus();
        return;
      }
      if (event.shiftKey && (here === head || here === root)) {
        event.preventDefault();
        tail.focus();
      } else if (!event.shiftKey && here === tail) {
        event.preventDefault();
        head.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);

    // AND THE PAGE UNDER IT DOES NOT SCROLL. A panel that covers the page while the page moves
    // under it is a panel whose own scrollbar looks broken; the shell is `h-dvh` so this is
    // usually a no-op, and it is the same line the image overlay takes for the same reason.
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = overflow;
      // 3. FOCUS COMES BACK -- IF THERE IS STILL SOMEWHERE TO PUT IT. The control that opened the
      // panel may itself have been unmounted by whatever closed it (the statistics drawer closes
      // the right-hand column it was opened from), and focusing a detached node is a silent no-op
      // this side would rather not pretend to have done.
      if (previous !== null && previous.isConnected) previous.focus();
    };
  }, [container]);
}
