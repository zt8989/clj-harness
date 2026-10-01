(ns harness.edge.host-test
  "The host-level doorbell (`harness.edge.host`) and the `events.host` downlink: a ring
  reaches every watcher, a thrower does not stop the others, the socket is handed the whole
  listing at once and again on every change, and closing it releases the watcher.

  A FAKE CHANNEL AGAIN (see `mux-test`): what matters is which frames a connection is sent,
  not the WebSocket framing under it. Unlike `mux-test` this one records the CLOSE handler, so
  'a tab that goes away takes its watcher with it' is a case rather than an assertion about
  code nobody ran."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.edge.host :as host]
            [harness.edge.http :as http]
            [org.httpkit.server :as hk]))

(defn- clear!
  ;; BOTH DOORBELLS: the statistics have a watcher set of their own (see `harness.edge.host`),
  ;; and a case that leaves one behind makes the next case's `stats-watching?` a lie.
  []
  (reset! (var-get #'host/watchers) #{})
  (reset! (var-get #'host/stats-watchers) #{}))

(use-fixtures :each (fn [f]
                      (clear!)
                      (try (f) (finally (clear!)))))

(defn- fake-channel
  [sent close?]
  (reify hk/Channel
    (open? [_] true)
    (websocket? [_] true)
    (close [_] nil)
    (send! [_ data] (swap! sent conj data) true)
    (send! [_ data _close-after] (swap! sent conj data) true)
    (on-receive [_ _] nil)
    (on-close [_ cb] (reset! close? cb) nil)
    (on-ping [_ _] nil)))

(defn- frames [sent]
  (mapv #(json/read-str % :key-fn keyword) @sent))

(deftest a-ring-reaches-every-watcher-and-a-thrower-does-not-stop-them
  (let [seen (atom [])]
    (host/watch! (fn [] (swap! seen conj :a)))
    (host/watch! (fn [] (throw (ex-info "boom" {}))))
    (host/watch! (fn [] (swap! seen conj :b)))
    (is (host/watching?))
    (host/ring!)
    ;; ORDER-AGNOSTIC ON PURPOSE: the watchers are a SET, so which one is called first is
    ;; not a fact this contract promises (the ring's own order is the set's). What matters
    ;; is that BOTH heard it -- the thrower did not stop the one registered after it.
    (is (= #{:a :b} (set @seen)) "every watcher heard it, including the one after the thrower")))

(deftest the-host-downlink-gets-the-listing-at-once-and-on-every-change
  (let [sent (atom []) closed (atom nil)
        ch   (fake-channel sent closed)]
    (#'http/host-get {:async-channel ch})

    (testing "the opening frame is the whole listing, tagged as this category"
      (let [frame (first (frames sent))]
        (is (= "projects" (:type frame)))
        (is (contains? frame :projects))
        (is (contains? frame :tasks))))

    (testing "a host-level change pushes another listing"
      (let [before (count @sent)]
        (host/ring!)
        (is (= (inc before) (count @sent)))))

    (testing "closing releases the watcher"
      (is (host/watching?))
      (@closed 1000)
      (is (not (host/watching?))))))

(deftest a-session-made-to-exist-rings-the-host-bus
  ;; THE ONE MUTATION THIS FILE DRIVES END TO END, and it is the sidebar's headline case: a
  ;; conversation that did not exist now does, which is what makes its row appear in another
  ;; window without a refresh.
  (let [seen (atom 0)
        stop (host/watch! (fn [] (swap! seen inc)))]
    (try
      (let [resp (#'http/sessions-post
                  {:body (java.io.ByteArrayInputStream.
                          (.getBytes (json/write-str {:threadId "host-ring-row"}) "UTF-8"))})]
        (is (= 200 (:status resp)))
        (is (pos? @seen) "registering a session is a host-level fact"))
      (finally (host/unwatch! stop)))))

(deftest the-subscribe-route-is-not-how-the-host-stream-works
  ;; A GUARD ON THE SHAPE, not a behaviour: `events.host` has no subscription, so the mux's
  ;; subscribe route must answer exactly one thing about a host connection -- that it knows
  ;; no such connection (host tokens are not mux tokens).
  (let [resp (#'http/mux-subscribe-post
              {:body (java.io.ByteArrayInputStream.
                      (.getBytes (json/write-str {:subscriber "not-a-mux-token" :subscribe []}) "UTF-8"))})]
    (is (= 404 (:status resp)))))

(deftest the-statistics-have-a-doorbell-and-a-downlink-of-their-own
  ;; A SECOND CATEGORY ON THE SAME NS, and the separation is the whole point: the leaderboards
  ;; are a scan of every tool call this home has made, so a page with only a sidebar open must
  ;; not be sent one. This case pins the two halves -- the frame carries all three
  ;; leaderboards, and a statistics ring reaches the statistics watchers and nobody else.
  (let [sent (atom []) closed (atom nil)
        ch   (fake-channel sent closed)]
    (#'http/stats-stream-get {:async-channel ch})
    (testing "the opening frame is the leaderboards, tagged as this category"
      (let [frame (first (frames sent))]
        (is (= "stats" (:type frame)))
        ;; THE WINDOW IS THE CONNECTION'S, and a request that names none gets the default (7).
        (is (= 7 (:days frame)))
        (is (contains? frame :tools))
        (is (contains? frame :skills))
        (is (contains? frame :models))))
    (testing "and the ring is the statistics' own"
      (let [listings (atom 0)
            stop     (host/watch! (fn [] (swap! listings inc)))]
        (try
          (let [before (count @sent)]
            (host/ring-stats!)
            (is (= (inc before) (count @sent)) "the statistics watcher heard it")
            (is (zero? @listings) "and the listing's watcher did not -- a different set"))
          (finally (host/unwatch! stop)))))
    (testing "closing releases the watcher"
      (is (host/stats-watching?))
      (@closed 1000)
      (is (not (host/stats-watching?))))))
