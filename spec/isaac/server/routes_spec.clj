(ns isaac.server.routes-spec
  (:require
    [isaac.comm.registry :as comm-registry]
    [isaac.fs :as fs]
    [isaac.hooks]
    [isaac.module.loader :as module-loader]
    [isaac.server.routes :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(defn exact-handler [_request]
  {:status 201 :body "exact"})

(defn post-handler [request]
  {:body request :status 202})

(describe "Routes"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (binding [comm-registry/*registry* (atom (comm-registry/fresh-registry))
                sut/*registry*           (atom (sut/fresh-registry))]
        (it))))

  (it "dispatches exact routes registered at runtime"
    (sut/register-route! :get "/bibelot" #'exact-handler)
    (should= {:status 201 :body "exact"}
             (sut/handler {:request-method :get :uri "/bibelot"})))

  (it "passes request to exact route handlers"
    (sut/register-route! :post "/thingy" #'post-handler)
    (let [request {:request-method :post :uri "/thingy" :body "payload"}
          opts    {:cfg {:mode :test}}]
      ;; the handler threads the server's config into the request as a value
      (should= {:status 202 :body (assoc request :config {:mode :test})}
               (sut/handler opts request))))

  (it "routes GET /status to status handler"
    (let [response (sut/handler {:request-method :get :uri "/status"})]
      (should= 200 (:status response))))

  (it "registers the hooks route from the hooks manifest's :isaac.server/route berth"
    ;; Phase 5 of brth (isaac-8v1n): routes flow through
    ;; process-manifest-berths!. Phase 5b (isaac-v5js) folded
    ;; route-prefix into route — the hooks contribution is now a
    ;; clout `/hooks/*` pattern with :method :*, so the matched
    ;; suffix lands at :route-params {:* "bibelot"}.
    (with-redefs [isaac.hooks/handler (fn [request]
                                        {:status 204 :body request})]
      (module-loader/clear-activations!)
      (let [request {:request-method :post :uri "/hooks/bibelot"}
            opts    {:cfg {:mode :test}}]
        (module-loader/process-manifest-berths! (module-loader/builtin-index))
        (should= {:status 204
                  :body   (assoc request :config {:mode :test} :route-params {:* "bibelot"})}
                 (sut/handler opts request)))))

  (it "returns 404 for unknown paths"
    (let [response (sut/handler {:request-method :get :uri "/unknown"})]
      (should= 404 (:status response))))

  (it "returns 404 for unknown methods on known paths"
    (let [response (sut/handler {:request-method :post :uri "/status"})]
      (should= 404 (:status response))))

  (it "GET /error throws an exception"
    (should-throw (sut/handler {:request-method :get :uri "/error"})))

  )
