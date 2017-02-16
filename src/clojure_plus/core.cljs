(ns clojure-plus.core
  (:require [cljs.nodejs :as nodejs]
            [weasel.repl :as repl]))

(nodejs/enable-util-print!)
(def vm (js/require "vm"))

(defmethod repl/process-message :eval-js [message]
  (let [code (:code message)]
    {:op :result
     :value (try
              {:status :success, :value (str ((fn [src] (.runInThisContext vm src)) code))}
              (catch js/Error e
                {:status :exception
                 :value (pr-str e)
                 :stacktrace (if (.hasOwnProperty e "stack")
                               (.-stack e)
                               "No stacktrace available.")})
              (catch :default e
                {:status :exception
                 :value (pr-str e)
                 :stacktrace "No stacktrace available."}))}))


(js/setInterval #(when-not (repl/alive?)
                   (repl/connect "ws://localhost:9001"))
                3000)
