(ns harness.web-test
  "`web_fetch`: getting bytes off the network, and the lossy reading of them.

  TWO HALVES, DELIBERATELY. The extraction rules are a PURE function and are tested
  as one -- empty input, markup only, an entity, a bit of source that must not come
  back as prose. Everything else needs a real socket, so the second half is a real
  http-kit server on an OS-assigned port (never a fixed one), and it is where the
  transport rules are pinned: redirects, statuses, content types, charsets, the
  size bound and the timeout.

  NOTHING HERE LEAVES THE MACHINE. Every request goes to the server started below,
  which is what makes the suite runnable with no network at all."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.tools :as tools]
            [harness.web :as web]
            [org.httpkit.server :as hk]))

;; ------------------------------------------------------- the extractor, pure

(deftest the-extractor-keeps-the-prose-and-drops-the-rest
  (testing "a whole small page"
    (let [text (web/text-of (str "<html><head><title>T</title>"
                                 "<style>p { color: red }</style></head>"
                                 "<body><h1>Heading</h1><p>One</p><p>Two</p></body></html>"))]
      (is (str/includes? text "Heading"))
      (is (str/includes? text "One"))
      (is (str/includes? text "Two"))
      (is (not (str/includes? text "color: red"))
          "a <style> body is source, not prose")
      (is (not (str/includes? text "<h1>")))))
  (testing "script bodies do not come back as text"
    (let [text (web/text-of "<p>before</p><script>var x = '<p>fake</p>';</script><p>after</p>")]
      (is (str/includes? text "before"))
      (is (str/includes? text "after"))
      (is (not (str/includes? text "fake"))
          "the point of dropping the region rather than the tag")))
  (testing "comments are not prose either"
    (is (not (str/includes? (web/text-of "<p>a</p><!-- note to self -->") "note to self"))))
  (testing "block tags end a line, so the words are not run together"
    (is (= ["alpha" "beta"] (str/split-lines (web/text-of "<div>alpha</div><div>beta</div>"))))))

(deftest the-extractor-undoes-entities-after-the-markup
  (is (= "a & b" (web/text-of "<p>a &amp; b</p>")))
  (is (= "<div>" (web/text-of "<p>&lt;div&gt;</p>"))
      "an escaped tag is text, and must not be stripped as markup")
  (is (= "\u00e9" (web/text-of "<p>&#233;</p>")) "decimal references")
  (is (= "\u00e9" (web/text-of "<p>&#xe9;</p>")) "and hexadecimal ones")
  (is (= "x &nosuchentity; y" (web/text-of "<p>x &nosuchentity; y</p>"))
      "an entity nobody knows is left as written rather than guessed at"))

(deftest the-extractor-faces-the-shapes-that-come-back-empty
  (testing "nothing in, nothing out"
    (is (= "" (web/text-of "")))
    (is (= "" (web/text-of "<html><body><script>only(this)</script></body></html>"))))
  (testing "markup with no text has no text"
    (is (= "" (web/text-of "<div><span></span></div>"))))
  (testing "a very long single line is still one line"
    (is (= 1 (count (str/split-lines (web/text-of (str "<p>" (apply str (repeat 5000 "x")) "</p>"))))))))

(deftest the-extractor-says-what-the-page-is-called
  (is (= "Example Domain" (web/title-of "<html><head><title>Example Domain</title></head></html>")))
  (is (= "A & B" (web/title-of "<title>A &amp; B</title>")))
  (is (nil? (web/title-of "<p>no title here</p>"))))

;; ------------------------------------------------------------ the socket half

