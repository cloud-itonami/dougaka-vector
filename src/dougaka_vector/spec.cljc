(ns dougaka-vector.spec
  "Storyboard validation. Hand-rolled (no external schema deps) so it runs
   identically on every runtime in the priority chain. Returns
   {:ok? bool :errors [string ...]} — never throws, callers decide.")

(def templates #{:title-card :bar-chart :callout :custom})

(defn- err [errors cond' msg]
  (if cond' errors (conj errors msg)))

(defn validate-scene [i {:scene/keys [id dur template] :as scene}]
  (-> []
      (err (keyword? id) (str "scene[" i "]: :scene/id must be a keyword"))
      (err (and (number? dur) (pos? dur)) (str "scene[" i "] " id ": :scene/dur must be a positive number of seconds"))
      (err (contains? templates template)
           (str "scene[" i "] " id ": unknown :scene/template " template " (known: " templates ")"))
      (err (or (not= template :custom) (map? (:scene/sg scene)))
           (str "scene[" i "] " id ": :custom template requires an inline :scene/sg scenegraph"))))

(defn copy-refs
  "All copy-ids referenced by scenes' :scene/copy maps. A role may reference
   a single copy-id or a vector of copy-ids (e.g. :callout's :lines)."
  [storyboard]
  (into #{}
        (comp (mapcat (comp vals :scene/copy))
              (mapcat #(if (vector? %) % [%])))
        (:video/scenes storyboard)))

(defn validate-storyboard [{:video/keys [id fps size scenes copy] :as sb}]
  (let [errors (-> []
                   (err (string? id) ":video/id must be a string")
                   (err (and (number? fps) (pos? fps)) ":video/fps must be a positive number")
                   (err (and (vector? size) (= 2 (count size)) (every? pos-int? size))
                        ":video/size must be [width height] positive ints")
                   (err (and (vector? scenes) (seq scenes)) ":video/scenes must be a non-empty vector"))
        errors (into errors (mapcat identity (map-indexed validate-scene scenes)))
        missing (remove #(contains? (or copy {}) %) (copy-refs sb))
        errors (into errors (map #(str "copy-id " % " referenced by a scene but missing from :video/copy") missing))
        dup-ids (->> scenes (map :scene/id) frequencies (keep (fn [[k c]] (when (> c 1) k))))
        errors (into errors (map #(str "duplicate :scene/id " %) dup-ids))]
    {:ok? (empty? errors) :errors errors}))

(defn locales
  "Locales available across all copy entries (intersection — a locale is only
   renderable if every referenced copy-id has it)."
  [{:video/keys [copy] :as sb}]
  (let [refs (copy-refs sb)]
    (if (empty? refs)
      #{}
      (reduce (fn [acc cid]
                (into #{} (filter #(contains? (get copy cid) %)) acc))
              (set (keys (get copy (first refs))))
              (rest refs)))))
