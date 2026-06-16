(ns hooks.zigar
  "clj-kondo hooks for Zigar's defining macros."
  (:require [clj-kondo.hooks-api :as api]))

(defn defrecordz
  "Rewrite a `defrecordz` form to the `defrecord` clj-kondo understands, so
  the record type and its `->Name`/`map->Name` factories resolve with the
  right field arity. A `defrecordz` carries an optional docstring and a
  field list pairing each name with a boundary type; only the names become
  record fields."
  [{:keys [node]}]
  (let [[_ name-node & tail] (:children node)
        tail        (if (api/string-node? (first tail)) (rest tail) tail)
        fields-node (first tail)
        field-syms  (->> (:children fields-node)
                         (partition 2)
                         (map first))
        new-node    (api/list-node
                     (list (api/token-node 'clojure.core/defrecord)
                           name-node
                           (api/vector-node (vec field-syms))))]
    {:node (with-meta new-node (meta node))}))
