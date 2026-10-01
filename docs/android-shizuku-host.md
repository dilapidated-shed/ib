# Android host through Shizuku

## Current decision

On experimental Android, IB may assume that the user starts Shizuku and makes
`rish` available. IB does not need a separate Crawl Space privilege broker in
front of Shizuku.

Crawl Space remains available as a Shizuku-family fork/variant when IB or the
phone OS needs a change that is better made below the host interface. It is not
an additional mandatory hop.

```text
IB browser/task semantics
          |
          v
Grease host/process work
          |
          v
Shizuku rish today
          |
          v
remote Android shell (normally UID 2000)
          |
          +--> Android commands/services
          +--> processes
          +--> /proc and other shell-visible state
```

Linux, macOS, Windows, root Android, or a future Crawl Space system should
implement the same host intentions without requiring the browser core to know
about Shizuku.

## First implemented surface

`lib/android_shizuku_host.grease` currently provides:

- discovery of the `rish` executable, with `IB_RISH_COMMAND` as an explicit
  override;
- execution of one trusted Grease command through `rish -c`, preserving the
  remote exit status and stderr;
- effective-UID / authority inspection;
- an identity receipt including the remote `id` result and SELinux context
  when Android exposes it;
- a raw process snapshot through `ps -A`;
- validated PID/signal forwarding;
- package PID lookup through `pidof`;
- deliberate Android package stop through ActivityManager's `am force-stop`.

`bin/ib_android_shizuku.grease` exposes those operations for development.

This is deliberately a small mechanism surface. Browser tasks, tab identity,
restart policy, durable results, and renderer policy remain owned by the Idriç
core.

The package-stop operation is immediately useful to the protected long-view
work: physical acceptance can kill the actual IB package through Shizuku rather
than requiring an ADB session, then verify that the durable task survives the
new host generation. The stop mechanism remains distinct from the durable task
semantics being tested.

## Why rish first

Upstream rish is specifically a shell whose process is created by the
high-privilege Shizuku/Sui daemon. It passes shell arguments to the remote
Android shell; for example, `rish -c 'ls'` executes `/system/bin/sh -c 'ls'`
remotely.

That makes it a useful first backend for IB process and operating-system work.
If command startup or text parsing later becomes measurable overhead, a direct
Shizuku Binder adapter can replace individual hot operations behind the same
host boundary.

Long-running work should eventually use maintained remote workers rather than a
large number of tiny `rish -c` calls. Those worker processes/threads remain
normal kernel-scheduled work; Shizuku supplies authority, not CPU scheduling.

## Evidence boundary

`tests/test_android_shizuku_host.grease` uses a fake rish executable only to
test local adapter behavior: argument forwarding, exit-status preservation,
identity classification, process/signal dispatch, and rejection of an unsafe
PID string.

That test is **not** Shizuku acceptance. Physical MIRO A1 acceptance still must
show the exact branch revision running against real Shizuku/rish and record the
remote UID, SELinux context, process visibility, signal behavior, and exact
denials.

Upstream rish reference:
https://github.com/RikkaApps/Shizuku-API/tree/master/rish
