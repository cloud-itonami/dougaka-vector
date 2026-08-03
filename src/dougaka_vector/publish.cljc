(ns dougaka-vector.publish
  "Pure record/metadata builders for publishing a rendered work to aozora.app
   (the AT Protocol boundary; its PDS backs onto kotobase.net, so records land
   there automatically) and for syndicating to YouTube.

   Model (owner decision, 2026-07-17, corrected): 作者 = 1 actor DID. The
   dougaka-vector channel is ONE atproto author with a single Ed25519 did:key,
   one aozora account+handle, and one actor profile (self). Each video WORK is a
   record UNDER that author DID: an app.bsky.feed.post (with the video embed)
   plus a catalog datom, keyed by the work slug. aozora is the primary surface;
   YouTube is a syndication of a work FROM its aozora record. (This is the
   established model — dougaka-actor and syosetsuka both use one author DID with
   works as records, not a DID per work.)

   This ns is IO-free and runtime-portable so it is testable under nbb without
   network. The CACAO self-mint + XRPC fetch + blob bytes live in
   bin/publish.cljs (nbb), mirroring the kotobase-client cljs convention
   (ai-gftd-dougaka-kodomo/tools/publish_aozora.cljs)."
  (:require [clojure.string :as str]))

;; ── author (channel) identity ────────────────────────────────────────────────

(def channel
  "The single dougaka-vector author/channel. One DID owns every work's records.
   Handle/name/description overridable via bin/publish.cljs opts."
  {:handle "dougaka-vector.aozora.app"
   :display-name "動画家ベクター (dougaka-vector)"
   :description (str "言語・キャラクター非依存のベクターアニメーション解説。"
                     "黒背景・ネオンのモーショングラフィックスで技術トピックを図解します。"
                     "ai-gftd-dougaka-vector で自動生成。")})

;; ── slugs ────────────────────────────────────────────────────────────────────

(defn work-slug
  "video-id → a DNS/rkey-safe slug: lowercase, [a-z0-9-], collapsed dashes,
   trimmed, ≤63 chars. Used for a work's feed.post/catalog rkey under the
   single author DID."
  [work-id]
  (let [s (-> (str work-id)
              str/lower-case
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"-+" "-")
              (str/replace #"^-|-$" ""))]
    (subs s 0 (min 63 (count s)))))

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
  "app.bsky.actor.profile (rkey \"self\") — the dougaka-vector AUTHOR/channel
   profile (one per DID, idempotent). aozora has no app.aozora.actor.profile
   lexicon; profiles are standard bsky."
  [{:keys [display-name description]}]
  {:$type "app.bsky.actor.profile"
   :displayName display-name
   :description description})

(defn news-post-text
  "Post text for a work produced from a newsfeed brief: the work's title, then
   the lead source and its url.

   A news video whose post does not name a source is an unsourced claim on the
   timeline. The full citation list goes in the catalog record, but nobody
   scrolling the feed reads catalog records — so the lead one has to be here.

   Clamped to `limit` graphemes (atproto's post limit is 300). The URL is
   never truncated: a cut url is worse than no url, because it looks like a
   citation and resolves to nothing. If the whole thing will not fit, the
   title gives way first, then the source name, and the url always survives."
  [{:keys [title lead-source lead-url also-count limit]
    :or {limit 300}}]
  (let [url (some-> lead-url str not-empty)
        also (when (and also-count (pos? also-count))
               (str " (+" also-count " more)"))
        src-line (str "Source: " lead-source also)
        tail (str "\n" src-line (when url (str "\n" url)))
        head (str "『" title "』")
        room (- limit (count tail))]
    (if (>= room (count head))
      (str head tail)
      ;; Title gives way. If even the tail alone does not fit, drop the source
      ;; name rather than the url.
      (let [short-head (when (> room 4) (str "『" (subs title 0 (max 0 (- room 3))) "…』"))]
        (if short-head
          (str short-head tail)
          (str (when url (str url))))))))

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
           fps duration-sec created-at topic citations]}]
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
    youtube-url (assoc :youtubeUrl youtube-url)
    ;; A work produced from a newsfeed brief carries the sources it was made
    ;; from. The post text can only name the lead one, so this is where the
    ;; full list lives and where a governor can check cites ⊆ ingested.
    topic (assoc :topic topic)
    (seq citations) (assoc :citations (vec citations))))

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
