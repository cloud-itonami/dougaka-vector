(ns dougaka-vector.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dougaka-vector.ease :as ease]
            [dougaka-vector.timeline :as timeline]
            [dougaka-vector.spec :as spec]
            [dougaka-vector.scene :as scene]
            [dougaka-vector.svg :as svg]
            [dougaka-vector.theme :as theme]))

(def sb
  {:video/id "t" :video/fps 30 :video/size [1920 1080] :video/theme :cyber-dark
   :video/copy {:a {:en "Hello" :ja "こんにちは"}
                :b {:en "Sub"}}
   :video/scenes
   [{:scene/id :s1 :scene/dur 2.0 :scene/template :title-card
     :scene/copy {:title :a :sub :b}}
    {:scene/id :s2 :scene/dur 3.0 :scene/template :bar-chart
     :scene/copy {:kicker :a :panel-title :b :caption :b :annotation :b :badge :b}
     :scene/args {:values [0.2 0.9 0.1]
                  :highlight {1 :accent}
                  :annotation {:from 0 :to 2}
                  :axis-labels ["0" "1"]}}]})

(deftest ease-bounds
  (doseq [[k f] ease/eases]
    (testing (str k)
      (is (== 0.0 (f 0.0)))
      (is (== 1.0 (f 1.0)))
      (is (== (f 0.5) (f 0.5)) "deterministic")))
  (is (< 0.99 (ease/out-expo 1.0) 1.01))
  (is (> (ease/out-back 0.8) 1.0) "out-back overshoots"))

(deftest keyframe-sampling
  (let [ks [[0.0 0] [1.0 100]]]
    (is (== 0 (timeline/sample-keys ks ease/linear -1)))
    (is (== 50.0 (timeline/sample-keys ks ease/linear 0.5)))
    (is (== 100 (timeline/sample-keys ks ease/linear 2.0))))
  (testing "non-numeric values switch at segment end"
    (is (= "a" (timeline/sample-keys [[0 "a"] [1 "b"]] ease/linear 0.5)))
    (is (= "b" (timeline/sample-keys [[0 "a"] [1 "b"]] ease/linear 1.0)))))

(deftest sample-merges-animated-attrs
  (let [sg {:sg/dur 1.0
            :sg/nodes [{:node/id :r :node/kind :rect :node/attrs {:x 0 :y 0 :w 10 :h 0 :fill :accent}}]
            :sg/tracks [{:track/node :r :track/attr :h :track/keys [[0.0 0] [1.0 50]] :track/ease :linear}]}
        [n] (timeline/sample sg 0.5)]
    (is (== 25.0 (get-in n [:node/attrs :h])))
    (is (== 10 (get-in n [:node/attrs :w])) "static attrs preserved")))

(deftest frame-times-count
  (is (= 30 (count (timeline/frame-times {:sg/dur 1.0} 30))))
  (is (== 0.0 (first (timeline/frame-times {:sg/dur 1.0} 30)))))

(deftest storyboard-validation
  (is (:ok? (spec/validate-storyboard sb)))
  (testing "missing copy-id is an error"
    (let [bad (assoc-in sb [:video/scenes 0 :scene/copy :title] :nope)]
      (is (not (:ok? (spec/validate-storyboard bad))))))
  (testing "unknown template is an error"
    (let [bad (assoc-in sb [:video/scenes 0 :scene/template] :wat)]
      (is (not (:ok? (spec/validate-storyboard bad))))))
  (testing "locale availability is the intersection"
    (is (= #{:en} (spec/locales sb)))))

(deftest compile-locale-swap-changes-text-not-structure
  (let [en (scene/compile-storyboard sb :en)
        ja (scene/compile-storyboard sb :ja)
        node-ids #(mapv (fn [s] (mapv :node/id (get-in s [:sg :sg/nodes]))) (:scenes %))]
    (is (= (node-ids en) (node-ids ja)) "same structure across locales")
    (is (= "Hello" (get-in en [:scenes 0 :sg :sg/nodes 0 :node/attrs :text])))
    (is (= "こんにちは" (get-in ja [:scenes 0 :sg :sg/nodes 0 :node/attrs :text])))
    (testing ":ja falls back to :en where missing"
      (is (= "Sub" (get-in ja [:scenes 0 :sg :sg/nodes 2 :node/attrs :text]))))))

(deftest bar-chart-compiles-bars-and-annotation
  (let [compiled (scene/compile-storyboard sb :en)
        sg (get-in compiled [:scenes 1 :sg])
        ids (set (map :node/id (:sg/nodes sg)))]
    (is (contains? ids :bar-0))
    (is (contains? ids :bar-2))
    (is (contains? ids :anno-line))
    (is (contains? ids :badge-box))
    (testing "bars start at h=0 and grow"
      (let [h-end (fn [t] (-> (timeline/sample sg t)
                              (->> (filter #(= :bar-1 (:node/id %))) first)
                              (get-in [:node/attrs :h])))]
        (is (== 0 (h-end 0.0)))
        (is (< 0 (h-end 3.0)))))))

(deftest svg-emit
  (let [th (theme/theme :cyber-dark)
        compiled (scene/compile-storyboard sb :en)
        sg (get-in compiled [:scenes 1 :sg])
        doc (svg/frame->svg th [1920 1080] (timeline/sample sg 2.9))]
    (is (str/starts-with? doc "<svg"))
    (is (str/includes? doc (:bg th)) "background painted")
    (is (str/includes? doc (:warn th)) "theme color resolved to hex")
    (is (str/includes? doc "Hello") "copy resolved into text element")
    (testing "escaping"
      (is (str/includes?
           (svg/frame->svg th [10 10]
                           [{:node/id :t :node/kind :text
                             :node/attrs {:x 0 :y 0 :text "<a & b>"}}])
           "&lt;a &amp; b&gt;")))))

(deftest cues-for-audio
  (let [compiled (scene/compile-storyboard sb :en)
        cues (timeline/track-events (get-in compiled [:scenes 1 :sg]))]
    (is (seq cues))
    (is (apply <= (map :cue/t cues)) "sorted by time")))
