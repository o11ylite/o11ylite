;; ---------------------------------------------------------
;; o11ylite.notebook.store
;;
;; SQLite CRUD operations for notebooks and notebook_cells.
;; Query payloads are stored as nippy-frozen BLOBs, thawed on read.
;; ---------------------------------------------------------

(ns o11ylite.notebook.store
  (:require
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [taoensso.nippy :as nippy]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -now-ms
  []
  (System/currentTimeMillis))

(defn- -parse-query
  "Thaw a nippy-frozen query BLOB back into Clojure data."
  [^bytes query-bytes]
  (nippy/thaw query-bytes))

(defn- -serialize-query
  "Freeze a query map into a nippy byte array for BLOB storage."
  [query-map]
  (nippy/freeze query-map))

(defn- -parse-cell-row
  "Parse a raw cell DB row, thawing the query BLOB."
  [row]
  (when row
    (update row :query -parse-query)))

;; ---------------------------------------------------------
;; Notebook CRUD

(defn create-notebook!
  "Insert a new notebook. Returns the id."
  [sqlite id {:keys [name description]}]
  (let [now (-now-ms)]
    (jdbc/execute!
      sqlite
      ["INSERT INTO notebooks (id, name, description, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?)"
       id name description now now])
    id))

(defn update-notebook!
  "Update a notebook's metadata by ID."
  [sqlite id {:keys [name description]}]
  (let [now (-now-ms)]
    (jdbc/execute!
      sqlite
      ["UPDATE notebooks
        SET name = ?, description = ?, updated_at = ?
        WHERE id = ?"
       name description now id])))

(defn delete-notebook!
  "Delete a notebook by ID. Cells cascade-delete."
  [sqlite id]
  (jdbc/execute!
    sqlite
    ["DELETE FROM notebooks WHERE id = ?" id]))

(defn get-notebook-by-id
  "Fetch a notebook by ID with its cells. Returns nil if not found."
  [sqlite id]
  (when-let [notebook (jdbc/execute-one!
                        sqlite
                        ["SELECT * FROM notebooks WHERE id = ?" id]
                        {:builder-fn rs/as-unqualified-lower-maps})]
    (let [cells (->> (jdbc/execute!
                       sqlite
                       ["SELECT * FROM notebook_cells WHERE notebook_id = ? ORDER BY position ASC" id]
                       {:builder-fn rs/as-unqualified-lower-maps})
                     (mapv -parse-cell-row))]
      (assoc notebook :cells cells))))

(defn list-notebooks
  "Fetch all notebooks with cell counts, ordered by updated_at desc."
  [sqlite]
  (jdbc/execute!
    sqlite
    ["SELECT n.*, COUNT(c.id) AS cell_count
      FROM notebooks n
      LEFT JOIN notebook_cells c ON c.notebook_id = n.id
      GROUP BY n.id
      ORDER BY n.updated_at DESC"]
    {:builder-fn rs/as-unqualified-lower-maps}))

;; ---------------------------------------------------------
;; Cell CRUD

(defn create-cell!
  "Insert a new cell at the end of a notebook. Returns the cell id."
  [sqlite id {:keys [notebook_id title query_mode query pinned_from pinned_to]}]
  (let [now (-now-ms)
        max-pos (or (:max_pos (jdbc/execute-one!
                                sqlite
                                ["SELECT MAX(position) AS max_pos FROM notebook_cells WHERE notebook_id = ?" notebook_id]
                                {:builder-fn rs/as-unqualified-lower-maps}))
                    -1)
        position (inc max-pos)]
    (jdbc/execute!
      sqlite
      ["INSERT INTO notebook_cells (id, notebook_id, position, title, query_mode, query, pinned_from, pinned_to, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
       id notebook_id position title query_mode (-serialize-query query)
       pinned_from pinned_to now now])
    id))

(defn update-cell!
  "Update a cell's content by ID."
  [sqlite id {:keys [title query_mode query pinned_from pinned_to]}]
  (let [now (-now-ms)]
    (jdbc/execute!
      sqlite
      ["UPDATE notebook_cells
        SET title = ?, query_mode = ?, query = ?, pinned_from = ?, pinned_to = ?, updated_at = ?
        WHERE id = ?"
       title query_mode (-serialize-query query) pinned_from pinned_to now id])))

(defn delete-cell!
  "Delete a cell by ID."
  [sqlite id]
  (jdbc/execute!
    sqlite
    ["DELETE FROM notebook_cells WHERE id = ?" id]))

(defn move-cell!
  "Move a cell up or down within its notebook.
   Direction is :up or :down. Swaps positions with the adjacent cell."
  [sqlite cell-id direction]
  (when-let [cell (jdbc/execute-one!
                    sqlite
                    ["SELECT id, notebook_id, position FROM notebook_cells WHERE id = ?" cell-id]
                    {:builder-fn rs/as-unqualified-lower-maps})]
    (let [current-pos (:position cell)
          notebook-id (:notebook_id cell)
          target-pos (case direction
                       :up (dec current-pos)
                       :down (inc current-pos))
          neighbor (jdbc/execute-one!
                     sqlite
                     ["SELECT id, position FROM notebook_cells WHERE notebook_id = ? AND position = ?" notebook-id target-pos]
                     {:builder-fn rs/as-unqualified-lower-maps})]
      (when neighbor
        (let [now (-now-ms)]
          (jdbc/execute!
            sqlite
            ["UPDATE notebook_cells SET position = ?, updated_at = ? WHERE id = ?" target-pos now cell-id])
          (jdbc/execute!
            sqlite
            ["UPDATE notebook_cells SET position = ?, updated_at = ? WHERE id = ?" current-pos now (:id neighbor)]))))))

(defn touch-notebook!
  "Update a notebook's updated_at timestamp."
  [sqlite id]
  (let [now (-now-ms)]
    (jdbc/execute!
      sqlite
      ["UPDATE notebooks SET updated_at = ? WHERE id = ?" now id])))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])
  (def sqlite (:db/sqlite system))

  ;; Create a notebook
  (create-notebook! sqlite "nb-1"
                    {:name "Debug session"
                     :description "Investigating latency spike"})

  ;; List all
  (list-notebooks sqlite)

  ;; Get with cells
  (get-notebook-by-id sqlite "nb-1")

  ;; Add a cell
  (create-cell! sqlite "cell-1"
                {:notebook_id "nb-1"
                 :title "Error count"
                 :query_mode "events"
                 :query {:filter {:field "service" :op "=" :value "frontend"}
                         :aggregations [{:id "A" :field "*" :function "count"}]
                         :visualization {:type "table"}}})

  ;; Delete notebook (cascades)
  (delete-notebook! sqlite "nb-1")

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
