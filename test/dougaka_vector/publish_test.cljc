(ns dougaka-vector.publish-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dougaka-vector.publish :as pub]))

(def sb
  {:video/id "SQL Injection 2026!"
   :video/copy {:t {:en "The SQL Injection Paradox" :ja "SQLインジェクションの矛盾"}
                :s {:en "Why it persists in 2026" :ja "2026年になぜ防げないのか"}}
   :video/scenes [{:scene/id :title :scene/template :title-card
                   :scene/copy {:title :t :sub :s}}]})

(deftest work-slug-is-dns-safe
  (is (= "sql-injection-2026" (pub/work-slug "SQL Injection 2026!")))
  (is (= "a-b" (pub/work-slug "  a__b  ")))
  (is (<= (count (pub/work-slug (apply str (repeat 100 "x")))) 63))
  (testing "slug is idempotent"
    (is (= (pub/work-slug "SQL Injection 2026!")
           (pub/work-slug (pub/work-slug "SQL Injection 2026!"))))))

(deftest handle-derivation
  (is (= "sql-injection-2026.aozora.app" (pub/handle "SQL Injection 2026!")))
  (is (= "sql-injection-2026.v.aozora.app" (pub/handle "SQL Injection 2026!" "v.aozora.app"))))

(deftest title-summary-from-storyboard
  (is (= "The SQL Injection Paradox" (pub/work-title sb :en)))
  (is (= "SQLインジェクションの矛盾" (pub/work-title sb :ja)))
  (is (= "Why it persists in 2026" (pub/work-summary sb :en)))
  (testing "falls back to video id when no title-card"
    (is (= "x" (pub/work-title {:video/id "x" :video/scenes []} :en)))))

(deftest profile-record-shape
  (let [r (pub/profile-record {:display-name "T" :description "D"})]
    (is (= "app.bsky.actor.profile" (:$type r)))
    (is (= "T" (:displayName r)))))

(deftest video-post-embeds-aozora-video
  (let [blob {:$type "blob" :ref {:$link "bafyX"} :mimeType "video/mp4"}
        r (pub/video-post-record {:text "『T』" :src "https://pds/getBlob?cid=bafyX"
                                  :blob blob :alt "T" :created-at "2026-01-01T00:00:00Z"})]
    (is (= "app.bsky.feed.post" (:$type r)))
    (is (= "app.aozora.embed.video" (get-in r [:embed :$type])))
    (is (= "https://pds/getBlob?cid=bafyX" (get-in r [:embed :src])))
    (is (= blob (get-in r [:embed :video])) "the uploadBlob ref is carried in the embed")))

(deftest catalog-record-and-youtube-join
  (let [base {:work-id "w" :title "T" :summary "S" :locale :ja :src "u" :blob-cid "c"
              :post-uri "at://x" :fps 30 :duration-sec 40.0 :created-at "2026"}
        r (pub/catalog-record base)
        r+yt (pub/catalog-record (assoc base :youtube-url "https://youtu.be/abc"))]
    (is (= "app.gftd.dougakaVector.video" (:$type r)))
    (is (= "at://x" (:postUri r)))
    (is (not (contains? r :youtubeUrl)) "no youtube link until syndicated")
    (is (= "https://youtu.be/abc" (:youtubeUrl r+yt)) "syndication joins onto the same catalog record")))

(deftest youtube-metadata-shape-and-clamps
  (let [m (pub/youtube-metadata {:title (apply str (repeat 200 "x"))
                                 :description "d" :tags ["a" "b"] :lang "ja" :privacy "unlisted"})]
    (is (= 100 (count (get-in m [:snippet :title]))) "title clamped to 100")
    (is (= ["a" "b"] (get-in m [:snippet :tags])))
    (is (= "unlisted" (get-in m [:status :privacyStatus]))))
  (testing "description links back to aozora (aozora 主)"
    (is (str/includes? (pub/youtube-description {:summary "S" :post-uri "at://p" :handle "h.aozora.app"})
                       "at://p"))
    (is (str/includes? (pub/youtube-description {:summary "S" :handle "h.aozora.app"})
                       "aozora.app"))))
