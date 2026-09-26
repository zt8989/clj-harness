(ns harness.infra.text
  "The two string operations more than one feature needs, in one place.

  CODE POINTS, NOT UTF-16 UNITS. Java counts a string in UTF-16 units, so `(count \"😀\")` is 2
  and a character outside the Basic Multilingual Plane is two of whatever a caller thought was
  one. To a person -- and to a token budget -- it is one. So a count is a CODE-POINT count, and
  a cut is made at a CODE-POINT offset, which is what keeps a cut from landing between the two
  halves of a surrogate pair and leaving two lone halves behind: mojibake that is worse than the
  bytes it saved.

  TWO FEATURES NEED EXACTLY THIS, for the same reason (pruning reads it to decide what it took
  out, spilling to decide what it keeps as a preview), and two copies of a rule they must agree
  on would be one copy too many. `harness.edge.prune` and `harness.cap.spill` both come here.")

(defn code-point-count
  "STRING -> its length in Unicode CODE POINTS. A non-string counts as nothing."
  [s]
  (if (string? s) (.codePointCount ^String s 0 (.length ^String s)) 0))

(defn slice
  "S -> the substring from code point FROM (inclusive) to TO (exclusive), CUT ON CODE-POINT
  BOUNDARIES. FROM/TO are clamped to the string, so a caller cannot ask for a pair to be split
  or walk past either end."
  [s from to]
  (let [n  (code-point-count s)
        lo (max 0 (min (long from) n))
        hi (max lo (min (long to) n))]
    (subs s (.offsetByCodePoints ^String s 0 (int lo)) (.offsetByCodePoints ^String s 0 (int hi)))))

(defn head
  "S -> its first N code points (a preview)."
  [s n]
  (slice s 0 n))
