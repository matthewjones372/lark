# 0073 — Nodes that know each other

## Problem

0068's transport is plain TCP. Anything on the network between two nodes can
read every message, including 0072's events on their way to an entity, and
anything that can reach a node's port can speak lark to it: send a frame to
any exposed actor, join the cluster through gossip, or claim to be another
node by giving its name in the hello. A node listens on loopback by default
for exactly that reason, so a real deployment today either runs on a network
it trusts completely or puts a sidecar mesh in front of every node.

## Not doing

- **Plain and TLS nodes in one cluster.** A node is one or the other; moving a
  running cluster to TLS is a rolling restart behind a flag, which is the
  service's.
- **Issuing or rotating certificates.** The service hands in an `SSLContext`
  or key stores it already manages; a rotated certificate takes effect on the
  next connection, since each connection is made from the context afresh.
- **Authorization.** A node that is let in may do whatever any node may do.
  Which actor a peer may tell is its own spec, if asked.
- **A certificate library.** The JDK's `javax.net.ssl` and `KeyStore` only.

## Shape

```kotlin
val tls = Tls.mutual(
    keys = KeyStore.getInstance("PKCS12").apply { load(stream, password) },  // this node's key and certificate
    password = password,
    trusted = caStore,                                                         // the cluster's CA
)
flock<Nothing, Unit> {
    val remote = node("shop-1", 25520, host = "10.0.0.7", tls = tls)
    val cluster = cluster(remote, seeds)
}
```

- **Both directions over TLS 1.3**, each side presenting its certificate and
  requiring the other's: the listening side sets `needClientAuth`, so a peer
  with no certificate, or one the trusted store did not sign, is refused
  before the hello. What was queued for it is dropped and reported, as for
  any peer that does not answer.
- **The certificate names the node.** A peer's certificate must carry its
  node name as a DNS subject alternative name, and the hello's name must
  match it, in both directions. A node with a good certificate for `shop-2`
  cannot claim to be `shop-3`.
- **`Tls(context)`** takes an `SSLContext` the service built itself, for a
  store `Tls.mutual` does not read; the name check is the same.
- **Nothing else changes.** Frames, codecs, ordering and at-most-once are
  0068's, on the TLS stream instead of the socket's.

## Why this shape

Mutual TLS with the node name in the certificate gives encryption and
identity in one mechanism every platform already issues certificates for,
and needs nothing beyond the JDK. The alternative is a shared secret in the
hello, as Pekko's older secure cookie did: simpler to set up, but it neither
encrypts nor tells one node from another, and anyone who reads it once can
join. Recommended: mutual TLS.

## Stack

- [ ] **`spec-0073-tls`** — `Tls`, and `Transport` over TLS 1.3 with client
      authentication. Done when: two nodes whose certificates one CA signed
      exchange frames both ways; a peer signed by another CA, or with none,
      is refused, and what was sent to it is dropped and reported.
- [ ] **`spec-0073-names`** — the certificate's name checked against the
      hello's. Done when: a node with a good certificate for one name that
      claims another is refused, whichever side claims it.
- [ ] **`spec-0073-node`** — `node(…, tls = …)`, through `RemoteNode` to the
      cluster. Done when: three nodes form a cluster, shard an entity and
      ask it over TLS, and a fourth with a certificate from another CA never
      joins.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Mutual TLS, or a shared secret?** Recommended: mutual TLS, as above.
- **Check the node name against the certificate?** Recommended: yes, as a
  DNS subject alternative name, so identity is the certificate's and not the
  hello's.
- **Test certificates: made by `keytool` when the tests run, or checked in?**
  Recommended: checked in as PKCS12 files valid for a century, with the
  script that made them beside them, so the tests need no process and no
  clock.
- **TLS 1.3 only?** Recommended: yes; every JDK lark supports has it, and it
  leaves no cipher list to choose.
