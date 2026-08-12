(ns dougaka-vector.youtube-test
  "The syndication order (ADR-2608124600).

   The defect: upload the mp4, then mint a CACAO, open a session and write the
   catalog entry. Three network hops between an irreversible public effect and
   the record that this repo's own header calls primary — 'YouTube is a
   syndication OF the aozora record'. A failure in that gap produced a public
   video with no catalog record, which inverts the stated model. And with no
   already-uploaded guard, re-running after such a failure published it twice.

   Effects are injected, so these tests assert the ORDER of the calls, which
   is the property at issue and is invisible in the final state. The load-
   bearing test is the retry after a crash between the upload and the record.

   Nothing here touches the network or YouTube; every identifier is synthetic."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [promesa.core :as p]
            [dougaka-vector.youtube :as yt]))

(def ^:private SYNTHETIC-ID "SYNTHETIC_VIDEO_ID_0001")
(def ^:private SESSION-URL "https://upload.example.invalid/session/SYNTHETIC-1")

(defn- recorder
  "Injected effects over a shared call log, so order can be asserted."
  [log & [{:keys [catalog prior query upload-result open-result]}]]
  {:fetch-catalog        (fn [] (swap! log conj :fetch-catalog) catalog)
   :open-write-session   (fn [] (swap! log conj :open-write-session)
                           {:jwt "synthetic-jwt" :did "did:example:synthetic"})
   :load-upload-session  (fn [] (swap! log conj :load-upload-session) prior)
   :save-upload-session  (fn [_u _t] (swap! log conj :save-upload-session) nil)
   :query-upload-session (fn [_u _t] (swap! log conj :query-upload-session)
                           (or query {:status 200 :body {:id SYNTHETIC-ID}}))
   :clear-upload-session (fn [] (swap! log conj :clear-upload-session) nil)
   :open-upload-session  (fn [] (swap! log conj :open-upload-session)
                           (or open-result {:url SESSION-URL :total 1024}))
   :upload               (fn [_] (swap! log conj :upload)
                           (or upload-result {:video-id SYNTHETIC-ID}))
   :put-catalog          (fn [_] (swap! log conj :put-catalog) nil)})

(defn- idx [log k] (.indexOf (vec log) k))

;; ── classification (pure, no promises) ───────────────────────────────────────

(deftest classify-session-outcomes
  (testing "200 with an id means the bytes landed"
    (is (= {:state :completed :video-id SYNTHETIC-ID}
           (yt/classify-session {:status 200 :body {:id SYNTHETIC-ID}}))))
  (testing "308 reports how much YouTube already holds"
    (is (= {:state :incomplete :received 9}
           (yt/classify-session {:status 308 :range-header "bytes=0-8"}))))
  (testing "a session YouTube has forgotten cannot have produced a video"
    (is (= {:state :gone} (yt/classify-session {:status 404})))
    (is (= {:state :gone} (yt/classify-session {:status 410}))))
  (testing "anything else is unknown — never assumed to be failure"
    (is (= :unknown (:state (yt/classify-session {:status 503}))))
    (is (= :unknown (:state (yt/classify-session {:status 200 :body {}}))))))

(deftest already-syndicated-reads-the-catalog
  (is (yt/already-syndicated? {:youtubeUrl "https://youtu.be/SYNTHETIC"}))
  (is (not (yt/already-syndicated? {:youtubeUrl ""})))
  (is (not (yt/already-syndicated? {})))
  (is (not (yt/already-syndicated? nil))))

;; ── the ordering ─────────────────────────────────────────────────────────────

(deftest upload-session-url-is-saved-before-the-bytes
  (testing "the resumable handle exists before anything is committed"
    (t/async done
      (let [log (atom [])]
        (-> (yt/syndicate! (recorder log))
            (p/then (fn [res]
                      (is (= :syndicated (:status res)))
                      (let [l @log]
                        ;; assert it happened at all before asserting where —
                        ;; an uncalled effect has index -1, which would satisfy
                        ;; a bare < and let the defect pass.
                        (is (nat-int? (idx l :save-upload-session))
                            "the session URL was never persisted")
                        (is (nat-int? (idx l :upload)))
                        (is (< (idx l :save-upload-session) (idx l :upload))
                            "the session URL must be persisted before the upload"))
                      (done))))))))

