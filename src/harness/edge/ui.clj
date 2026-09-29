(ns harness.edge.ui
  "The built page, served off disk: what `clojure -M:run` puts in front of a
  browser.

  WHY THIS EXISTS. Until now this process was half an application: it served
  `/api`, and the page came from somewhere else -- a vite dev server on a port of
  its own, or whatever host a deployment put in front. That is two processes and
  two addresses to keep in step, when the page's own default address is already
  THIS ORIGIN: `ui/src/lib/threads.ts` reads `VITE_AGENT_URL ?? \"/\"`, which
  exists precisely so that a page served from this server talks to this server,
  makes no cross-origin request, and carries no address of ours in the bundle. The
  missing half was only ever a directory -- `ui/dist`, the output of
  `npm run build`.

  WHAT IT IS NOT. Not a dev server: no TSX, no Tailwind scan, no HMR. It is the
  COMPILED page, and an edit under `ui/src` is invisible to it until
  `cd ui && npm run build` runs again. `node scripts/dev.mjs` still uses vite and
  still gets the page from vite; this is the other mode -- one process, one
  address, the built thing.

  NOTHING IS CACHED IN THIS PROCESS, so a build that lands while the server is
  running is picked up on the next request: the directory is a path, whether it
  holds a page is asked per request, and the shell is answered `no-cache` so the
  browser comes back for the new hashed names instead of keeping the old ones.
  THAT INCLUDES THE COMPRESSED FORM: a file is folded on the way out, per request,
  and the bytes are not kept (`gzipped`).

  THE PAGE GOES OUT COMPRESSED when the client says it takes gzip (`accepts-gzip?`),
  and only then. The built bundle is 1.5 MB of JavaScript that gzips to 417 KB and
  a stylesheet that gzips to a sixth of itself -- 1.62 MB of page becomes 444 KB,
  measured on this repo's own `ui/dist` 2026-09-29 -- which is the whole of what
  this namespace is for: handing a browser the page, over a link that may be a
  phone on the LAN rather than the loopback.

  TWO RULES BOUND IT, and both are about not making things worse. Only a type that
  CAN be folded is folded (`compressible?`): a PNG or a woff2 is already
  compressed, and gzipping one makes it BIGGER (measured: 1 KB of random bytes ->
  1047), so the type decides and not the size. And only a file above a floor
  (`gzip-min-bytes`), because gzip's own header and trailer are ~20 bytes that a
  very small body cannot pay for (measured: 24 bytes -> 44, 48 -> 68, 96 -> 68).

  THREE RULES, and each one is a refusal:

    * GET and HEAD only. A POST to a path that names a file is not that file.
    * Never under `/api`. That prefix is the management edge's, and a mistyped
      endpoint must stay a JSON 404 that names itself rather than become a file
      lookup -- or, worse, an HTML page the caller tries to parse as JSON.
    * NO FALLBACK TO index.html. This app has no client-side router -- nothing in
      `ui/src` reads `window.location` -- so a path that names no file names
      nothing. Answering every unknown path with the shell would turn each typo
      into a 200 and hide exactly the mistake a 404 is for. Adding routes would
      change this rule by adding a router, not by adding a fallback here.

  ONE REFUSAL IS NAMED. A GET of the page itself (`/`, `/index.html`) when this
  process has no build to serve gets a 404 whose sentence names the directory and
  the command that fills it: `no such route: /` is a true sentence about the wrong
  thing, and this is the case a person starting the server is most likely to hit.
  Every OTHER unknown path keeps the edge's ordinary 404, so that answer does not
  change depending on whether a build happens to exist on the machine."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream File]
           [java.net URLConnection URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.util Locale]
           [java.util.zip GZIPOutputStream]))

(defn default-dir
  "Where a build lands when nobody names one: `ui/dist` under the directory this
  process was started in.

  THE WORKING DIRECTORY, and it is the only place this can be: `clojure -M:run`
  takes its classpath from the `deps.edn` in the directory it is invoked in, so
  the repo root is the one directory where that command even resolves http-kit
  (measured 2026-09-20: run from `ui/`, the classpath is the user-level
  `deps.edn`'s and holds no http-kit at all -- the launcher warns about the
  undeclared `:run` alias and carries on). A launcher that starts the process
  somewhere else names the directory instead of this guessing (`:ui-dist` on
  `start!`, `--ui-dist` on the command line)."
  []
  (io/file (System/getProperty "user.dir") "ui" "dist"))

