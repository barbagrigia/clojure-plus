(ns clojure-plus.helpers-test
  (:require [clojure.test :refer-macros [deftest is testing run-tests]]
            [clojure-plus.helpers :as helpers]
            [clojure-plus.core :as core]))

(def foo-element (.createElement js/document "foo"))

(defn find-commands [name]
  (-> js/atom
      .-commands
      (.findCommands #js {:target foo-element})
      js->clj
      (->> (filter #(= (% "displayName") name)))))

(find-commands "")

(deftest commands
  (helpers/add-command "foo" "Sample Test" #(println))
  (testing "command is on global"
    (is (not-empty (find-commands "Sample Test"))))

  (helpers/remove-all-commands)
  (testing "removes command"
    (is (empty? (find-commands "Sample Test")))))

(run-tests)