(deftest write-session-is-opened-before-the-upload
  (testing "a refused CACAO must not be able to strand a public video"
    (t/async done
      (let [log (atom [])]
        (-> (yt/syndicate! (recorder log))
            (p/then (fn [_]
                      (let [l @log]
                        (is (< (idx l :open-write-session) (idx l :upload))
                            "auth is proven before the irreversible effect")
                        (is (< (idx l :upload) (idx l :put-catalog))))
                      (done))))))))

(deftest already-syndicated-work-is-not-uploaded-again
  (testing "**the guard** — a retry of a finished run publishes nothing"
    (t/async done
      (let [log (atom [])]
        (-> (yt/syndicate!
             (recorder log {:catalog {:youtubeUrl "https://youtu.be/SYNTHETIC_EXISTING"}}))
            (p/then (fn [res]
                      (is (= :already-syndicated (:status res)))
                      (is (= "https://youtu.be/SYNTHETIC_EXISTING" (:youtube-url res)))
                      (is (not (some #{:upload} @log))
                          "an already-syndicated work was uploaded a second time")
                      (is (not (some #{:open-upload-session} @log)))
                      (done))))))))

(deftest retry-after-crash-between-upload-and-record-does-not-republish
  (testing "**the load-bearing test** — crash after the upload, before the record"
    ;; The first run uploaded and died before put-catalog, so the catalog is
    ;; still empty but a saved session remains. The retry must adopt the video
    ;; that already exists rather than uploading a second one.
    (t/async done
      (let [log (atom [])]
        (-> (yt/syndicate!
             (recorder log {:catalog nil
                            :prior {:url SESSION-URL :total 1024}
                            :query {:status 200 :body {:id SYNTHETIC-ID}}}))
            (p/then (fn [res]
                      (is (= :syndicated (:status res)))
                      (is (= SYNTHETIC-ID (:video-id res)))
                      (is (:resumed? res))
                      (is (not (some #{:upload} @log))
                          "the retry uploaded again — that is a second video")
                      (is (not (some #{:open-upload-session} @log))
                          "a new session was opened on top of an unresolved one")
                      (is (some #{:put-catalog} @log)
                          "the retry must still finish the record that was missing")
                      (done))))))))

(deftest incomplete-prior-session-is-resumed-not-restarted
  (t/async done
    (let [log (atom [])]
      (-> (yt/syndicate!
           (recorder log {:prior {:url SESSION-URL :total 1024}
                          :query {:status 308 :range-header "bytes=0-511"}}))
          (p/then (fn [res]
                    (is (= :syndicated (:status res)))
                    (is (some #{:upload} @log) "the remaining bytes are sent")
                    (is (not (some #{:open-upload-session} @log))
                        "resuming must not open a new session")
                    (done)))))))

(deftest unresolvable-prior-session-refuses-rather-than-duplicating
  (testing "on an unknown outcome, uploading is the choice that duplicates"
    (t/async done
      (let [log (atom [])]
        (-> (yt/syndicate!
             (recorder log {:prior {:url SESSION-URL :total 1024}
                            :query {:status 503}}))
            (p/then (fn [res]
                      (is (= :failed (:status res)))
                      (is (re-find #"unresolved" (:error res)))
                      (is (not (some #{:upload} @log)))
                      (is (not (some #{:put-catalog} @log)))
                      (done))))))))

(deftest expired-prior-session-may-start-a-fresh-upload
  (testing "a session YouTube has forgotten cannot have produced a video"
    (t/async done
      (let [log (atom [])]
        (-> (yt/syndicate!
             (recorder log {:prior {:url SESSION-URL :total 1024}
                            :query {:status 404}}))
            (p/then (fn [res]
                      (is (= :syndicated (:status res)))
                      (is (some #{:open-upload-session} @log))
                      (is (< (idx @log :save-upload-session) (idx @log :upload)))
                      (done))))))))

(deftest a-failed-upload-writes-no-catalog-record
  (testing "no record may claim a syndication that did not happen"
    (t/async done
      (let [log (atom [])]
        (-> (yt/syndicate! (recorder log {:upload-result {:error "synthetic put failure"}}))
            (p/then (fn [res]
                      (is (= :failed (:status res)))
                      (is (not (some #{:put-catalog} @log)))
                      (is (not (some #{:clear-upload-session} @log))
                          "the handle stays, so the next run can resolve it")
                      (done))))))))

(deftest the-handle-is-released-only-after-the-record-exists
  (testing "clearing the session before the record would reopen the same window"
    (t/async done
      (let [log (atom [])]
        (-> (yt/syndicate! (recorder log))
            (p/then (fn [_]
                      (is (< (idx @log :put-catalog) (idx @log :clear-upload-session)))
                      (done))))))))
