// The line a launcher reads to learn the port the backend actually BOUND.
//
// The backend is started on port 0 and PRINTS the number the OS gave it (see
// `harness.edge.http/-main`: "a script can start this on 0, read the line, and point a
// browser at it"), so the port is read back rather than guessed -- probing for a free
// port and then handing the number over would be a race with everything else on this
// machine, and would ask the backend to trust a port it did not choose. Nothing here
// waits for a sleep; it waits for this line.
//
// SPELLED ONCE, for the same reason `scripts/proc.mjs` is: TWO launchers start a backend
// (`scripts/dev.mjs` in front of vite, `scripts/run.mjs` on its own), and a banner that
// moved would otherwise leave one of them waiting forever for a line that is not coming.
// The line itself belongs to `harness.edge.http/start!`; this is the only copy.
export const LISTENING = /harness listening on http:\/\/localhost:(\d+)/;
