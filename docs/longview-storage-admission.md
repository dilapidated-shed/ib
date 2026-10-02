# Longview storage admission and ordinary-file publication

This is the first implementation slice for #96 and #97. It does not move
WebView profiles, secrets, executables, or bulk phone data, and it does not
claim appendfat is present.

## Per-volume admission

IB keeps filesystem observation separate from browser reservations.

For one capacity pool, admission is equivalent to:

```text
observed available
- commitments not yet reflected in that observation
- proposed additional peak growth
>= configured safety floor
```

A reservation is accounting only. It is not a filesystem allocation guarantee.
When a later free-space observation is known to include bytes previously held
as a commitment, the matching commitment is cleared; otherwise the same bytes
would be charged twice.

Unknown, stale, denied, read-only, and absent observations remain distinct and
none can admit a write. The ledger uses `Integer` byte counts so policy tests
include capacities above 4 GiB without narrowing to a 32-bit value.

This first slice does not yet enforce every runtime envelope from #96. Network
transfer, decompression, decoded-image, request-concurrency, log-growth, and
opaque WebView-profile limits remain separate follow-on gates.

## Ordinary-file result contract

`lib/durable_object_store.grease` is a deliberately small ordinary-file
adapter used for deterministic acceptance.

A caller supplies:

- an expected backend identity;
- a logical object identity independent of mount pathname;
- a source file;
- a maximum permitted byte count.

The adapter refuses unsafe identifiers, an absent root, a changed backend at
the same pathname, an oversized source, and conflicting bytes for an already
published immutable object. Equal bytes for the same object are idempotent.

Publication copies into a private staging area, requests synchronization,
renames within the same filesystem, then requests synchronization again.
Readers only open the published object namespace, so an interrupted staging
tail is not a result. Independent late readers can reopen the same committed
bytes; reading does not consume them.

The shell adapter demonstrates process-interruption publication semantics and
issues `sync` requests. It does **not** claim a fine-grained whole-device
power-loss receipt equivalent to a reviewed native fsync/fsync-directory
implementation. That remains a later physical/native acceptance gate.

A card/backend identity is stored separately from its current pathname.
Moving the same store to another mount path preserves logical object lookup.
A different card mounted at the old path fails the expected-backend check
instead of being treated as the original store.

## Future appendfat adapter

appendfat, a userspace pre-zeroed append arena, and ordinary files may expose
different reservation and crash guarantees. They must enter the same
browser-owned admission/object-identity boundary and report unsupported
operations honestly. No simulated appendfat capability authorizes a physical
appendfat claim.
