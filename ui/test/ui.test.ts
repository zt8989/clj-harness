// The vitest driver: one harness for the run, one place a suite is registered,
// and a guard against the failure mode a green run can hide.
//
// Why the suites are plain modules under test/suites/ rather than test files of
// their own, when vitest would happily run four files:
//
// 1. ONE harness per run. vitest gives each test FILE its own module graph, so
//    four suite files would spawn four Clojure servers. Here `beforeAll` runs
//    once, the facts are handed to test/e2e.ts once, and every case shares it.
// 2. ONE registration list. A suite missing from SUITES below does not run --
//    the same shape the ClojureScript runner had, where a suite absent from its
//    list silently did not run.
//
// That silent drop is the failure mode this file exists to make impossible, in
// both its forms -- a suite that contributes no cases, and a suite dropped from
// the list. The old bridge guarded only the TOTAL (`deftest` dropped by the
// compiler -> 0 cases -> a green run that proved nothing); the checks below guard
// each suite's non-emptiness AND pin the total, and they run at COLLECTION time,
// so a throw here fails the file before a single case starts -- there is no window
// in which "no cases" is reported as "passed".
import { afterAll, beforeAll, describe, expect, test } from "vitest";

import { configure, type Suite } from "./e2e";
import { startHarness } from "./support/harness";
import { approvalSuite } from "./suites/approval";
import { attachmentsSuite } from "./suites/attachments";
import { clientSuite } from "./suites/client";
import { contextSuite } from "./suites/context";
import { elicitationSuite } from "./suites/elicitation";
import { elicitationCardSuite } from "./suites/elicitation-card";
import { framesSuite } from "./suites/frames";
import { idSuite } from "./suites/id";
import { i18nSuite } from "./suites/i18n";
import { skillsSuite } from "./suites/skills";
import { statsSuite } from "./suites/stats";
import { turnSuite } from "./suites/turn";
import { pickerSuite } from "./suites/picker";
import { restoreSuite } from "./suites/restore";
import { runningSuite } from "./suites/running";
import { turnsSuite } from "./suites/turns";
import { concurrentSuite } from "./suites/concurrent";
import { sidebarSuite } from "./suites/sidebar";
import { sessionTitleSuite } from "./suites/session-title";
import { relativeTimeSuite } from "./suites/relative-time";
import { sidebarRowsSuite } from "./suites/sidebar-rows";
import { sidebarRefetchSuite } from "./suites/sidebar-refetch";
import { injectionSuite } from "./suites/injections";
import { recordSuite } from "./suites/record";
import { windowSuite } from "./suites/window";
import { reasoningRowSuite } from "./suites/reasoning-row";
import { toolRowSuite } from "./suites/tool-row";
import { subagentsSuite } from "./suites/subagents";
import { subagentViewSuite } from "./suites/subagent-view";
import { muxSuite } from "./suites/mux";
import { coalesceSuite } from "./suites/coalesce";
import { rightPaneSuite } from "./suites/right-pane";
import { threadMessagesSuite } from "./suites/thread-messages";
/// Every suite, in the order the runner reports them. A suite that is not listed
/// here is not run, so this is the one place a new one has to be added.
///
/// THIS LIST IS THE MERGED ONE, and both sides of the merge grew it: `brand-header`
/// appended `sessionTitleSuite`, `relativeTimeSuite` and `sidebarRowsSuite` after the
/// `sidebar` suite, and `sessions-live-on-the-server` appended `recordSuite` and
/// `windowSuite`. Neither side touched the other's additions, which is why the resolved
/// list is a concatenation rather than a choice. `ask` is the third side: it added
/// `elicitationCardSuite` beside the `elicitation` suite it belongs to, and
/// `session-after-refresh` is the fourth (`runningSuite`, after `restoreSuite`).
/// `subagent-view` is the fifth, and it brought `reasoningRowSuite`, `toolRowSuite`,
/// `subagentsSuite` and `subagentViewSuite`. `new-session-appears` is the sixth, and it
/// appended `sidebarRefetchSuite` after `sidebarRowsSuite` -- each side only ever appended.
/// `refreshed-turn-keeps-growing` is the seventh: it appended `threadMessagesSuite` -- the suite
/// for the module that builds the page's copy of a conversation out of the server's messages,
/// where a tool call still in flight used to lose the server's word (`state: running`) and come
/// back 待审批. APPENDED, like every side before it.
const SUITES: readonly Suite[] = [framesSuite, clientSuite, turnSuite, approvalSuite, skillsSuite, statsSuite, contextSuite, elicitationSuite, elicitationCardSuite, attachmentsSuite, turnsSuite, injectionSuite, pickerSuite, i18nSuite, restoreSuite, runningSuite, concurrentSuite, sidebarSuite, sessionTitleSuite, relativeTimeSuite, idSuite, sidebarRowsSuite, sidebarRefetchSuite, recordSuite, windowSuite, reasoningRowSuite, toolRowSuite, subagentsSuite, subagentViewSuite, muxSuite, rightPaneSuite, threadMessagesSuite, coalesceSuite];

