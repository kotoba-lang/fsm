(ns kami.fsm
  "hiccup for state machines — an animation/behaviour FSM described as EDN data.
   Pure + cross-platform (.cljc): states carry params (visual or otherwise), transitions
   fire on named events. Store it as datoms, fork it, retune without code.

     {:initial :idle
      :states  {:idle {:emissive 0.4 :scale 1.0}
                :move {:emissive 0.95 :scale 1.08}}
      :transitions [{:from :idle :to :move :on :moving}
                    {:from :move :to :idle :on :still}
                    {:from :any  :to :jump :on :jumping}]}")

(def default-player-fsm
  {:initial :idle
   :states  {:idle {:emissive 0.35 :scale 1.0}
             :move {:emissive 0.95 :scale 1.08}
             :jump {:emissive 1.4  :scale 1.16}}
   :transitions [{:from :any  :to :jump :on :jumping}
                 {:from :idle :to :move :on :moving}
                 {:from :jump :to :move :on :moving}
                 {:from :move :to :idle :on :still}
                 {:from :jump :to :idle :on :still}]})

(defn advance
  "Return the next state: the target of the first transition whose :from matches `state`
   (or :any) and whose :on event is present in `events` (a set); else stay put."
  [fsm state events]
  (or (some (fn [{:keys [from to on]}]
              (when (and (or (= from :any) (= from state)) (contains? events on)) to))
            (:transitions fsm))
      state))

(defn params
  "The param map for a state (e.g. {:emissive :scale})."
  [fsm state]
  (get-in fsm [:states state] {}))

(defn initial [fsm] (:initial fsm))
