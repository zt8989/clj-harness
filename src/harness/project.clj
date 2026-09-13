(ns harness.project
  "A session's project directory. thread-id -> binding is this namespace's
  whole state, and it is deliberately shaped like the rest of the session-scoped
  surface (harness.memory): an atom keyed by thread-id, asked rather than
  copied, nil meaning the normal case of NO binding.

  The binding re-roots the file tools and the shell for one session:

    - resolve-path maps a RELATIVE path into the project directory; an
      absolute path passes through untouched.
    - binding-for answers the directory (or nil), which is what bash needs
      for its working directory and what introspection surfaces as
      harness.memory/active-project.

  A binding is a DEFAULT, not a fence: nothing here checks that a resolved
  path stays inside the directory, and absolute paths are never redirected.
  The enforcement question (the \"out of bounds\" approval) is ticket 02's
  business and will reuse the approval park, per the standing philosophy --
  this namespace only decides WHERE an operation happens, never WHETHER."
  (:require [clojure.java.io :as io]))

(defonce ^:private bindings
  (atom {}))
;; thread-id -> absolute path string of the project directory

(defn- absolute
  "DIR as an absolute path string. Kept as the input's own form where possible
  (getAbsolutePath does not chase symlinks), because the value is echoed back
  to the user and handed to ProcessBuilder -- both want what was asked for,
  resolved against the process working directory, not canonicalized."
  [dir]
  (str (.getAbsoluteFile (io/file dir))))

(defn bind!
  "Bind THREAD-ID's session to directory DIR, which must exist and be a
  directory -- anything else throws a NAMED error, because a typo'd path must
  fail where it was typed, not surface later as a confusing read failure.
  Returns the absolute path the binding stored.

  DIR of nil DROPS the binding (the unbind direction; rebinding to another
  directory is just binding again -- the last bind wins)."
  ([dir] (bind! nil dir))
  ([thread-id dir]
   (if (nil? dir)
     (do (swap! bindings dissoc thread-id)
         nil)
     (let [f (io/file dir)]
       (when-not (.exists f)
         (throw (ex-info (str "no such directory: " dir)
                         {:path (str dir) :reason :missing})))
       (when-not (.isDirectory f)
         (throw (ex-info (str "not a directory: " dir)
                         {:path (str dir) :reason :not-a-directory})))
       (let [abs (absolute dir)]
         (swap! bindings assoc thread-id abs)
         abs)))))

(defn binding-for
  "The project directory THREAD-ID's session is bound to, as an absolute path
  string -- or nil. Nil is the explicit, everyday answer for NO binding, never
  an error: most sessions are unbound, and an unbound session must behave
  exactly as it did before this namespace existed."
  [thread-id]
  (get @bindings thread-id))

(defn resolve-path
  "PATH as this session will use it: relative paths resolve against the
  session's project directory when one is bound, and pass through UNCHANGED
  when none is; absolute paths always pass through. Absolute means absolute
  per java.io.File (a drive letter on Windows, a leading / on Unix).

  The unbound case is the identity function ON PURPOSE: it is the regression
  guarantee that a session without a binding sees byte-for-byte the behavior
  the tools had before project directories existed."
  ([path] (resolve-path nil path))
  ([thread-id path]
   (let [f (io/file path)]
     (if (or (.isAbsolute f) (nil? (binding-for thread-id)))
       path
       (str (io/file (binding-for thread-id) path))))))
