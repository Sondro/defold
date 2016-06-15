(ns editor.code-view
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.code-view-ux :as cvx]
            [editor.core :as core]
            [editor.handler :as handler]
            [editor.ui :as ui]
            [editor.workspace :as workspace])
  (:import [com.defold.editor.eclipse DefoldRuleBasedScanner Document DefoldStyledTextSkin]
           [javafx.scene Parent]
           [javafx.scene.input Clipboard ClipboardContent KeyEvent MouseEvent]
           [javafx.scene.image Image ImageView]
           [java.util.function Function]
           [javafx.scene.control ListView]
           [org.eclipse.fx.text.ui TextAttribute]
           [org.eclipse.fx.text.ui.presentation PresentationReconciler]
           [org.eclipse.fx.text.ui.rules DefaultDamagerRepairer]
           [org.eclipse.fx.text.ui.source SourceViewer SourceViewerConfiguration]
           [org.eclipse.fx.ui.controls.styledtext StyledTextArea StyleRange TextSelection]
           [org.eclipse.fx.ui.controls.styledtext.behavior StyledTextBehavior]
           [org.eclipse.fx.ui.controls.styledtext.skin StyledTextSkin]
           [org.eclipse.jface.text DocumentEvent IDocument IDocumentListener IDocumentPartitioner]
           [org.eclipse.jface.text.rules FastPartitioner ICharacterScanner IPredicateRule IRule IToken IWhitespaceDetector
            IWordDetector MultiLineRule RuleBasedScanner RuleBasedPartitionScanner SingleLineRule Token WhitespaceRule WordRule]))

(set! *warn-on-reflection* true)

(ui/extend-menu ::text-edit :editor.app-view/edit
                (cvx/create-menu-data))

(defn- code-node [text-area]
  (ui/user-data text-area ::code-node))

(defn- behavior [text-area]
  (ui/user-data text-area ::behavior))

(defn- assist [selection]
  (ui/user-data selection ::assist))

(defn- syntax [selection]
  (ui/user-data selection ::syntax))

