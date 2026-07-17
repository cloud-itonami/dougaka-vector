(ns publish
  "nbb publisher: publish one rendered work to aozora.app as its OWN actor DID
   (作品 = 1 actor DID), then optionally syndicate to YouTube from the aozora
   record. Data lands on kotobase.net automatically (aozora PDS backs onto it).

   Auth is self-sovereign: a fresh Ed25519 seed per work → did:key → depth-1
   self-minted CACAO (kotobase.cacao). No owner credentials for aozora. YouTube
   syndication needs operator OAuth env (gated, skipped otherwise).

   Run (needs kotobase-client src + @noble/@ipld on the classpath/node path):
     nbb --classpath src:../../kotoba-lang/kotobase-client/src \\
       bin/publish.cljs <render-out-dir> --mp4 <file.mp4> --locale ja \\
       [--handle-domain aozora.app] [--keyring .dougaka-vector-keyring] \\
       [--dry-run] [--youtube]

   <render-out-dir> is a bin/render.cljs output dir (has manifest.edn); the
   storyboard is read from manifest's sibling or --storyboard.

   --dry-run prints the derived DID/handle and every XRPC/YouTube request body
   WITHOUT touching the network (offline verification)."
  (:require ["fs" :as fs]
            ["path" :as path]
            ["@noble/curves/ed25519.js" :refer [ed25519]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [promesa.core :as p]
            [kotobase.cacao :as cacao]
            [kotobase.cid :as cid]
            [dougaka-vector.publish :as pub]))

(def pds "https://pds.aozora.app")
(def aud "did:web:aozora.app")

(defn- parse-args [argv]
  (loop [args argv opts {:locale :en :handle-domain "aozora.app"
                         :keyring ".dougaka-vector-keyring" :dry-run false :youtube false}]
    (if (empty? args)
      opts
      (let [[a & more] args]
        (case a
          "--mp4"           (recur (rest more) (assoc opts :mp4 (first more)))
          "--locale"        (recur (rest more) (assoc opts :locale (keyword (first more))))
          "--storyboard"    (recur (rest more) (assoc opts :storyboard (first more)))
          "--handle-domain" (recur (rest more) (assoc opts :handle-domain (first more)))
          "--keyring"       (recur (rest more) (assoc opts :keyring (first more)))
          "--dry-run"       (recur more (assoc opts :dry-run true))
          "--youtube"       (recur more (assoc opts :youtube true))
          (recur more (assoc opts :dir a)))))))

;; ── per-work identity keyring (作品 = 1 DID) ─────────────────────────────────

(defn- hex [^js u8] (apply str (map #(.padStart (.toString % 16) 2 "0") (array-seq u8))))
(defn- unhex [h] (js/Uint8Array.from (map #(js/parseInt (apply str %) 16) (partition 2 h))))

(defn load-or-create-work-identity!
  "One fresh Ed25519 seed per work id, persisted (gitignored) at
   <keyring>/<work-id>.edn = {:work-id :seed-hex :did :handle :created-at}.
   Re-publishing the same work reuses its DID (idempotent identity)."
  [keyring work-id handle-domain]
  (let [f (path/join keyring (str (pub/work-slug work-id) ".edn"))]
    (if (fs/existsSync f)
      (let [m (edn/read-string (fs/readFileSync f "utf8"))]
        (assoc m :seed (unhex (:seed-hex m))))
      (let [seed (js/crypto.getRandomValues (js/Uint8Array. 32))
            seed-hex (hex seed)
            did (cid/did-key-from-ed25519-pub (.getPublicKey ed25519 seed))
            h (pub/handle work-id handle-domain)
            m {:work-id (str work-id) :seed-hex seed-hex :did did :handle h
               :created-at nil}]
        (fs/mkdirSync keyring #js {:recursive true})
        (fs/writeFileSync f (pr-str (dissoc m :seed)))
        (assoc m :seed seed)))))

;; ── XRPC ─────────────────────────────────────────────────────────────────────

(defn xrpc! [ep body jwt]
  (p/let [res (js/fetch (str pds "/xrpc/" ep)
                        (clj->js {:method "POST"
                                  :headers (cond-> {"content-type" "application/json"}
                                             jwt (assoc "authorization" (str "Bearer " jwt)))
                                  :body (js/JSON.stringify (clj->js body))}))
          j (.json res)]
    (js->clj j :keywordize-keys true)))

(defn upload-blob! [mp4-path jwt]
  (p/let [bytes (fs/readFileSync mp4-path)
          res (js/fetch (str pds "/xrpc/com.atproto.repo.uploadBlob")
                        (clj->js {:method "POST"
                                  :headers {"content-type" "video/mp4"
                                            "authorization" (str "Bearer " jwt)}
                                  :body bytes}))
          j (.json res)]
    (:blob (js->clj j :keywordize-keys true))))

;; ── main ─────────────────────────────────────────────────────────────────────

(defn- load-context [{:keys [dir storyboard locale mp4]}]
  (let [manifest (edn/read-string (fs/readFileSync (path/join dir "manifest.edn") "utf8"))
        sb-path (or storyboard (path/join dir "storyboard.edn"))
        sb (when (fs/existsSync sb-path)
             (edn/read-string (fs/readFileSync sb-path "utf8")))
        work-id (:video/id manifest)
        title (if sb (pub/work-title sb locale) work-id)
        summary (if sb (pub/work-summary sb locale) work-id)
        dur (/ (:frame/count manifest) (double (:fps manifest)))]
    {:manifest manifest :work-id work-id :title title :summary summary
     :duration dur :mp4 (or mp4 (path/join dir (str work-id ".mp4")))}))

(defn -main [& argv]
  (let [{:keys [dir locale dry-run youtube keyring handle-domain] :as opts} (parse-args argv)]
    (when-not dir
      (println "usage: nbb --classpath src:<kotobase-client>/src bin/publish.cljs <render-out-dir> --mp4 <file> [--locale ja] [--handle-domain aozora.app] [--keyring dir] [--dry-run] [--youtube]")
      (js/process.exit 2))
    (let [{:keys [work-id title summary duration manifest mp4]} (load-context opts)
          id (load-or-create-work-identity! keyring work-id handle-domain)
          now (.toISOString (js/Date.))
          mint #(:cacao-b64 (cacao/mint-cacao {:secret-key (:seed id) :aud aud
                                               :capability "account:session"
                                               :graph (:did id) :ttl-sec 300}))
          profile (pub/profile-record
                   {:display-name title
                    :description (str summary
                                      " — ai-gftd-dougaka-vector (言語・キャラクター非依存のベクターアニメーション).")})]
      (println "work        :" work-id)
      (println "actor did   :" (:did id) "(作品=1 DID)")
      (println "handle      :" (:handle id))
      (println "mp4         :" mp4 (if (fs/existsSync mp4) "" "(MISSING)"))
      (if dry-run
        (do
          (println "\n--- DRY RUN (no network) ---")
          (println "createAccount body:" (pr-str {:handle (:handle id) :cacao_b64 "<cacao>"}))
          (println "profile record   :" (pr-str profile))
          (println "video post embed :" (pr-str (pub/video-post-record
                                                 {:text (str "『" title "』") :src (str pds "/xrpc/com.atproto.sync.getBlob?cid=<cid>")
                                                  :blob {:$type "blob" :ref {:$link "<cid>"} :mimeType "video/mp4"}
                                                  :alt title :created-at now})))
          (println "catalog record   :" (pr-str (pub/catalog-record
                                                 {:work-id work-id :title title :summary summary
                                                  :locale locale :src "<src>" :blob-cid "<cid>"
                                                  :post-uri "<at-uri>" :fps (:fps manifest)
                                                  :duration-sec duration :created-at now})))
          (when youtube
            (println "youtube metadata :" (pr-str (pub/youtube-metadata
                                                   {:title title
                                                    :description (pub/youtube-description
                                                                  {:summary summary :handle (:handle id)})
                                                    :lang (name locale)}))))
          (println "\nno records were sent (--dry-run).")
          (js/process.exit 0))
        (p/let [_ (when-not (fs/existsSync mp4)
                    (println "mp4 not found:" mp4) (js/process.exit 1))
                acct (xrpc! "com.atproto.server.createAccount" {:handle (:handle id) :cacao_b64 (mint)} nil)
                sess (if (:accessJwt acct) acct
                         (xrpc! "com.atproto.server.createSession" {:cacao_b64 (mint)} nil))
                jwt (:accessJwt sess)
                _ (when-not jwt (throw (js/Error. (str "auth failed: " (js/JSON.stringify (clj->js [acct sess]))))))
                rdid (or (:did sess) (:did id))
                blob (upload-blob! mp4 jwt)
                _ (when-not blob (throw (js/Error. "uploadBlob failed")))
                blob-cid (get-in blob [:ref :$link])
                src (str pds "/xrpc/com.atproto.sync.getBlob?cid=" blob-cid)
                _ (xrpc! "com.atproto.repo.createRecord"
                         {:repo rdid :collection "app.bsky.actor.profile" :rkey "self" :record profile} jwt)
                post (xrpc! "com.atproto.repo.createRecord"
                            {:repo rdid :collection "app.bsky.feed.post" :rkey (pub/work-slug work-id)
                             :record (pub/video-post-record
                                      {:text (str "『" title "』") :src src :blob blob
                                       :alt title :created-at now})} jwt)
                catalog (pub/catalog-record
                         {:work-id work-id :title title :summary summary :locale locale
                          :src src :blob-cid blob-cid :post-uri (:uri post)
                          :fps (:fps manifest) :duration-sec duration :created-at now})
                _ (xrpc! "com.atproto.repo.createRecord"
                         {:repo rdid :collection pub/catalog-collection :rkey (pub/work-slug work-id)
                          :record catalog} jwt)]
          (println "\naozora published:")
          (println "  blob cid :" blob-cid)
          (println "  video    :" src)
          (println "  post uri :" (:uri post))
          (println "  catalog  :" pub/catalog-collection "/" (pub/work-slug work-id))
          (println "  data     : kotobase.net (yoro-social, via aozora PDS)")
          (if youtube
            (println "\n--youtube: run bin/youtube.cljs with this work + OAuth env to syndicate FROM the aozora record (writes youtubeUrl back into the catalog).")
            (println "\n(YouTube syndication skipped; pass --youtube + OAuth env to enable.)"))
          (js/process.exit 0))))))

(apply -main *command-line-args*)