(defn- handler
  [{:keys [uri]}]
  (case uri
    "/html" {:status 200
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body (str "<html><head><title>Page One</title><style>p{color:red}</style></head>"
                        "<body><script>var fake = 1;</script>"
                        "<h1>Heading</h1><p>first &amp; last</p><p>second</p></body></html>")}
    "/plain" {:status 200 :headers {"Content-Type" "text/plain"} :body "line one\n  line two\n"}
    "/json" {:status 200 :headers {"Content-Type" "application/json"} :body "{\"a\":1}"}
    ;; A body that is NOT UTF-8 and says so: `caf` followed by the byte 0xE9.
    ;; `unchecked-byte` because Clojure's `byte` refuses anything over 127 -- which
    ;; is the whole point of the case: the byte is out of ASCII's range on purpose.
    "/latin1" {:status 200
               :headers {"Content-Type" "text/html; charset=iso-8859-1"}
               :body (byte-array (map unchecked-byte
                                      [0x3c 0x70 0x3e 0x63 0x61 0x66 0xE9 0x3c 0x2f 0x70 0x3e]))}
    "/image" {:status 200 :headers {"Content-Type" "image/png"} :body (byte-array [1 2 3])}
    "/missing" {:status 404 :headers {"Content-Type" "text/html"} :body "<p>gone</p>"}
    "/broken" {:status 500 :headers {"Content-Type" "text/html"} :body "<p>boom</p>"}
    "/hop" {:status 302 :headers {"Location" "/html"} :body ""}
    ;; A RELATIVE Location: resolving it against the request's URL is the only way
    ;; this works, and it is what real servers send.
    "/relative" {:status 301 :headers {"Location" "hop"} :body ""}
    "/loop" {:status 302 :headers {"Location" "/loop"} :body ""}
    "/nohost" {:status 302 :headers {"Location" "ftp://example.invalid/x"} :body ""}
    ;; The sleep is caught because the fixture stops the server while this handler
    ;; is still in it, which interrupts the sleep -- and an uncaught interrupt is an
    ;; ERROR line in the suite's output for something that is not a failure.
    "/slow" (do (try (Thread/sleep 1500) (catch InterruptedException _ nil))
                {:status 200 :headers {"Content-Type" "text/plain"} :body "eventually"})
    "/big" {:status 200
            :headers {"Content-Type" "text/html"}
            :body (str "<p>" (apply str (repeat 60000 "a")) "</p>")}
    {:status 200 :headers {"Content-Type" "text/plain"} :body "other"}))

(def ^:private base (atom nil))

(use-fixtures :each
  (fn [f]
    (let [stop (hk/run-server handler {:port 0})]
      (reset! base (str "http://127.0.0.1:" (:local-port (meta stop))))
      (try (f) (finally (stop) (reset! base nil))))))

(defn- url [path] (str @base path))

(defn- refused
  "The message fetching PATH was refused with -- nil when it was not refused."
  [path]
  (try (web/fetch-text (url path)) nil (catch Exception e (ex-message e))))

(deftest a-page-comes-back-as-text-with-the-facts-about-it
  (let [answer (web/fetch-text (url "/html"))]
    (is (str/includes? answer "HTTP 200"))
    (is (str/includes? answer "text/html"))
    (is (str/includes? answer "title: Page One"))
    (is (str/includes? answer "Heading"))
    (is (str/includes? answer "first & last") "entities decoded")
    (is (not (str/includes? answer "var fake")) "script bodies dropped")
    (is (not (str/includes? answer "color:red")) "style bodies dropped")))

(deftest a-body-that-is-already-text-is-not-touched
  (testing "text/plain keeps its own line breaks and spacing"
    (is (str/includes? (web/fetch-text (url "/plain")) "line one\n  line two")))
  (testing "and so does json"
    (is (str/includes? (web/fetch-text (url "/json")) "{\"a\":1}"))))

(deftest the-declared-charset-is-the-one-used
  ;; Never the JVM default: on a Chinese Windows install that is GBK, and every
  ;; page would come back mangled with nothing saying so.
  (is (str/includes? (web/fetch-text (url "/latin1")) "caf\u00e9")))

(deftest redirects-are-followed-and-reported
  (let [answer (web/fetch-text (url "/hop"))]
    (is (str/includes? answer "title: Page One"))
    (is (str/includes? answer (str "redirected from " (url "/hop")))
        "the answer says where the request STARTED, not only where it ended"))
  (testing "a relative Location resolves against the URL that sent it"
    (is (str/includes? (web/fetch-text (url "/relative")) "title: Page One")))
  (testing "and a chain that does not end is refused rather than followed forever"
    (let [message (refused "/loop")]
      (is (string? message))
      (is (str/includes? message (str web/max-redirects))))))

