(ns andel.editor
  (:require [clojure.core.async :as a]
            [clojure.set]

            [cljsjs.create-react-class]
            [cljsjs.react.dom]
            [garden.core :as g]
            [garden.stylesheet :refer [at-keyframes]]

            [andel.styles :as styles]
            [andel.theme :as theme]
            [andel.lexer :as lexer]
            [andel.text :as text]
            [andel.intervals :as intervals]
            [andel.utils :as utils]
            [andel.tree :as tree]
            [cljsjs.element-resize-detector])
  (:require-macros [reagent.interop :refer [$ $!]]
                   [cljs.core.async.macros :refer [go]]))


(defn font->str [font-name height]
  (str height "px " font-name))


(defn measure [font-name height spacing]
  (let [canvas (js/document.createElement "canvas")
        ctx    (.getContext canvas "2d")]
    (set! (.-font ctx) (font->str font-name height))
    (let [width (.-width (.measureText ctx "X"))]
      {:width     width
       :height    height
       :spacing   spacing
       :font-name font-name})))


(defn measure-async [font-name size spacing]
  (go
   (measure font-name size spacing)
   (loop []
     (if (.. js/document
             -fonts
             (check (font->str font-name size)))
       (measure font-name size spacing)
       (do
         (a/<! (a/timeout 100))
         (recur))))))


(defn load-editors-common! []
  (let [loaded (a/promise-chan)]
    (go
     (dotimes [pr (map styles/include-script
                       ["codemirror/addon/runmode/runmode-standalone.js"
                        "codemirror/addon/runmode/runmode-standalone.js"
                        "codemirror/mode/javascript/javascript.js"
                        "codemirror/mode/clike/clike.js"
                        "codemirror/mode/clojure/clojure.js"
                        "bloomfilter.js/bloomfilter.js"])]
       (a/<! pr))
     (let [{:keys [height font-name] :as font-metrics} (a/<! (measure-async "Fira Code" 16 3))]
       (styles/defstyle
         (garden.stylesheet/at-keyframes "blinker"
                                         ["50%" {:opacity "0"}]))
       (styles/defstyle :line-text
                        [:.line-text
                         {:position      :absolute
                          :padding       "0px"
                          :margin        "0px"
                          :background    "inherit"
                          :border-radius "0px"
                          :border-width  "0px"
                          :font-family   font-name
                          :font-size     (styles/px height)
                          :color         theme/foreground
                          :left          0
                          :line-height   "normal"
                          :user-select   :none
                          :top           0}])
       (a/>! loaded {:font-metrics font-metrics})))
    loaded))


(defonce *editors-common (load-editors-common!))

