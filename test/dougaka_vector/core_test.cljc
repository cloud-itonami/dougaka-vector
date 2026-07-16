(ns dougaka-vector.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dougaka-vector.ease :as ease]
            [dougaka-vector.timeline :as timeline]
            [dougaka-vector.spec :as spec]
            [dougaka-vector.scene :as scene]
            [dougaka-vector.svg :as svg]
            [dougaka-vector.theme :as theme]
            [dougaka-vector.storyboard :as sb]
            [dougaka-vector.audio :as audio]))

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

(def sb2
  {:video/id "t2" :video/fps 30 :video/size [1920 1080] :video/theme :cyber-dark
   :video/copy {:k {:en "KICK"} :l {:en "PPL"} :s1 {:en "FP16"} :s2 {:en "INT4"}
                :lb {:en "smaller"}}
   :video/scenes
   [{:scene/id :lc :scene/dur 4.0 :scene/template :line-chart
     :scene/copy {:kicker :k :label :l}
     :scene/args {:values [0.2 0.4 0.35 0.7 0.9] :axis-labels ["2b" "16b"]}}
    {:scene/id :fl :scene/dur 4.0 :scene/template :flow
     :scene/copy {:steps [:s1 :s2]}}
    {:scene/id :bn :scene/dur 3.0 :scene/template :big-number
     :scene/copy {:label :lb}
     :scene/args {:from 0 :to 4 :suffix "x"}}]})

(deftest new-templates-compile-and-animate
  (is (:ok? (spec/validate-storyboard sb2)))
  (let [compiled (scene/compile-storyboard sb2 :en)
        [lc fl bn] (map :sg (:scenes compiled))]
    (testing ":line-chart draws progressively"
      (let [prog (fn [t] (-> (timeline/sample lc t)
                             (->> (filter #(= :curve (:node/id %))) first)
                             (get-in [:node/attrs :progress])))]
        (is (== 0.0 (prog 0.0)))
        (is (== 1.0 (prog 3.9)))))
    (testing ":flow has boxes, steps and arrows"
      (let [ids (set (map :node/id (:sg/nodes fl)))]
        (is (contains? ids :box-0))
        (is (contains? ids :step-1))
        (is (contains? ids :arrow-1))
        (is (not (contains? ids :arrow-0)) "no arrow before the first box")))
    (testing ":big-number counter runs from → to"
      (let [v (fn [t] (-> (timeline/sample bn t)
                          (->> (filter #(= :figure (:node/id %))) first)
                          (get-in [:node/attrs :value])))]
        (is (== 0 (v 0.0)))
        (is (== 4 (v 2.5)))))))

(deftest polyline-partial-and-counter-render
  (let [th (theme/theme :cyber-dark)]
    (testing "polyline honors :progress"
      (let [render #(svg/frame->svg th [100 100]
                                    [{:node/id :p :node/kind :polyline
                                      :node/attrs {:points [[0 0] [10 0] [20 0]]
                                                   :progress % :stroke :accent}}])]
        (is (str/includes? (render 0.5) "10,0") "midpoint reached at 0.5")
        (is (not (str/includes? (render 0.5) "20,0")) "endpoint hidden at 0.5")
        (is (str/includes? (render 1.0) "20,0"))))
    (testing "counter formats value with prefix/suffix and decimals"
      (is (str/includes?
           (svg/frame->svg th [100 100]
                           [{:node/id :c :node/kind :counter
                             :node/attrs {:x 0 :y 0 :value 3.14159 :decimals 1
                                          :prefix "~" :suffix "x"}}])
           "~3.1x")))))

(deftest storyboard-actor-pure-parts
  (testing "extract-edn tolerates fences and prose"
    (is (= {:a 1} (sb/extract-edn "Here you go:\n```edn\n{:a 1}\n```\nthanks")))
    (is (nil? (sb/extract-edn "no map here"))))
  (testing "check catches locale gaps and duration overrun"
    (let [good (sb/check sb2 {:locales [:en] :max-dur 60})
          bad-locale (sb/check sb2 {:locales [:en :ja] :max-dur 60})
          bad-dur (sb/check sb2 {:locales [:en] :max-dur 5})]
      (is (:ok? good))
      (is (not (:ok? bad-locale)))
      (is (not (:ok? bad-dur)))))
  (testing "feedback lists every error"
    (is (str/includes? (sb/feedback ["e1" "e2"]) "e2")))
  (testing "system prompt embeds the template catalog and locales"
    (let [p (sb/system-prompt {:locales [:en :ja]})]
      (is (str/includes? p ":line-chart"))
      (is (str/includes? p ":ja")))))

(deftest audio-plan-from-cues
  (let [compiled (scene/compile-storyboard sb :en)
        cues (timeline/track-events (get-in compiled [:scenes 1 :sg]))
        manifest {:video/id "t" :fps 30 :frame/count 90}
        plan (audio/plan manifest cues)]
    (is (== 3.0 (:audio/duration plan)))
    (is (seq (:audio/sfx plan)) "bar growth produces sfx events")
    (is (every? #(contains? (:audio/sfx-catalog plan) (:sfx/kind %)) (:audio/sfx plan))
        "catalog covers every used kind")
    (testing "staggered bars coalesce instead of firing one tick per bar"
      (is (< (count (filter #(= :tick (:sfx/kind %)) (:audio/sfx plan)))
             (count (filter #(= :h (:cue/attr %)) cues)))))
    (testing "opacity fades stay silent"
      (is (not-any? #(= :opacity (:sfx/kind %)) (:audio/sfx plan))))))

(deftest cues-for-audio
  (let [compiled (scene/compile-storyboard sb :en)
        cues (timeline/track-events (get-in compiled [:scenes 1 :sg]))]
    (is (seq cues))
    (is (apply <= (map :cue/t cues)) "sorted by time")))
