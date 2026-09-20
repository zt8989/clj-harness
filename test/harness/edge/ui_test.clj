(ns harness.edge.ui-test
  "The built page, served off disk: a real server, real files, real requests.

  THE FILES ARE WRITTEN BY THIS NAMESPACE INTO A TEMP DIRECTORY, never read from
  the repo's own `ui/dist`. That is not tidiness -- `ui/dist` is gitignored and may
  not exist at all (a fresh clone, CI), and a test that read it would pass on the
  machine where somebody had just run `npm run build` and fail everywhere else.
  What is asserted here is the RULE, and a rule is provable with three files.

  WHAT IS NOT ASSERTED: that the bundle works. This namespace answers 'is the
  right file sent with the right type at the right path', and the layer that can
  see a rendered page is `node scripts/dev.mjs --scripted` (AGENTS.md)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.edge.http :as http]
            [harness.edge.ui :as ui]
            [harness.test-support :as support])
  (:import [java.net Socket URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

;; ------------------------------------------------------------------- the tools

(defn- write-file!
  "Write CONTENT at RELATIVE under ROOT, making the directories on the way."
  [root relative content]
  (let [f (io/file root relative)]
    (.mkdirs (.getParentFile f))
    (spit f content :encoding "UTF-8")
    f))

(defn- exchange
  "One request to PATH on PORT. METHOD is :get, :head or :post; BODY goes with a
  post. The status, the headers and the body as text, which is everything these
  cases ask about."
  [port method path & [body]]
  (let [builder (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
        builder (case method
                  :get  (.GET builder)
                  :head (.method builder "HEAD" (HttpRequest$BodyPublishers/noBody))
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                        (str body) StandardCharsets/UTF_8)))]
    (.send (HttpClient/newHttpClient) (.build builder)
           (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))))

(defn- header [^HttpResponse resp name]
  (str (.orElse (.firstValue (.headers resp) name) "")))

(defn- raw-get
  "PATH asked for over a BARE SOCKET, byte for byte, and the whole response back as
  text.

  HERE BECAUSE THE SPELLING IS THE WHOLE TEST: a traversal case asks whether THIS
  server resolves `..`, and both curl (without `--path-as-is`) and whatever an HTTP
  client library decides to do with a path can squash it before it is ever sent --
  which would leave the case green while proving something about the client. A
  socket sends what it is told. `Connection: close` is what ends the read."
  [port path]
  (with-open [socket (Socket. "127.0.0.1" (int port))]
    (let [out (.getOutputStream socket)]
      (.write out (.getBytes (str "GET " path " HTTP/1.1\r\n"
                                  "Host: 127.0.0.1\r\n"
                                  "Connection: close\r\n\r\n")
                             StandardCharsets/UTF_8))
      (.flush out)
      (slurp (io/reader socket :encoding "UTF-8")))))

(defn- with-dist
  "Run F with a live server whose page is DIST, and F receives the bound port."
  [dist f]
  (let [stop (http/start! {:port 0 :ui-dist dist})]
    (try
      (f (:local-port (meta stop)))
      (finally (stop)))))

;; ----------------------------------------------------------- the served page

(deftest the-built-page-is-served-where-it-was-pointed
  ;; ONE temp directory, laid out the way a vite build lays one out: a shell at the
  ;; root and hashed files under `assets/`. A traversal target is planted BESIDE it
  ;; so that a path which escapes the root has something to reach.
  (let [dist    (support/temp-dir "ui-dist")
        outside (io/file (str dist "-outside"))]
    (try
      (.mkdirs outside)
      (spit (io/file outside "secret.txt") "not yours" :encoding "UTF-8")
      (write-file! dist "index.html" "<!doctype html><div id=\"root\"></div>\n")
      (write-file! dist "assets/index-abc123.js" "export const answer = 42;\n")
      (write-file! dist "assets/index-abc123.css" "body { margin: 0 }\n")
      (write-file! dist "assets/logo.svg" "<svg/>")
      (write-file! dist "assets/unknown.xyzzy" "raw")
      (write-file! dist "notes/has space.txt" "spaced")
      (with-dist
        dist
        (fn [port]
          (testing "the shell is what `/` and `/index.html` answer"
            (doseq [path ["/" "/index.html"]]
              (let [resp (exchange port :get path)]
                (is (= 200 (.statusCode resp)) path)
                (is (= "text/html; charset=utf-8" (header resp "Content-Type")) path)
                (is (str/includes? (.body resp) "id=\"root\"") path)
                (is (= "no-cache" (header resp "Cache-Control"))
                    "the shell keeps its NAME across builds, so it is revalidated"))))

          (testing "a hashed file is served with the type its consumer needs"
            (let [resp (exchange port :get "/assets/index-abc123.js")]
              (is (= 200 (.statusCode resp)))
              (is (= "text/javascript; charset=utf-8" (header resp "Content-Type"))
                  "a module served as text/plain is refused by the browser's loader")
              (is (= "export const answer = 42;\n" (.body resp))
                  "the bytes, not a paraphrase of them")
              (is (= "public, max-age=31536000, immutable" (header resp "Cache-Control"))
                  "a new build of a changed file is a new name, so this one never changes"))
            (is (= "text/css; charset=utf-8"
                   (header (exchange port :get "/assets/index-abc123.css") "Content-Type")))
            (is (= "image/svg+xml"
                   (header (exchange port :get "/assets/logo.svg") "Content-Type"))))

          (testing "an extension nobody declared is still given a type, not sniffed"
            (is (= "application/octet-stream"
                   (header (exchange port :get "/assets/unknown.xyzzy") "Content-Type"))))

          (testing "a percent-encoded name is decoded once, and `+` stays a plus"
            (is (= "spaced" (.body (exchange port :get "/notes/has%20space.txt")))))

          (testing "HEAD carries the headers and no body"
            (let [resp (exchange port :head "/assets/index-abc123.js")]
              (is (= 200 (.statusCode resp)))
              (is (= "text/javascript; charset=utf-8" (header resp "Content-Type")))
              (is (= "" (.body resp)))))

          (testing "the page is GET and HEAD only -- a POST names no route"
            (let [resp (exchange port :post "/" "{}")]
              (is (= 404 (.statusCode resp)))
              (is (str/includes? (.body resp) "no such route"))))

          (testing "the management edge is not shadowed"
            (let [resp (exchange port :get "/api/thread")]
              (is (= 404 (.statusCode resp)))
              (is (str/includes? (.body resp) "no such route")
                  "a mistyped endpoint is still JSON that names itself, never the shell")))

          (testing "no fallback to index.html: an unknown path names nothing"
            (doseq [path ["/nope" "/assets/index-gone.js" "/settings"]]
              (let [resp (exchange port :get path)]
                (is (= 404 (.statusCode resp)) path)
                (is (not (str/includes? (.body resp) "id=\"root\""))
                    (str path " must not be answered with the shell")))))

          (testing "a path that escapes the root is refused, encoded or not"
            (doseq [path ["/%2e%2e/secret.txt"
                          "/%2e%2e/%2e%2e/etc/hosts"
                          "/..%2fsecret.txt"
                          (str "/%2e%2e/" (.getName outside) "/secret.txt")]]
              (let [resp (exchange port :get path)]
                (is (= 404 (.statusCode resp)) path)
                (is (not (str/includes? (.body resp) "not yours")) path)))

            ;; ...AND THE SAME PATHS AS THE SERVER ITSELF RECEIVED THEM, with no
            ;; client in between to normalize a `..` away before it is sent. The
            ;; target exists and is reachable from the root by exactly one `..`, so
            ;; a server that resolved it would answer 200 with the file's contents.
            (doseq [path [(str "/../" (.getName outside) "/secret.txt")
                          (str "/%2e%2e/" (.getName outside) "/secret.txt")]]
              (let [resp (raw-get port path)]
                (is (str/starts-with? resp "HTTP/1.1 404") path)
                (is (not (str/includes? resp "not yours")) path))))))
      (finally
        (support/wipe-tree! dist)
        (support/wipe-tree! outside)))))

;; ------------------------------------------------------------- no build at all

(deftest a-home-with-no-build-says-so-instead-of-lying-about-the-path
  (let [empty-dist (support/temp-dir "ui-empty")]
    (try
      (testing "a directory with no index.html in it is not a build"
        (with-dist
          empty-dist
          (fn [port]
            (let [resp (exchange port :get "/")]
              (is (= 404 (.statusCode resp)))
              (is (str/starts-with? (header resp "Content-Type") "text/plain"))
              (is (str/includes? (.body resp) "npm run build")
                  "the sentence names the command that fills the directory")
              (is (str/includes? (.body resp) (str empty-dist))
                  "and the directory it looked in")))))

      (testing ":ui-dist nil is 'this process serves no page', not the default"
        (with-dist
          nil
          (fn [port]
            (is (str/includes? (.body (exchange port :get "/")) "npm run build")))))

      (testing "every other path keeps the edge's own 404"
        (with-dist
          empty-dist
          (fn [port]
            (doseq [path ["/nope" "/api/thread"]]
              (let [resp (exchange port :get path)]
                (is (= 404 (.statusCode resp)) path)
                (is (str/includes? (.body resp) "no such route") path)
                (is (not (str/includes? (.body resp) "npm run build"))
                    "a path the table does not know is not a missing build"))))))

      (testing "a build that lands while the process runs needs no restart"
        ;; Nothing is cached here: the directory is a path, and whether it holds a
        ;; page is asked per request. A server started before `npm run build` is
        ;; therefore not a server that has to be restarted after it.
        (with-dist
          empty-dist
          (fn [port]
            (is (= 404 (.statusCode (exchange port :get "/"))))
            (write-file! empty-dist "index.html" "<!doctype html><p>late</p>")
            (let [resp (exchange port :get "/")]
              (is (= 200 (.statusCode resp)))
              (is (str/includes? (.body resp) "late"))))))

      (finally (support/wipe-tree! empty-dist)))))

;; --------------------------------------------------------- the rules, directly

(deftest the-lookup-rules-hold-without-an-http-client-in-the-way
  ;; A client normalizes, re-encodes and sometimes refuses to send the very paths
  ;; worth asking about, so the refusals that make this namespace safe are also
  ;; asked of `answer` directly -- the spelling that escapes is the point, and no
  ;; proxy in between gets a vote.
  (let [dist (support/temp-dir "ui-rules")]
    (try
      (write-file! dist "index.html" "<!doctype html>")
      (write-file! dist "assets/app.js" "x")
      (ui/serve-from! dist)

      (testing "what is served"
        (is (= 200 (:status (ui/answer {:request-method :get :uri "/"}))))
        (is (= 200 (:status (ui/answer {:request-method :get :uri "/assets/app.js"}))))
        (is (= 200 (:status (ui/answer {:request-method :head :uri "/assets/app.js"})))))

      (testing "what is refused"
        (is (nil? (ui/answer {:request-method :get :uri "/../index.html"}))
            "a `..` segment never resolves")
        (is (nil? (ui/answer {:request-method :get :uri "/%2e%2e/index.html"}))
            "and decoding happens before the check, so an encoded one does not either")
        (is (nil? (ui/answer {:request-method :get :uri "/api/agent"})))
        (is (nil? (ui/answer {:request-method :post :uri "/index.html"})))
        (is (nil? (ui/answer {:request-method :get :uri "/%zz"}))
            "a malformed escape names no file -- nil, not a 500"))

      (testing "the banner names the file, and the command when there is none"
        (is (str/includes? (ui/banner) "index.html"))
        (ui/serve-from! nil)
        (is (str/includes? (ui/banner) "npm run build"))
        (is (nil? (ui/answer {:request-method :get :uri "/"}))
            "with no root there is no file to answer with"))

      (testing "`--ui-dist` is not needed for the ordinary case: the default is ui/dist"
        (is (= (io/file (System/getProperty "user.dir") "ui" "dist")
               (ui/default-dir))))

      (finally
        (ui/serve-from! nil)
        (support/wipe-tree! dist)))))