/// The number of cases the suites are expected to contribute, pinned. The count
/// is a contract, not bookkeeping: it is what makes a suite silently dropping out
/// of the list above -- or losing its cases -- fail the run instead of quietly
/// shrinking a green one. Bump it deliberately when a case is genuinely added or
/// removed.
///
/// 11 -> 14: the `skills` suite's three cases (both layers from the real roots; a
/// shadowed name listed once; asking changes nothing).
/// 14 -> 18: the `stats` suite's four (the strip's cells, the numbers it leaves
/// out, a real run folded by the endpoint, and a session that has not run).
/// 18 -> 24: the `elicitation` suite's five -- one through the whole loop (a
/// server's question parks the run and the answer finishes it) and four on the
/// form itself (the four kinds; answers keep the declared type; a field nobody
/// expected is kept and named; an empty or odd schema is an empty form).
/// 24 -> 26: the `attachments` suite's two -- the model rule (the same judgement
/// as the server's `undeclared-input`, and the one place the two could disagree)
/// and the 2 MB cap on source bytes, boundary included. Both are pure.
/// 29 -> 31: the `picker` suite's two -- what a query keeps (the label, the hint,
/// the group, case and whitespace) and how runs of one group are grouped. Both pure.
/// 26 -> 29: the `turns` suite's three -- the boundary of a turn (a run of adjacent
/// assistant messages), when it counts as settled, and the summary line's two
/// numbers. All three are pure arithmetic over a literal message list.
/// 31 -> 33: the `i18n` suite's two -- how a language the server hands the page
/// narrows to one it speaks (falling back to English for anything unusable) and the
/// parity between the two catalogs (the same keys, every value a non-empty string).
/// Both pure; this is the pair that makes a half-translated page a red run.
/// 33 -> 34: the `stats` suite's third -- the durations and the timestamps in BOTH
/// languages, plus the two units that deliberately do not translate. Ticket 02 merged
/// the four copies of the size/duration words into `lib/format.ts`, and this is the
/// case that pins what they now say.
/// 34 -> 35: the `i18n` suite's third -- every catalog entry is NAMED by some source
/// file, the other direction of the parity check (a mistyped key that was then added
/// to the catalog to satisfy the types, an entry left behind by a deleted row). It
/// reads the sources through `import.meta.glob`; the case itself says what a grep
/// costs and which way it errs.
/// 35 -> 36: the `concurrent` suite's one -- two thread ids running at once, with
/// each session's own log read back off disk (its `input` row, its terminal frame)
/// and rebuilt through the server. It is the backend half of parallel sessions: the
/// server always allowed this and nothing had ever asked it to.
/// 36 -> 39: the `sidebar` suite's three -- the session row RENDERED (react-dom/server)
/// and read as text: its id line, the second line that says which absence it is looking
/// at, and the parked word beside the id rather than inside a line that truncates. This
/// is the suite that exists because the id line went blank in the i18n merge and a green
/// tree could not see it -- see suites/sidebar.tsx and vitest.config.ts.
/// 44 -> 46: the `restore` suite's two -- the page's memory of which session it is in
/// (the one key, the conditional forget, and a storage that throws rather than answers)
/// and the single question it asks of a listing. Both pure; the reload itself is a
/// browser's, and it is the walkthrough in `.scratch/session-after-refresh/`.
/// 47 -> 50: the `injections` suite's three -- what an injected-context card says (its
/// title is the tag the block arrived with, its size is BYTES), how a rebuilt
/// conversation gets its cards back, and the adapter contract the whole feature rests on
/// (`toAgUiMessages` never sends a `data` part back).
/// 46 -> 47: the `sidebar` suite's fourth -- the label a row can wear, which the flat
/// archived block needs (a filed-away session has to say which project it came from,
/// since nothing groups it any more). Same reason the other three are there: it is a
/// thing the row SAYS, and only a render can see it.
/// 50 -> 52: the `sidebar` suite's fifth and sixth -- the two controls that fold the
/// sidebar away and bring it back (`components/sidebar-toggle.tsx`): the word each one
/// says in both languages, and the two `aria-*` facts that make them one verb (one
/// `aria-controls`, and an `aria-expanded` each). The single reference a render in this
/// run cannot reach -- the sidebar's own element -- is read as source in the same case.
/// 52 -> 69: THE `brand-header` SIDE OF THIS MERGE, counted from the same base as the
/// other chain below. Two of them are the new
/// `session-title` suite, which pins a session's title as arithmetic over literal
/// messages -- the first text the user said, what whitespace becomes, the 60-code-point
/// clip (an emoji is two UTF-16 units, so the cut is by character), the fallback word in
/// both languages, and the `· clj-harness` tail -- because a title is derived, not
/// stored, and a browser tab has no ellipsis. The third is the `sidebar` suite's: the
/// brand row's own name and mark, and (read as source, like the `id` beside it) that the
/// collapse control left the header row and now ends the brand row.
/// 55 -> 56: the rail (`sidebar-rail`) -- the mark alone with no wordmark, and the list
/// hidden-but-mounted, both read out of the sidebar's source because this run cannot
/// render that file at all. What the rail LOOKS like is the browser walkthrough's.
/// 60 -> 62: two of the `sidebar` suite's, for the store-backed row: one case was
/// REWRITTEN rather than added (the row is one line now, so the case that read its second
/// line asserts that line's ABSENCE instead), and the two new ones pin the relative age
/// as the row words it -- both languages -- and the indent slot the spinner goes in.
/// 62 -> 65: the `relative-time` suite's three -- the ladder's boundaries (where two
/// branches meet, which is where an off-by-one hides), a clock running ahead (the server
/// writes the number, this machine reads it), and the counts being whole and floored.
/// Pure: `lib/relative-time.ts` imports nothing, which is why it answers a bucket and the
/// row answers the words.
/// 65 -> 69: the `sidebar-rows` suite's four, for "一个项目（或任务那一块）最多画 5 行":
/// the fold itself and the count it reports, the row being read never being the one that
/// folds away, a block opened by hand versus one the current session outranks, and the
/// list not being copied when nothing is folded. Same reason to be pure: `lib/sidebar-rows.ts`
/// imports nothing, and the two callers (`components/sidebar.tsx`) cannot be imported here
/// at all -- so where the control lands and whether clicking it draws the rows are the
/// browser walkthrough's.
/// 52 -> 65: THE `sessions-live-on-the-server` SIDE, and it starts from the same 52 as the
/// chain above -- the two sides were counted independently, so these two totals are two
/// branches of one arithmetic, not a continuation of each other.
/// 52 -> 54: the `record` suite's two -- a record with nothing to say (nothing drawn,
/// and a state this client does not know is silence rather than the wire value on
/// screen) and a degraded one RENDERED in both languages, carrying the writer's own
/// reason and the plural of how much is waiting. It exists for the same reason the
/// `sidebar` suite does: a sentence that reaches the screen is the one thing a green
/// tree could not see.
/// 54 -> 56: the `client` suite's two, and they are ticket 03's UI half. ONE reads the
/// wire: a run's body carries `append` (this action's own entries) and NOT the
/// accumulated `messages` nor a client `runId` -- the second run's `append` holds only
/// the second question, which is the whole change. THE OTHER reads the other end of the
/// same contract: `startTask` with nothing to name comes back with an id the SERVER
/// minted, listed as a conversation this home keeps and usable for a run -- where a
/// page-made id used to be quietly registered by the run edge.
/// 56 -> 65: the `window` suite's nine, and they are ticket 06's UI half -- the rules
/// that turn feed frames into a copy, the control that asks for older history, the
/// sentences owed when the answer is not simply "more messages", and the arithmetic
/// that keeps a reader's place when a page is prepended. The three answers that are not
/// "append" are the reason it exists: a hole, a reopen and a copy that is ahead of the
/// conversation are all SILENT failures when they go wrong, and silence is not
/// something a later test can notice.
/// 69 + 13 = 82: THE RESOLVED TREE. The addition is exact because neither side rewrote a
/// case the other added: `brand-header`'s seventeen (69 - 52) and `main`'s thirteen
/// (65 - 52) land in different files -- the sidebar's own suites on one side, the record
/// and the window on the other -- so the two counts add. What the MERGE itself changed
/// in these suites is one rule rather than a case count: the page speaks a live title
/// only for a session it MINTED (`app.tsx`'s `minted`), because a window's first user
/// message is not the conversation's first -- and this run cannot reach that rule at all
/// (`components/sidebar.tsx` cannot be imported here, so the `sidebar` suite reads it as
/// text and the behaviour itself is the browser walkthrough's).
/// 82 -> 83: `parallel-call-parent`'s sixth `frames` case -- two tool calls in one turn
/// are ONE assistant message. On that branch it was 65 -> 66, because it forked before the
/// sidebar work; here the same case adds one to the merged total. The provider refuses the
/// split shape outright (the 2026-09-21 RUN_ERROR: "leaves 4 tool calls unanswered"), so
/// the wire contract is worth a real-client case rather than an offline fold alone.
/// 83 -> 84: `injections`' fourth -- the OPENING ENTRY, which is one message with two
/// readings (a card part and the text the model read, `.scratch/session-opening`). It pins
/// both halves of that change that only this side can see: the card replaces the text
/// rather than joining it, and the copy that goes back out is the text under the same
/// entry id, which is what makes the server drop the repeat instead of writing the opening
/// into the conversation twice.
/// 84 -> 85: `injections`' fifth -- `isCardOnly`, the shape an opening entry is DRAWN in
/// (ticket 02 of `.scratch/session-opening`). The fourth case had spelled that entry
/// `role: "assistant"`, which is not what the server writes, and that is why every gate
/// stayed green while the app drew the person's AGENTS.md as a bubble they had typed. The
/// fifth pins the test that stops it, over the PARTS rather than the role, because the role
/// is the one thing the two kinds of message share.
/// 86 -> 88: the `running` suite's two, which are ticket 04 of `.scratch/session-after-refresh`
/// -- a conversation the SERVER is still answering must not offer Send. One is the union as
/// arithmetic (`statusOf`: this page's own run OR the window's word, and which words do NOT
/// mean in flight); the other RENDERS the sentence in both languages. Two readings of one
/// session used to disagree and the only reply was the run edge's 409 -- found in a browser,
/// because nothing in this run could see either half.
/// 88 -> 89: the `picker` suite's third, for `.scratch/provider-availability`'s ticket
/// 03 -- the rule that a vendor is offered only when this home holds a key pointing at
/// it, which the settings page and the composer's model picker both read
/// (`lib/provider-key.ts`, zero imports). The two components cannot be imported here,
/// so this case pins the RULE and the walkthrough checks the drawing.
///
/// 89 -> 92: THE OTHER SIDE OF THIS MERGE -- the `reasoning-row` suite's three, which
/// are the words on a thinking row (the first line of a thought that has stopped; the
/// whole of one that is still arriving) and WHICH PARTS ARE ONE THOUGHT (a thought
/// spans the messages of its turn: a tool call ends one, the answer's text does not).
/// They are here because the rules MOVED: they used to be three functions inside
/// `message-parts.tsx`, where this run cannot reach them at all, and the row they
/// decide is the one thing a reader watches while the model thinks. What a string
/// What a string
/// cannot show -- that the row never unfolds itself, that the live line is dragged
/// (interpolated, not a jump per token), that it is PAINTED (a masked-away row is
/// green on geometry and blank on screen), and that the first line comes back when the
/// thought ends -- is the browser walkthrough's half (`.scratch/thinking-row-tail/`).
///
/// 92 -> 94: the `tool-row` suite's two, for `.scratch/omp-parity`'s ticket 01 -- the rename
/// of `anchor_grep` to `grep`. The ticket asked for a case to be CHANGED in each of the two
/// places keyed by tool name (`TOOL_ICONS` and `subjectOf`) and there was none: both fall
/// back rather than fail, so a renamed tool loses its icon and its subject with nothing going
/// red. Read as source, not rendered -- `message-parts.tsx` reaches `lib/i18n.ts`, which
/// touches `document` at module scope, so this run cannot import it at all; the drawing is
/// the browser walkthrough's half.
///
/// 94 -> 95: THE OTHER SIDE OF THIS MERGE, and it is the same day as the two paragraphs
/// above -- the `client` suite's fourth. The wire stopped closing a thought when the answer
/// started (`harness.edge.ag-ui`: the reasoning message stays open across the answer and
/// closes at the end of the MODEL CALL), and this is the half only a real client can
/// answer: a thought that comes BACK after the answer has begun arrives as ONE reasoning
/// message, drawn ABOVE the answer. What a page gets wrong here is a stray 思考 row under
/// the answer -- which is what a reader saw before either half was fixed.
/// 95 -> 96: the `client` suite's fifth -- a run this page hung up reports itself as
/// a cancellation rather than a failure. The transport turns a stream this side cut off
/// into its own `RUN_ERROR` frame carrying the browser's wording for the abort
/// (`code: "abort"`), which used to reach the interface as a run failure: pressing
/// Stop drew the call that was in flight as `Failed` with `BodyStreamBuffer was
/// aborted` under it. This reads the two facts the fix turns on -- no `RUN_ERROR`
/// reached the subscriber, and the error it is handed instead carries the `AbortError`
/// name the interface maps to "Cancelled" -- and it has to be a live client: the abort
/// has to land on a stream that is genuinely in flight.
/// 96 -> 102: THE OTHER SIDE OF THIS MERGE -- the `subagents` suite's six, which are
/// `.scratch/subagents`' UI half. The endpoint's wire shape (the two built-ins, their
/// baselines, the `builtin` flag, the file a save would write, the problem that is part
/// of a 200); a delegation row's three facts; one run in flight marked and the other
/// not, in one list; the definitions group with and without a custom entry; the settings
/// roster's built-in flag; and the same rows in both languages. Five of the six RENDER
/// (`react-dom/server`), which is why the rows live in `components/subagent-list.tsx` --
/// a module that must not reach `lib/i18n.ts`, whose `document` write would break this run.
///
/// 102 -> 106: `.scratch/subagent-view`'s UI half, and it is a COUNTED CHANGE IN BOTH
/// DIRECTIONS -- two cases out, six in.
///
/// OUT (-2): the `subagents` suite's two delegation-row cases, and the `RunRows` half of
/// its both-languages case. `RunRows` had exactly one reader (the sidebar's subagent
/// block), ticket 06 of the feature retires that block, and a rendered-string case for a
/// component with no screen is a case that tests the test. What the suite keeps is the
/// ENDPOINT, `runs` still included: the route stays, and "the front end has no reader for
/// half of this answer" is exactly the kind of asymmetry that should be visible in a file
/// rather than inferred from a deleted one.
///
/// IN (+6): the `subagent-view` suite, whose name is the feature. The `agent` card's
/// subject (which subagent, and one line of what it was asked -- without the arm the row
/// answers the task and never names the subagent); the door's two conditions (the
/// record's answer for this `toolCallId`, and a panel to open) and the fact that the
/// call's RESULT is not one of them, because a delegation is worth watching while it
/// runs; one delegations read per parent session rather than per card; the follow
/// client's transport (`GET`, no body, signal kept -- the abort is how closing the panel
/// drops the server-side subscription) and the mirror's refusal to hydrate from
/// `rebuild`; the `composer` switch on `Thread` with the new-chat furniture going with
/// it; and the retired sidebar block leaving nothing that still compiles behind.
///
/// ALL SIX READ SOURCES (`?raw`), the idiom `tool-row` introduced and for its reason:
/// `message-parts.tsx` and `app.tsx` reach `lib/i18n.ts`, which touches `document` at
/// module scope, and this run has no jsdom by design. So they pin DECISIONS -- which
/// vocabulary a row answers, what a door is conditioned on, what a transport sends, where
/// the column sits in the flex row -- and the DRAWING (the door's hover, the panel's
/// width, the main column staying readable beside it, the transcript growing live) is the
/// browser walkthrough's half, as the suite's own header says.
/// 106 -> 108: `ask`, the one tool whose purpose is to stop. One case through the whole
/// loop on the `elicitation` suite -- a BUILT-IN's question parks the run, the endpoint
/// answers who is asking, and the answers come back as the call's result -- and one new
/// suite beside it (`elicitation-card`) whose single case RENDERS the card's title in
/// both languages: three askers, three distinct lines, and none of them inventing a
/// server. The second is the sidebar lesson applied to the other card that names
/// somebody: a title that draws nothing is invisible to every check about keys.
/// 108 -> 112: the rest of what `ask` can ask. Three on the `elicitation` suite's RULES --
/// candidates driven verbatim with no own-words box assumed, the own-words answer that
/// stands where the pick would have, and a list of answers that is never a joined
/// string -- and one on `elicitation-card` that RENDERS a field for each kind and counts
/// the `data-slot`s: one tick box per candidate, a select for a single choice, an
/// own-words box only where the schema asked for one. The card's count is the claim, not
/// bookkeeping -- a select drawn over a multiple choice loses every answer but one and
/// looks perfectly fine doing it.
/// 113 -> 114: THIS BRANCH'S OWN CASE (`.scratch/session-after-refresh` ticket 09) -- the
/// `client` suite's stop: the page asks the SERVER to end a running conversation and the
/// terminal that comes back is a cancellation, not a failure. It is a live client case for
/// the same reason as the one above it: the stop has to land on a stream in flight.
/// 114 -> 124: `new-session-appears`'s ten, the rule a NEW SESSION'S ROW appears by --
/// `sidebar-refetch` (`.scratch/new-session-appears`). One read is not enough, because it
/// can be served before the registration that writes the row commits and an id spent on it
/// is a row that never comes; and waiting for the run to end is not the answer either,
/// because the row is the registration's rather than the run's -- NEITHER IS STOPPING AT
/// THE ROW, since the name and the send time are the run's own write and a row can be there
/// and still say 还没跑过. AND THE ROW THAT HAS ARRIVED IS A CANDIDATE IN ITS OWN RIGHT: the
/// id leaves the page's minted titles the moment the listing names it, so the last case is
/// the one that catches a spinner the stale snapshot put on a row nothing would ask about
/// again. The rule is pure (`lib/sidebar-refetch.ts`), so what these cases pin is how many
/// asks one id is worth, when they go out, and which id gets the next one.
/// 130 -> 128: the `id` suite is TWO CASES NOW, and the story is worth keeping even at
/// this length. It exists because a GREEN TREE SHIPPED A BROKEN PAGE: the phone threw
/// `TypeError: crypto.randomUUID is not a function` on a dev server reached over
/// `http://192.168.x.x` -- `randomUUID` is secure-context-only, so it is there on
/// 127.0.0.1, where every gate ran, and absent on the LAN address where the owner was
/// reading. The fix of that day added a fallback chain to `lib/id.ts` and pinned its four
/// branches here. THE NEXT DAY BOTH ENDS MOVED: the page stopped naming conversations for
/// a server route (2026-09-23, withdrawn on the 24th -- the design is the client
/// library's, and its `AbstractAgent` already does `threadId ?? v4()`), and `lib/id.ts`
/// became a re-export of `@ag-ui/client`'s own `randomUUID`, whose header calls itself
/// *"Cross-platform compatible (Node.js, browsers, React Native)"*. With no generator of
/// ours left there are no branches to pin, so what remains is the DECISION (that function
/// and not the platform's -- the identity case) and the shape the store, the edge and the
/// record all read. That a page actually starts over a LAN address is still the browser
/// walkthrough's half, as the suite's own header says.
/// 128 -> 130: the `picker` suite's two, for the model picker's ROW IDENTITY. A model id
/// does not name a row -- two vendors may declare the same one -- and the row used to be
/// carried by the id alone, so a pick under either vendor's heading resolved to whichever
/// vendor the catalog listed first. `lib/model-rows.ts` is the pure rule (the pair as one
/// key, the rows the menu offers, and the session's own row when the menu cannot offer it),
/// which is why these two are literals in and options out; that a click actually SENDS the
/// vendor under whose heading it sat is the browser walkthrough's half.
/// 128 -> 130: the `mux` suite's two (`.scratch/events-mux-and-host`), for the downlink's
/// ADDRESS and the declaration it carries: the handshake rides the one `/api` prefix with a
/// subscriber token and the followed set on it, and a page that follows nothing declares
/// nothing. That a socket really carries a window is the browser walkthrough's half, as
/// `lib/mux.ts` says.
/// 130 -> 131: the `turns` suite's one -- a turn's CONCLUSION (the last thing it said),
/// which is what a folded turn keeps. A turn that only thought or called tools has
/// none, so the fold puts it away whole instead of leaving its last step on screen
/// (`lib/turns.ts`, ticket 06 of `.scratch/events-mux-and-host`).
/// 128 -> 132: `.scratch/right-pane-tasks`' ticket 01, the switch on the RIGHT-hand column and the
/// shell it opens onto -- the `right-pane` suite's three, and one more in `subagent-view`.
///
/// THE NEW SUITE RENDERS the two icon controls and the task pane to a string (the `sidebar`
/// suite's idiom, on the other side) and READS the sources for what a render cannot reach: that
/// the task pane is the mirror's own column (the class strings compared, so the two states of one
/// element cannot drift), that the state is one value with three shapes and who writes each of
/// them, and that the open control is drawn only while the column is closed -- `hidden` below the
/// `md` the column itself stops at, because a control that does nothing when pressed is worse
/// than no control. The LAYOUT (the column beside the conversation, the two corners, nothing
/// drawn at 767px) is the browser walkthrough's half, as that suite's header says.
///
/// THE ONE IN `subagent-view` IS A MOVED ASSERTION, not an added one: the mirror header's close X
/// is retired (the column's own collapse is the same verb, and one row does not get two doors into
/// one room), so the case that pinned the panel's way out now pins the shared control standing
/// where the X was -- and the `key={threadId}` assertion above it was renamed to the new state,
/// not deleted. Nothing was removed from the count to make either one pass.
/// 132 -> 133: `.scratch/right-pane-tasks`' ticket 02, the task pane's BOTTOM section -- the
/// `right-pane` suite's fourth case. It renders a job row in both languages and pins the two
/// things a green tree would not see: the command is ONE line whatever it was written as, and
/// the clock is drawn ONLY on a row that is still running (a finished row's duration slot is
/// absent, and that absence is the assertion). The same ticket REWROTE ticket 01's "no fetch"
/// block rather than adding to it -- that boundary was written to move here -- and what the file
/// pins now is that the pane still fetches nothing ITSELF: the read and the poll live in one hook
/// (`hooks/use-task-pane.ts`) and one reader (`lib/jobs.ts`), which is what lets ticket 03's
/// second read share the same tick. What a source read cannot show -- that a long command is
/// clipped rather than widening the column, and that a closed pane really leaves nothing in
/// flight -- is the browser walkthrough's half, as that suite's header says.
/// 133 -> 137: `.scratch/right-pane-tasks`' ticket 03, the task pane's TOP section -- FOUR
/// cases. Three in the `right-pane` suite: the join and the narrowing (`lib/subagents-runs.ts`)
/// are PURE, so "only THIS session's delegations" and "a run whose definition was deleted still
/// draws" are asserted without a browser, together with the door each row is (its OWN
/// `threadId`, never a position); the row RENDERED in both languages (name, clipped description,
/// the status word read from `running` ALONE, and `delegatedAt: null` drawing no line); and the
/// column's new way back RENDERED, which also pins what it is not (a disclosure, or a second
/// close). The fourth is in the `subagent-view` suite: the mirror's header carries that way back
/// at its TRAILING end -- the place ticket 01 deliberately kept for it -- and the page wires it
/// to the TASK VIEW rather than to a closed column. What a source read cannot show -- that the
/// rows are clickable, and that the back control really lands on the list -- is the browser
/// walkthrough's half, as that suite's header says.
/// 137 -> 138: `.scratch/right-pane-tasks`' ticket 04, the stop control -- ONE case. The row
/// RENDERED in both languages carries a ■ while its job is running and does not once it is
/// over (the absence is the assertion, as it is for the clock); the rest is a source read --
/// the in-flight bit and the disable that shape `components/session-run-stop.tsx` established,
/// the refusal that is drawn rather than swallowed, the POST (`lib/jobs.stopJob`) and the fact
/// that the row's new state arrives on the pane's EXISTING tick (no second clock, and no
/// second reader of the list, for one press). What it cannot show -- that a real press stops
/// the process tree, and that the NEXT model call is handed the person's block -- belongs to
/// the Clojure suites and to the browser walkthrough.
/// 131 + 10 IS THE MERGED ONE: the downlink side and the right-hand column were cut from
/// the same 128 and neither touched the other's cases, so a merged tree owes the sum -- 131
/// from `.scratch/events-mux-and-host` (the `mux` and `turns` suites above) plus the ten
/// `.scratch/right-pane-tasks` added (128 -> 132 -> 133 -> 137 -> 138, above).
/// 141 -> 142: `.scratch/readback-verbs` ticket 01 -- ONE case, `todo_read`'s icon. It pins
/// the read half of the task list to the SAME `ListTodoIcon` as `todo_write`, and pins the
/// two as a pair, because a row for a tool with no arguments has nothing but its icon and
/// its name to say which hand this call is. The DRAWING is still the browser walkthrough's.
/// 142 -> 143: `.scratch/readback-verbs` ticket 02 -- ONE case, `job_list`'s family. The four names
/// (`job`, `job_kill`, `job_list`, `job_output`) now carry ONE icon, because they are one subject --
/// a command nobody is waiting for -- and their rows are read together: a listing is opened, then
/// one of its ids is addressed. Until this ticket NONE of the four had an entry, so every one of
/// them drew the fallback's `WrenchIcon` -- 'the page does not know this tool' about tools the
/// backend has had for months. What an icon LOOKS like is still the walkthrough's.
/// 142 -> 144: the tree this branch forked from added TWO cases of its own before the merge --
/// `the-job-family-carries-one-hand` (the note just above) and, in the `id` suite,
/// `no-source-file-reaches-for-the-platform-s-mint` (the mobile mint that is not `crypto.randomUUID`).
/// 144 -> 146: `.scratch/refreshed-turn-keeps-growing` -- TWO cases, one per ticket. Ticket 01
/// is the window's merge (`a same id that comes back longer grows in place`), ticket 02 the
/// turn's furniture while it is still being written (`a turn the server is writing wears no
/// action bar`). Both are arithmetic over values the page holds; whether the dot REPLACES the
/// bar on screen, and whether the answer GROWS on screen, are the browser walkthrough's.
/// THE NUMBER IS ASSERTED AGAINST THE SUITES THEMSELVES, which is what makes a fork's drift
/// visible: the count below is what they contribute, so a suite dropped from `SUITES` still fails.
/// AND ONE CASE THIS BRANCH WROTE IS NOT HERE: it mirrored upstream's in-body dot so that only
/// one of the two was ever on screen -- two rules chasing one fact, which the owner's second
/// report (two dots) showed does not hold. The body's dot is switched OFF instead
/// (`indicator="never"` in `thread.aui.tsx`) and the case went with the rule.
/// 146 -> 148: the SAME feature's fourth finding, and a NEW SUITE for it (`thread-messages`,
/// appended to `SUITES`): the status a rebuilt conversation is handed. `fromAgUiMessages` reads
/// a message whose tool call has no result yet as `requires-action` (right for a PARKED run),
/// and the window's `running` only wins if the status RIDES ON THE MESSAGE -- the difference
/// between a `bash` call in flight drawn as a spinner and drawn as 待审批. The second case is
/// the four words that reading can be.
/// 155 on main, plus the one this branch added: the moment a NEW turn is sent, an earlier turn's
/// end must not wear the dot -- the owner's third report (two dots, one per turn end). The
/// assertion is over `lib/live-turn.ts`'s three facts (a turn is open, somebody is writing, and
/// this is the live turn); its name and its place move with that module.
const EXPECTED_CASES = 158;