(deftest a-redirect-out-of-http-is-refused
  (let [message (refused "/nohost")]
    (is (string? message))
    (is (str/includes? message "http"))
    (is (not (str/includes? message "title:")))))

(deftest an-error-status-is-refused-by-number
  (doseq [[path status] {"/missing" "404" "/broken" "500"}]
    (let [message (refused path)]
      (is (string? message) path)
      (is (str/includes? message status) (str path " -> " message)))))

(deftest a-body-that-is-not-text-is-refused-by-type
  (let [message (refused "/image")]
    (is (string? message))
    (is (str/includes? message "image/png"))))

(deftest a-page-longer-than-one-answer-is-cut-and-says-so
  (let [answer (web/fetch-text (url "/big"))]
    (is (str/includes? answer (str "first " web/max-bytes " bytes")))
    (is (< (count answer) 60000) "the whole page did not come through")))

(deftest a-host-that-never-answers-is-refused-with-the-bound
  ;; The bound is lowered through the var rather than by waiting ten seconds: the
  ;; seam discipline this repo uses for every other bound (alter-var-root, because
  ;; the request runs on another thread and a `binding` would not reach it).
  (let [real web/timeout-ms]
    (try
      (alter-var-root #'web/timeout-ms (constantly 150))
      (let [message (refused "/slow")]
        (is (string? message))
        (is (str/includes? message "150ms"))
        (is (str/includes? message "did not answer")))
      (finally
        (alter-var-root #'web/timeout-ms (constantly real))))))

(deftest an-unreachable-host-is-refused-with-the-reason
  ;; Nothing is listening on port 1, and the refusal says what happened rather than
  ;; pretending the page was empty.
  (let [message (try (web/fetch-text "http://127.0.0.1:1/x") nil (catch Exception e (ex-message e)))]
    (is (string? message))
    (is (str/includes? message "could not reach"))))

(deftest only-http-and-https-are-fetched
  (doseq [bad ["file:///etc/hosts" "data:text/plain,hello" "ftp://example.invalid/x"]]
    (let [message (try (web/fetch-text bad) nil (catch Exception e (ex-message e)))]
      (is (string? message) bad)
      (is (str/includes? message "http") (str bad " -> " message))))
  (testing "and a URL with no scheme is told to give one"
    (let [message (try (web/fetch-text "example.com/x") nil (catch Exception e (ex-message e)))]
      (is (string? message))
      (is (str/includes? message "https://")))))

(deftest a-blank-url-is-the-ordinary-missing-argument
  (doseq [blank [nil "" "   "]]
    (let [message (try (web/fetch-text blank) nil (catch Exception e (ex-message e)))]
      (is (string? message) (pr-str blank))
      (is (str/includes? message "url")))))

;; ------------------------------------------------------------- through the seam

(deftest the-tool-answers-through-the-execution-seam
  ;; The body is one line in harness.tools; what this pins is that the call really
  ;; goes through the seam a model's call goes through -- no park, no fence, and a
  ;; refusal arriving as the tool's RESULT rather than as a failed run.
  (let [call (fn [args] (tools/run! {:id "c" :type "function"
                                     :function {:name "web_fetch"
                                                :arguments (json/write-str args)}}
                                    "web-test"))]
    (testing "a page"
      (let [{:keys [content error]} (call {:url (url "/html")})]
        (is (false? error) content)
        (is (str/includes? content "title: Page One"))))
    (testing "and a refusal is information for the model, not a run failure"
      (let [{:keys [content error]} (call {:url (url "/missing")})]
        (is (true? error))
        (is (str/includes? content "404"))))
    (testing "a missing url is the ordinary missing-argument refusal"
      (is (str/includes? (:content (call {})) "missing required argument")))))
