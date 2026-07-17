(ns dougaka-vector.publish
  "Pure record/metadata builders for publishing a rendered work to aozora.app
   (the AT Protocol boundary; its PDS backs onto kotobase.net, so records land
   there automatically) and for syndicating to YouTube.

   Model (owner decision, 2026-07-17): 作品 = 1 actor DID. Each video work gets
   its own Ed25519 did:key (minted per work, self-sovereign CACAO — no owner
   creds), its own aozora account+handle, and its own actor profile. aozora is
   the primary surface; YouTube is a syndication of the same work FROM the
   aozora record.

   This ns is IO-free and runtime-portable so it is testable under nbb without
   network. The CACAO self-mint + XRPC fetch + blob bytes live in
   bin/publish.cljs (nbb), mirroring the kotobase-client cljs convention
   (ai-gftd-dougaka-kodomo/tools/publish_aozora.cljs)."
  (:require [clojure.string :as str]))

;; ── identity / handle ───────────────────────────────────────────────────────

(defn work-slug
  "video-id → a DNS-label-safe slug for handles/rkeys: lowercase, [a-z0-9-],
   collapsed dashes, trimmed, ≤63 chars."
  [work-id]
  (let [s (-> (str work-id)
              str/lower-case
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"-+" "-")
              (str/replace #"^-|-$" ""))]
    (subs s 0 (min 63 (count s)))))

(defn handle
  "Per-work aozora handle: <slug>.<domain> (domain default aozora.app).
   One handle per work is the 作品=1 DID surface name."
  ([work-id] (handle work-id "aozora.app"))
  ([work-id domain] (str (work-slug work-id) "." domain)))

;; ── copy extraction from a compiled/rendered work ────────────────────────────

(defn- copy-str
  "Resolve a copy-id for locale from a storyboard's :video/copy, :en fallback."
  [storyboard locale cid]
  (when cid
    (let [e (get-in storyboard [:video/copy cid])]
      (or (get e locale) (get e :en)))))

(defn work-title
  "Human title for a work: first :title-card's title copy, else the video id."
  [storyboard locale]
  (let [tc (first (filter #(= :title-card (:scene/template %)) (:video/scenes storyboard)))
        cid (get-in tc [:scene/copy :title])]
    (or (copy-str storyboard locale cid)
        (:video/id storyboard))))

(defn work-summary
  "Short description: title-card :sub if present, else the title."
  [storyboard locale]
  (let [tc (first (filter #(= :title-card (:scene/template %)) (:video/scenes storyboard)))
        sub (copy-str storyboard locale (get-in tc [:scene/copy :sub]))]
    (or sub (work-title storyboard locale))))

;; ── aozora records ───────────────────────────────────────────────────────────

(defn profile-record
  "app.bsky.actor.profile (rkey \"self\") — the per-work actor's profile.
   aozora has no app.aozora.actor.profile lexicon; profiles are standard bsky."
  [{:keys [display-name description]}]
  {:$type "app.bsky.actor.profile"
   :displayName display-name
   :description description})

(defn video-post-record
  "app.bsky.feed.post carrying an app.aozora.embed.video direct-URL embed —
   exactly the shape aozora /videos plays (VOD: :src = getBlob URL)."
  [{:keys [text src blob mime alt aspect created-at]
    :or {mime "video/mp4" aspect {:width 16 :height 9}}}]
  {:$type "app.bsky.feed.post"
   :text text
   :createdAt created-at
   :embed {:$type "app.aozora.embed.video"
           :src src
           :mimeType mime
           :video blob
           :alt (or alt text)
           :aspectRatio aspect}})

(def catalog-collection
  "dougaka-vector's own generic record collection — the per-work catalog entry
   (separate from the feed-post announcement), so getVideo-style reads can find
   a work by id and carry cross-surface links (e.g. the YouTube syndication)."
  "app.gftd.dougakaVector.video")

(defn catalog-record
  "The work's canonical catalog datom on its own graph. Carries the aozora post
   uri + blob src and, once syndicated, the YouTube url — the join point that
   makes YouTube a syndication OF the aozora record, not a parallel publish."
  [{:keys [work-id title summary locale src blob-cid post-uri youtube-url
           fps duration-sec created-at]}]
  (cond-> {:$type catalog-collection
           :workId work-id
           :title title
           :summary summary
           :locale (name (or locale :en))
           :src src
           :blobCid blob-cid
           :postUri post-uri
           :fps fps
           :durationSec duration-sec
           :createdAt created-at}
    youtube-url (assoc :youtubeUrl youtube-url)))

;; ── youtube syndication metadata ─────────────────────────────────────────────

(defn youtube-metadata
  "com-youtube video-metadata shape {:snippet :status}. Title clamped 100,
   description 5000 (YouTube limits)."
  [{:keys [title description tags category-id lang privacy made-for-kids?]
    :or {category-id "27" ; Education
         lang "en" privacy "public" made-for-kids? false}}]
  {:snippet (cond-> {:title (subs (str title) 0 (min 100 (count (str title))))
                     :description (subs (str description) 0 (min 5000 (count (str description))))
                     :categoryId category-id
                     :defaultLanguage lang}
              (seq tags) (assoc :tags (vec tags)))
   :status {:privacyStatus privacy
            :madeForKids made-for-kids?
            :embeddable true
            :selfDeclaredMadeForKids made-for-kids?}})

(defn youtube-description
  "Description body for the YouTube syndication — links back to the aozora
   record as the canonical source (aozora 主, youtube 連携)."
  [{:keys [summary post-uri handle]}]
  (str summary
       "\n\n—\n"
       "Published on aozora.app"
       (when handle (str " (@" handle ")"))
       (when post-uri (str "\nSource: " post-uri))
       "\nGenerated with ai-gftd-dougaka-vector (language/character-independent vector animation)."))
