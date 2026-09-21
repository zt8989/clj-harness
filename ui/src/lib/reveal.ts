// THE CLASSES OF A CONTROL THAT STAYS OUT OF THE WAY UNTIL ITS ROW IS REACHED FOR: the
// sidebar's row actions (archive / unarchive / delete) and its per-project "more" button
// are drawn only when the pointer is over the row or the keyboard is inside it, so that a
// list of rows reads as rows and not as a toolbar repeated forty times.
//
// IT IS ONE STRING IN ONE PLACE BECAUSE THE TWO CALL SITES GOT IT WRONG THE SAME WAY, and
// the way they got it wrong is the reason this file exists at all:
//
//   `shadcn`'s Button carries `disabled:opacity-50`, and THIS IS THE SAME UTILITY WITH THE
//   SAME VARIANT as the `disabled:opacity-0` below. They are not merged by the cascade but
//   by `cn` (tailwind-merge), which keeps the LAST one -- so a call site that has the
//   reveal and no `disabled:opacity-0` LOSES THE REVEAL THE MOMENT THE CONTROL IS DISABLED.
//   The sidebar disables every row while it is busy (a session being created, a folder
//   being picked), so that bug is not a corner: clicking "Add project" -- whose native
//   dialog keeps `busy` true for as long as a human takes to answer it -- made every
//   archive button in the list appear at half opacity at once. Reported by the owner,
//   2026-09-21, measured at `opacity: 0.5` while idle rows read `0`.
//
// `group-hover:disabled:opacity-50` IS NOT A DUPLICATE OF IT: the dimming should still be
// visible once a disabled control IS revealed (hover a row while the sidebar is busy and
// the button is there, greyed). Specificity settles that pair without depending on
// stylesheet order -- `.group:hover .btn:disabled` is more specific than `.btn:disabled` --
// and `disabled:opacity-0` above settles the other pair by sitting last, which is the only
// thing that can settle two rules of equal specificity.
//
// `group-focus-within` IS THE KEYBOARD'S WAY IN, and it is why the reveal is `opacity`
// rather than `visibility: hidden`: an invisible element cannot be focused, so a keyboard
// that Tab-ed into the row could never reach the button to reveal it. Opacity keeps the
// control focusable and in the accessibility tree; the row's `:focus-within` then shows it.
export const REVEAL_ON_HOVER =
  "opacity-0 group-hover:opacity-100 group-focus-within:opacity-100 disabled:opacity-0 group-hover:disabled:opacity-50";
