(ns youtube
  "nbb YouTube syndication: take a work already published to aozora and
   cross-post its mp4 to YouTube, then write the youtube url BACK into the
   aozora catalog record (aozora 主 / youtube 連携 — YouTube is a syndication
   OF the aozora record, not a parallel publish).

   Operator OAuth is required (never held in code; injected via env, mirroring
   yukkuri's youtube-upload-setup.md policy):
     YOUTUBE_CLIENT_ID  YOUTUBE_CLIENT_SECRET  YOUTUBE_REFRESH_TOKEN
     [YOUTUBE_PRIVACY_STATUS=public] [YOUTUBE_CATEGORY_ID=27]

   Run:
     nbb --classpath src:../../kotoba-lang/kotobase-client/src bin/youtube.cljs \\
       <render-out-dir> --mp4 <file.mp4> --locale ja --keyring .dougaka-vector-keyring \\
       [--dry-run]

   Resumable upload implemented directly over node fetch (com-youtube is a JVM
   byte[] lib; this repo is nbb-first, so the HTTP flow is reimplemented per the
   repo runtime priority rather than shelling to the JVM)."
  (:require ["fs" :as fs]
            ["path" :as path]
            ["@noble/curves/ed25519.js" :refer [ed25519]]
            [clojure.edn :as edn]
            [promesa.core :as p]
            [kotobase.cacao :as cacao]
            [kotobase.cid :as cid]
            [dougaka-vector.publish :as pub]))

(def pds "https://pds.aozora.app")
(def aud "did:web:aozora.app")
(def token-url "https://oauth2.googleapis.com/token")
(def upload-url "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status")

(defn- parse-args [argv]
  (loop [args argv opts {:locale :en :keyring ".dougaka-vector-keyring" :dry-run false}]
    (if (empty? args) opts
        (let [[a & more] args]
          (case a
            "--mp4"        (recur (rest more) (assoc opts :mp4 (first more)))
            "--locale"     (recur (rest more) (assoc opts :locale (keyword (first more))))
            "--storyboard" (recur (rest more) (assoc opts :storyboard (first more)))
            "--keyring"    (recur (rest more) (assoc opts :keyring (first more)))
            "--dry-run"    (recur more (assoc opts :dry-run true))
            (recur more (assoc opts :dir a)))))))

(defn- env [k] (some-> (.-env js/process) (aget k)))

(defn- creds []
  (let [c {:client-id (env "YOUTUBE_CLIENT_ID")
           :client-secret (env "YOUTUBE_CLIENT_SECRET")
           :refresh-token (env "YOUTUBE_REFRESH_TOKEN")}]
    (when (every? some? (vals c)) c)))

(defn refresh-token! [{:keys [client-id client-secret refresh-token]}]
  (p/let [body (str "client_id=" (js/encodeURIComponent client-id)
                    "&client_secret=" (js/encodeURIComponent client-secret)
                    "&refresh_token=" (js/encodeURIComponent refresh-token)
                    "&grant_type=refresh_token")
          res (js/fetch token-url (clj->js {:method "POST"
                                            :headers {"content-type" "application/x-www-form-urlencoded"}
                                            :body body}))
          j (.json res)]
    (or (.-access_token j)
        (throw (js/Error. (str "token refresh failed: " (js/JSON.stringify j)))))))

(defn insert-video! [access-token metadata mp4-path]
  (p/let [init (js/fetch upload-url
                         (clj->js {:method "POST"
                                   :headers {"authorization" (str "Bearer " access-token)
                                             "content-type" "application/json; charset=UTF-8"
                                             "x-upload-content-type" "video/mp4"}
                                   :body (js/JSON.stringify (clj->js metadata))}))
          loc (.get (.-headers init) "location")
          _ (when-not loc (p/let [t (.text init)] (throw (js/Error. (str "upload init failed: " t)))))
          bytes (fs/readFileSync mp4-path)
          put (js/fetch loc (clj->js {:method "PUT"
                                      :headers {"content-type" "video/mp4"}
                                      :body bytes}))
          j (.json put)]
    (or (.-id j) (throw (js/Error. (str "upload put failed: " (js/JSON.stringify j)))))))

;; reuse publish.cljs's identity + context loaders by re-deriving here (keyring EDN)
(defn- unhex [h] (js/Uint8Array.from (map #(js/parseInt (apply str %) 16) (partition 2 h))))

(defn- load-identity [keyring work-id]
  (let [f (path/join keyring (str (pub/work-slug work-id) ".edn"))]
    (when-not (fs/existsSync f)
      (throw (js/Error. (str "no keyring identity for " work-id " — publish to aozora first"))))
    (let [m (edn/read-string (fs/readFileSync f "utf8"))]
      (assoc m :seed (unhex (:seed-hex m))))))

(defn xrpc! [ep body jwt]
  (p/let [res (js/fetch (str pds "/xrpc/" ep)
                        (clj->js {:method "POST"
                                  :headers (cond-> {"content-type" "application/json"}
                                             jwt (assoc "authorization" (str "Bearer " jwt)))
                                  :body (js/JSON.stringify (clj->js body))}))
          j (.json res)]
    (js->clj j :keywordize-keys true)))

(defn -main [& argv]
  (let [{:keys [dir mp4 locale keyring dry-run storyboard]} (parse-args argv)]
    (when-not dir
      (println "usage: nbb --classpath src:<kotobase-client>/src bin/youtube.cljs <render-out-dir> --mp4 <file> [--locale ja] [--keyring dir] [--dry-run]")
      (js/process.exit 2))
    (let [manifest (edn/read-string (fs/readFileSync (path/join dir "manifest.edn") "utf8"))
          work-id (:video/id manifest)
          sb-path (or storyboard (path/join dir "storyboard.edn"))
          sb (when (fs/existsSync sb-path) (edn/read-string (fs/readFileSync sb-path "utf8")))
          title (if sb (pub/work-title sb locale) work-id)
          summary (if sb (pub/work-summary sb locale) work-id)
          id (load-identity keyring work-id)
          desc (pub/youtube-description {:summary summary :handle (:handle id)})
          metadata (pub/youtube-metadata {:title title :description desc :lang (name locale)
                                          :privacy (or (env "YOUTUBE_PRIVACY_STATUS") "public")
                                          :category-id (or (env "YOUTUBE_CATEGORY_ID") "27")})
          mp4 (or mp4 (path/join dir (str work-id ".mp4")))
          c (creds)]
      (println "work    :" work-id)
      (println "title   :" title)
      (println "aozora  :" (:handle id) "(" (:did id) ")")
      (cond
        dry-run
        (do (println "\n--- DRY RUN ---")
            (println "youtube metadata:" (pr-str metadata))
            (println "would upload    :" mp4)
            (println "would write youtubeUrl back into aozora catalog" pub/catalog-collection "/" (pub/work-slug work-id))
            (js/process.exit 0))
        (nil? c)
        (do (println "\nYOUTUBE_CLIENT_ID / _SECRET / _REFRESH_TOKEN not set — cannot syndicate.")
            (println "(operator-provisioned OAuth; see yukkuri docs/youtube-upload-setup.md)")
            (js/process.exit 1))
        :else
        (p/let [tok (refresh-token! c)
                yt-id (insert-video! tok metadata mp4)
                yt-url (str "https://youtu.be/" yt-id)
                _ (println "youtube  :" yt-url)
                ;; write youtubeUrl back into the aozora catalog (syndication join)
                mint (:cacao-b64 (cacao/mint-cacao {:secret-key (:seed id) :aud aud
                                                    :capability "account:session" :graph (:did id) :ttl-sec 300}))
                sess (xrpc! "com.atproto.server.createSession" {:cacao_b64 mint} nil)
                jwt (:accessJwt sess)
                rdid (or (:did sess) (:did id))
                dur (/ (:frame/count manifest) (double (:fps manifest)))
                catalog (pub/catalog-record {:work-id work-id :title title :summary summary
                                             :locale locale :fps (:fps manifest) :duration-sec dur
                                             :youtube-url yt-url :created-at (.toISOString (js/Date.))})
                _ (xrpc! "com.atproto.repo.putRecord"
                         {:repo rdid :collection pub/catalog-collection :rkey (pub/work-slug work-id)
                          :record catalog} jwt)]
          (println "aozora catalog updated with youtubeUrl (syndication linked).")
          (js/process.exit 0))))))

(apply -main *command-line-args*)
