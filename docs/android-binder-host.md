# Android Binder host boundary

## Architectural decision

IB is designed around **Binder**, not around Shizuku.

Longview, worker lifecycle, process identity, callbacks, file-descriptor transfer,
death/reconnect handling, and selected Android service operations should be
expressed as Binder-facing capabilities. They must not make Shizuku lifecycle,
`rish`, or shell command syntax part of browser semantics.

The current route on stock experimental Android is intentionally small:

```text
IB / Longview
     |
     v
Binder capability boundary
     |
     +-- provider today: Shizuku
     |
     v
Android Binder / system services / provider-owned worker mechanisms
```

`src/IB/Binder.idric` records the same decision in the semantic layer:
Binder access has a provider, and the only selected provider today is
`shizuku`.

## Current lowering

The implementation is transitional. Existing proven Shizuku/rish operations
are retained behind `lib/android_binder_host.grease`. The public names used by
new Longview/worker code are `ib_binder_*`; those functions currently delegate
to `ib_shizuku_*`.

That means the architecture no longer says "Longview talks to rish." It says
"Longview needs this Binder-side capability; Shizuku currently supplies it."
Some current operations still lower through `rish -c` while direct Binder
paths are proved and substituted underneath the same boundary.

The old `bin/ib_android_shizuku.grease` remains a backend/debug tool.
`bin/ib_android_binder.grease` is the Binder-facing development entry point.

## Migration rule

New browser/Longview code must not add a direct `ib_shizuku_*` dependency.
Provider-specific code belongs below the Binder boundary.

Existing Shizuku code is not deleted merely for naming purity. Move a behavior
behind Binder first, retain its tests/evidence, then replace the lowering when a
direct Binder implementation is physically accepted.

Shizuku is therefore replaceable by another Binder authority source—root,
system integration, Crawl Space, or a custom Android build—without changing
Longview's durable task model.
