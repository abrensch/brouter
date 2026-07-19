---
title: "Threading, concurrency and load"
parent: Developers
---

# Threading, concurrency and behaviour under load

Route calculation in BRouter is **CPU-bound** (Dijkstra / weighted-A\* over the
road graph). This page documents how the server admits and executes requests,
what that means under high load, and how the round-trip child engines fit into
that model. It distinguishes the **long-standing model** from the pieces the
round-trip work introduced, so it is clear which behaviour is which.

## Three layers

BRouter's concurrency lives at three independent layers:

| Layer | Model | Origin |
| :---- | :---- | :----- |
| 1. Server admission | `maxthreads` thread pool, pre-empt the oldest in-flight request when full | Long-standing BRouter server design |
| 2. Per request | One `RoutingEngine` (which `extends Thread`) per request, run synchronously | Foundational BRouter design |
| 3. Intra-request (round trips only) | `AUTO` runs candidate algorithms in **isolated child engines**, sequentially on the request's own thread | Round-trip work |

Layers 1 and 2 are unchanged by the round-trip work. Only layer 3 is new —
see [Child engines](#3-child-engines-round-trips) below.

## 1. Server admission — bounded concurrency with oldest-victim pre-emption

`RouteServer.main` (`brouter-server`) accepts connections in a loop and keeps a
`PriorityQueue<RouteServer>` of in-flight request threads, ordered oldest-first.
For each accepted connection (`RouteServer.java`, the `accept()` loop):

1. Finished threads are reaped (`cleanupThreadQueue`).
2. If the pool is at `maxthreads`, wait up to **2 s** for a slot (a finishing
   thread notifies early).
3. If still full after the wait, **stop the oldest in-flight request**
   (`oldest.stopRouter()` → `RoutingEngine.terminate()`, the volatile
   watchdog flag checked once per Dijkstra pop).
4. Start the new request thread.

**Consequences for the "guaranteed resources" question.** This design bounds the
number of concurrently *executing* request threads (good — no thread
explosion), but it does **not** guarantee an admitted request the resources to
finish within its timeout. Two failure modes under sustained overload:

- **Pre-emption.** Enough newer requests arriving while the pool is full will
  kill the oldest in-flight request — potentially *before* its own timeout
  expires. Completion is not guaranteed even within the request's budget. This
  is a deliberate "favour new-request latency / stay responsive" policy, not
  resource-guaranteed admission.
- **CPU oversubscription.** The request timeout is **wall-clock**, but the work
  is **CPU**. If `maxthreads` exceeds the core count, admitted requests share
  cores and each gets roughly `cores / maxthreads` of a CPU — so its *effective
  compute within the wall-clock timeout* shrinks proportionally. A 30 s budget
  on a 4×-oversubscribed core buys ~7.5 s of actual search, so the plan hits its
  deadline having explored far less and degrades (or fails).

### Operating for a completion guarantee

For "an admitted request keeps a core for its whole timeout", keep

```
maxthreads ≈ number of cores
```

so each admitted request gets ~1 dedicated core and the wall-clock timeout is
meaningful. Note the pre-empt-oldest policy still applies when the pool is full;
a hard "never kill an admitted request, shed excess cleanly" contract would
require a reject-when-full admission policy (return 503/429 instead of
pre-empting) — not the current default.

## 2. Per-request engine

Each request builds one `RoutingEngine` and runs it synchronously on the pool
thread (`doRun`). Memory per engine is bounded by `memoryclass` (default 64 MB)
for the `NodesCache`; `ProfileCache` is a static synchronised LRU sized
`2 × maxthreads`. Cross-request shared state is read-mostly (tile file handles,
profile cache); routing state is per-engine.

The request's wall-clock budget is `maxRunningTime` (server default 60 s),
checked once per Dijkstra pop and enforced as an absolute request deadline for
round trips (`roundTripRequestDeadline`).

## 3. Child engines (round trips)

`AUTO` round-trip mode compares candidate algorithms (ISO_GREEDY, GREEDY, and
the WAYPOINT / ISOCHRONE fallbacks), each built in a **fully isolated child
`RoutingEngine`** from a request-fields-only copy of the context. The children
run **sequentially** on the request's own thread (ISO_GREEDY first, GREEDY
only when still needed), so an `AUTO` request occupies one core and the pool's
implicit "≈ 1 CPU-bound thread per admitted request" assumption holds.

**Termination cascades to children.** Server pre-emption calls `terminate()`
on the *parent* engine; each child engine checks its own kill flag per pop, so
the parent registers a termination hook per child (`child::terminate`) that
forwards the pre-emption. A pre-empted `AUTO` request therefore stops within
~one heap pop instead of running out the child's own budget.

## Tuning knobs

| Knob | Scope | Meaning |
| :--- | :---- | :------ |
| `maxthreads` (launch arg) | Server | Max concurrently-executing requests. Keep ≈ cores for a per-request-core guarantee. |
| `-DmaxRunningTime` (seconds) | Server | Operator **ceiling** on per-request wall-clock budget (default 60 s). Malformed values fail closed to the default. |
| `timeout` (URL param, seconds) | Request | Per-request budget, **clamped to the `maxRunningTime` ceiling**. Lets a client resend a degraded round trip with more budget without exceeding the operator cap. |
| `memoryclass` | Request | `NodesCache` memory bound per engine (MB, default 64). |

## Round-trip budget scaling

Within a single round-trip plan the wall-clock budget scales with the requested
loop length and reserves time for loop closure — see
[Round-trip and loop routing → Calculation budget](../features/roundtrips.md#calculation-budget)
for the 40–100 km standard class, the linear scaling to 200 km, the &gt;200 km
explicit opt-in, and the per-plan `budget:` headroom diagnostic used to measure
whether the budget actually binds in production.
