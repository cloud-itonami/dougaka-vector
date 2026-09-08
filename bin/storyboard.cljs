(ns storyboard
  "nbb storyboard actor: topic → validated storyboard EDN via an
   OpenAI-compatible chat endpoint (murakumo.cloud gateway), with a
   validation-feedback retry loop.

     MURAKUMO_API_KEY=... nbb --classpath src bin/storyboard.cljs \\
       \"How INT4 quantization breaks\" --out /tmp/sb.edn --locales en,ja

   Offline: --mock <file> uses a canned LLM response instead of the network
   (same extraction/validation path, no credentials needed).

   env: MURAKUMO_URL   (default https://murakumo.cloud/api/v1)
        MURAKUMO_MODEL (default qwen-agentworld-35b-a3b)
        MURAKUMO_API_KEY (required unless --mock; mint per cloud-murakumo
        README: MURAKUMO_TOKEN_SECRET=... clojure -M:token issue <sub> ...)"
  (:require ["fs" :as fs]
            [kotoba.lang.text :as str]
            [promesa.core :as p]
            [dougaka-vector.storyboard :as sb]))

(defn- parse-args [argv]
  (loop [args argv opts {:locales [:en] :max-scenes 8 :max-dur 60 :attempts 3}]
    (if (empty? args)
      opts
      (let [[a & more] args]
        (case a
          "--out"      (recur (rest more) (assoc opts :out (first more)))
          "--locales"  (recur (rest more) (assoc opts :locales (mapv keyword (str/split (first more) #","))))
          "--max-dur"  (recur (rest more) (assoc opts :max-dur (js/parseFloat (first more))))
          "--attempts" (recur (rest more) (assoc opts :attempts (js/parseInt (first more))))
          "--mock"     (recur (rest more) (assoc opts :mock (first more)))
          "--dump-raw" (recur (rest more) (assoc opts :dump-raw (first more)))
          (recur more (assoc opts :topic a)))))))

(defn- chat! [messages]
  (let [url (str (or js/process.env.MURAKUMO_URL "https://murakumo.cloud/api/v1")
                 "/chat/completions")
        key js/process.env.MURAKUMO_API_KEY
        model (or js/process.env.MURAKUMO_MODEL "qwen-agentworld-35b-a3b")]
    (when-not key
      (println "MURAKUMO_API_KEY is required (or use --mock <file>)")
      (js/process.exit 2))
    (p/let [resp (js/fetch url
                           (clj->js {:method "POST"
                                     :headers {"Content-Type" "application/json"
                                               "Authorization" (str "Bearer " key)}
                                     :body (js/JSON.stringify
                                            (clj->js {:model model
                                                      :messages messages
                                                      :temperature 0.4
                                                      ;; reasoning upstreams truncate EDN at the
                                                      ;; default budget; murakumo README: budget
                                                      ;; generously + disable thinking
                                                      :max_tokens 8000
                                                      :chat_template_kwargs {:enable_thinking false}}))}))
            body (.json resp)]
      (when-not (.-ok resp)
        (throw (ex-info (str "chat/completions HTTP " (.-status resp) ": "
                             (js/JSON.stringify body))
                        {})))
      (-> body .-choices (aget 0) .-message .-content))))

(defn- attempt! [{:keys [topic mock dump-raw] :as opts} messages n]
  (p/let [raw (if mock
                (fs/readFileSync mock "utf8")
                (chat! (clj->js messages)))
          _ (when dump-raw (fs/appendFileSync dump-raw (str "==== attempt " n " ====\n" raw "\n")))
          parsed (sb/extract-edn raw)
          {:keys [ok? errors]} (sb/check parsed opts)]
    (cond
      ok? parsed
      (or mock (<= n 1))
      (do (println "storyboard rejected after final attempt:")
          (doseq [e errors] (println "  -" e))
          (js/process.exit 1))
      :else
      (do (println (str "attempt rejected (" (count errors) " errors), retrying…"))
          (attempt! opts
                    (conj (vec messages)
                          {:role "assistant" :content (str raw)}
                          {:role "user" :content (sb/feedback errors)})
                    (dec n))))))

(let [{:keys [topic out attempts] :as opts} (parse-args *command-line-args*)]
  (when-not (and topic out)
    (println "usage: nbb --classpath src bin/storyboard.cljs <topic> --out <file.edn>"
             "[--locales en,ja] [--max-dur 60] [--attempts 3] [--mock <raw-llm-output-file>]")
    (js/process.exit 2))
  (p/let [storyboard (attempt! opts (sb/messages topic opts) attempts)]
    (fs/writeFileSync out (pr-str storyboard))
    (println "storyboard ok →" out
             (str "(" (count (:video/scenes storyboard)) " scenes, locales "
                  (pr-str (:locales opts)) ")"))))