(defonce ^:private root
  ;; AN ATOM, NOT A DYNAMIC VAR, and for the same reason `named-origin` in
  ;; harness.edge.http is one: every request is served on an http-kit thread, so a
  ;; `binding` here would never reach the thread doing the lookup (AGENTS.md).
  (atom nil))

(defn serve-from!
  "Point this process at DIR -- a directory holding a built page -- or at nil,
  which is 'serve no page'.

  Called by the composition root (`harness.edge.http/start!`) BEFORE the socket
  opens, so the value is in place before the first request can read it."
  [dir]
  (reset! root (when dir (io/file dir))))

(defn- index-file
  "The one file a directory must have to be a build."
  ^File [^File dir]
  (io/file dir "index.html"))

(defn- built?
  "Whether there is a page to serve at all: a directory with an `index.html` in it.

  INDEX.HTML IS THE TEST, not the directory's existence. A `dist` that was cleaned
  and not rebuilt is not a build, and a rule that stopped at `exists?` would
  answer the page request with a 404 that blamed the PATH rather than the missing
  build -- which is the one thing the refusal above is for."
  []
  (let [f (some-> @root index-file)]
    (boolean (and f (.isFile f)))))

(def ^:private content-types
  "What a browser is told each kind of built file is. THE TYPE DECIDES WHETHER THE
  PAGE RUNS AT ALL: a `.js` served as `text/plain` is refused by every modern
  browser's module loader, and a `.css` served as `application/octet-stream` is
  not applied. So the handful a vite build emits are named here rather than left
  to a guesser.

  UTF-8 IS SPELLED OUT on every text type, not because a browser needs to be told
  (HTML and CSS have their own defaults, JSON is UTF-8 by definition) but because
  this JVM's default charset is GBK on Chinese Windows: a header that says which
  encoding the bytes are in is the one place that difference cannot leak into what
  a person sees. The entries a build does not emit sit here too -- a logo, a
  favicon, a font, a source map -- because the cost of a name is one line and the
  cost of a mis-typed asset is a silent one."
  {".html"  "text/html; charset=utf-8"
   ".js"    "text/javascript; charset=utf-8"
   ".mjs"   "text/javascript; charset=utf-8"
   ".css"   "text/css; charset=utf-8"
   ".json"  "application/json; charset=utf-8"
   ".map"   "application/json; charset=utf-8"
   ".txt"   "text/plain; charset=utf-8"
   ".svg"   "image/svg+xml"
   ".ico"   "image/x-icon"
   ".png"   "image/png"
   ".jpg"   "image/jpeg"
   ".jpeg"  "image/jpeg"
   ".gif"   "image/gif"
   ".webp"  "image/webp"
   ".avif"  "image/avif"
   ".woff"  "font/woff"
   ".woff2" "font/woff2"
   ".ttf"   "font/ttf"
   ".otf"   "font/otf"
   ".wasm"  "application/wasm"})

(defn- extension
  "NAME's lower-cased extension, dot included, or nil when it has none.

  LOCALE/ROOT, not the default locale: `.toLowerCase` alone is a different function
  in a Turkish locale, and file names are ASCII machinery rather than a language."
  [^String name]
  (let [i (.lastIndexOf name ".")]
    (when (pos? i)
      (.toLowerCase (subs name i) Locale/ROOT))))

(defn- content-type
  "What to say a file is. The table above first, the JDK's name-based guesser
  second (it knows a few extensions this table does not), and the honest
  catch-all last -- a type nobody can act on is still better than a missing
  header, which leaves the browser sniffing."
  [^File f]
  (let [name (.getName f)]
    (or (get content-types (extension name))
        (URLConnection/guessContentTypeFromName name)
        "application/octet-stream")))

(def ^:private gzip-min-bytes
  "How small a file may be and still be worth folding, in bytes.

  GZIP'S OWN HEADER AND TRAILER ARE ABOUT 20 BYTES, and the deflate framing adds a
  little more, so a body with nothing to fold comes back BIGGER. Measured on this
  machine 2026-09-29: a 24-byte body becomes 44, a 48-byte body 68, a 64-byte body
  68; 96 bytes is where it starts paying. 128 sits above that line rather than on
  it, so this stays one number instead of a judgement call per request."
  128)

