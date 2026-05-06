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

;; ---------------------------------------------------------
;; Asset Loading

;; Prod: Vite extracts CSS to separate files, listed in manifest.
;; Must be loaded via <link> tags.
(defn- load-prod-assets
  "Load asset paths from Vite manifest for production."
  [manifest-path asset-base-url entry-point]
  (when-let [manifest (read-manifest manifest-path)]
    (when-let [entry (get-manifest-entry manifest entry-point)]
      {:js (str asset-base-url "/" (:file entry))
       :css (mapv #(str asset-base-url "/" %) (:css entry))
       :favicon (str asset-base-url "/favicon.svg")
       :version (str (hash manifest))})))

;; Dev: Vite injects CSS via JS as <style> tags (enables HMR).
;; No <link> tags needed - CSS comes through the entry point module.
;; With Caddy proxy, all assets go through /frontend/* path.
(defn- load-dev-assets
  "Asset paths for development mode (via Caddy proxy to Vite)."
  [asset-base-url entry-point]
  {:js (str asset-base-url "/" entry-point)
   :vite-client (str asset-base-url "/@vite/client")
   :react-refresh (str asset-base-url "/@react-refresh")
   :favicon (str asset-base-url "/favicon.svg")
   :css []
   :version "dev"})

;; ---------------------------------------------------------
;; Public API

(defn load-assets
  "Load assets based on config. Called once at startup.

   Config:
   - :dev?           - Development mode flag
   - :asset-base-url - Base URL for assets (e.g. '/frontend' or CDN URL)
   - :manifest-path  - Path to manifest.json in resources (prod only)
   - :entry-point    - Entry point file path"
  [{:keys [dev? manifest-path asset-base-url entry-point]
    :or {entry-point "src/main.tsx"
         manifest-path ".vite/manifest.json"
         asset-base-url "/frontend"}}]
  (if dev?
    (load-dev-assets asset-base-url entry-point)
    (let [assets (load-prod-assets manifest-path asset-base-url entry-point)]
      (when-not assets
        (mulog/log ::manifest-not-found :o11ylite.inertia.manifest_path manifest-path))
      assets)))

;; ---------------------------------------------------------
;; HTML Template

;; React Refresh preamble - required for HMR when serving HTML from backend.
;; Sets up window.$RefreshReg$ and $RefreshSig$ before any React code loads.
;; Per: https://vite.dev/guide/backend-integration.html
(defn- react-refresh-preamble
  [react-refresh-url]
  (str "import RefreshRuntime from '" react-refresh-url "';
RefreshRuntime.injectIntoGlobalHook(window);
window.$RefreshReg$ = () => {};
window.$RefreshSig$ = () => (type) => type;
window.__vite_plugin_react_preamble_installed__ = true;"))

(defn- render-html
  "Generate the HTML template for Inertia."
  [{:keys [title assets page-data]}]
  (str
    (h/html
      (raw-string "<!DOCTYPE html>")
      [:html {:lang "en" :class "h-full dark"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:title (or title "o11ylite")]
        [:link {:rel "icon" :href (:favicon assets) :type "image/svg+xml"}]
        ;; CSS (prod only - dev injects via JS)
        (for [css-path (:css assets)]
          [:link {:rel "stylesheet" :href css-path}])
        ;; Vite client (dev only)
        (when-let [vite-client (:vite-client assets)]
          [:script {:type "module" :src vite-client}])
        ;; React Refresh preamble (dev only) - must run before app code
        (when-let [react-refresh (:react-refresh assets)]
          [:script {:type "module"}
           (raw-string (react-refresh-preamble react-refresh))])]
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
