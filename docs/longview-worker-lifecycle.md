# Bounded Longview worker lifecycle

This is the first concrete worker slice under #98. It is an acceptance fixture,
not a second browser/task framework and not the selected general IPC design.

## Identity

The browser-owned worker record separates:

- durable work identity;
- durable result identity;
- operation/replay class;
- attempt count and restart limit;
- current lifecycle phase;
- optional process attachment.

A process attachment contains PID, process start ticks, process name and host
generation. None of those replaces work or result identity.

A frontend or initiating rish caller disconnect does not mutate the durable
worker task. A process may disappear while the task and any committed result
remain.

## Recovery policy

The first policy is deliberately conservative:

- a committed result is never replayed merely because the worker later exits;
- a bounded observation may be automatically restarted while attempts remain;
- unavailable Shizuku authority waits for authority rather than becoming
  success;
- a state-changing/nonreplayable operation requires manual recovery rather than
  automatic replay.

The first deterministic fixture is a restartable bounded observation. It is not
an authenticated transaction and contains no secret or page-derived material.

## Owned process signaling

PID-only signaling is refused.

Before signaling a worker, the caller supplies the PID, Linux process start
identity from /proc/<pid>/stat, and process name captured for that attempt. The
remote shell checks those values immediately before kill in the same command.
A stale PID whose numeric value has been reused therefore fails instead of
targeting the new process.

Package force-stop is also restricted to the explicitly configured physical
fault-injection target.

## First Android fixture

`lib/android_longview_worker.grease` starts only a fixed reviewed operation.
The only interpolated fields are validated work/result atoms and a selected
immediate/delayed test mode. Page text, model output, downloaded scripts and
arbitrary shell command strings cannot become worker programs.

The fixture uses:

```text
/data/local/tmp/ib-longview-fixture-v1
  .ib-backend-id
  .ib-staging/
  objects/
  work/
```

This directory is intentionally shell-owned and the payload is deliberately
non-sensitive. It proves the shell worker lifecycle and the ordinary-file
publication shape without pretending shell UID can read IB app-private files.
It is not selected product storage.

The worker publishes at most 256 bytes through stage -> sync -> same-filesystem
rename -> sync and then marks its small status. A later rish invocation can
read the immutable result independently of the launching caller. The same
limitations documented for the ordinary shell store apply: this is not yet a
fine-grained whole-device power-loss receipt.

The launcher backgrounds the worker with stdin/stdout/stderr detached and HUP
ignored, then returns:

```text
worker<TAB>pid<TAB>start_ticks<TAB>process_name
```

Whether that process actually survives rish disconnection, Shizuku restart,
and the relevant Android lifecycle on the MIRO A1 remains **physical evidence
to collect**. The code does not infer survival from desktop shell behavior.

## Physical acceptance still required

At one exact source head, test separately:

1. immediate worker completes and a later caller reads its result;
2. launching rish/Termux caller exits while delayed worker is running;
3. UI/IB process dies while the worker is running;
4. owned delayed worker is killed and is observed as uncommitted;
5. Shizuku stops/restarts and committed information remains distinguishable
   from unavailable authority;
6. device reboot with Shizuku not restarted reports unavailable authority,
   never false success;
7. after Shizuku is restarted, inspect whether the fixed shell-owned durable
   result survived and record that physical fact without turning it into a
   general guarantee;
8. stale PID/start-tick receipt cannot signal a different process.

The provider/PFD/Binder/socket experiments still decide the eventual
authorized UI/app-private live/late-reader boundary. This fixture does not
retire them.
