// The composer's session numbers, and the ONE place that decides when to ask for them.
//
// ------------------------------------------------------------ one answer, one clock
//
// THE STRIP UNDER THE COMPOSER, THE RING BESIDE THE MODEL AND WHATEVER COMES NEXT ARE
// ONE ANSWER. That is not tidiness: .scratch/composer-status/spec.md's first decision is
// that this session's numbers come from ONE place -- the record, folded server-side, read
// through one GET -- because cells filled from two clocks would be two moments of the
// same log on screen at once. A hook each component called for itself would do exactly
// that: one request each, landing whenever each one happens to land. So the fetch lives
// in `ComposerFrame` (the one component both the strip and the action row are rendered
// inside), and the payload reaches them through this scope.
//
// ------------------------------------------------------------------- when it asks
//
// ON MOUNT, WHEN THE SESSION CHANGES, WHEN A MODEL CALL ENDS, AND WHEN A RUN ENDS. A
// call's numbers only exist once its `model/end` line is written, so that is the natural
// boundary to ask at -- and the client can see it without any new protocol: one ReAct
// round is one assistant message on this side, so a rise in that count is a call that
// just finished. A run's own end is the same trigger one last time (a run that ends on an
// error has no assistant message to count).
//
// THERE IS NO POLLING, deliberately. A long call streams for minutes; everything the
// composer draws from this stands still for the length of it and then moves. That is
// honest rather than laggy: the number it would show mid-call does not exist yet, and
// inventing one would be the same mistake as estimating the tokens.
//
// ------------------------------------------------------------------------ the value
//
// Null means there is nothing to draw: a session that has never run has no log (a 404 is
// an ordinary answer -- see lib/stats.ts), and a server that cannot be reached is not
// something the composer has words for either. A component decides what to do with the
// absence; this scope only carries it.
import {
  createContext,
  type FC,
  type PropsWithChildren,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import { useAuiState } from "@assistant-ui/react";

import { type StatsPayload } from "@/lib/format";
import { subscribeFacts } from "@/lib/mux";
import { statsFor, withPushedNumbers } from "@/lib/stats";

/// What the composer draws with: the payload, and a way to ask again.
export interface SessionNumbersValue {
  /// Null until the first answer, and for a session whose record cannot be read.
  payload: StatsPayload | null;
  /// Ask now, rather than waiting for the next trigger. The panel calls this when it
  /// is opened: that click is a person asking the question, and the answer may have
  /// moved since the last ask (a run that ended after the last one).
  reload: () => void;
}

/// The resting value, for the paths where the scope is not there at all (an unbound
/// session: `ComposerFrame` renders its children without providing anything).
const NOTHING: SessionNumbersValue = { payload: null, reload: () => {} };

const Numbers = createContext<SessionNumbersValue>(NOTHING);

/// Fetches this session's numbers once per ask and hands them to everything inside.
export const SessionNumbers: FC<PropsWithChildren<{ threadId: string }>> = ({
  threadId,
  children,
}) => {
  const numbers = useSessionNumbers(threadId);
  return <Numbers.Provider value={numbers}>{children}</Numbers.Provider>;
};

/// The numbers, as `ComposerFrame` fetched them.
export const useComposerNumbers = (): SessionNumbersValue => useContext(Numbers);

/// The fetch and its triggers -- the whole of "when to ask".
function useSessionNumbers(threadId: string): SessionNumbersValue {
  const isRunning = useAuiState((s) => s.thread.isRunning);
  // THE SNAPSHOT (an ask) AND THE PUSH (the session's own facts on the socket), kept apart
  // until they are drawn: `withPushedNumbers` is the one place that decides how they meet.
  const [snapshot, setSnapshot] = useState<StatsPayload | null>(null);
  const [pushed, setPushed] = useState<Partial<StatsPayload> | null>(null);
  const [nonce, setNonce] = useState(0);
  const reload = useCallback(() => setNonce((n) => n + 1), []);

  useEffect(() => {
    let live = true;
    void statsFor(threadId).then((next) => {
      // A late answer from a previous session must not land on this one.
      if (live) setSnapshot(next);
    });
    return () => {
      live = false;
    };
    // `assistantCount` IS NOT A DEPENDENCY ANY MORE (ticket 04b): the numbers now arrive as
    // the server writes them, so asking again after every message was the client guessing at
    // a change it is told about. The asks that remain are the ones with a reason: this
    // session, a run that ended, and a person opening the panel (`reload`).
  }, [threadId, isRunning, nonce]);

  // THE PUSH: every `model/end` the session sends carries the numbers its own folds had at
  // that moment. A fact for another conversation -- or another family -- never reaches this
  // subscription (`lib/mux.ts` routes by `familyOf`).
  useEffect(() => {
    const { unsubscribe } = subscribeFacts(threadId, (fact) => {
      if (fact.type !== "model/end") return;
      const numbers = fact.numbers as Partial<StatsPayload> | undefined;
      if (numbers !== undefined) setPushed(numbers);
    });
    return unsubscribe;
  }, [threadId]);

  const payload = withPushedNumbers(snapshot, pushed);

  // AND ONE MORE ASK A BEAT AFTER A RUN ENDS, because the record's own writer is a
  // beat behind its last frame: the run's message tail lands on `:run/done`, which is
  // written after the RUN_FINISHED the client reacts to. Everything the strip draws is
  // on lines written before that frame, but the context split is counted from the tail
  // -- so without this ask the ring would keep a single-coloured arc, and the panel
  // would keep an empty one, until the NEXT run. NOT POLLING: it is one ask per run,
  // and a run that never ends asks nothing.
  const wasRunning = useRef(false);
  useEffect(() => {
    const ended = wasRunning.current && !isRunning;
    wasRunning.current = isRunning;
    if (!ended) return;
    const timer = setTimeout(reload, 400);
    return () => clearTimeout(timer);
  }, [isRunning, reload]);

  return { payload, reload };
}
