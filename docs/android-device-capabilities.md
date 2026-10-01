# Android device capability report

This report is the first storage/capacity implementation slice under #95. It
runs after the Binder/workaround audit in #106 and uses the existing Shizuku
host boundary from #93 rather than creating another Android authority layer.

The report is observational. It does not move data, create directories, reserve
space, fill a filesystem, remount storage, format media, or test executable
placement on an SD card.

## Interfaces

```text
bin/ib_android_capabilities.grease report
bin/ib_android_capabilities.grease summary
```

`report` emits tab-separated machine-readable records. `summary` renders a
phone-readable subset from the same records.

The report schema is:

```text
KIND    NAME    STATUS    AUTHORITY    VALUE
```

The first line is `schema\tib-android-capabilities-v1`. Status and authority are
kept separate. A capability may be `measured`, `reported`, `unknown`,
`simulated`, `stale`, `invalid`, or `failed`; an unknown value is not zero and
is not success.

## Cat Food first, then live observation

IB consults Cat Food persistent state before asking Android about the live
machine. Today the concrete shared contract is Cat Food's preserved Shizuku
`rish` bundle from `isomorphisms/catfood#93` / PR #94. When present, IB reports
the snapshot id, source path, manager package and hashes from Cat Food's receipt.

Cat Food does not yet publish the full per-device manifest requested by
`isomorphisms/catfood#65`. The report therefore says that fact is unknown rather
than deriving an installation identity from a model name or checkout directory.
`IB_DEVICE_LABEL` is an explicit temporary local label for physical acceptance;
it is not inferred device identity.

Cat Food is configuration/history. It cannot prove that a remembered SD card is
currently mounted or that the IB app UID can write it. Live storage state is
observed separately.

## One bounded Shizuku snapshot

The Android observations are collected in one trusted static `rish -c` command,
not one shell process per field. It records:

- shell UID, `id` identity and SELinux context;
- product model, build fingerprint, Android release/API and ABI lists;
- bounded `df` rows;
- bounded `sm list-volumes all` rows;
- selected mount rows for `/data`, `/storage` and `/mnt/media_rw`;
- `MemTotal`, `MemAvailable`, swap/zram-related `/proc/meminfo` rows and
  memory-pressure information when readable;
- whether a mounted Android public volume is observed;
- whether appendfat is observed in current mounts.

A mounted public Android volume is surfaced as a **bulk candidate**, with its
mount path and `df` total/available KiB when a matching row exists. This is a
candidate, not a placement decision.

Counters remain text/wide integer values. The deterministic fixture includes a
32-bit phone profile and a public volume above 4 GiB so an implementation that
narrows sizes to 32 bits is rejected.

## What the report refuses to infer

Several Android pathnames can expose the same backing storage. A `df` row and an
Android volume record are observations, not proof of independent physical
capacity pools. The first report therefore does not add them together. It emits
`capacity_pool_relationship=unknown` until backing relationships are established
by evidence.

Likewise, shell visibility is not application write authority. The report emits
`ib_app_writer_access=unknown` until an app-side IB probe verifies the exact
approved root. A large removable volume must never cause Longview to silently
fall back to internal storage or assume that the app can publish there.

Disk and RAM remain separate. A large SD card does not authorize a large decoded
image, JavaScript heap, or other RAM working set.

## Appendfat and future capabilities

`IB_SIMULATE_APPENDFAT=1` adds a separate
`appendfat_simulation=simulated` record. It does not change the physical
`appendfat_status` record. A mock can therefore exercise future reserve/append
policy without claiming that appendfat is installed on the phone.

The same rule applies to later capabilities: simulated, reported, measured,
denied and unknown states must remain distinguishable.

## Deterministic acceptance

`tests/test_android_device_capabilities.grease` covers two installations with
the same `MIRO A1` model:

- one with a large mounted public volume;
- one without a public volume.

It also checks Cat Food snapshot ingestion, a >4 GiB capacity, explicit
appendfat simulation, unknown app-writer authority, and a lexical read-only
tripwire over the trusted remote command.

These tests prove the observation contract only. Physical acceptance still must
run the exact source head against real Shizuku/rish on each phone and preserve
the raw report as evidence. The next app-side slice must establish the actual IB
writer roots before #96 uses a volume for admitted writes.
