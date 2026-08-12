(ns run
  "nbb test runner: nbb --classpath src:test test/run.cljs"
  (:require [clojure.test :as t]
            [dougaka-vector.core-test]
            [dougaka-vector.publish-test]
            [dougaka-vector.youtube-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (js/process.exit 1)))

(t/run-tests 'dougaka-vector.core-test 'dougaka-vector.publish-test
             'dougaka-vector.youtube-test)
