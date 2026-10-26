# surefold

Structured concurrency, streams, actors and clustering for Kotlin. These are the
words the code, the specs and the docs use for its concepts.

## Shared state

**Step**:
One run of `transact { }`: the `Shared` values it changes all commit together
or not at all. A step runs again when another commit changed what it read.
_Avoid_: transaction (the API's receiver type, not the concept), atomic block

**Effect**:
Work a step queues with `afterCommit { }`: it runs once the step has
committed, and not at all if it does not.
_Avoid_: hook, callback, side effect (an effect written in the body runs once
per run)

## Actors

**Turn**:
One message handled by an actor: the behaviour's `step` function run once
against the actor's state. Turns run one at a time, so the state needs no lock.
_Avoid_: step (in prose; that is a `transact` run), tick

**Lifecycle**:
The rules an actor follows from start to stop: taking a message, supervision and
restart, timers, stash, receive timeout and the end sequence. One lifecycle, the
same under a scope and under test actors.
_Avoid_: actor core, cell logic

**Life**:
One run of a cluster member: its node and a uid that changes on every restart.
_Avoid_: lifecycle (that is an actor's), incarnation

## Sharding

**Handoff**:
The exchange that moves a shard between nodes: the node that wins it asks
every other holder to release it, and runs none of it until each has.
_Avoid_: migration, rebalance (a rebalance decides who should own a shard;
the handoff moves it)

## Producers

**Outbox**:
The commands a producer has sent to one entity and not yet seen confirmed,
oldest first. Only the head is in flight; the rest wait behind it.
_Avoid_: queue, buffer

**Room**:
How many unconfirmed commands a producer may hold before `send` waits.
_Avoid_: capacity, credit

**Resumed producer**:
A durable producer the cluster starts again, under a life that has left, to send
only what that life kept. It takes no new commands.
_Avoid_: orphan, recovered producer

**Retirement**:
A resumed producer stopping itself, and deleting what it kept, once every
command it kept is confirmed.
_Avoid_: drain (draining is waiting for confirmation, not stopping)

## Applications

**Wiring graph**:
What each module of an application provides and needs. surefold-app and the compiler
plugin both read it for missing, cyclic and unreached keys.
_Avoid_: dependency graph, plan

**Section**:
The part of an application's configuration one module owns, read into one type.
A section that cannot be read refuses the start, naming every fault at once.
_Avoid_: settings, config block

## Streams

**Stage**:
One operator of a stream as written, numbered in the order data flows through it.
_Avoid_: step (in prose), node

**Profile**:
The numbers a `Profiler` kept for each stage of one measured stream, together
with the stages they belong to.
_Avoid_: metrics, measurement
