(ns ccw.editors.antlrbased.RaiseSelectionAction
  (:use [paredit [core :only [paredit]]])
  (:use [clojure.contrib.core :only [-?>]])  
  (:import
    [org.eclipse.jface.text IAutoEditStrategy
                            IDocument
                            DocumentCommand]
    [ccw.editors.antlrbased AntlrBasedClojureEditor
                            ClojureEditorMessages])
  (:gen-class
   :extends org.eclipse.jface.action.Action
   :constructors {[ccw.editors.antlrbased.AntlrBasedClojureEditor] [String]}
   :init init
   :post-init post-init
   :state state))
   
#_(set! *warn-on-reflection* true)

(defn- -init
  [#^AntlrBasedClojureEditor editor]
  [[ClojureEditorMessages/RaiseSelectionAction_label] (ref {:editor editor})])   

(defn- -post-init
  [#^ccw.editors.antlrbased.RaiseSelectionAction this editor]
  (.setEnabled this true))

(defn -run
  [#^ccw.editors.antlrbased.RaiseSelectionAction this]
  (let [editor #^AntlrBasedClojureEditor (:editor @(.state this))
        {:keys #{length offset}} (bean (.getUnSignedSelection editor))
        text  (.get (.getDocument #^AntlrBasedClojureEditor editor))
        result (paredit :paredit-raise-sexp {:text text :offset offset :length length})]
    (when-let [modif (-?> result :modifs first)]
      (let [{:keys #{length offset text}} modif
            document (-> editor .getDocument)]
        (.replace document offset length text)
        (.selectAndReveal editor offset (.length #^String text))))))

