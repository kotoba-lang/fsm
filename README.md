# kotoba-lang/fsm

**SSoT for `kami.fsm`** — EDN animation/behaviour state machines.

`kotoba.fsm` is a thin facade. See ADR-2607102200 addendum 7.

The generic map-driven API remains in `.cljc`. The canonical bounded
`player-v1` transition profile is implemented as the multi-module Kotoba graph
`src/kami/fsm.kotoba` → `src/kotoba/fsm.kotoba`. It deliberately does not
reinterpret arbitrary legacy maps as the closed player profile.

The two statements of the machine are held together by
`test/fsm_parity_test.clj`, which compiles the `.kotoba` in-process and runs it
against the `.cljc` over every state crossed with every subset of the declared
event alphabet. Change one side and it names the input that separates them.

## Test

```sh
clojure -M:test
```
