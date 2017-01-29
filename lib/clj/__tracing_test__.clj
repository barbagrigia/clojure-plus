(ns clj.__tracing-test__
  (:require [clojure.test :refer :all]
            [clj.__tracing__ :as t]
            [com.billpiel.sayid.core :as sayid]))

(defn test1 [x y]
  (let [a (+ x y)
        b (- x y)]
    (+ a b)))

(defn test2 [a]
  (let [b (test1 a 10)]
    (+ b 10)))

(defn test3 [x y]
  (when-let [c (inc x)]
    (+ c y)))

(defn reset-all []
  (reset! sayid/workspace nil)
  (sayid/ws-get-active!)
  (with-out-str (sayid/ws-reset!)))

(reset-all)
(sayid/ws-add-inner-trace-fn! test3)

(defn norm-namespace [namespace]
  (-> namespace
      (select-keys [:name :args :return :src-pos :form :xpanded-frm :xpanded-parent :children :arg-map])
      (update :children #(some-> % deref count))
      (update :arg-map #(some-> % deref))))

(def nn
  (norm-namespace (-> (sayid/ws-get-active!) :children deref first
                      :children deref first
                      :children deref second
                      :children deref first)))
                    ; :children deref first))

(defn traced [stacks]
  (when stacks (for [row stacks]
                 (-> row
                     (dissoc :id)
                     (update-in [:children] traced)))))

(testing "tracing a single function"
  (reset-all)
  (sayid/ws-add-trace-fn! test1)
  (test1 11 20)
  (is (= [{:fn "clj.__tracing-test__/test1"
           :args ["11" "20"]
           :returned "22"
           :mapping {'x "11", 'y "20"}
           :children nil}]
         (traced (t/trace-str))))
  (is (keyword? (-> (t/trace-str) first :id keyword))))

(testing "tracing dependent functions"
  (reset-all)
  (sayid/ws-add-trace-fn! test1)
  (sayid/ws-add-trace-fn! test2)
  (test2 5)
  (is (= [{:fn "clj.__tracing-test__/test2"
           :args ["5"]
           :returned "20"
           :mapping {'a "5"}
           :children [{:fn "clj.__tracing-test__/test1"
                       :args ["5" "10"]
                       :returned "10"
                       :mapping {'x "5", 'y "10"}
                       :children nil}]}]
         (traced (t/trace-str)))))

(testing "reset"
  (reset-all)
  (sayid/ws-add-trace-fn! test1)
  (test1 11 20)
  (t/reset-sayid!)
  (is (empty? (t/trace-str)))
  (test1 11 20)
  (is (not (empty? (t/trace-str)))))

(testing "inner trace"
  (reset-all)
  (sayid/ws-add-trace-fn! test3)
  (test3 11 20)
  (let [id (-> (t/trace-str) first :id keyword)]
    (is (= [{:fn "when-let"
             :args nil
             :mapping nil
             :returned "32"
             :children [{:fn "inc"
                         :args ["11"]
                         :mapping nil
                         :returned "12"
                         :children nil}
                        {:fn "+"
                         :args ["12" "20"]
                         :mapping nil
                         :returned "32"
                         :children nil}]}]
           (traced (t/trace-inner id))))))

(testing "inner trace with let"
  (reset-all)
  (sayid/ws-add-trace-fn! test1)
  (test1 11 20)
  (test1 22 20)
  (let [id (-> (t/trace-str) first :id keyword)]
    (is (= [{:fn "let"
             :args ["[a (+ x 1) b (- x 1)]"]
             :mapping {'a "31" 'b "-9"}
             :returned "22"
             :children [{:fn "(+ x y)"
                         :returned "31"
                         :children nil}
                        {:fn "(- x y)"
                         :returned "-9"
                         :children nil}
                        {:fn "+"
                         :args ["31" "-9"]
                         :mapping nil
                         :returned "22"
                         :children nil}]}]
           (traced (t/trace-inner id))))))

(defn grandfather [number]
  (+ number 10))

(defn father1 [end]
  (let [r (range end)
        mapped (map grandfather r)]
    (take 3 mapped)))

(defn father2 [end]
  (map inc (range end)))

(defn child [x y]
  (let [a (father1 x)
        b (father2 y)]
    (reduce + (concat a b))))

(testing "complex trace with inner trace"
  (let [last-traces (atom nil)
        trace-inner #(let [id (-> @last-traces first :id keyword)]
                       (reset! last-traces (t/trace-inner id)))]
    (reset-all)
    (sayid/ws-add-trace-fn! child)
    (child 7 8)
    (reset! last-traces (t/trace-str))
    (is (= [{:fn "clj.__tracing-test__/child"
             :args ["7" "8"]
             :returned "69"
             :mapping {'x "7", 'y "8"}
             :children nil}]
           (traced @last-traces)))
    (trace-inner)
    (is (= [{:fn "let"
             :args ["[a (father1 x) b (father2 y)]"]
             :returned "69"
             :mapping {:a '(10 11 12)
                       :b '(1 2 3 4 5 6 7)}
             :children [{:fn "(father1 x)"
                         :returned []}]}]
           (traced @last-traces)))))

; (t/trace-str)