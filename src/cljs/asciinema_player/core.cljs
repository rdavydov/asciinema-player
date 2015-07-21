(ns asciinema-player.core
  (:require [reagent.core :as reagent :refer [atom]]
            [asciinema-player.view :as view]
            [asciinema-player.util :as util]
            [cljs.core.async :refer [chan >! <! timeout close!]]
            [clojure.walk :as walk]
            [ajax.core :refer [GET]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn make-player-state [snapshot]
  (atom {
         :width 80
         :height 24
         :duration 148.297910690308
         :frames-url "/frames.json"
         :font-size "small"
         :theme "seti"
         :lines snapshot
         :play-from 0
         :current-time 23}))

(defn apply-changes [state changes]
  (if-let [line-changes (seq (:lines changes))]
    (update-in state [:lines] #(apply assoc % (apply concat line-changes)))
    state))

(defn coll->chan [coll]
  (let [ch (chan)]
    (go
      (loop [coll coll]
        (when-let [[delay data] (first coll)]
          (<! (timeout (* 1000 delay)))
          (>! ch data)
          (recur (rest coll))))
      (print "finished sending data")
      (close! ch))
    ch))

(defn prev-changes [frames seconds]
  (loop [frames frames
         seconds seconds
         candidate nil]
    (let [[delay changes :as frame] (first frames)]
      (if (or (nil? frame) (< seconds delay))
        candidate
        (recur (rest frames) (- seconds delay) (merge-with merge candidate changes))))))

(defn next-frames [frames seconds]
  (if (seq frames)
    (let [[delay changes] (first frames)]
      (if (<= delay seconds)
        (recur (rest frames) (- seconds delay))
        (cons [(- delay seconds) changes] (rest frames))))
    frames))

(defn elapsed-time-since [then]
  (/ (- (.getTime (js/Date.)) (.getTime then)) 1000))

(defn start-playback [state dispatch]
  (print "starting")
  (let [start (js/Date.)
        play-from (:play-from state)
        frames (next-frames (:frames state) play-from)
        stop-playback-chan (chan)
        changes-chan (coll->chan frames)
        timer-chan (coll->chan (repeat [0.3 true]))]
    (go-loop []
      (let [[v c] (alts! [changes-chan timer-chan stop-playback-chan])]
        (condp = c
          timer-chan (let [t (+ play-from (elapsed-time-since start))]
                       (dispatch [:update-state assoc :current-time t])
                       (recur))
          changes-chan (if v
                         (do
                           (dispatch [:update-state apply-changes v])
                           (recur))
                         (dispatch [:update-state #(-> % (dissoc :stop-playback-chan) (assoc :play-from 0))]))
          stop-playback-chan (let [t (+ play-from (elapsed-time-since start))]
                               (dispatch [:update-state assoc :play-from t])))))
    (go
      (<! stop-playback-chan)
      (print (str "finished in " (elapsed-time-since start))))
    (assoc state :stop-playback-chan stop-playback-chan)))

(defn stop-playback [state]
  (print "stopping")
  (close! (:stop-playback-chan state))
  (dissoc state :stop-playback-chan))

(defn fix-line-changes-keys [frame]
  (update-in frame [1 :lines] #(into {} (map (fn [[k v]] [(js/parseInt (name k) 10) v]) %))))

(defn frames-json->clj [frames]
  (map fix-line-changes-keys (walk/keywordize-keys frames)))

(defn fetch-frames [state dispatch]
  (let [url (:frames-url state)]
    (GET
     url
     {:format :json
      :handler #(dispatch [:frames-response %])
      :error-handler #(dispatch [:bad-response %])})
    (assoc state :loading true)))

(defn new-position [current-time total-time offset]
  (/ (util/adjust-to-range (+ current-time offset) 0 total-time) total-time))

(defn handle-toggle-play [state dispatch]
  (if (contains? state :frames)
    (if (contains? state :stop-playback-chan)
      (stop-playback state)
      (start-playback state dispatch))
    (fetch-frames state dispatch)))

(defn handle-seek [state _ [position]]
  (let [new-time (* position (:duration state))
        changes (prev-changes (:frames state) new-time)]
    (-> state
        (assoc :current-time new-time :play-from new-time)
        (apply-changes changes))))

(defn handle-rewind [state dispatch]
  (let [position (new-position (:current-time state) (:duration state) -5)]
    (handle-seek state dispatch [position])))

(defn handle-fast-forward [state dispatch]
  (let [position (new-position (:current-time state) (:duration state) 5)]
    (handle-seek state dispatch [position])))

(defn handle-frames-response [state dispatch [frames-json]]
  (dispatch [:toggle-play])
  (assoc state :loading false
               :frames (frames-json->clj frames-json)))

(defn handle-update-state [state _ [f & args]]
  (apply f state args))

(def event-handlers {:toggle-play handle-toggle-play
                     :seek handle-seek
                     :rewind handle-rewind
                     :fast-forward handle-fast-forward
                     :frames-response handle-frames-response
                     :update-state handle-update-state})

(defn process-event [state dispatch [event-name & args]]
  (if-let [handler (get event-handlers event-name)]
    (reset! state (handler @state dispatch args))
    (print (str "unhandled event: " event-name))))

(defn create-player-with-state [state dom-node]
  (let [events (chan)
        dispatch (fn [event] (go (>! events event)))]
    (go-loop []
      (when-let [event (<! events)]
        (process-event state dispatch event)
        (recur)))
    (reagent/render-component [view/player state dispatch] dom-node)
    (clj->js {:toggle (fn [] true)})))
