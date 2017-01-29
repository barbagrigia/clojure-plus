(ns clj.__tracing__
  (:require [com.billpiel.sayid.core :as sayid]
            [clojure.string :as s]))

(defn reset-sayid! []
  (let [{:keys [fn ns]} (:traced (sayid/ws-get-active!))]
    (reset! sayid/workspace nil) ; Sayid bug?
    (with-out-str (sayid/ws-reset!))
    (doseq [f fn] (sayid/ws-add-trace-fn!* f))
    (doseq [n ns] (sayid/ws-add-trace-ns!* n))))

(defn- truncate [obj]
  (let [s (str obj)]
    (if (< (count s) 20)
      s
      (-> s (subs 0 20) (str "...")))))

(declare trace-child)
(defn- let-binds [child]
  (let [outer-fns (mapv (fn [[ret _ form]]
                          {:fn (str form)
                           :returned (str ret)
                           :children nil})
                       (:let-binds child))
        maps (map (fn [[ret var _]] [var (str ret)]) (:let-binds child))
        let-fn {:id (name (:id child))
                :fn (-> child :inner-tags last name)
                ; :args (->> maps (mapv #(symbol (s/join " " %))) str vector)
                :args (-> child :xpanded-frm second str vector)
                :mapping (into {} maps)
                :returned (str (:return child))
                :children (-> outer-fns (concat (trace-child child)) vec)}]
    [let-fn]))

(defn- treat-return [return]
  (str (if (seq? return)
         (seq return)
         return)))

(defn- function [child]
  ; (println "BUA2 =>" (select-keys child [:id :fn :src-pos :xpanded-frm :xpanded-parent :form :arg-map :args :mapping :return]))
  (let [f (str (or (:form child) (:name child) (:xpanded-frm child)))]
    ; (if (or (.startsWith f "(fn*") (.startsWith f "(let*"))
    ;   (trace-child child)
    [{:id (name (:id child))
      :fn f
      :args (not-empty (mapv treat-return (:args child)))
      :mapping (some->> child :arg-map deref not-empty (map (fn [[k v]] [k (str v)])) (into {}))
      :returned (treat-return (:return child))
      :children (trace-child child)}]))

(defn p [x] (println "DEBUG:" x) x)
(defn- trace-child [workspace]
  ; (println "BUA2" (select-keys workspace [:name :form :src-pos :xpanded-form :pos]))
  (when-let [children (some-> workspace :children deref not-empty)]
    (mapcat (fn [child]
              (println "BUA" (select-keys child [:name :form :xpanded-frm :id]))
              ; (println "KEYS" (keys child))
              (cond
                (:let-binds child) (let-binds child)

                (or (some-> child :name p (not= 'let*))
                    (and (p (:form child)) #_(-> child :form first p (not= 'let*))))
                ; :all
                (function child)

                :else (trace-child child)))
            children)))

            ;  (and (-> child :name nil?) (-> child :form nil?) (-> child :xpanded-frm nil?) (-> child :src-pos empty?))
            ;     ; (-> child :src-pos empty?))
            ;  (trace-child child)
             ;
            ;  :else (function child)))

(defn trace-str []
  (trace-child (sayid/ws-get-active!)))

(defn trace-inner [id]
  (when-let [call (-> [:id id] sayid/ws-query :children first)]
    (let [var (ns-resolve (or (:ns call) *ns*) (:name call))
          f (symbol (str (.name (.ns var)) "/" (.sym var)))
          args (:args call)]
      (println var)
      (println f)
      (sayid/ws-add-inner-trace-fn!* f)
      (apply (resolve f) args)
      (trace-child (some-> (sayid/ws-get-active!) :children deref last)))))


(declare child-as-str)

(defn replace-mappings [form mappings]
  (cond
    (vector? form) (mapv #(replace-mappings % mappings) form)
    (map? form) (->> form (map  (fn [[k v]] [(replace-mappings k mappings)
                                             (replace-mappings v mappings)]))
                          (into {}))
    (coll? form) (->> form
                      (map #(replace-mappings % mappings))
                      (apply list))
    :else (if (contains? mappings form)
            (get mappings form)
            form)))

(defn element-as-str [child mappings]
  (let [ret (:return child)
        mappings (merge mappings (some-> child :arg-map deref))
        children-els (child-as-str child mappings)
        display (if-let [args (:args child)]
                  (->> args (cons (:name child)) (s/join " ") (#(str "(" % ")")))
                  (:form child))
        with-replaced (cond-> display (not (string? display)) (replace-mappings mappings))
        display (or (:name child) (:form child))]
    {:display (str display)
     :ret ret
    ;  :id (:id child)
    ;  :mapping mappings
     :replaced (str with-replaced)
     :children children-els}))

(defn child-as-str [workspace mappings]
  (when-let [children (some-> workspace :children deref not-empty)]
    (->> children
         (mapcat #(if (or (:name %) (:form %))
                    [(element-as-str % mappings)]
                    (child-as-str % mappings)))
         vec)))
    ; (mapv #(element-as-str % mappings) children)))

(defn traced-as-str []
  (child-as-str (sayid/ws-get-active!) {}))
;
; (def f clj.__tracing-test__/f)
;
; m
; (contains? m (-> f (nth 2) (nth 2)))