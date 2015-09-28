(ns afterglow.effects
  "Support functions for building the effects pipeline."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.effects.params :as params]
            [afterglow.rhythm :as rhythm]
            [afterglow.show-context :refer [*show*]]
            [afterglow.util :as util]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.profiling :as profiling :refer [pspy profile]])
  (:import [afterglow.rhythm MetronomeSnapshot]))

(defonce
  ^{:private true
    :doc "Protect protocols against namespace reloads"}
  _PROTOCOLS_
  (do
    (defprotocol IAssigner
  "Assign some attribute (color, attitude, channel value) to an
  element of a light show at a given point in time. Any previous
  assignment to this element will be supplied as an argument, and may
  be tweaked or ignored as needs dictate. The target will be a subtree
  of the show's fixtures, currently either a head or channel."
  (assign [this show ^MetronomeSnapshot snapshot target previous-assignment]
    "Calculate the value the show element should have at this moment
  in time. Return a value appropriate for the kind of assignment, e.g.
  color object, channel value."))

;; At each DMX frame generation, we will run through all the effects and ask them if they are still
;; active. If not, they will be removed from the list of active effects. For the remaining ones,
;; we obtain a list of assignments they want to make, and handle them as described above.
(defprotocol IEffect
    "The effect is the basic building block of an Afterglow light show.
  It generates a list of assignments that should be in effect at a
  given moment in the show. It can end on its own, or be asked to end.
  When asked, it may end immediately, or after some final activity,
  such as a fade."
    (^{:doc/format :markdown} still-active? [this show snapshot]
      "An inquiry about whether this effect is finished, and can be
      cleaned up. A `false` return value will remove the effect from
      the show.")
    (^{:doc/format :markdown} generate [this show snapshot]
      "List the asignments needed to implement the desired effect at
      this moment in time. Must return a sequence of
      `afterglow.effects.Assigner` objects which will be merged into
      the current frame based on their kind, target, and the effect's
      priority. If the effect currently has nothing to contribute, it
      may return an empty sequence.")
    (^{:doc/format :markdown} end [this show snapshot]
      "The effect has been asked to end. It should arrange to finish
      as soon as possible; return `true` if it can end immediately,
      and it will be removed from the show. Otherwise it will be
      allowed to continue running as it performs its graceful shutdown
      until [[still-active?]] reuthrns `false`. If the user asks to
      end the effect a second time during htis process, however, it
      will simply be removed from the show at that time."))))
(alter-meta! #'IEffect assoc :doc/format :markdown)

;; See https://github.com/brunchboy/afterglow/blob/master/doc/rendering_loop.adoc#assigners
;;
;; Afterglow runs through the list of effects in priority order; each will spit out some
;; number of assigners, which are a tuple identifying what is to be assigned, and a function
;; that can do the assigning, when provided with the show and current metronome snapshot:
(defrecord Assigner [^clojure.lang.Keyword kind ^clojure.lang.Keyword target-id target ^clojure.lang.IFn f]
  IAssigner
  (assign [this show snapshot target previous-assignment]
    (f show snapshot target previous-assignment)))

;; We will gather these into a map, whose keys are the assigner kind, and whose values, in turn, are
;; maps of assigners of that kind. Each key in the inner map is a target ID for which values are
;; to be assigned, and the values are the priority-ordered list of assigners to run on that target.
;; On each DMX frame we will run through these lists in parallel, and determine the final assignment
;; value which results for each target:
(defrecord Assignment [^clojure.lang.Keyword kind ^clojure.lang.Keyword target-id target value])

;; Finally, once that is done, the resulting assignments will be
;; resolved to DMX values by calling:
(defmulti resolve-assignment
  "Translates an attribute assignment (e.g. color, direction, channel
  value) for an element of a light show to the actual DMX values that
  will implement it. Since the value of the assignment may still be a
  dynamic parameter, the show and snapshot might be needed to resolve
  it."
  (fn [assignment show snapshot buffers]
    (:kind assignment)))

;; The effect is the basic building block of a light show. It has a name, which can appear in
;; the user interface for interacting with the effect, and three functions which are called
;; with the show and current metronome snapshot, as specified by the IEffect interface above.
(defrecord Effect [^String name ^clojure.lang.IFn active-fn
                   ^clojure.lang.IFn gen-fn ^clojure.lang.IFn end-fn]
  IEffect
  (still-active? [this show snapshot]
    (active-fn show snapshot))
  (generate [this show snapshot]
    (gen-fn show snapshot))
  (end [this show snapshot]
    (end-fn show snapshot)))

(defn always-active
  "An implementation of [[still-active?]] which simply always returns
  `true`, useful for effects which run until you ask them to end."
  {:doc/format :markdown}
  [show snapshot]
  true)

(defn end-immediately
  "An implementation of [[end]] which just reports that the effect
  is now finished by returning `true`. Useful for effects which can
  simply be removed as soon as they are asked to end."
  {:doc/format :markdown}
  [show snapshot]
  true)

(defn build-head-assigner
  "Returns an assigner of the specified type which applies the
  specified assignment function to the provided head or fixture."
  [kind head f]
  (Assigner. kind (keyword (str "i" (:id head))) head f))

(defn build-head-assigners
  "Returns a list of assigners of the specified type which apply an
  assignment function to all the supplied heads or fixtures."
  [kind heads f]
  (map #(build-head-assigner kind % f) heads))

(defn build-head-parameter-assigner
  "Returns an assigner of the specified kind which applies a parameter
  to the supplied head or fixture. If the parameter is not
  frame-dynamic, it gets resolved when creating this assigner.
  Otherwise, resolution is deferred to frame rendering time."
  [kind head param show snapshot]
  (let [resolved (params/resolve-unless-frame-dynamic param show snapshot head)]
    (build-head-assigner kind head (fn [show snapshot target previous-assignment] resolved))))

(defn build-head-parameter-assigners
  "Returns a list of assigners of the specified kind which apply a
  parameter to all the supplied heads or fixtures."
  [kind heads param show]
  (let [snapshot (rhythm/metro-snapshot (:metronome show))]
    (map #(build-head-parameter-assigner kind % param show snapshot) heads)))

(defn scene
  "Create an effect which combines multiple effects into one.

  Scenes are a way to group a list of effects to run as a single
  effect. All of their assigners are combined into a single list, in
  the order in which the effects were added to the scene. Because of
  the way Afterglow evaluates assigners, that means that if any
  constituent effects try to assign to the same target, the later ones
  will have a chance to override or blend with the earlier ones."
  {:doc/format :markdown}
  [scene-name & effects]
  (let [active (atom effects)]
    (Effect. scene-name
             (fn [show snapshot]
               (swap! active (fn [fx] (filterv #(still-active? % show snapshot) fx)))
               (seq @active))
             (fn [show snapshot] (mapcat #(generate % show snapshot) @active))
             (fn [show snapshot]
               (swap! active (fn [fx] () (filterv #(not (end % show snapshot)) fx)))
               (empty? @active)))))

(defn blank
  "Create an effect which does nothing. This can be useful, for
  example, when you want to use [[fade]] to fade into an effect from a
  state where there was simply nothing happening (or where earlier and
  lower-priority effects can show through)."
  {:doc/format :markdown}
  []
  (scene "Blank"))

(defmulti fade-between-assignments
  "Calculates an intermediate value between two attribute assignments
  of the same kind (e.g. color, direction, channel value) for an
  element of a light show. Most code will not call this directly, and
  will instead use the higher-level [[fade-assignment]] function to
  help set it up, or simply use a full-blown [[fade]] effect. This is
  the low-level mechanism which performs the fade calculations by
  dispatching to an appropriate implementation based on the `:kind`
  value of `from-assignment`, and it requires both `from-assignment`
  and `to-assignment` to be non-`nil` instances of
  `afterglow.effects.Assignment` of the same `:kind`.

  The amount contributed by each assignment
  is controlled by `fraction`, which can range from `0` (or less),
  meaning that only `from-assignment` is considered, to `1` (or
  greater), meaning that `to-assignment` is simply returned.
  Intermediate values will ideally result in a blend between the two
  assignments, with `0.5` representing an equal contribution from
  each. Since the value of the assignments may still be dynamic
  parameters, the show and snapshot might be needed to resolve them in
  order to calculate the blended value. Some kinds of assignment may
  not support blending, in which case the default implementation will
  simply switch from `from-assignment` to `to-assignment` once
  `fraction` reaches `0.5`."
  (fn [from-assignment to-assignment fraction show snapshot]
    (:kind from-assignment)))
(alter-meta! #'fade-between-assignments assoc :doc/format :markdown)

;; Provide a basic fallback implementation for assignment types which do not support blending.
;; This will switch from the first to the second assignment once fraction crosses the halfway
;; point.
(defmethod fade-between-assignments :default [from-assignment to-assignment fraction _ _]
  (if (util/float< fraction 0.5)
    from-assignment
    to-assignment))

(defn fade-assignment
  "Calculates an intermediate value between two attribute assignments
  of the same kind (e.g. color, direction, channel value) for an
  element of a light show. The values of `from-assignment` and
  `to-assignment` may either be instances of
  `afterglow.effects.Assignment` of the same `:kind`, or they may be
  `nil`, to indicate that the attribute is being faded to or from
  nothing. This function does the preparation and valiation needed in
  order to delegate safely to [[fade-between-assignments]] by
  promoting `nil` values to empty assignments of the appropriate
  `:kind` affecting the same target.

  The amount contributed by each assignment is controlled by
  `fraction`, which can range from `0` (or less), meaning that only
  `from-assignment` is considered, to `1` (or greater), meaning that
  `to-assignment` is simply returned. Intermediate values will ideally
  result in a blend between the two assignments, with `0.5`
  representing an equal contribution from each. Since the value of the
  assignments may still be dynamic parameters, the show and snapshot
  might be needed to resolve them in order to calculate the blended
  value. Some kinds of assignment may not support blending, in which
  case the default implementation will simply switch from
  `from-assignment` to `to-assignment` once `fraction` reaches `0.5`."
  {:doc/format :markdown}
  [from-assignment to-assignment fraction show snapshot]
  {:pre [(or (nil? from-assignment) (instance? Assignment from-assignment))
         (or (nil? to-assignment) (instance? Assignment to-assignment))
         (or (nil? from-assignment) (nil? to-assignment)
             (and (= (:kind from-assignment) (:kind to-assignment))
                   (= (:target-id from-assignment) (:target-id to-assignment))))]}
  (when (or from-assignment to-assignment) ; Return nil unless there is anything to fade
    (let [from (or from-assignment (map->Assignment (merge to-assignment {:value nil})))
          to (or to-assignment (map->Assignment (merge from-assignment {:value nil})))]
      (fade-between-assignments from to fraction show snapshot))))

(defn conditional-effect
  "Create an effect which makes the output of another effect
  conditional on whether a parameter has a non-zero value. Very useful
  when combined with [[variable-effect]] which can set that value to
  turn parts of a scene on or off independently. When `condition` has
  the value `0`, this effect does nothing; when `condition` has any
  other value, this effect behaves exactly like the effect passed in
  as `effect`."
  {:doc/format :markdown}
  [effect-name condition effect]
  {:pre [(some? *show*) (some? effect-name) (satisfies? IEffect effect)]}
  (params/validate-param-type condition Number)
  (let [snapshot (rhythm/metro-snapshot (:metronome *show*))
        condition (params/resolve-unless-frame-dynamic condition *show* snapshot)]
    ;; Could optimize for a non-dymanic condition, but that seems unlikely to be useful.
    (Effect. effect-name
             always-active
             (fn [show snapshot]
               (let [v (params/resolve-param condition show snapshot)]
                 (when-not (zero? v)
                   (generate effect show snapshot))))
             end-immediately)))

(defn- group-assigners
  "Organize a sequence of assigners into a map whose keys are a tuple
  of the assigner's `:kind` and `:target-id` and, whose values are all
  of the assigners with that type and target, in the same order in
  which they were found in the original sequence."
  {:doc/format :markdown}
  [assigners]
  (reduce (fn [results assigner]
            (update results [(:kind assigner) (:target-id assigner)] (fnil conj []) assigner))
          {} assigners))

(defn run-assigners
  "Returns the final assignment value that results from iterating over
  an assigner list that was gathered for a particular kind and target
  ID, feeding each intermediate result to the next assigner in the
  chain."
  ([show snapshot assigners]
   (run-assigners show snapshot assigners nil))
  ([show snapshot assigners previous-assignment]
   (pspy :run-assigners
         (when (seq assigners)
           (let [{:keys [kind target-id target]} (first assigners)
                 assignment (reduce (fn [result assigner]
                                      (assign assigner show snapshot target result))
                                    previous-assignment assigners)]
             (Assignment. kind target-id target assignment))))))

(defn- with-default-assignment
  "If the current assignment is empty, but there was a previous
  assignment value, turn it into an actual assignment based on the
  supplied template."
  [assignment template previous-assignment]
  (or assignment
      (when previous-assignment (map->Assignment (assoc template :value previous-assignment)))))

(defn fade
  "Create an effect which fades between two other effects as the value
  of a parameter changes from zero to one. When `phase` is `0` (or
  less), this effect simply acts as if it were `from-effect`. When
  `phase` is `1` (or greater), this effect acts like `to-effect`. For
  values of `phase` between `0` and `1`, a proportional linear blend
  between `from-effect` and `to-effect` is created, so that at `0.5`
  each effect contributes the same amount.

  Of course `phase` can be a dynamic parameter; interesting results
  can be obtained with oscillated and variable parameters. And either
  or both of the effects being faded between can be a scene, grouping
  many other effects.

  One of the effects may also be [[blank]], which allows the other
  effect to be faded into or out of existence. When fading to or from
  a blank effect, the fade allows any previous or lower-priority
  effects to pass through as it approaches the blank effect. The same
  is true when fading between effects that do not include all the same
  fixtures, or affect different aspects of fixtures."
  {:doc/format :markdown}
  [effect-name from-effect to-effect phase]
  {:pre [(some? *show*) (some? effect-name) (satisfies? IEffect from-effect) (satisfies? IEffect to-effect)]}
  (params/validate-param-type phase Number)
  (let [snapshot (rhythm/metro-snapshot (:metronome *show*))
        phase (params/resolve-unless-frame-dynamic phase *show* snapshot)]
    ;; Could optimize for a non-dymanic phase, but that seems unlikely to be useful.
    (Effect. effect-name
             always-active
             (fn [show snapshot]
               (let [v (params/resolve-param phase show snapshot)]
                 (cond
                   (util/float<= v 0.0) (generate from-effect show snapshot)
                   (util/float>= v 1.0) (generate to-effect show snapshot)
                   :else
                   ;; For each assigner kind and target generated by either the from or to effect,
                   ;; create a new assigner that will run through the list of assigners of that
                   ;; kind/target for both sides, then fade between the resulting assignments.
                   (let [from-groups (group-assigners (generate from-effect show snapshot))
                         to-groups (group-assigners (generate to-effect show snapshot))
                         keys (set (concat (keys from-groups) (keys to-groups)))]
                     (map (fn [k]
                            (let [from-assigners (get from-groups k)
                                  to-assigners (get to-groups k)
                                  template (or (first from-assigners) (first to-assigners))
                                  f (fn [show snapshot target previous-assignment]
                                      (let [from (with-default-assignment
                                                   (run-assigners show snapshot from-assigners previous-assignment)
                                                   template previous-assignment)
                                            to (with-default-assignment
                                                 (run-assigners show snapshot to-assigners previous-assignment)
                                                 template previous-assignment)]
                                        (:value (fade-assignment from to v show snapshot))))]
                              (map->Assigner (assoc template :f f))))
                          keys)))))
             end-immediately)))
