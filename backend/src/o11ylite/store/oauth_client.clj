;; ---------------------------------------------------------
;; o11ylite.store.oauth-client
;;
;; Persistence for clients registered through Dynamic Client
;; Registration (RFC 7591). Only public clients are stored — no
;; secrets — so the redirect_uris column is the only credential.
;; redirect_uris is a JSON array kept verbatim: the authorization
;; endpoint matches a requested redirect_uri exactly.
;; ---------------------------------------------------------

(ns o11ylite.store.oauth-client
  (:require
    [jsonista.core :as json]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    [java.net URI]))

;; ---------------------------------------------------------
;; Configuration

(def ^:private max-clients
  "Upper bound on stored registrations. Registration is open, so the
   table is pruned to the newest N rows instead of growing forever."
  1000)

;; ---------------------------------------------------------
;; Private Helpers

(defn- -client
  "Build the client map returned to callers. Self-registered clients have
   no verified identity, so the consent page shows the client_uri host."
  [{:keys [client-id client-name client-uri client-host redirect-uris scope]}]
  {:client-id client-id
   :client-name client-name
   :client-uri client-uri
   :client-host (or client-host
                    (when client-uri (try (.getHost (URI. client-uri))
                                          (catch Exception _ nil))))
   :redirect-uris redirect-uris
   :scope scope
   :self-registered? true})

(defn- -row->client
  [row]
  (when row
    (-client {:client-id (:client_id row)
              :client-name (:client_name row)
              :client-uri (:client_uri row)
              :redirect-uris (json/read-value (:redirect_uris row))
              :scope (:scope row)})))

(defn- -prune!
  "Keep only the newest max-clients registrations."
  [sqlite]
  (jdbc/execute!
    sqlite
    ["DELETE FROM oauth_clients
      WHERE client_id NOT IN
        (SELECT client_id FROM oauth_clients ORDER BY created_at DESC LIMIT ?)"
     max-clients]))

;; ---------------------------------------------------------
;; Public API

(defn create!
  "Insert a registered client. Returns the stored client map."
  [sqlite {:keys [client-id client-name client-uri client-host redirect-uris scope]}]
  (jdbc/execute!
    sqlite
    ["INSERT INTO oauth_clients
        (client_id, client_name, client_uri, redirect_uris, scope, created_at)
      VALUES (?, ?, ?, ?, ?, ?)"
     client-id
     client-name
     client-uri
     (json/write-value-as-string redirect-uris)
     scope
     (System/currentTimeMillis)])
  (-prune! sqlite)
  (-client {:client-id client-id
            :client-name client-name
            :client-uri client-uri
            :client-host client-host
            :redirect-uris redirect-uris
            :scope scope}))

(defn get-client
  "Fetch a registered client by id. Returns nil when it was never
   registered (or has been pruned)."
  [sqlite client-id]
  (some-> (jdbc/execute-one!
            sqlite
            ["SELECT client_id, client_name, client_uri, redirect_uris, scope
              FROM oauth_clients WHERE client_id = ?"
             client-id]
            {:builder-fn rs/as-unqualified-lower-maps})
          -row->client))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (create! (:db/sqlite integrant.repl.state/system)
           {:client-id "o11y_test"
            :client-name "Test Client"
            :client-uri "https://client.example.com"
            :redirect-uris ["https://client.example.com/cb"]
            :scope "read"})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
