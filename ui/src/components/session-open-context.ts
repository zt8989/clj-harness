import { createContext } from "react";

/// OPEN ANOTHER SESSION -- what a message's Fork does once the server has made one.
///
/// IT IS A CONTEXT RATHER THAN A PROP for the reason `ThreadIdContext` is one: the action
/// bar lives inside the copied `Thread` element, which the host renders as `children` and so
/// cannot hand anything to. The page owns which session is on screen (`app.tsx`'s `show`),
/// so the page is what provides this.
///
/// THE DEFAULT DOES NOTHING, and that is the honest answer for a bare element (a storybook, a
/// test that mounts the transcript alone): the fork still happens on the server, there is
/// simply no page here to switch to.
export const SessionOpenContext = createContext<(threadId: string) => void>(() => {});
