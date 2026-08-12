(ns dougaka-vector.youtube
  "The order in which a YouTube syndication may touch the world.

   This repo's own header states the model: YouTube is a syndication OF the
   aozora record, not a parallel publish. The upload path inverted that. It
   uploaded the mp4 first, then minted a CACAO, opened a session and wrote the
   catalog entry — three more network hops, any of which could fail, between an
   irreversible public effect and the record that is supposed to be primary.
   A failure anywhere in that gap left a public video with no catalog record:
   the syndication existing without the thing it claims to syndicate.

   There was also no already-uploaded guard, so re-running the command — the
   natural response to it having failed — published the work a second time.

   The order enforced here:

     1. read the catalog     — is this work already syndicated?
     2. open the write session — prove we CAN record before we do the thing
     3. save the upload session URL — YouTube returns it before accepting any
        bytes, so persisting it makes a half-finished upload nameable
     4. upload
     5. write the catalog record
     6. release the saved session

   Steps 2 and 3 are what make step 4 recoverable. Every effect is injected so
   the order itself can be tested without a network."
  (:require [promesa.core :as p]))

(defn already-syndicated?
  "Does the catalog already carry a YouTube url for this work?

   The guard that keeps a retry from publishing the work twice. Reads the
   record the repo already treats as primary rather than inventing new state."
  [catalog-record]
  (boolean (some-> catalog-record :youtubeUrl seq)))

(defn classify-session
  "What a resumable-session query says became of an upload we never recorded.

   Google's documented protocol for `PUT <session-url>` with an empty body and
   `Content-Range: bytes */<total>`:

     200/201 — completed; the body carries the video id
     308     — incomplete; `Range` reports how much was received
     404/410 — the session is gone, so it cannot have produced a video
     other   — unknown

   `:unknown` is deliberately not treated as failure: uploading again on an
   unknown outcome is the choice that puts a second video on the channel."
  [{:keys [status body range-header]}]
  (cond
    (and (#{200 201} status) (:id body)) {:state :completed :video-id (:id body)}
    (#{200 201} status)                  {:state :unknown}
    (= 308 status)
    {:state :incomplete
     :received (if-let [m (and range-header (re-find #"bytes=0-(\d+)" range-header))]
                 (inc (parse-long (second m)))
                 0)}
    (#{404 410} status) {:state :gone}
    :else               {:state :unknown :status status}))

(defn syndicate!
  "Run one syndication in the order above. Returns a promise of a result map.

   Effects are injected rather than called directly, which is what lets the
   ordering be asserted in a test:

     :fetch-catalog        () -> record | nil
     :open-write-session   () -> {:jwt .. :did ..}
     :load-upload-session  () -> {:url .. :total ..} | nil
     :save-upload-session  (url total) -> any     (must precede :upload)
     :query-upload-session (url total) -> {:status .. :body .. :range-header ..}
     :upload               ({:session-url ..}) -> {:video-id ..} | {:error ..}
     :open-upload-session  () -> {:url ..} | {:error ..}
     :put-catalog          ({:jwt .. :did .. :youtube-url ..}) -> any
     :clear-upload-session () -> any"
  [{:keys [fetch-catalog open-write-session
           load-upload-session save-upload-session query-upload-session
           clear-upload-session open-upload-session upload put-catalog
           video-url]
    :or   {video-url #(str "https://youtu.be/" %)}}]
  (p/let [existing (fetch-catalog)]
    (if (already-syndicated? existing)
      ;; Already on YouTube. Re-uploading would publish the work twice.
      {:status :already-syndicated :youtube-url (:youtubeUrl existing)}

      ;; Establish the ability to record BEFORE the irreversible effect. If the
      ;; CACAO or the session is going to be refused, it is refused here, with
      ;; nothing published yet — instead of after a public video exists.
      (p/let [session (open-write-session)
              prior   (load-upload-session)
              ;; Resolve any session a previous run left behind, rather than
              ;; starting a new upload on top of it.
              resumed (when (:url prior)
                        (p/let [q (query-upload-session (:url prior) (:total prior))]
                          (classify-session q)))]
        (p/let [outcome
                (cond
                  (= :completed (:state resumed))
                  {:video-id (:video-id resumed) :resumed? true}

                  (= :incomplete (:state resumed))
                  (upload {:session-url (:url prior) :resume-from (:received resumed)})

                  (and resumed (not= :gone (:state resumed)))
                  {:error "prior upload session unresolved — resolve before retrying"}

                  :else
                  ;; Fresh upload. The session URL is saved before any bytes.
                  (p/let [opened (open-upload-session)]
                    (if (:error opened)
                      opened
                      (p/let [_ (save-upload-session (:url opened) (:total opened))]
                        (upload {:session-url (:url opened)})))))]
          (if (:error outcome)
            (assoc outcome :status :failed)
            (p/let [yt-url (video-url (:video-id outcome))
                    _ (put-catalog (merge session {:youtube-url yt-url}))
                    _ (clear-upload-session)]
              {:status :syndicated
               :youtube-url yt-url
               :video-id (:video-id outcome)
               :resumed? (boolean (:resumed? outcome))})))))))