let total = 0;
for (const suite of SUITES) {
  if (suite.cases.length === 0) {
    throw new Error(`suite "${suite.name}" contributed no cases -- a green run that proves nothing`);
  }
  total += suite.cases.length;
}
if (total !== EXPECTED_CASES) {
  throw new Error(
    `the suites contributed ${total} cases, expected ${EXPECTED_CASES} -- a suite may have been dropped from SUITES; update EXPECTED_CASES if the change is intended`,
  );
}

let harness: Awaited<ReturnType<typeof startHarness>> | null = null;

beforeAll(async () => {
  harness = await startHarness();
  // Both homes go to the suites: a case that wants a system-level skill, or an
  // instruction file, writes into the OS home first -- and a case that wants to
  // read a session log looks under the config root.
  configure({
    url: harness.url,
    scriptPath: harness.scriptPath,
    home: harness.home,
    userHome: harness.userHome,
  });
  console.log(`harness for this run: ${harness.url}`);
});

afterAll(async () => {
  if (harness) await harness.stop();
});

for (const suite of SUITES) {
  describe(suite.name, () => {
    for (const c of suite.cases) {
      test(c.name, async () => {
        await c.run();
        // A case that ran but asserted nothing is EMPTY, not passing -- the same
        // distinction the old bridge drew from its pass counter. vitest's own
        // `hasAssertions` is that guard: without it a body that stopped calling
        // `expect` (an early return, a renamed helper) would report green.
        expect.hasAssertions();
      });
    }
  });
}
