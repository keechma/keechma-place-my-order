(ns client.controllers.order-history
  (:require [keechma.controller :as controller]
            [client.edb :as edb]
            [cljs-http.client :as http]
            [cljs.core.async :as async :refer [<! put!]]
            [client.util :refer [unpack-req]]
            [clojure.set :as set])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn connect-socketio [ctrl]
  ;; This function listens to the websocket messages, and dispatches
  ;; them to the controllers in-chan. This way controller can react
  ;; to these messages.
  (try
    (let [conn (.io js/window)
          send (fn [command order]
                 (controller/execute ctrl command (js->clj order :keywordize-keys true)))]
      (.connect conn #js {:forceNew true})
      (.on conn "orders created" #(send :order-created %))
      (.on conn "orders updated" #(send :order-updated %))
      (.on conn "orders removed" #(send :order-removed %))
      (fn []
        (println "Disconnecting from socket.io")
        (.disconnect conn)))
    (catch :default e
      (do
        (println "io function doesn't exist.")
        (fn [])))))

(defn make-req [status]
  (http/get "/orders" {:query-params {:status status}}))

(defn load-order-history [app-db-atom]
  (go
    ;; Make four requests to load the order history
    ;; We save them all in the same collection, and filter it in the UI layer
    (let [reqs [(<! (make-req "new"))
                (<! (make-req "preparing"))
                (<! (make-req "delivery"))
                (<! (make-req "delivered"))]
          success (every? true? (map :success reqs))]
      (when success
        ;; Combine the data into one list and save it in the entity db
        (let [data (into [] (apply concat (map #(get-in % [:body :data]) reqs)))]
          (reset! app-db-atom
                  (edb/insert-collection @app-db-atom :orders :history data)))))))

(defn order-created [app-db-atom order]
  ;; When the order is created, we check if we already have this order in the
  ;; list (if this client was the source of the new order). If we don't have 
  ;; it in the list add it to the order history
  (let [current-history (edb/get-collection @app-db-atom :orders :history)
        ids (set (map :_id current-history))]
    (when-not (contains? ids (:_id order))
      (reset! app-db-atom
              (edb/insert-collection @app-db-atom :orders :history (concat [order] current-history))))))

(defn order-updated [app-db-atom order]
  ;; Update the order
  (reset! app-db-atom
          (edb/insert-item @app-db-atom :orders order)))

(defn order-removed [app-db-atom order]
  ;; Remove the order
  (reset! app-db-atom
          (edb/remove-item @app-db-atom :orders (:_id order))))

(defrecord Controller []
  controller/IController
  (params [_ route]
    ;; This controller is active only on the order-history page
    (when (= (get-in route [:data :page]) "order-history")
      true))
  (start [this params app-db]
    ;; When the controller is started, load the order history
    (controller/execute this :load-order-history)
    app-db)
  (handler [this app-db-atom in-chan out-chan]
    ;; When the controller is started connect to the websocket.
    ;; This way we can receive the messages when something changes
    ;; and update the application state accordingly. 
    ;;
    ;; connect-socketio function returns the function that can be
    ;; used to disconnect from the websocket.
    (let [disconnect (connect-socketio this)]
      (go (loop []
            (let [[command args] (<! in-chan)]
              (case command
                ;; When the controller is started, load the order-history
                :load-order-history (load-order-history app-db-atom)
                ;; When we get the order-created command from the websocket,
                ;; create a new order
                :order-created (order-created app-db-atom args)
                ;; When we get the order-updated command from the websocket,
                ;; update the order in the entity-db
                :order-updated (order-updated app-db-atom args)
                ;; When we get the order-removed command from the websocket,
                ;; remove the item from the entity-db. This will automatically 
                ;; remove it from any list that references it
                :order-removed (order-removed app-db-atom args)
                ;; Disconnect from the websocket
                :disconnect (disconnect)
                nil)
              (when command (recur)))))))
  (stop [this params app-db]
    ;; When the controller is stopped, send the command to disconnect from
    ;; the websocket and remove any data this controller has loaded.
    (controller/execute this :disconnect)
    (edb/remove-collection app-db :orders :history)))
