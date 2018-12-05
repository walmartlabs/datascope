# walmartlabs/datascope

A library that can be used to render typical Clojure data structures using
Graphviz.
 
[![Clojars Project](https://img.shields.io/clojars/v/walmartlabs/datascope.svg)](https://clojars.org/walmartlabs/datascope)

[API Documentation](http://walmartlabs.github.io/apidocs/datascope/)

## Usage

Pretty printing can only take you so far; eventually 
a different visual representation is handy for really seeing how all you
data is connected and related.


```
(require '[com.walmartlabs.datascope :as ds])

(ds/view {:missing nil
          :string "A scalar."
          :n 10
          :enabled? true
          :vector [1 2 3 4]
          :sequence (iterate inc 1)
          :set #{:science-fiction :romance :military}
          :atom (atom {:foo :bar :empty {}})
          :empty {}})
```

This will bring up a frame (care of [Rhizome](https://github.com/ztellman/rhizome))
that displays the following:

![example](basics.png)

* Maps, Sets, and Sequences have rounded edges, Vectors have square edges.

* Composite types are labeled with their (abbreviated) type.

* Refs, such as Atoms and Vars, are supported.

* Sequences are abbreviated; Maps, Sets, and Vectors are always fully rendered.

* Functions are rendered as their de-mangled Clojure name.

Using the rest of the Rhizome API, you can easily save the images to disk
instead of viewing them in a frame.

And, remember, Clojure code is data:


```
(require '[com.walmartlabs.datascope :as ds])

(ds/view 
'(defn save
    [root-object file-name]
    (-> (dot root-object)
      viz/dot->image
      (viz/save-image file-name))))
```

![save-function](save-function.png)

## Upcoming Features

* Optional rendering of meta data

* Depth limit on rendering

* Open up more internal API to support rendering of more types

* Rendering of JavaBeans

## License

Copyright © 2016 Walmartlabs

Distributed under the terms of the Apache Software License 2.0.
