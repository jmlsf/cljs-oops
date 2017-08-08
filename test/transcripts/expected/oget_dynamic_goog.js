// Clojure v1.9.0-alpha17, ClojureScript v1.9.854, js-beautify v1.6.14
// ----------------------------------------------------------------------------------------------------------
// COMPILER CONFIG:
//   arena/oget_dynamic.cljs [goog]
//   {:elide-asserts true,
//    :external-config
//    {:oops/config {:key-set :goog, :key-get :goog},
//     :devtools/config {:silence-optimizations-warning true}},
//    :main oops.arena.oget-dynamic,
//    :optimizations :advanced,
//    :output-dir "test/resources/.compiled/oget-dynamic-goog/_workdir",
//    :output-to "test/resources/.compiled/oget-dynamic-goog/main.js",
//    :pseudo-names true}
// ----------------------------------------------------------------------------------------------------------

// SNIPPET #1:
//   (testing "simple get"
//     (oget+ #js {"key" "val"} (return-this-key "key"))
//     (oget+ #js {"key" "val"} (identity "key"))
//     (oget+ #js {"key" "val"} (return-this-key-with-side-effect "key")))
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

oops.core.get_selector_dynamically({
  key: "val"
}, "key");
oops.core.get_selector_dynamically({
  key: "val"
}, "key");
window.x = "dirty";
oops.core.get_selector_dynamically({
  key: "val"
}, "key");

// SNIPPET #2:
//   (testing "simple miss"
//     (oget+ #js {"key" "val"} (return-this-key "xxx")))
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

oops.core.get_selector_dynamically({
  key: "val"
}, "xxx");

// SNIPPET #3:
//   (testing "nested get"
//     (def o1 #js {"key"    "val"
//                  "nested" #js {"nested-key" "nested-val"}})
//     (oget+ o1 (return-this-key "key") (return-this-key "nested"))
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

oops.core.get_selector_dynamically({
  key: "val",
  nested: {
    "nested-key": "nested-val"
  }
}, ["key", "nested"]);
oops.core.get_selector_dynamically({
  key: "val",
  nested: {
    "nested-key": "nested-val"
  }
}, new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector$EMPTY_NODE, ["key", "nested"], null));
oops.core.get_selector_dynamically({
  key: "val",
  nested: {
    "nested-key": "nested-val"
  }
}, ["key", new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector$EMPTY_NODE, ["nested"], null)]);
oops.core.get_selector_dynamically({
  key: "val",
  nested: {
    "nested-key": "nested-val"
  }
}, ["key", "nested"]);