(defmacro binding-atom [a val & body]
  `(let [old-val# (deref ~a)]
     (try
       (reset! ~a ~val)
       ~@body
       (finally
         (reset! ~a old-val#)))))

(g/defnk update-source-viewer [^SourceViewer source-viewer code-node code caret-position selection-offset selection-length]
  (ui/user-data! (.getTextWidget source-viewer) ::code-node code-node)
    (when (not= code (cvx/text source-viewer))
    (try
      (cvx/text! source-viewer code)
      (catch Throwable e
        (println "exception during .set!")
        (.printStackTrace e))))
  (if (pos? selection-length)
    (do
      ;; There is a bug somewhere in the e(fx)clipse that doesn't
      ;; display the text selection property after you change the text programatically
      ;; when it's resolved uncomment
      ;(cvx/text-selection! source-viewer selection-offset selection-length)
      (cvx/caret! source-viewer caret-position false)
    )
    (cvx/caret! source-viewer caret-position false))
  [code-node code caret-position])


(defn- default-rule? [rule]
  (= (:type rule) :default))

(def ^:private attr-tokens (atom nil))
(def ^:private ^Character char0 (char 0))

(defn- attr-token [attr]
  (Token. (TextAttribute. attr)))

(defn get-attr-token [name]
  (if-let [token (get @attr-tokens name)]
    token
    (do
      (swap! attr-tokens assoc name (attr-token name))
      (get-attr-token name))))

(def ^:private tokens (atom nil))

(defn- token [name]
  (Token. name))

(defn- get-token [name]
  (if-let [token (get @tokens name)]
    token
    (do
      (swap! tokens assoc name (token name))
      (get-token name))))

(defmulti make-rule (fn [rule token] (:type rule)))

(defmethod make-rule :default [rule _]
  (assert false (str "default rule " rule " should be handled separately")))

(defn- white-space-detector [space?]
  (reify IWhitespaceDetector
    (isWhitespace [this c] (boolean (space? c)))))

(defn- is-space? [^Character c] (Character/isWhitespace c))

(defmethod make-rule :whitespace [{:keys [space?]} token]
  (let [space? (or space? is-space?)]
    (if token
      (WhitespaceRule. (white-space-detector space?) token)
      (WhitespaceRule. (white-space-detector space?)))))

(defn- word-detector [start? part?]
  (reify IWordDetector
    (isWordStart [this c] (boolean (start? c)))
    (isWordPart [this c] (boolean (part? c)))))

(defmethod make-rule :keyword [{:keys [start? part? keywords]} token]
  (let [word-rule (WordRule. (word-detector start? part?))]
    (doseq [keyword keywords]
      (.addWord word-rule keyword token))
    word-rule))

(defmethod make-rule :word [{:keys [start? part?]} token]
  (WordRule. (word-detector start? part?) token))

(defmethod make-rule :singleline [{:keys [start end esc]} token]
  (if esc
    (SingleLineRule. start end token esc)
    (SingleLineRule. start end token)))

(defmethod make-rule :multiline [{:keys [start end esc eof]} token]
  (MultiLineRule. start end token (if esc esc char0) (boolean eof)))

(defn- make-predicate-rule [scanner-fn ^IToken token]
  (reify IPredicateRule
    (evaluate [this scanner]
      (.evaluate this scanner false))
    (evaluate [this scanner resume]
      (let [^DefoldRuleBasedScanner sc scanner
            result (scanner-fn (.readString sc))]
        (if result
          (let [len (:length result)]
            (when (pos? len) (.moveForward sc len))
            token)
          Token/UNDEFINED)))
    (getSuccessToken ^IToken [this] token)))

(defmethod make-rule :custom [{:keys [scanner]} token]
  (make-predicate-rule scanner token))

(deftype NumberRule [^IToken token]
  IRule
  (^IToken evaluate [this ^ICharacterScanner scanner]
   (let [c (.read scanner)]
     (if (and (not= c ICharacterScanner/EOF) (Character/isDigit (char c)))
       (do
         (loop [c (.read scanner)]
           (when (and (not= c ICharacterScanner/EOF) (Character/isDigit (char c)))
             (recur (.read scanner))))
         (.unread scanner)
         token)
       (do
         (.unread scanner)
         Token/UNDEFINED)))))

(defmethod make-rule :number [_ token]
  (NumberRule. token))

(defn- make-scanner-rule [{:keys [class] :as rule}]
  (make-rule rule (when class (get-attr-token class))))

(defn- make-scanner [rules]
  (let [scanner (DefoldRuleBasedScanner.)
        default-rule (first (filter default-rule? rules))
        rules (remove default-rule? rules)]
    (when default-rule
      (.setDefaultReturnToken scanner (get-attr-token (:class default-rule))))
    (.setRules scanner (into-array IRule (map make-scanner-rule rules)))
    scanner))

(def ^:private default-content-type-map {:default IDocument/DEFAULT_CONTENT_TYPE})

(defn- make-multiline-dr [sc]
  (proxy [DefaultDamagerRepairer] [sc]
    (getDamageRegion [p e chg]
      p)))

(defn- make-reconciler [^SourceViewerConfiguration configuration ^SourceViewer source-viewer scanner-syntax]
  (let [pr (PresentationReconciler.)]
    (.setDocumentPartitioning pr (.getConfiguredDocumentPartitioning configuration source-viewer))
    (doseq [{:keys [partition rules]} scanner-syntax]
      (let [partition (get default-content-type-map partition partition)]
        (let [damager-repairer (make-multiline-dr (make-scanner rules))] ;;_(DefaultDamagerRepairer. (make-scanner rules))]
          (.setDamager pr damager-repairer partition)
          (.setRepairer pr damager-repairer partition))))
    pr))

(defn- ^SourceViewerConfiguration create-viewer-config [source-viewer opts]
  (let [{:keys [language syntax assist]} opts
        scanner-syntax (:scanner syntax)]
    (proxy [SourceViewerConfiguration] []
      (getStyleclassName [] language)
      (getPresentationReconciler [source-viewer]
        (make-reconciler this source-viewer scanner-syntax))
      (getConfiguredContentTypes [source-viewer]
        (into-array String (replace default-content-type-map (map :partition scanner-syntax)))))))

(defn- default-partition? [partition]
  (= (:type partition) :default))

(defn- make-partition-rule [rule]
  (make-rule rule (get-token (:partition rule))))

(defn- make-partition-scanner [partitions]
  (let [rules (map make-partition-rule (remove default-partition? partitions))]
    (doto (RuleBasedPartitionScanner.)
      (.setPredicateRules (into-array IPredicateRule rules)))))

(defn- ^IDocumentPartitioner make-partitioner [opts]
  (when-let [partitions (get-in opts [:syntax :scanner])]
    (let [legal-content-types (map :partition (remove default-partition? partitions))]
      (FastPartitioner. (make-partition-scanner partitions)
                        (into-array String legal-content-types)))))

(defn setup-source-viewer [opts use-custom-skin?]
  (let [source-viewer (SourceViewer.)
        source-viewer-config (create-viewer-config source-viewer opts)
        document (Document. "")
        partitioner (make-partitioner opts)]

    (when partitioner
      (.setDocumentPartitioner document (.getConfiguredDocumentPartitioning source-viewer-config source-viewer) partitioner)
      (.connect partitioner document))

    (.configure source-viewer source-viewer-config)
    (.setDocument source-viewer document)

    (let [text-area (.getTextWidget source-viewer)
          styled-text-behavior  (proxy [StyledTextBehavior] [text-area]
                                  (callActionForEvent [key-event]
                                    ;;do nothing we are handling all
                                    ;;the events
                                    ))]
      (.addEventHandler ^StyledTextArea text-area
                        KeyEvent/KEY_PRESSED
                        (ui/event-handler e (cvx/handle-key-pressed e source-viewer)))
      (.addEventHandler ^StyledTextArea text-area
                        KeyEvent/KEY_TYPED
                        (ui/event-handler e (cvx/handle-key-typed e source-viewer)))
     (when use-custom-skin?
       (let [skin (new DefoldStyledTextSkin text-area styled-text-behavior)]
         (.setSkin text-area skin)
         (.addEventHandler  ^ListView (.getListView skin)
                            MouseEvent/MOUSE_CLICKED
                            (ui/event-handler e (cvx/handle-mouse-clicked e source-viewer)))))


      (ui/user-data! text-area ::behavior styled-text-behavior)
      (ui/user-data! source-viewer ::assist (:assist opts))
      (ui/user-data! source-viewer ::syntax (:syntax opts)))

  source-viewer))

(g/defnode CodeView
  (property source-viewer SourceViewer)
  (input code-node g/Int)
  (input code g/Str)
  (input caret-position g/Int)
  (input selection-offset g/Int)
  (input selection-length g/Int)
  (output new-content g/Any :cached update-source-viewer))

(defn setup-code-view [view-id code-node initial-caret-position]
  (g/transact
   (concat
    (g/connect code-node :_node-id view-id :code-node)
    (g/connect code-node :code view-id :code)
    (g/connect code-node :caret-position view-id :caret-position)
    (g/connect code-node :selection-offset view-id :selection-offset)
    (g/connect code-node :selection-length view-id :selection-length)
    (g/set-property code-node :caret-position initial-caret-position)
    (g/set-property code-node :selection-offset 0)
    (g/set-property code-node :selection-length 0)))
  view-id)

(extend-type Clipboard
  cvx/TextContainer
  (text! [this s]
    (let [content (ClipboardContent.)]
      (.putString content s)
      (.setContent this content)))
  (text [this]
    (when (.hasString this)
      (.getString this))))

(defn source-viewer-set-caret! [source-viewer offset select?]
  (try
   (.impl_setCaretOffset (.getTextWidget ^SourceViewer source-viewer) offset select?)
   (catch Exception e
     (println "ignoring source-viewer-set-caret! failure")
     ;;do nothing there is a bug in the StyledTextSkin that creates
     ;;null pointers due to the skin rendered completely yet not being created yet (the skin
     ;;is created on the ui pulse) Eventually we should consider
     ;;rewriting the Skin class and porting it to Clojure
     )))

(extend-type SourceViewer
  workspace/SelectionProvider
  (selection [this] this)
  cvx/TextContainer
  (text! [this s]
    (.set (.getDocument this) s))
  (text [this]
    (.get (.getDocument this)))
  (replace! [this offset length s]
    (-> this (.getTextWidget) (.getContent) (.replaceTextRange offset length s)))
  cvx/TextView
  (selection-offset [this]
    (.-offset ^TextSelection (-> this (.getTextWidget) (.getSelection))))
  (selection-length [this]
    (.-length ^TextSelection (-> this (.getTextWidget) (.getSelection))))
  (caret! [this offset select?]
    (source-viewer-set-caret! this offset select?))
  (caret [this] (.getCaretOffset (.getTextWidget this)))
  (text-selection [this]
    (.get (.getDocument this) (cvx/selection-offset this) (cvx/selection-length this)))
  (text-selection! [this offset length]
    (.setSelectionRange (.getTextWidget this) offset length))
  (editable? [this]
    (-> this (.getTextWidget) (.getEditable)))
  (editable! [this val]
    (-> this (.getTextWidget) (.setEditable val)))
  (screen-position [this]
    (let [tw (.getTextWidget this)
        caret-pos (cvx/caret this)
        p (.getLocationAtOffset tw caret-pos)]
      (.localToScreen tw p)))
  cvx/TextStyles
  (styles [this] (let [document-len (-> this (.getDocument) (.getLength))
                       text-widget (.getTextWidget this)
                       len (dec (.getCharCount text-widget))
                       style-ranges (.getStyleRanges text-widget (int 0) len false)
                       style-fn (fn [sr] {:start (.-start ^StyleRange sr)
                                         :length (.-length ^StyleRange sr)
                                         :stylename (.-stylename ^StyleRange sr)})]
                   (mapv style-fn style-ranges)))
  cvx/TextUndo
  (changes! [this]
    (let [code-node-id (-> this (.getTextWidget) (code-node))
          selection-offset (cvx/selection-offset this)
          selection-length (cvx/selection-length this)
          code (cvx/text this)
          caret (cvx/caret this)
          code-changed? (not= code (g/node-value code-node-id :code))
          caret-changed? (not= caret (g/node-value code-node-id :caret-position))
          selection-changed? (or (not= selection-offset (g/node-value code-node-id :selection-offset))
                                 (not= selection-length (g/node-value code-node-id :selection-length)))]
      (when (or code-changed? caret-changed? selection-changed?)
        (g/transact (remove nil?
                            (concat
                             (when code-changed?  [(g/set-property code-node-id :code code)])
                             (when caret-changed? [(g/set-property code-node-id :caret-position caret)])
                             (when selection-changed?
                               [(g/set-property code-node-id :selection-offset selection-offset)
                                (g/set-property code-node-id :selection-length selection-length)])))))))
  cvx/TextProposals
  (propose [this]
    (when-let [assist-fn (assist this)]
      (let [document (.getDocument this)
            offset (cvx/caret this)
            line-no (.getLineOfOffset document offset)
            line-offset (.getLineOffset document line-no)
            line (.get document line-offset (- offset line-offset))
            code-node-id (-> this (.getTextWidget) (code-node))
            completions (g/node-value code-node-id :completions)]
        {:proposals (assist-fn completions (cvx/text this) offset line)
         :line line}))))

(defn make-view [graph ^Parent parent code-node opts]
  (let [source-viewer (setup-source-viewer opts true)
        view-id (setup-code-view (g/make-node! graph CodeView :source-viewer source-viewer) code-node (get opts :caret-position 0))]
    (ui/children! parent [source-viewer])
    (ui/fill-control source-viewer)
    (ui/context! source-viewer :code-view {:code-node code-node :view-node view-id :clipboard (Clipboard/getSystemClipboard)} source-viewer)
    (g/node-value view-id :new-content)
    (let [refresh-timer (ui/->timer 1 "collect-text-editor-changes" (fn [_] (cvx/changes! source-viewer)))
          stage (ui/parent->stage parent)]
      (ui/timer-stop-on-close! ^Tab (:tab opts) refresh-timer)
      (ui/timer-stop-on-close! stage refresh-timer)
      (ui/timer-start! refresh-timer))
    view-id))

(defn register-view-types [workspace]
  (workspace/register-view-type workspace
                                :id :code
                                :label "Code"
                                :make-view-fn (fn [graph ^Parent parent code-node opts] (make-view graph parent code-node opts))))
