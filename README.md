# kotoba-lang/fsm

**SSoT for `kami.fsm`** — EDN animation/behaviour state machines.

`kotoba.fsm` is a thin facade. See ADR-2607102200 addendum 7.

The generic map-driven API remains in `.cljc`. The canonical bounded
`player-v1` transition profile is implemented as the multi-module Kotoba graph
`src/kami/fsm.kotoba` → `src/kotoba/fsm.kotoba`. It deliberately does not
reinterpret arbitrary legacy maps as the closed player profile.

## Test

```sh
clojure -M:test
```
