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

  A binding is a DEFAULT, not a fence -- the enforcement question is a separate,
  explicit boolean: out-of-bounds? answers whether a resolved path stays inside
  the directories a bound session is allowed to touch (the project directory
  and the configuration home). The fence engages ONLY when a binding exists,
  so an unbound session gets false for everything -- the same regression
  guarantee resolve-path makes. What to DO about an out-of-bounds answer (park
  it, ask a human) is the tool seam's business, not this namespace's: here it
  is still only the WHERE question, now also stated as containment."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.home :as home])
  (:import (java.io File)))

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

(defn- under?
  "Canonical containment: PATH's canonical form is DIR's canonical form or
  starts with it followed by a separator -- so C:\\proj contains C:\\proj\\a.txt
  but never C:\\project2, however alike the strings look. Both sides are
  canonicalized, so .. segments, case differences and symlinks collapse to the
  same answer whatever form the path arrived in. A path that cannot be
  canonicalized at all (a broken link, an invalid name) is provably inside
  NOTHING and answers false -- the caller decides what an unprovable path
  costs, and for the fence that cost is an approval."
  [path dir]
  (try
    (let [cp  (.getCanonicalPath (io/file path))
          cd  (.getCanonicalPath (io/file dir))
          sep (File/separator)]
      (boolean (or (= cp cd)
                   (str/starts-with? cp (if (.endsWith cd sep) cd (str cd sep))))))
    (catch Exception _ false)))

(defn out-of-bounds?
  "TRUE when PATH, as THREAD-ID's session resolves it, lands outside every
  directory a bound session may touch: the project directory itself and the
  configuration home (config.edn, providers.edn, .env -- reading one's own
  configuration is the fence's explicit allowance, not an escape from it).

  The fence engages ONLY when a binding exists: an unbound session answers
  false for every path, byte-for-byte the pre-binding behavior. This fn is the
  WHERE question as a boolean -- whether to PARK an out-of-bounds call is the
  tool seam's decision, made through the ordinary approval flow."
  [thread-id path]
  (boolean
   (when-let [dir (binding-for thread-id)]
     (let [resolved (resolve-path thread-id path)]
       (not (or (under? resolved dir)
                (under? resolved (home/root))))))))
