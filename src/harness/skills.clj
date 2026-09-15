(ns harness.skills
  "Where a session's skills live.

  THE DEFAULT IS THE HOST'S OWN CONVENTION DIRECTORY, <os-home>/.agents/skills --
  the same files ZCode and Claude read on this machine, so one `git clone` of a
  skill serves every agent in the room. It is deliberately NOT under
  harness.home/root: that root is where harness keeps ITS configuration, and
  moving it (a deployment, a test run) has not moved the machine's home
  directory. A bound session gets a second root, <project>/.agents/skills, so a
  project can pin the skills it depends on next to the code that uses them.

  `roots` IS PURE, AND THAT IS A SHAPE CONSTRAINT RATHER THAN A STYLE. It takes
  the configured value and the session's project directory and answers with
  paths: it does not read harness.edn, does not look up a binding, and does not
  require harness.project. The reason is a cycle. The project fence has to know
  these roots -- a skill's body says 'read references/x.md', and that path lands
  outside the project directory -- so harness.project requires THIS namespace,
  and a require back would be a cycle Clojure refuses at load. Each caller
  therefore supplies what it already has in hand: the fence has the binding, a
  tool body has its thread-id, a test has neither and passes nil."
  (:require [clojure.java.io :as io]
            [harness.home :as home]))

(def convention-dir
  "The host's own layout for skills, relative to a home directory: a directory
  of directories, each one a skill. Spelled once because there are two homes to
  build it under -- the OS home's and a bound project's -- and a second spelling
  is how the two drift apart."
  [".agents" "skills"])

(defn roots
  "The skill directories this session reads, as absolute path strings, in
  PRECEDENCE ORDER -- earlier entries win a name conflict.

  SKILLS-CFG is the `:skills` value from harness.edn (or nil), which the caller
  takes from (harness.project/harness-config thread-id); PROJECT-DIR is that
  session's binding, or nil. Read fresh on every call, matching config.edn and
  harness.edn, so editing the configuration moves the roots without a restart.

  Two shapes:

    {:roots [\"/abs/skills\" \"relative/to/project\"]}   the whole list, replacing
                                                         both defaults
    (absent)                                             <os-home>/.agents/skills,
                                                         plus <project>/.agents/skills
                                                         when bound

  A configured list REPLACES the defaults rather than adding to them, which is
  what makes 'only the project's skills, please' expressible -- write
  {:roots [\".agents/skills\"]} and the OS home drops out of the picture. The
  relative spelling is the tool-path rule; see harness.home/resolve-against."
  ([] (roots nil nil))
  ([skills-cfg project-dir]
   (let [cfg (home/as-config-section skills-cfg ":skills" project-dir)]
     (if (contains? cfg :roots)
       (mapv #(home/resolve-against project-dir %)
             (home/path-list (:roots cfg)
                             "{:skills {:roots [\"/abs/skills\" \"relative/to/project\"]}}"
                             ":skills {:roots" project-dir))
       (cond-> [(str (apply io/file (home/user-home) convention-dir))]
         project-dir (conj (str (apply io/file project-dir convention-dir))))))))
