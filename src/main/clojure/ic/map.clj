(ns ic.map  
  (:use [clojure.test])
  (:use [mc.util])
  (:use [ic.protocols])
  (:require [ic.graphics])
  (:require [clojure.set]) 
  (:import [mikera.engine.Hex])
  (:import [mikera.math.Bounds4i])
  (:import [mikera.persistent.SparseMap])
  (:import [java.awt Color]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

(def TERRAIN_TYPES [
  (def TERRAINTYPE_OPEN "Open")
  (def TERRAINTYPE_WATER "Water")
  (def TERRAINTYPE_DEEP_WATER "Deep Water")
  (def TERRAINTYPE_WOODS "Woods")
  (def TERRAINTYPE_SAND "Sand")
  (def TERRAINTYPE_HILLS "Hills")
  (def TERRAINTYPE_MOUNTAIN "Mountain")
  (def TERRAINTYPE_URBAN "Urban")
  (def TERRAINTYPE_FORTRESS "Fortress")
  (def TERRAINTYPE_IMPASSABLE "Impassable")])

;; ================================================
;; Locations and points

; forward declarations
(declare adjacents)

; Concrete type to implement a set of points
(deftype PointSet [^clojure.lang.IPersistentSet pointset]
  PLocationSet
    (get-points [p] pointset)
    (union [p q]  
      (reduce conj p (get-points q)))
    (intersection [p q] 
      (clojure.set/intersection pointset (get-points q)))
    (expand [p]
      (PointSet. (reduce clojure.set/union  pointset (map adjacents pointset))))
  clojure.lang.Seqable
    (seq [p] (seq  pointset))
  clojure.lang.ISeq
    (first [p] (first pointset))
    (next [p] (next pointset))
    (more [p] (rest pointset))
    (cons [p x] (cons x pointset)))

; Concrete type to represent a point location
(deftype Point [^long x ^long y]
  Object
    (toString [self] (str "(" x "," y ")"))
    (hashCode [self] (unchecked-add x (Integer/rotateRight y 16)))
    (equals [self b] 
            (if (instance? Point b) 
              (let [b ^Point b] (and (= x (.x b)) (= y (.y b)))) 
              false)))

(defn get-x ^long [^Point p] (.x p))

(defn get-y ^long [^Point p] (.y p)) 

(defn add ^Point [^Point p ^Point q] 
  (Point. (+ (.x p) (.x q)) (+ (.y p) (.y q))))

(defn adjacents [^Point p]
  (PointSet. 
    (areduce
      mikera.engine.Hex/HEX_DX 
      i 
      pts 
      #{} 
      (conj pts (Point. 
                (+ (.x p) (aget mikera.engine.Hex/HEX_DX i))
                (+ (.y p) (aget mikera.engine.Hex/HEX_DY i)))))))

(defn adjacent-point-list 
  ([^long x ^long y]
    (areduce
      mikera.engine.Hex/HEX_DX 
      i 
      pts 
      nil 
      (cons  
        (Point. 
                  (+ x (aget mikera.engine.Hex/HEX_DX i))
                  (+ y (aget mikera.engine.Hex/HEX_DY i))) 
        pts)))
  ([^Point p]
	  (areduce
		  mikera.engine.Hex/HEX_DX 
		  i 
		  pts 
		  nil 
		  (cons  
		    (Point. 
			            (+ (.x p) (aget mikera.engine.Hex/HEX_DX i))
			            (+ (.y p) (aget mikera.engine.Hex/HEX_DY i))) 
		    pts))))

(defn ^Point point 
  ([x y] 
    (Point. (int x) (int y)))
  ([[x y]] 
    (Point. (int x) (int y))))

(defn ^PointSet point-set 
  ([] 
    (PointSet. #{}))
  ([#^Point x] 
    (PointSet. #{x})))

; Direction function

(defn calc-dir 
  ([^Point p]
    (mikera.engine.Hex/direction (.x p) (.y p)))
    ([^Point s ^Point t]
    (mikera.engine.Hex/direction (- (.x t) (.x s)) (- (.y t) (.y s))))
  ([^Point p ^Integer tx ^Integer ty]
    (mikera.engine.Hex/direction (- tx (.x p)) (- ty (.y p))))
  ([^Integer sx ^Integer sy ^Integer tx ^Integer ty]
    (mikera.engine.Hex/direction (- tx sx) (- ty sx))))


; Map functions
 
(defn new-map [] 
  (mikera.persistent.SparseMap.))

(defn mget [^mikera.persistent.SparseMap m ^long x ^long y]
  (.get m (int x) (int y)))
	
(defn mset [^mikera.persistent.SparseMap m ^long x ^long y v]
  (.update m (int x) (int y) v))

(defn mvisit [^mikera.persistent.SparseMap m f]
  (.visit 
    m 
    (proxy [mikera.persistent.SparseMap$Visitor] []
      (call [x y value param]
        (f x y value)
        false))
    nil))

(defn mmap ^mikera.persistent.SparseMap [^mikera.persistent.SparseMap m f]
  (let [a (atom m)]
    (.visit 
      m 
      (proxy [mikera.persistent.SparseMap$Visitor] []
        (call [x y value a]
          (swap! a mset x y (f value))
          false))
      a)
    @a))

(defn mmap-indexed ^mikera.persistent.SparseMap [^mikera.persistent.SparseMap m f]
  (let [a (atom m)]
    (.visit 
      m 
      (proxy [mikera.persistent.SparseMap$Visitor] []
        (call [x y value a]
          (swap! a mset x y (f x y value))
          false))
      a)
    @a))

(defn random-point [^mikera.persistent.SparseMap m]
  (let [^mikera.math.Bounds4i bds (.getNonNullBounds m)]
    (loop [i 100]
      (let [x (mikera.util.Rand/range (.xmin bds) (.xmax bds))
            y (mikera.util.Rand/range (.ymin bds) (.ymax bds))
            v (mget m x y)]
        (if (nil? v)
          (if (> i 0) (recur (dec i)) nil)
          (point x y))))))

; Terrain functions

(def TERRAIN_SIZE (int 128))

(def HALF_TERRAIN_SIZE (int (/ TERRAIN_SIZE 2)))


(defrecord Terrain []
  PDrawable
    (sourcex [t]
      (* (:ix t) TERRAIN_SIZE))
	  (sourcey [t]
      (* (:iy t) TERRAIN_SIZE))
	  (sourcew [t]
      TERRAIN_SIZE)
		(centrex [t]
		  HALF_TERRAIN_SIZE)
		(centrey [t]
		  HALF_TERRAIN_SIZE)
	  (sourceh [t]
      TERRAIN_SIZE)
	  (source-image [t]
      ic.graphics/terrain-image)
    (drawable-icon [t]
     (mikera.ui.BufferedImageIcon. (source-image t) (sourcex t) (sourcey t) (sourcew t) (sourceh t))))

(def default-terrain-data 
  {:image ic.graphics/terrain-image
   :ix 0
   :move-cost-multiplier 100
   :defence-multiplier 100
   :map-colour (Color. 255 0 255)
   :iy 0
   :elevation 1})

; list of terrain types, built by make-terrain
(def temp-terrain-types (atom nil))

(defn make-terrain 
  ([props]
    (swap! temp-terrain-types conj
      (Terrain.
        nil
        (merge default-terrain-data props))))
  ([parent-name props] 
	  (make-terrain
		  (merge (find-first #(= parent-name (:name %)) @temp-terrain-types) props))))


;; ============================================== 
;; Open terrain

(make-terrain
    {:name "Grassland"
     :terrain-type TERRAINTYPE_OPEN
     :ix 1
     :iy 0
     :map-colour (Color. 50 100 20)
     :elevation 1})

(make-terrain "Grassland"
  {:name "Rocky Grassland"
   :ix 1
   :iy 1
   :move-cost-multiplier 110
   :defence-multiplier 110})

(make-terrain "Grassland"
  {:name "Wooded Grassland"
   :ix 1
   :iy 2
   :move-cost-multiplier 110
   :defence-multiplier 120})

; fortifications
(make-terrain
  {:name "Trench"
   :terrain-type TERRAINTYPE_FORTRESS
   :map-colour (Color. 90 60 30)
   :ix 3
   :iy 0
   :has-wire true})

;; =======================================
;; Hills and Mountains

(make-terrain
    {:name "Hills"
     :terrain-type TERRAINTYPE_HILLS
     :map-colour (Color. 100 60 30)
     :ix 4
     :iy 0})

(make-terrain "Hills"
    {:name "Rocky Hills"
     :ix 4
     :iy 1
     :move-cost-multiplier 110
     :defence-multiplier 130})

(make-terrain "Hills"
    {:name "Wooded Hills"
     :move-cost-multiplier 120
     :defence-multiplier 120
     :ix 4
     :iy 2})

(make-terrain
    {:name "Mountain"
     :map-colour (Color. 100 80 60)
     :terrain-type TERRAINTYPE_MOUNTAIN
     :ix 7
     :iy 0})


 (make-terrain "Mountain"
    {:name "Impassable Mountain"
     :terrain-type TERRAINTYPE_IMPASSABLE
     :ix 7
     :iy 1})

;; =======================================
;; Woods 
(make-terrain
    {:name "Woods"
     :map-colour (Color. 30 80 10)
     :terrain-type TERRAINTYPE_WOODS
     :ix 5
     :iy 0})

;; =======================================
;; Seas and rivers

(make-terrain
  {:name "Sea"
   :terrain-type TERRAINTYPE_WATER
   :map-colour (Color. 0 40 80)
   :is-water true
   :ix 2
   :iy 0
   :elevation 0})

(make-terrain "Sea"
  {:name "Sea Rocks"
   :terrain-type TERRAINTYPE_IMPASSABLE
   :move-cost-multiplier 0
   :is-water true
   :ix 2
   :iy 1})

(make-terrain "Sea"
  {:name "Deep Sea"
   :terrain-type TERRAINTYPE_DEEP_WATER
   :is-water true
   :ix 2
   :iy 2})

(def terrain-types
  @temp-terrain-types)

(def terrain-type-map 
   (reduce 
     (fn [m t] (assoc m (:name t) t)) 
     {} 
     terrain-types))

(defn ^Terrain terrain [tname] 
  (or 
    (terrain-type-map tname) 
    (throw (Error. (str  "Terrain type [" tname "] not found")))))


; Map builders

(defn rand-terrain []
  (terrain (rand-choice [
      "Impassable Mountain" "Mountain" "Rocky Hills" "Wooded Hills" "Trench" 
      "Hills" "Hills" "Hills" "Hills"
      "Grassland" "Grassland" "Grassland" "Grassland" "Grassland" "Grassland" 
      "Grassland" "Grassland" "Grassland" 
      "Rocky Grassland" "Wooded Grassland" "Woods" "Woods"
      "Sea" "Sea" "Sea" "Sea" "Sea" "Sea" "Sea"
      "Sea Rocks" "Deep Sea" "Deep Sea"])))

(defn make-map2 [] 
  (let [m (ic.map/new-map)]
    (->
      m
      (mset 0 0 (terrain "Grassland"))
      (mset 1 0 (terrain "Grassland"))
      (mset 0 1 (terrain "Sea")))))