(def token-class
  (let [tokens-cache #js {}]
    (fn [tt]
      (when tt
        (if-let [c (aget tokens-cache (name tt))]
          c
          (let [class (name tt)]
            (styles/defstyle tt [(str "." class) (theme/token-styles tt)])
            (aset tokens-cache (name tt) class)
            class))))))

(defn tokens->markers [tokens]
  (let [tokens-count (count tokens)]
    (loop [i      0
           offset 0
           result (make-array tokens-count)]
      (if (< i tokens-count)
        (let [[len tt] (aget tokens i)
              m        (intervals/->Marker offset (+ offset len) false false (intervals/->Attrs nil nil (token-class tt) 0))]
          (aset result i m)
          (recur (inc i)
            (+ offset len)
            result))
        result))))

(defn deliver-lexems! [{:keys [req-ts tokens index text]} state-ref]
  (let [res (swap! state-ref
                   (fn [state]
                     (let [timestamp (-> state :document :timestamp)]
                       (if (= timestamp req-ts)
                         (-> state
                             (assoc-in [:document :lines index :tokens] tokens)
                             ;; (assoc-in [:document :hashes (hash text)] markers)
                             (assoc-in [:document :first-invalid] (inc index)))
                         state))))]
    (= (get-in res [:document :timestamp]) req-ts)))


(defn run-lexer-loop! [state-ref]
  (let [{:keys [document] :as state}    @state-ref
        {:keys [modespec lexer-broker]} document
        {:keys [input output]}          (lexer/new-lexer-worker modespec)]
    (go
     (loop [state      nil
            line       0
            start-time 0]
       (let [elapsed    (- (.getTime (js/Date.)) start-time)
             next-text  (when (< line (text/lines-count (get-in state [:document :text])))
                          (some-> state :document :text (text/line-text line)))
             [val port] (a/alts!
                          (cond-> [lexer-broker output]
                            (some? next-text) (conj
                                                [input
                                                 {:index  line
                                                  :text   next-text
                                                  :req-ts (get-in state [:document :timestamp])}]))
                          :priority true)]
         (let [start-time' (if (< 3 elapsed)
                             (do (a/<! (a/timeout 1))
                               (.getTime (js/Date.)))
                             start-time)]
           (cond
             (= port lexer-broker) (recur val (get-in val [:document :first-invalid]) start-time')
             (= port output)       (let [delivered? (deliver-lexems! val state-ref)]
                                     (recur state (if delivered? (inc line) line) start-time'))
             (= port input)        (recur state line start-time'))))))))


(defn attach-lexer! [state-ref]
  (run-lexer-loop! state-ref)
  (add-watch state-ref :lexer
             (fn [_ _ old-s new-s]
               (let [old-ts (get-in old-s [:document :timestamp])
                     new-ts (get-in new-s [:document :timestamp])
                     broker (get-in new-s [:document :lexer-broker])]
                 (when (not= old-ts new-ts)
                   (a/put! broker new-s))))))

(defn ready-to-view? [editor-state]
  (some? (get-in editor-state [:viewport :metrics])))

;; renderring

(defn el
  ([tag props]
   (.createElement js/React tag props))
  ([tag props children]
   (.createElement js/React tag props children)))


(defn div [class style]
  (doto (js/document.createElement "div")
        (.setAttribute "class" class)
        (.setAttribute "style" style)))

(defn span [class text]
  (doto (js/document.createElement "span")
        (.setAttribute "class" class)
        (.appendChild (js/document.createTextNode text))))

(defn style [m]
  (reduce-kv
    (fn [s k v]
      (str s (name k) ":" (if (keyword? v) (name v) v) ";"))
    nil m))

(defn make-node [tag]
  (js/document.createElement (name tag)))

(defn make-text-node [s]
  (js/document.createTextNode s))

(defn assoc-attr! [e a v]
  (.setAttribute e (name a) (if (keyword? v) (name v) v))
  e)

(defn conj-child! [e c]
  (.appendChild e c)
  e)

(defn infinity? [x] (keyword? x))

(defn render-selection [[from to] {:keys [width] :as metrics}]
  (div nil
       (style
         {:background-color theme/selection
          :height           (styles/px (utils/line-height metrics))
          :position         :absolute
          :top              (styles/px 0)
          :left             (if (infinity? to)
                              0
                              (styles/px (* from width)))
          :margin-left      (when (infinity? to) (styles/px (* from width)))
          :width            (if (infinity? to)
                              "100%"
                              (styles/px (* (- to from) width)))})))


(defn render-caret [col {:keys [width] :as metrics}]
  (doto (div nil nil)
        (.appendChild
          (div nil
               (style
                 {:height           (styles/px (inc (utils/line-height metrics)))
                  :width            "100%"
                  :background-color (:bg-05 theme/zenburn)
                  :position         :absolute
                  :left             0
                  :top              0
                  :z-index          "-1"})))
        (.appendChild
          (div nil
               (style
                 {:width            (styles/px 1)
                  :animation        "blinker 1s cubic-bezier(0.68, -0.55, 0.27, 1.55) infinite"
                  :top              0
                  :background-color "white"
                  :position         :absolute
                  :left             (styles/px (* col width))
                  :height           (styles/px (inc (utils/line-height metrics)))})))))

(defn push! [a x]
  (.push a x)
  a)

(defn append-child! [e c]
  (.appendChild e c)
  e)

(defn measure-time [name f]
  (fn [& args]
    (let [start (str name "-start")
          end   (str name "-end")]
      (js/performance.mark start)
      (let [result (apply f args)]
        (js/performance.mark end)
        (js/performance.measure (str name) start end)
        result))))

(defn shred-markup [type]
  (fn [rf]
        (let
          [pendings     (array)
          *last-pos    (atom 0)
          next-pending (fn [pendings] (reduce (fn [c p] (if (or (nil? c) (< (.-to p) (.-to c))) p c)) nil pendings))
          make-class   (fn [markers] (reduce (fn [c m] (str c " " (case type
                                                                    :background (some-> m (.-attrs) (.-background))
                                                                    :foreground (some-> m (.-attrs) (.-foreground))))) nil pendings))
          js-disj!     (fn [arr v]
                         (let [idx (.indexOf arr v)]
                           (if (< -1 idx)
                             (do
                               (.splice arr idx 1)
                               arr)
                             arr)))]
      (fn
        ([] (rf))
        ([res m]
         (loop [res res]
           (let [p         (next-pending pendings)
                 last-pos  @*last-pos
                 new-class (make-class pendings)]
             (if (or (nil? p) (< (.-from m) (.-to p)))
               (do
                 (push! pendings m)
                 (reset! *last-pos (.-from m))
                 (if (identical? last-pos (.-from m))
                   res
                   (rf res (- (.-from m) last-pos) new-class)))

               (do
                 (js-disj! pendings p)
                 (reset! *last-pos (.-to p))
                 (if (identical? last-pos (.-to p))
                   (recur res)
                   (recur (rf res (- (.-to p) last-pos) new-class))))))))
        ([res]
         (rf
           (loop [res res]
             (let [p         (next-pending pendings)
                   last-pos  @*last-pos
                   new-class (make-class pendings)]
               (if (some? p)
                 (do
                   (js-disj! pendings p)
                   (reset! *last-pos (.-to p))
                   (if (identical? last-pos (.-to p))
                     (recur res)
                     (recur (rf res (- (.-to p) last-pos) new-class))))
                 res)))))))))

(def real-dom
  (js/createReactClass
   #js {:componentWillUpdate
        (fn [next-props next-state]
          (this-as this
            (let [elt   ($ next-props :dom)
                  node  (.findDOMNode js/ReactDOM this)
                  child (.-firstChild node)]
              (when (some? child)
                (.removeChild node child))
              (.appendChild node elt))))
        :componentDidMount
        (fn []
          (this-as this
            (let [elt  ($ ($ this :props) :dom)
                  node (.findDOMNode js/ReactDOM this)]
              (.appendChild node elt))))

        :render
        (fn [_] (el "div" #js {:key "realDOM"}))}))

(defn multiplex [rf1 rf2]
  (fn [rf]
    (fn
      ([] (transient [(rf1) (rf2)]))
      ([result input]
       (assoc! result
               0 (rf1 (get result 0) input)
               1 (rf2 (get result 1) input)))
      ([result] (rf (rf) [(rf1 (get result 0)) (rf2 (get result 1))])))))

(defn transduce2
  ([xform f coll]
   (let [r-f (xform f)]
     (r-f (reduce r-f (r-f) coll))))
  ([xform f init coll]
   (transduce2 xform
               (fn
                 ([] init)
                 ([acc input] (f acc input)))
               coll)))

(comment

  (defn fancy [start]
    (fn [rf]
      (fn
        ([] [start (rf)])
        ([[start result] input]
         [(inc start) (rf result (str input start))])
        ([result] (second result)))))


  (transduce2
    (comp
     (map (fn [x] (str x x)))
     (fancy 10)
     (map clojure.string/upper-case))
    conj [] ["a" "a" "b" "c"]))

(defn merge-tokens [lexer-markers]
  (fn [rf]
    (let [lexer-markers-count (count lexer-markers)
          *i                  (atom 0)]
      (fn
        ([] (rf))
        ([acc m]
         (loop [i   @*i
                acc acc]
           (if (and (< i lexer-markers-count) (< (.-from (aget lexer-markers i)) (.-from m)))
             (recur (inc i) (rf acc (aget lexer-markers i)))
             (do
               (reset! *i i)
               (rf acc m)))))
        ([acc]
         (loop [i   @*i
                acc acc]
           (if (< i lexer-markers-count)
             (recur (inc i)
               (rf acc (aget lexer-markers i)))
             (rf acc))))))))

(defrecord LineInfo
  [text
   caret
   selection
   foreground
   background])

(defn build-line-info [{:keys [caret selection markers-zipper start-offset end-offset deleted-markers tokens text-zipper]}]
  (let [markup              (intervals/xquery-intervals markers-zipper start-offset end-offset)
        text                (text/text text-zipper (- end-offset start-offset))
        text-length         (count text)
        to-relative-offsets (map
                              (fn [marker]
                                (intervals/->Marker (min text-length (max 0 (- (.-from marker) start-offset)))
                                                    (min text-length (max 0 (- (.-to marker) start-offset)))
                                                    false
                                                    false
                                                    (.-attrs marker))))
        collect-to-array    (fn
                              ([] (array))
                              ([r t style]
                               (doto r
                                 (.push t)
                                 (.push style)))
                              ([r] r))
        bg-xf               (comp
                              (filter (fn [marker] (some-> marker (.-attrs) (.-background))))
                              (shred-markup :background))
        fg-xf               (comp
                              (filter (fn [marker] (some-> marker (.-attrs) (.-foreground))))
                              (shred-markup :foreground))]

    (transduce2
     (comp
      (remove (fn [m] (contains? deleted-markers (.-id (.-attrs m)))))
      to-relative-offsets
      (merge-tokens (tokens->markers tokens))
      (multiplex (bg-xf collect-to-array)
                 (fg-xf collect-to-array)))
     (fn [acc [bg fg]]
       (LineInfo. text caret selection fg bg))
     nil
     markup)))

(defn line-info-eq? [x y]
  (let [array-eq? (fn [a b]
                    (and (= (count a) (count b))
                         (loop [i 0]
                           (if (< i (count a))
                             (if (= (aget a i) (aget b i))
                               (recur (inc i))
                               false)
                             true))))]
    (and (= (.-caret x) (.-caret y))
         (= (.-selection x) (.-selection y))
         (= (.-text x) (.-text y))
         (array-eq? (.-foreground x) (.-foreground y))
         (array-eq? (.-background x) (.-background y)))))


(def render-raw-line
  (js/createReactClass
    #js {:shouldComponentUpdate (fn [new-props new-state]
                                  (this-as this
                                    (let [old-props (aget this "props")
                                          old-line-info (aget old-props "lineInfo")
                                          new-line-info (aget new-props "lineInfo")]
                                      (or (not (line-info-eq? old-line-info new-line-info))
                                          (not (= (aget old-props "metrics") (aget new-props "metrics")))))))
          :render (fn [_]
                    (this-as this
                            (let [props (aget this "props")
                                  metrics (aget props "metrics")
                                  line-info (aget props "lineInfo")
                                  text      (.-text line-info)
                                  selection (.-selection line-info)
                                  bg-markup (.-background line-info)
                                  fg-markup (.-foreground line-info)
                                  caret (.-caret line-info)
                                  {:keys [width height]} metrics
                                  bg-dom (loop [i 0
                                                col 0
                                                dom (js/document.createElement "div")]
                                           (if (< i (count bg-markup))
                                             (let [len (aget bg-markup i)
                                                   class (aget bg-markup (inc i))]
                                               (if (some? class)
                                                 (recur (+ i 2)
                                                        (+ col len)
                                                        (append-child! dom (div class
                                                                                (str "left: " (* col width) "px;"
                                                                                     "width:" (* width len) "px;"
                                                                                     "height:100%; position : absolute;"))))
                                                 (recur (+ i 2)
                                                        (+ col len)
                                                        dom)))
                                             dom))
                                  fg-dom (loop [i 0
                                                col 0
                                                dom (doto (js/document.createElement "pre")
                                                      (.setAttribute "class" "line-text"))]
                                           (if (< i (count fg-markup))
                                             (let [len (aget fg-markup i)
                                                   class (aget fg-markup (inc i))]
                                               (recur (+ i 2)
                                                      (+ col len)
                                                      (append-child! dom (span class (subs text col (+ col len))))))
                                             (if (< col (count text))
                                               (append-child! dom (span nil (subs text col (count text))))
                                               dom)))]
                              (el real-dom #js {:dom (-> (div "render-line" nil)
                                                         (cond-> (some? selection)
                                                                 (append-child! (render-selection selection metrics)))
                                                         (append-child! bg-dom)
                                                         (append-child! fg-dom)
                                                         (cond-> (some? caret)
                                                                 (append-child! (render-caret caret metrics))))}))))}))

(def render-line
  (js/createReactClass
   #js {:shouldComponentUpdate
        (fn [new-props new-state]
          (this-as this
            (let [old-props (aget ($ this :props) "props")
                  new-props (aget new-props "props")
                  old-start (:start-offset old-props)
                  old-end   (:end-offset old-props)
                  new-start (:start-offset new-props)
                  new-end   (:end-offset new-props)]
              (not
                (and (= (- old-end old-start) (- new-end new-start))
                     (tree/compare-zippers (:text-zipper old-props) (:text-zipper new-props) (text/by-offset (max old-end new-end)))
                     (tree/compare-zippers (:markers-zipper old-props) (:markers-zipper new-props) (intervals/by-offset (max old-end new-end)))
                     (= (:caret old-props) (:caret new-props))
                     (= (:selection old-props) (:selection new-props))
                     (identical? (:deleted-markers old-props) (:deleted-markers new-props))
                     (identical? (:tokens old-props) (:tokens new-props)))))))
        :render (fn [_]
                  (this-as this
                    (let [props (aget (aget this "props") "props")
                          {:keys [metrics]} props
                          line-info (build-line-info props)]

                      (el render-raw-line #js{:lineInfo line-info
                                              :metrics metrics}))))}))


(defn line-selection [[from to] [line-start-offset line-end-offset]]
  (cond (and (< from line-start-offset) (< line-start-offset to))
    (if (< line-end-offset to)
      [0 :infinity]
      [0 (- to line-start-offset)])
    (and (<= line-start-offset from) (<= from line-end-offset))
    [(- from line-start-offset)
     (if (<= to line-end-offset)
       (- to line-start-offset)
       :infinity)]
    :else nil))

(defonce erd (js/elementResizeDetectorMaker #js{:strategy "scroll"}))

(defn setup-erd [dom-node listener]
  (.listenTo erd dom-node listener))

(defn remove-erd [dom-node listener]
  (.removeListener erd dom-node listener))

(def scroll
  (js/createReactClass
   #js {:shouldComponentUpdate
        (fn [new-props new-state]
          (this-as this
            (let [old-props ($ this :props)]
              (not= (aget old-props "props") (aget new-props "props")))))
        :componentDidMount
        (fn []
          (this-as cmp
            (let [props     (aget (aget cmp "props") "props")
                  node      (.findDOMNode js/ReactDOM cmp)
                  on-resize (fn [node]
                              (let [height ($ node :offsetHeight)
                                    width  ($ node :offsetWidth)]
                                ;; paint flashing on linux when viewport bigger than screen size
                                ((props :onResize) (- width 0) (- height 3))))]
              (aset cmp "onResize" on-resize)
              (on-resize node)
              (setup-erd node on-resize))))
        :componentWillUnmount
        (fn []
          (this-as cmp
            (let [node (.findDOMNode js/ReactDOM cmp)]
              (remove-erd node (aget cmp "onResize")))))
        :render (fn [_]
                  (this-as this
                    (let [props          (aget (aget this "props") "props")
                          child          (props :child)
                          on-resize      (props :onResize)
                          on-mouse-wheel (props :onMouseWheel)]
                      (el "div"
                          #js {:key     "scroll"
                               :style   #js {:display  "flex"
                                             :flex     "1"
                                             :overflow :hidden}
                               :onWheel (fn [evt]
                                          (on-mouse-wheel (.-deltaX evt) (.-deltaY evt))
                                          (.preventDefault evt))}
                          [child]))))}))

(defn editor-viewport [props]
  (let [state                              ($ props :editorState)
        on-mouse-down                      (aget props "onMouseDown")
        on-drag-selection                  (aget props "onDragSelection")
        {:keys [editor document viewport]} state
        {:keys [pos view-size metrics]}    viewport
        line-height                        (utils/line-height metrics)
        {:keys [text lines hashes]}        document
        {:keys [caret selection]}          editor
        [_ from-y-offset]                  pos
        [w h]                              view-size
        left-columnt                       0
        top-line                           (int (/ from-y-offset line-height))
        bottom-line                        (+ top-line (int (/ h line-height)) 2)
        y-shift                            (- (* line-height (- (/ from-y-offset line-height) top-line)))
        caret-offset                       (get caret :offset)
        _                                  (styles/defstyle :render-line
                                                            [:.render-line
                                                             {:height   (styles/px (utils/line-height metrics))
                                                              :position :relative
                                                              :overflow :hidden}])
        deleted-markers                    (-> state :document :deleted-markers)
        children                           (loop [text-zipper    (text/scan-to-line-start (text/zipper text) top-line)
                                                  markers-zipper (intervals/zipper (:markup document))
                                                  line-number    top-line
                                                  result         #js[]]
                                             (if (and (or (>= line-number bottom-line) (tree/end? text-zipper))
                                                      (not (empty? result)))
                                               result
                                               (let [start-offset          (text/offset text-zipper)
                                                     next-line-text-zipper (text/scan-to-line-start text-zipper (inc line-number))
                                                     end-offset            (cond-> (text/offset next-line-text-zipper)
                                                                             (not (tree/end? next-line-text-zipper)) (dec))
                                                     intersects?           (intervals/by-intersect start-offset end-offset)
                                                     overscans?            (intervals/by-offset end-offset)
                                                     markers-zipper        (tree/scan markers-zipper
                                                                                      (fn [acc metrics]
                                                                                        (or (intersects? acc metrics)
                                                                                            (overscans? acc metrics))))]
                                                 (recur next-line-text-zipper
                                                   markers-zipper
                                                   (inc line-number)
                                                   (push! result
                                                          (el "div"
                                                              (js-obj "key" line-number
                                                                      "style" (js-obj "transform" (str "translate3d(0px, " y-shift "px, 0px)")))
                                                              #js [(el render-line
                                                                       #js{:key   line-number
                                                                           :props {:text-zipper     text-zipper
                                                                                   :markers-zipper  markers-zipper
                                                                                   :tokens          (:tokens (get lines line-number))
                                                                                   :start-offset    start-offset
                                                                                   :selection       (line-selection selection [start-offset end-offset])
                                                                                   :caret           (when (and (<= start-offset caret-offset) (<= caret-offset end-offset))
                                                                                                      (- caret-offset start-offset))
                                                                                   :end-offset      end-offset
                                                                                   :metrics         metrics
                                                                                   :deleted-markers deleted-markers}})]))))))]
    (el "div"
        #js {:style       #js {:background theme/background
                               :width      "100%"
                               :overflow   "hidden"}
             :key         "viewport"
             :onMouseDown (fn [event]
                            (when on-mouse-down

                              (let [offsetHost (or (.-offsetParent (.-currentTarget event))
                                                   (.-currentTarget event))
                                    x          (- ($ event :clientX) (.-offsetLeft offsetHost))
                                    y          (- ($ event :clientY) (.-offsetTop offsetHost))]
                                (on-mouse-down x y))))
             :onMouseMove (fn [event]
                            (when on-drag-selection
                              (when (= ($ event :buttons) 1)
                                (let [offsetHost (or (.-offsetParent (.-currentTarget event))
                                                     (.-currentTarget event))
                                      x          (- ($ event :clientX) (.-offsetLeft offsetHost))
                                      y          (- ($ event :clientY) (.-offsetTop offsetHost))]
                                  (on-drag-selection x y)))))}
        children)))

(def next-tick
  (let [w js/window]
    (or ($ w :requestAnimationFrame)
        ($ w :webkitRequestAnimationFrame)
        ($ w :mozRequestAnimationFrame)
        ($ w :msRequestAnimationFrame))))

(def hidden-text-area-cmp
  (js/createReactClass
   #js {:componentDidMount
        (fn []
          (this-as cmp
            (let [props         (aget cmp "props")
                  focused?      (aget props "isFocused")
                  dom-node      (aget (aget cmp "refs") "textarea")
                  selected-text (aget props "selectedText")]
              (when focused?
                (.focus dom-node)
                (set! (.-value dom-node) selected-text)
                (.select dom-node)
                (.setSelectionRange dom-node 0 (count selected-text))))))
        :componentDidUpdate
        (fn []
          (this-as cmp
            (let [props    (aget cmp "props")
                  focused? (aget props "isFocused")
                  dom-node (aget (aget cmp "refs") "textarea")
                  selected-text (aget props "selectedText")]
              (when focused?
                (.focus dom-node)
                (set! (.-value dom-node) selected-text)
                (.select dom-node)
                (.setSelectionRange dom-node 0 (count selected-text))))))
        :shouldComponentUpdate
        (fn [new-props new-state]
          true
          #_(this-as this
                   (let [old-props ($ this :props)]
                     (not= (aget old-props "isFocused") (aget new-props "isFocused")))))
        :render
        (fn []
          (this-as cmp
            (let [props       (aget cmp "props")
                  on-input    (aget props "onInput")
                  focused?    (aget props "isFocused")
                  on-key-down (aget props "onKeyDown")]
              (el "textarea"
                  #js {:key       "textarea"
                       :ref       "textarea"
                       :autoFocus focused?
                       :style     #js {:opacity 0
                                       :padding "0px"
                                       :border  "none"
                                       :position "absolute"
                                       :left    "-9999px"
                                       ;; :height  "0px" ;;
                                       ;; :width   "0px"
                                       }
                       :onInput   (fn [evt]
                                    (let [e   (.-target evt)
                                          val (.-value e)]
                                      (set! (.-value e) "")
                                      (on-input val)))
                       :onKeyDown on-key-down}))))}))

