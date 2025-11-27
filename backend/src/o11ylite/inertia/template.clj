;; ---------------------------------------------------------
;; o11ylite.inertia.template
;;
;; HTML template and Vite manifest loading for Inertia
;; ---------------------------------------------------------

(ns o11ylite.inertia.template
  (:require
   [clojure.java.io :as io]
   [com.brunobonacci.mulog :as mulog]
   [hiccup2.core :as h]
   [hiccup.util :refer [raw-string]]
   [jsonista.core :as json]))

;; ---------------------------------------------------------
;; Vite Manifest

(defn- read-manifest
  "Read and parse the Vite manifest.json file."
  [manifest-path]
  (when-let [resource (io/resource manifest-path)]
    (json/read-value (slurp resource) json/keyword-keys-object-mapper)))

(defn- get-manifest-entry
  "Get entry point data from manifest."
  [manifest entry-point]
  (get manifest (keyword entry-point)))

;; Prod: Vite extracts CSS to separate files, listed in manifest.
;; Must be loaded via <link> tags.
(defn- load-prod-assets
  "Load asset paths from Vite manifest for production."
  [manifest-path asset-base-url entry-point]
  (when-let [manifest (read-manifest manifest-path)]
    (when-let [entry (get-manifest-entry manifest entry-point)]
      (let [base (or asset-base-url "/")]
        {:js (str base (:file entry))
         :css (mapv #(str base %) (:css entry))
         :version (str (hash manifest))}))))

;; Dev: Vite injects CSS via JS as <style> tags (enables HMR).
;; No <link> tags needed - CSS comes through the entry point module.
(defn- load-dev-assets
  "Asset paths for development mode (Vite dev server)."
  [vite-dev-url entry-point]
  {:js (str vite-dev-url "/" entry-point)
   :vite-client (str vite-dev-url "/@vite/client")
   :css []
   :version "dev"})

;; ---------------------------------------------------------
;; Public API

(defn load-assets
  "Load assets based on config. Called once at startup.
   
   Config:
   - :dev?          - Development mode flag
   - :vite-dev-url  - Vite dev server URL (dev only)
   - :manifest-path - Path to manifest.json in resources (prod only)
   - :asset-base-url - Base URL for assets (prod only)
   - :entry-point   - Entry point file path"
  [{:keys [dev? vite-dev-url manifest-path asset-base-url entry-point]
    :or {entry-point "src/main.tsx"
         manifest-path ".vite/manifest.json"}}]
  (if dev?
    (load-dev-assets vite-dev-url entry-point)
    (let [assets (load-prod-assets manifest-path asset-base-url entry-point)]
      (when-not assets
        (mulog/log ::manifest-not-found :path manifest-path))
      assets)))

;; ---------------------------------------------------------
;; HTML Template

(defn- render-html
  "Generate the HTML template for Inertia."
  [{:keys [title assets page-data]}]
  (str
   (h/html
    (raw-string "<!DOCTYPE html>")
    [:html {:lang "en" :class "h-full"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title (or title "o11ylite")]
      ;; CSS
      (for [css-path (:css assets)]
        [:link {:rel "stylesheet" :href css-path}])
      ;; Vite client (dev only)
      (when-let [vite-client (:vite-client assets)]
        [:script {:type "module" :src vite-client}])]
     [:body.h-full
      [:div#app {:data-page page-data}]
      [:script {:type "module" :src (:js assets)}]]])))

(defn make-template-fn
  "Create a template function for use with Inertia middleware.
   Takes pre-loaded assets map (from load-assets)."
  [assets]
  (fn [page-data-json]
    (render-html {:assets assets
                  :page-data page-data-json})))
