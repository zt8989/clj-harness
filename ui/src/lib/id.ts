// THE NAME A CONVERSATION GETS, from the one library that already knows how.
//
// ------------------------------------------------------------- whose name it is
//
// AN ID IS PART OF WHAT THE CLIENT SENDS, by AG-UI's design: `RunAgentInput.threadId` is
// the caller's field, and `@ag-ui/client`'s own `AbstractAgent` names any conversation it
// was handed without one (`this.threadId = threadId ?? v4()` -- its constructor, and this
// page's host passes the id instead, so the library's mint is what `newId` below hands to
// it). This page follows that design: it mints the name when it opens a conversation (at
// mount, and at 新建) and hands it over at the first SEND -- `POST /api/sessions` for a
// task, `POST /api/project` with the directory for a project session -- so
// 「点击新增不立刻会话，发送才新建」 holds: the click writes nothing, and the store hears
// about this conversation only when somebody sends to it. That binding is where the id
// and the project meet, and it is the only moment they do.
//
// IT WAS THE SERVER'S FOR ONE DAY (2026-09-23, `.scratch/server-named-sessions`): a route
// that minted a name and wrote nothing, and the page asking for one. That was withdrawn on
// 2026-09-24 -- the design above is the library's, and fighting it put two owners on one
// name -- and the half of it that mattered is kept here instead: see the next paragraph.
//
// --------------------------------------------------------- why not crypto.randomUUID
//
// BECAUSE THE PLATFORM'S IS NOT UNIVERSAL. `crypto.randomUUID` exists only in a SECURE
// CONTEXT -- https, or localhost -- and the phone that started this was reading the dev
// server over `http://192.168.x.x:<port>`, which is neither: the page threw
// `TypeError: crypto.randomUUID is not a function` before it drew anything, while the
// machine it was built on (127.0.0.1, which IS secure) stayed green. That is the class of
// bug a LAN address is needed to find, and it is why this page does not call that
// function -- anywhere.
//
// WHAT IT CALLS INSTEAD IS THE CLIENT LIBRARY'S OWN, whose header says what this file
// would otherwise have to say for it: *"Generate a random UUID v4. Cross-platform
// compatible (Node.js, browsers, React Native)"*. Its implementation is the same v4 built
// on `crypto.getRandomValues` -- which is NOT restricted to secure contexts -- used
// exactly where the platform's shortcut is missing. So the page owns no generator and
// adds no dependency: the library already in this bundle for the run protocol is the thing
// that names conversations, which is also what its own `AbstractAgent` would have done.
//
// A RE-EXPORT RATHER THAN A WRAPPER, and that is the decision rather than tidiness: there
// is no rule of ours to keep in the middle, and the identity is pinned (`test/suites/id.ts`
// asserts it IS the library's function), so a future edit that swaps in the platform's
// call -- or a hand-rolled v4 -- has to break a case to do it.
export { randomUUID as newId } from "@ag-ui/client";
