# App-private durable-result late reader

This carries the #84 / PR #87 experiment onto the current Longview stack. It
remains an acceptance adapter, not a new canonical storage model.

## Boundary

The IB app commits a fixed immutable result under its app-private files
directory. DurableResultProvider exposes that committed object only through a
fresh read-only ParcelFileDescriptor after Android has granted the reader
package access to the exact content URI.

The provider is not the durable store. Killing its process and creating a new
provider generation must not change the committed bytes.

The separate org.isomorphisms.ib.resultreader APK exists to make the Android
UID boundary visible. It records its own UID/PID and provider metadata,
including provider UID/PID/generation, Binder calling UID and calling package.

## Relation to the Longview storage work

This adapter is the app-private counterpart to the ordinary-file publication
contract in #97/#109:

- stable result identity is independent of provider process lifetime;
- publication precedes exposure;
- reads are independent and non-consuming;
- an unavailable provider is not an absent result;
- private result bytes are not moved into shared storage merely to cross a UID.

The implementation remains a narrow fixture with the fixed hello-v1 object.
It does not yet replace the general Longview result store or expose arbitrary
worker output.

## Acceptance retained from #87

CI installs the IB APK and result-reader APK under distinct Android UIDs,
commits the fixed result, grants the exact URI, opens two independent
descriptors, kills the IB/provider host process, and verifies a later reader
gets the same bytes through a new provider generation.

Physical MIRO A1 acceptance still matters for the actual Android 14 device,
especially grant lifetime across process death, force-stop and reboot. Grant
loss must be recorded as authorization loss rather than result loss.

## What this does not decide

This proves a durable late-read path. It does not select the live worker
control channel required by #85/#98, does not make a ContentProvider a worker
supervisor, and does not imply that Termux can directly bind to arbitrary IB
Binder objects.

The next transport experiment remains a live PFD/Binder channel with explicit
caller authorization and both process-death directions.
