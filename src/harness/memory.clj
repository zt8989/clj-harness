(ns harness.memory
  "What is left of the introspectable surface: the calls parked for a human's
  approval, and two facts about now (this thread's log path, the project
  directory it is bound to).

  This namespace is on its way out. Everything that was here when the surface was
  argued into being has a better owner:

    the frozen prompt          -> harness.llm, with the prefix-cache reason it exists
    the tool table + overlay   -> harness.tools, beside the seam that reads it
    config.edn, the api-key,   -> harness.providers
    the tier fold, the outbox

  and the two remaining pieces follow in the next ticket: the log path to
  harness.home, whose path derivation it already is, and the project binding to
  harness.project, which is where the binding lives and where it is re-read from.

  WHY THE SURFACE IS GOING, in one line: it existed so that eval could READ the
  harness from inside a run. That reading is gone -- it answered questions the
  files on disk answer better -- and what replaced it, a session growing itself
  hooks at runtime, has no use for a namespace whose point was introspection.

  What is deliberately still NOT here: a copy of a thread's history. The jsonl log
  already holds the conversation, and a second copy in memory can only drift
  from it -- so the log is the record, read it there."
  (:require [harness.home :as home]
            [harness.project :as project]))

;; -------------------------------------------------------------- introspection
;;
;; FACTS ABOUT NOW, computed on demand -- not copies of anything. Where this
;; thread's log is, and which project directory it is bound to: neither is in the
;; jsonl (the path is a fact about this process; the binding moves), so asking
;; beats copying. Both re-derive every call, matching config.

(defn log-path
  "This thread's JSONL log file, as a string path -- the file harness.http
  appends to, named by the same harness.home/log-file the writer uses. Computed
  fresh every call: the root can change (CLJ_HARNESS_HOME, a test binding)
  between calls, and a cached path would silently point at the wrong file.

  A THREAD-ID of nil answers for the process-wide slot, matching the rest of the
  session-scoped surface."
  [thread-id]
  (str (home/log-file thread-id)))

(defn active-project
  "The project directory THREAD-ID's session is bound to, as an absolute path
  string -- or nil, the explicit answer for NO binding (never an error):
  an unbound session is the normal case, and its file tools and shell run
  exactly as they did before bindings existed.

  Asked, not copied: the binding lives in harness.project and is re-read every
  call, matching active-provider and log-path."
  [thread-id]
  (project/binding-for thread-id))