(defn- compressible?
  "Whether a body of this TYPE has anything for gzip to fold.

  BY TYPE AND NOT BY SIZE, which is the half worth stating: a PNG, a JPEG, a woff2
  or a `.gz` is ALREADY compressed, and folding one makes the answer bigger
  (measured: 1 KB of random bytes -> 1047 bytes) as well as slower. What is listed
  here is what a build of this page emits and can be folded, plus `application/wasm`,
  which compresses to about half and is fetched by the same browser that asked for
  the JavaScript."
  [^String type]
  (let [bare (first (str/split type #";"))]
    (or (str/starts-with? bare "text/")
        (contains? #{"application/json" "application/javascript" "application/xml"
                     "image/svg+xml" "application/wasm"}
                   bare))))

(defn- accepts-gzip?
  "Whether the CLIENT said it takes gzip -- the whole of what makes folding legal.
  Answering gzip to a client that did not ask is a corrupt file, not a smaller one.

  PARSED RATHER THAN MATCHED BY SUBSTRING, because `gzip;q=0` is a client saying NO
  and `str/includes?` would read it as a yes. A bare `gzip`, `gzip;q=0.5` and `*` are
  all a yes; anything unreadable is a no -- and the safe answer to 'unreadable' is
  the file exactly as it sits on disk."
  [req]
  (let [said (get-in req [:headers "accept-encoding"])]
    (boolean
     (when (string? said)
       (some (fn [entry]
               (let [[token params] (str/split entry #";" 2)
                     name  (str/lower-case (str/trim (or token "")))
                     q     (some->> params (re-find #"q=([0-9.]+)") second Double/parseDouble)]
                 (and (contains? #{"gzip" "*"} name)
                      (or (nil? q) (pos? q)))))
             (str/split said #","))))))

(defn- gzipped
  "FILE's bytes, folded. ONE PASS, PER REQUEST, AND NOTHING IS KEPT -- the rule the
  directory above keeps, so a build that lands mid-flight is picked up by the next
  request and there is no copy here that could go stale.

  WHAT THE PASS COSTS, measured on this machine 2026-09-29 (the JDK's default level):
  the 1.5 MB bundle folds in ~160 ms, the 102 KB stylesheet in ~2.5 ms. Once per page
  load, after which the browser keeps it for a year (`cache-control`), and the bytes
  that go out are 1.62 MB -> 444 KB."
  ^bytes [^File file]
  (let [out (ByteArrayOutputStream. 65536)]
    (with-open [gzip (GZIPOutputStream. out)]
      (io/copy file gzip))
    (.toByteArray out)))

(defn- cache-control
  "HOW LONG A BROWSER MAY KEEP THIS WITHOUT ASKING AGAIN.

  `/assets/*` is vite's HASHED output -- a new build of a changed file is a new
  name -- so it is immutable and kept for a year. Everything else is the shell,
  whose name stays `index.html` across builds and whose content is the one thing
  that points at the new hashes: revalidated every time. `no-cache` means 'ask',
  not 'do not store'."
  [^String path]
  (if (str/starts-with? path "/assets/")
    "public, max-age=31536000, immutable"
    "no-cache"))

(defn- requested-path
  "REQ's path, percent-decoded, or nil when the value is not a path this edge can
  turn into a file name.

  DECODED, because a browser sends `%20` for a space and `%23` for a `#`, and a
  file whose name has either is a file somebody put in `public/`. The `+` swap is
  the one trap: `URLDecoder` is a FORM decoder, where `+` means a space, and in a
  path it means a plus. A malformed escape (`/%zz`) throws, and answers nil here
  rather than a 500: it names no file, which is exactly what nil means."
  [req]
  (when-some [uri (:uri req)]
    (try
      (let [decoded (URLDecoder/decode (str/replace (str uri) "+" "%2B") "UTF-8")]
        (when-not (str/includes? decoded "\u0000")
          decoded))
      (catch Exception _ nil))))

(defn- api-path?
  "Is PATH the management edge's? Matched WHOLE and by the one prefix that edge
  owns, so `/apifoo` is an ordinary path and `/api/thread` is never a file name."
  [^String path]
  (or (= path "/api") (str/starts-with? path "/api/")))

(defn- file-for
  "The file PATH names under ROOT, or nil when it names none, escapes ROOT, or is
  a directory with no build in it.

  CANONICAL PATHS ON BOTH SIDES AND `Path.startsWith` ON THE RESULT, which is what
  makes `..` and a symlink out of the tree fail HERE rather than be served:
  `getCanonicalFile` resolves both, and `startsWith` compares path COMPONENTS, so
  a sibling named `dist-secret` is not inside `dist`. A string-prefix check would
  accept `/root/../elsewhere` and `/rootfoo` alike."
  [^File root ^String path]
  (try
    (let [relative  (str/replace path #"^/+" "")
          canonical (.getCanonicalFile (io/file root relative))
          within    (.getCanonicalFile root)]
      (when (.startsWith (.toPath canonical) (.toPath within))
        (cond
          ;; A FILE is the ordinary case.
          (.isFile canonical) canonical
          ;; A DIRECTORY is only ever the root or a folder of the build, and the
          ;; only file it can answer with is its index: no listing is produced,
          ;; here or anywhere.
          (.isDirectory canonical) (let [index (index-file canonical)]
                                     (when (.isFile index) index))
          :else nil)))
    (catch Exception _ nil)))

(defn- file-response
  "FILE, at PATH, as a ring response for METHOD and REQ.

  HEAD CARRIES THE HEADERS AND NO BODY, which is what ring's own head middleware
  does and what the HTTP spec asks for. http-kit then reports
  `Content-Length: 0` for it -- it computes that header from the body it was
  handed and overwrites whatever is in the map -- so a HEAD here answers the right
  type, the right cache rule, and a length that belongs to the stream rather than
  the file. Sending the body instead would be a worse lie; measured 2026-09-20
  with `curl -I` against a built `ui/dist`. THE COMPRESSED FORM IS PART OF 'THE
  HEADERS': a HEAD from a client that takes gzip says `Content-Encoding: gzip`,
  because that is what the GET it stands for would answer -- the 0 length above is
  the only thing about a HEAD that is not the GET's.

  THE TWO FORMS ARE DECIDED HERE AND ONLY HERE: the same file is answered as itself
  or folded, depending on the client's `Accept-Encoding` (`accepts-gzip?`), the type
  (`compressible?`) and the size (`gzip-min-bytes`). The folding itself is `gzipped`,
  and it happens only for a GET -- a HEAD has no body to fold."
  [^File file ^String path method req]
  (let [type  (content-type file)
        fold? (and (accepts-gzip? req)
                   (compressible? type)
                   (<= gzip-min-bytes (.length file)))]
    {:status  200
     :headers (cond-> {"Content-Type"  type
                       "Cache-Control" (cache-control path)}
                ;; THE SAME URL ANSWERS TWO DIFFERENT BODIES DEPENDING ON A REQUEST
                ;; HEADER, and that is what a cache has to be told: without this line a
                ;; proxy may hand the folded bytes to a client that never asked for them,
                ;; or the plain file to one that did. It goes on whether or not THIS
                ;; response was folded.
                (compressible? type) (assoc "Vary" "Accept-Encoding")
                ;; AND WHAT THE BODY IS, which a browser undoes before the file gets to
                ;; it.
                fold? (assoc "Content-Encoding" "gzip"))
     :body    (when (= :get method)
                (if fold? (gzipped file) file))}))

(defn answer
  "REQ as one file of the built page, or nil -- nil being 'this request is not one
  of ours', which leaves the caller's own 404 in charge. See
  `harness.edge.http/dispatch`, whose last branch is the only caller."
  [req]
  (let [method (:request-method req)
        path   (requested-path req)]
    (when (and (contains? #{:get :head} method)
               path
               (not (api-path? path)))
      (when-let [dir @root]
        (when-let [file (file-for dir path)]
          (file-response file path method req))))))

(defn- no-build
  "The sentence a person gets when the page they asked for is not there. It names
  the DIRECTORY it looked in and the command that fills one, because 'not found'
  is the answer that sends somebody reading source to find out what path the
  server wanted."
  []
  (if-let [dir (some-> @root .getPath)]
    (str "no built page at " dir " -- run `cd ui && npm run build`, or start this"
         " process with --ui-dist naming a directory that holds one")
    (str "this process serves no page -- run `cd ui && npm run build` and start it"
         " from the repo root, or name a directory with --ui-dist")))

(defn absent
  "The refusal a request for the PAGE ITSELF gets when there is no build, or nil
  when there is one (or when REQ is not a request for the page).

  ONLY THE PAGE: `/` and `/index.html`, the two paths that are one file. Every
  other unknown path keeps the edge's ordinary 404, so an answer to a path the
  table does not know does not depend on whether a build happens to exist on the
  machine -- which is what makes it safe for a test to assert."
  [req]
  (let [method (:request-method req)
        uri    (:uri req)]
    (when (and (contains? #{:get :head} method)
               (contains? #{"/" "/index.html"} uri)
               (not (built?)))
      {:status  404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body    (.getBytes (no-build) StandardCharsets/UTF_8)})))

(defn banner
  "One line for the startup banner: where the page comes from, or that there is
  none. Printed by `harness.edge.http/start!` beside the address, because 'which
  page does this process serve' is the second question a person asks of a
  server they just started and the answer is a path on a disk."
  []
  (if (built?)
    (str "serving the built UI at / from " (.getPath (index-file @root)))
    (str "NO UI BUILD: " (no-build))))
