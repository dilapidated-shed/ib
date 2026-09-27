# IB WebView protected transactions

The application launcher is now **IB Long View**, the first live browser path
whose protected task survives both renderer replacement and IB host-process
restart.  It uses browser-owned task, tab, and navigation identities under the
app-private `state/` tree.  The task and security model are documented in
[`docs/long-view-protected-tasks.md`](../docs/long-view-protected-tasks.md).

The original issue #59 loopback harness remains in `WebViewActivity` as test
equipment.  It is no longer the launcher and does not own the product model.

## Long-view behavior

The launcher opens a switchable real URL.  The complete URL is used only by the
live renderer; durable state retains only a neutral scheme/host/port origin and
records whether path, query, or fragment material was removed.  On renderer death the
activity attaches a replacement WebView to the same task.  **Kill IB host**
provides the separate whole-process acceptance leg; after relaunch IB discovers
the same task, advances the host generation, and performs safe reconstruction.

WebView's app profile owns cookies.  The task file does not copy them, and a
reloaded page remains `authenticated=not-proven` until **Session works** is
pressed.  **Needs repeat** records an explicit incomplete remote-site state.

The path does not require Picture-in-Picture or a foreground service.  Important
renderer priority remains a survival optimization, not the correctness model.

**Copy receipt** produces a phone-visible receipt without query strings, field
values, cookies, authorization codes, or heap-canary values.  Inspect the copied
text for unexpected secrets before sharing it.

## Page-load accounting

Version 0.6.0 records page-load latency without putting receipt `fsync` calls on
the Android UI thread. Each main-frame load gets a sequence number and monotonic
times for the request, page start, first committed visible paint, progress
buckets, and page finish. At commit and finish the page also reports
`PerformanceNavigationTiming` durations for DNS, connect/TLS, request to first
byte, response, DOM milestones, protocol, and byte counts. The timing report
does not include the page URL.

Protected-task persistence is still synchronous where it defines restartable
browser state. Each durable checkpoint records how many milliseconds it blocked
the UI thread. Main-frame navigation observations caused by redirects are kept
in memory until the page commits, and heap-canary samples are diagnostic only;
neither forces a durable write on its own.

The trace separately records time from the user/navigation request to WebView
navigation start and time from WebView navigation start to the browser callbacks.
This makes pre-network IB storage delay visible instead of folding it into DNS or
server time.

Ordinary diagnostic receipt lines are appended by one background writer without
a per-line flush. The deliberate **Kill IB host** control queues an explicit
receipt sync behind prior receipt writes before terminating the process.

## Issue #59 fixture

This is a deliberately separate Android fixture for issue #59. It is not the
prepaint viewer and it does not change the prepaint boundary.

The app starts a loopback HTTP server that establishes an HttpOnly authenticated
session, then displays a tiny form in WebView. The form has one ordinary field
that the host may checkpoint and one synthetic password field that the host must
not checkpoint.

The host records only:

- current fixture URL;
- scroll position;
- ordinary-field value;
- the renderer heap canary used only as an acceptance observation.

It does not call `WebView.saveState()`, persist a DOM, persist the synthetic
password field, or claim JavaScript heap continuity.

## Manual phone run

1. Install and launch the debug APK.
2. Wait for `Protected transaction fixture` to appear.
3. Tap **Seed fields** (or type your own ordinary and synthetic-secret values).
4. Tap **Protect**.
5. Tap **Pressure**, allocate memory in the separate `:pressure` process, and
   return to the WebView.
6. Tap **Check live**. The receipt reports authenticated-session survival,
   ordinary-form survival, whether the synthetic secret is still live, renderer
   death events, and whether the same live document/heap canary survived.
7. On API 29+, tap **Kill renderer**. If the WebView provider exposes an
   isolated renderer process, `WebViewRenderProcess.terminate()` requests a
   deterministic renderer death. The `onRenderProcessGone` callback destroys
   the dead WebView, creates a new one, reloads the same URL, and restores only
   the ordinary field and scroll position.
8. Tap **Check recovery**. A successful narrow recovery shows session survival,
   ordinary-field reconstruction, the synthetic secret left blank, a new heap
   canary, and a renderer-death event.

If `getWebViewRenderProcess()` returns null, the receipt says isolated renderer
recovery is unavailable on that provider/device. The fixture does not substitute
`chrome://crash` or pretend host-process death is renderer-process death.

## Update identity

The package name and version code are part of the acceptance boundary. CI signs
the installable debug artifact with the repository's stable public test signer
and verifies repeated `adb install -r` replacement without uninstalling. A
local build without the configured stable signer is deliberately left unsigned
rather than receiving a machine-local Gradle debug identity. The test signer is
not a production or store signing identity.

## Evidence boundary

This first slice can provide four separate observations:

- cookie/session survival;
- form-state survival while the renderer remains live;
- renderer survival or renderer-death observation;
- reconstruction after renderer death.

It does **not** yet prove recovery after the IB host process itself is killed.
The in-memory checkpoint is intentional so renderer-death recovery is not
silently conflated with durable host-process recovery.

`android:usesCleartextTraffic="true"` is present only because the deterministic
fixture server is HTTP on `127.0.0.1`. The harness does not browse arbitrary
cleartext sites.