(defn selected-text [state]
  (let [text (get-in state [:document :text])
        [from to] (get-in state [:editor :selection])]
    (text/text (text/scan-to-offset (text/zipper text) from) (- to from))))

(def editor-cmp
  (js/createReactClass
   #js {:shouldComponentUpdate
        (fn [new-props new-state]
          (this-as this
            (let [old-props ($ this :props)]
              (not= (aget old-props "editorState") (aget new-props "editorState")))))

        :render
        (fn []
          (this-as cmp
            (let [props             ($ cmp :props)
                  state             ($ props :editorState)
                  {:keys [on-input on-mouse-down on-drag-selection on-resize on-scroll on-focus on-key-down]
                   :as   callbacks} ($ props :callbacks)
                  *bindings         ($ cmp :bindings)]
              (if (ready-to-view? state)
                (el "div"
                    #js {:key      "editor"
                         :style    #js {:display "flex"
                                        :flex    "1"
                                        :cursor  "text"
                                        :outline "transparent"}
                         :tabIndex -1
                         :onFocus  on-focus}
                    #js [(el scroll
                             (js-obj "key" "viewport"
                                     "props"
                                     {:child        (el editor-viewport
                                                        #js {:key             "editor-viewport"
                                                             :editorState     state
                                                             :onMouseDown     on-mouse-down
                                                             :onDragSelection on-drag-selection})
                                      :onResize     on-resize
                                      :onMouseWheel on-scroll}))
                         (el hidden-text-area-cmp
                             #js {:key       "textarea"
                                  :isFocused (get-in state [:viewport :focused?])
                                  :onInput   on-input
                                  :onKeyDown on-key-down
                                  :selectedText (selected-text state)})])
                (el "div" #js {:key "editor-placeholder"} #js ["EDITOR PLACEHOLDER"])))))}))

(defn editor-view [editor-state callbacks]
  (el editor-cmp
      #js {:editorState editor-state
           :callbacks   callbacks
           :key         "editor"}))
