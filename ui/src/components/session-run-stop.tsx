// THE STOP BUTTON for a conversation the SERVER is answering -- and it really stops it.
// Ticket 09 of `.scratch/session-after-refresh`, on top of the server's own half (07/08).
//
// ============================================================== why it is not Cancel
//
// WHAT USED TO STAND HERE IS UPSTREAM'S `ComposerPrimitive.Cancel`, and it aborted THIS
// PAGE'S FETCH (`@assistant-ui/react-ag-ui` calls the AG-UI client's `abortRun`). That is
// a stop only for the page that is driving the run and a LIE for a page that opened
// somebody else's: there is no fetch to close, so the button stopped nothing while the run
// kept writing frames into the record. Both cases draw this one now -- the whole point is
// that there is ONE thing a person presses and it stops the RUN -- and this one asks the
// server (`POST /api/threads/<id>/cancel`), which rings the running run's own stop
// switch: the calls in flight are stopped with it (a command's process tree too), the
// record is given a result for every call it was holding, and the run reaches a terminal
// that says a person stopped it.
//
// A PAGE DRIVING THE RUN STILL GETS A PROPER ENDING: the terminal the server emits arrives
// on that page's own stream, and `lib/agent.ts` reads its `code: "stopped"` as a
// CANCELLATION -- the same channel the browser's own abort was reclassified through
// (`.scratch/stop-abort`), so the turn is drawn "Cancelled" and not "Failed".
//
// ============================================================== why it replaced a sentence
//
// A CONVERSATION THE SERVER IS STILL ANSWERING used to draw a shut composer and a sentence
// saying why (`run.stillAnswered`: "这一场还在跑；等它结束再发。"). A sentence is what a
// door owes when there is no way through it -- but there IS one: the run can be stopped.
// So the compositor's Send becomes this button, and the sentence goes: the same rule the
// approval gate follows (a control that will not press says nothing about why), with the
// difference that this control does something.
//
// THE STATE IT IS ACTED ON COMES BACK ON ITS OWN: the window's feed sends the state frame
// when the run settles, so the composer returns to Send without this component arranging
// anything -- and the page is not left holding a button it has to guess about.
import { type FC, useState } from "react";
import { useTranslation } from "react-i18next";
import { SquareIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { stopRun } from "@/lib/threads";

export const SessionRunStop: FC<{ threadId: string | null }> = ({ threadId }) => {
  const { t } = useTranslation("composer");
  const { t: tErrors } = useTranslation("errors");
  const [pressing, setPressing] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const press = () => {
    if (threadId === null || pressing) return;
    setPressing(true);
    setFailure(null);
    void stopRun(threadId, tErrors)
      .catch((error: unknown) => {
        // A REFUSAL IS SAID, not swallowed: the likely one is "nothing to cancel" -- the
        // run ended between this page's last state frame and the press -- and the honest
        // answer to that is the server's own sentence rather than a button that stays
        // pressed forever.
        setFailure(error instanceof Error ? error.message : String(error));
        setPressing(false);
      });
  };

  return (
    <>
      <Button
        type="button"
        variant="default"
        size="icon"
        className="aui-composer-stop size-7 rounded-full"
        aria-label={t("run.stop")}
        title={t("run.stop")}
        data-slot="session-stop"
        disabled={pressing || threadId === null}
        onClick={press}
      >
        <SquareIcon className="aui-composer-stop-icon size-3.5 fill-current" />
      </Button>
      {failure !== null && (
        <p role="status" data-slot="session-stop-refusal" className="text-destructive text-xs">
          {failure}
        </p>
      )}
    </>
  );
};