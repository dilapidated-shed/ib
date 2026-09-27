package org.isomorphisms.ib.webview;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.InputType;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewRenderProcess;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The live WebView adapter for one browser-owned protected long-view task. */
public final class LongViewActivity extends Activity {
    private static final String DEFAULT_URL =
        "https://console.cloud.google.com/auth/scopes?project=cockswain&authuser=5";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService receipt_writer = Executors.newSingleThreadExecutor();
    private final Runnable periodic_sample = new Runnable() {
        @Override
        public void run() {
            sample_page("periodic");
            handler.postDelayed(this, 5000);
        }
    };

    private DurableTaskStore store;
    private DurableTaskRecord task;
    private LinearLayout web_container;
    private EditText url_input;
    private TextView status;
    private WebView web_view;
    private File receipt_file;
    private String run_identity;
    private String volatile_initial_url;
    private String observed_heap_canary;
    private long started_at;
    private long offscreen_started_at;
    private long state_recovery_ms;
    private long load_requested_at;
    private int load_sequence;
    private int last_progress_bucket = -1;
    private boolean form_dirty;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle saved_instance_state) {
        super.onCreate(saved_instance_state);
        started_at = SystemClock.elapsedRealtime();
        run_identity = "run-" + UUID.randomUUID();
        store = new DurableTaskStore(getFilesDir().toPath());
        long state_recovery_started_at = SystemClock.elapsedRealtime();

        String supplied_url = getIntent().getStringExtra("url");
        String requested_url = supplied_url == null || supplied_url.trim().isEmpty()
            ? DEFAULT_URL
            : supplied_url.trim();

        boolean new_task = false;
        try {
            DurableTaskRecord discovered = store.discover_latest();
            if (discovered == null) {
                DurableNavigation navigation = DurableNavigation.from_user_url(requested_url);
                task = DurableTaskRecord.start(
                    "task-" + UUID.randomUUID(),
                    "tab-" + UUID.randomUUID(),
                    "navigation-" + UUID.randomUUID(),
                    navigation,
                    HostProcessIdentity.VALUE,
                    Process.myPid(),
                    System.currentTimeMillis()
                );
                volatile_initial_url = requested_url;
                persist_task(true);
                new_task = true;
            } else {
                task = discovered.opened_by_host(
                    HostProcessIdentity.VALUE,
                    Process.myPid(),
                    System.currentTimeMillis()
                );
                persist_task(false);
                volatile_initial_url = task.navigation.neutral_url;
            }
        } catch (IOException | IllegalArgumentException exception) {
            show_fatal_state("Durable task recovery failed: " + exception.getMessage());
            return;
        }

        state_recovery_ms = SystemClock.elapsedRealtime() - state_recovery_started_at;
        receipt_file = create_receipt_file();
        build_ui();
        record("run", device_and_artifact_receipt());
        record("startup", "state-recovery-and-checkpoint-ms=" + state_recovery_ms);
        record("task", task_receipt("discovered"));
        record(
            "session-profile",
            "owner=webview-default-profile available-to-replacement-renderer=true authenticated=not-proven"
        );
        record(
            "security",
            "query-fragment-persisted=false form-values-persisted=false cookies-copied=false"
        );

        attach_webview(new_task);
        if (task.navigation.recovery == DurableNavigation.Recovery.UNAVAILABLE) {
            mark_remote_site_blocked("no-safe-navigation");
        } else {
            load_url(volatile_initial_url, "initial");
        }
        handler.postDelayed(periodic_sample, 5000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String supplied_url = intent.getStringExtra("url");
        if (supplied_url != null && !supplied_url.trim().isEmpty()) {
            url_input.setText(supplied_url);
            navigate_to_entered_url();
            return;
        }
        record("return", "case=live-renderer-survival " + task_receipt("reentry"));
        sample_page("launcher-reentry");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (task != null && status != null) {
            long offscreen_duration = offscreen_started_at == 0
                ? 0
                : SystemClock.elapsedRealtime() - offscreen_started_at;
            offscreen_started_at = 0;
            record(
                "activity",
                "foreground off-screen-duration-ms=" + offscreen_duration
                    + " " + task_receipt("resume")
            );
        }
    }

    @Override
    protected void onPause() {
        if (task != null && status != null) {
            offscreen_started_at = SystemClock.elapsedRealtime();
            record("activity", "off-screen task-remains-durable=true");
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(periodic_sample);
        destroy_webview();
        receipt_writer.shutdown();
        super.onDestroy();
    }

    private void build_ui() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView heading = new TextView(this);
        heading.setText("IB protected long-view task");
        heading.setTextSize(19);
        heading.setPadding(dp(12), dp(10), dp(12), dp(4));
        root.addView(heading);

        LinearLayout address_row = new LinearLayout(this);
        address_row.setOrientation(LinearLayout.HORIZONTAL);
        url_input = new EditText(this);
        url_input.setSingleLine(true);
        url_input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url_input.setSaveEnabled(false);
        url_input.setText(volatile_initial_url);
        url_input.setSelectAllOnFocus(true);
        address_row.addView(
            url_input,
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        );
        Button go = button("Go");
        go.setOnClickListener(view -> navigate_to_entered_url());
        address_row.addView(go);
        root.addView(address_row);

        LinearLayout lifecycle_row = controls_row();
        Button reload = button("Safe reload");
        reload.setOnClickListener(view -> reconstruct_from_neutral_url("user-reload"));
        lifecycle_row.addView(reload, weighted_button_params());
        Button kill_renderer = button("Kill renderer");
        kill_renderer.setOnClickListener(view -> kill_renderer());
        lifecycle_row.addView(kill_renderer, weighted_button_params());
        Button kill_host = button("Kill IB host");
        kill_host.setOnClickListener(view -> kill_host_process());
        lifecycle_row.addView(kill_host, weighted_button_params());
        root.addView(lifecycle_row);

        LinearLayout result_row = controls_row();
        Button session_works = button("Session works");
        session_works.setOnClickListener(view -> confirm_authenticated_session());
        result_row.addView(session_works, weighted_button_params());
        Button blocked = button("Needs repeat");
        blocked.setOnClickListener(view -> mark_remote_site_blocked("user-reported"));
        result_row.addView(blocked, weighted_button_params());
        Button sample = button("Sample");
        sample.setOnClickListener(view -> sample_page("manual"));
        result_row.addView(sample, weighted_button_params());
        root.addView(result_row);

        LinearLayout receipt_row = controls_row();
        Button copy_receipt = button("Copy receipt");
        copy_receipt.setOnClickListener(view -> copy_receipt());
        receipt_row.addView(copy_receipt, weighted_button_params());
        root.addView(receipt_row);

        status = new TextView(this);
        status.setTextSize(11);
        status.setMinLines(6);
        status.setMaxLines(10);
        status.setTextIsSelectable(true);
        status.setPadding(dp(12), dp(4), dp(12), dp(6));
        root.addView(status);

        web_container = new LinearLayout(this);
        web_container.setOrientation(LinearLayout.VERTICAL);
        root.addView(
            web_container,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        );
        setContentView(root);
    }

    private void attach_webview(boolean initial_renderer) {
        if (web_view != null) {
            return;
        }
        String renderer_identity = "renderer-" + UUID.randomUUID();
        long constructor_started_at = SystemClock.elapsedRealtime();
        WebView view = new WebView(this);
        record(
            "webview",
            "constructor-ms=" + (SystemClock.elapsedRealtime() - constructor_started_at)
        );
        view.setSaveEnabled(false);
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(view, true);

        view.addJavascriptInterface(new PageBridge(), "IBLongView");
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView changed_view, int new_progress) {
                super.onProgressChanged(changed_view, new_progress);
                int bucket = Math.min(100, (new_progress / 10) * 10);
                if (bucket != last_progress_bucket) {
                    last_progress_bucket = bucket;
                    record(
                        "progress",
                        "sequence=" + load_sequence
                            + " elapsed-ms=" + load_elapsed_ms()
                            + " percent=" + bucket
                    );
                }
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView loading_view, WebResourceRequest request) {
                if (request.isForMainFrame() && request.hasGesture()) {
                    try {
                        begin_browser_navigation(request.getUrl().toString(), "page-gesture");
                    } catch (IllegalArgumentException exception) {
                        record("navigation", "blocked-unsafe-target");
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView started_view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(started_view, url, favicon);
                last_progress_bucket = -1;
                observe_neutral_navigation(url);
                record(
                    "load-stage",
                    "sequence=" + load_sequence
                        + " stage=page-started elapsed-ms=" + load_elapsed_ms()
                        + " target=" + safe_target_identity(url)
                );
            }

            @Override
            public void onPageCommitVisible(WebView committed_view, String url) {
                super.onPageCommitVisible(committed_view, url);
                task = task.page_committed(System.currentTimeMillis());
                persist_or_block(false, "page-commit");
                record(
                    "load-stage",
                    "sequence=" + load_sequence
                        + " stage=commit-visible elapsed-ms=" + load_elapsed_ms()
                        + " " + task_receipt("commit")
                );
                sample_navigation_timing(committed_view, "commit-visible");
                install_form_dirty_tracker(committed_view);
                sample_page("commit-visible");
            }

            @Override
            public void onPageFinished(WebView finished_view, String url) {
                super.onPageFinished(finished_view, url);
                record(
                    "load-stage",
                    "sequence=" + load_sequence
                        + " stage=page-finished elapsed-ms=" + load_elapsed_ms()
                        + " target=" + safe_target_identity(url)
                );
                sample_navigation_timing(finished_view, "page-finished");
                install_form_dirty_tracker(finished_view);
                sample_page("page-finished");
            }

            @Override
            public void onReceivedError(
                WebView error_view,
                WebResourceRequest request,
                WebResourceError error
            ) {
                super.onReceivedError(error_view, request, error);
                if (request.isForMainFrame()) {
                    record(
                        "error",
                        "sequence=" + load_sequence
                            + " elapsed-ms=" + load_elapsed_ms()
                            + " main-frame code=" + error.getErrorCode()
                    );
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView dead_view, RenderProcessGoneDetail detail) {
                task = task.renderer_was_lost(System.currentTimeMillis());
                persist_or_block(false, "renderer-loss");
                record(
                    "renderer-gone",
                    "didCrash=" + detail.didCrash()
                        + " priority=" + detail.rendererPriorityAtExit()
                        + " form-dirty=" + form_dirty + " " + task_receipt("lost")
                );
                if (dead_view == web_view) {
                    web_container.removeView(dead_view);
                    dead_view.destroy();
                    web_view = null;
                }
                attach_webview(false);
                reconstruct_from_neutral_url("renderer-replacement");
                return true;
            }
        });

        web_view = view;
        web_container.addView(
            view,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        );
        task = initial_renderer
            ? task.attach_initial_renderer(renderer_identity, System.currentTimeMillis())
            : task.attach_replacement_renderer(renderer_identity, System.currentTimeMillis());
        persist_or_block(false, "renderer-attachment");
        record("renderer", "attached " + task_receipt(initial_renderer ? "initial" : "replacement"));
    }

    private void navigate_to_entered_url() {
        String requested = url_input.getText().toString().trim();
        try {
            begin_browser_navigation(requested, "address-bar");
        } catch (IllegalArgumentException exception) {
            append_status(exception.getMessage());
            return;
        }
        form_dirty = false;
        if (web_view == null) {
            attach_webview(false);
        }
        load_url(requested, "address-bar");
    }

    private void begin_browser_navigation(String requested, String source) {
        DurableNavigation navigation = DurableNavigation.from_user_url(requested);
        task = task.begin_navigation(
            "navigation-" + UUID.randomUUID(),
            navigation,
            System.currentTimeMillis()
        );
        persist_or_block(true, "new-navigation");
        record("user", "navigate source=" + source + " target=" + safe_target_identity(requested));
    }

    private void observe_neutral_navigation(String url) {
        try {
            task = task.observe_current_navigation(
                DurableNavigation.from_user_url(url),
                System.currentTimeMillis()
            );
            persist_or_block(false, "navigation-observation");
        } catch (IllegalArgumentException exception) {
            record("navigation", "neutral-target-unavailable");
        }
    }

    private void reconstruct_from_neutral_url(String reason) {
        if (web_view == null) {
            attach_webview(false);
        }
        if (task.navigation.recovery == DurableNavigation.Recovery.UNAVAILABLE) {
            mark_remote_site_blocked("no-safe-navigation");
            return;
        }
        form_dirty = false;
        record(
            "reconstruction",
            "requested reason=" + reason
                + " address=" + task.navigation.recovery.record_text
                + " authenticated=not-proven"
        );
        load_url(task.navigation.neutral_url, "reconstruction-" + reason);
    }

    private void load_url(String url, String reason) {
        load_sequence += 1;
        load_requested_at = SystemClock.elapsedRealtime();
        last_progress_bucket = -1;
        record(
            "load",
            "sequence=" + load_sequence
                + " stage=loadUrl elapsed-ms=0"
                + " reason=" + clean(reason)
                + " target=" + safe_target_identity(url)
        );
        web_view.loadUrl(url);
    }

    private long load_elapsed_ms() {
        if (load_requested_at == 0) {
            return -1;
        }
        return SystemClock.elapsedRealtime() - load_requested_at;
    }

    private void sample_navigation_timing(WebView view, String reason) {
        String script = "(function(){"
            + "var n=performance.getEntriesByType('navigation')[0];"
            + "if(!n){return 'available=false';}"
            + "var r=function(x){return Math.max(0,Math.round(x||0));};"
            + "var tls=n.secureConnectionStart>0?r(n.connectEnd-n.secureConnectionStart):0;"
            + "return 'available=true'"
            + "+';dns-ms='+r(n.domainLookupEnd-n.domainLookupStart)"
            + "+';connect-ms='+r(n.connectEnd-n.connectStart)"
            + "+';tls-ms='+tls"
            + "+';request-to-first-byte-ms='+r(n.responseStart-n.requestStart)"
            + "+';response-ms='+r(n.responseEnd-n.responseStart)"
            + "+';dom-interactive-ms='+r(n.domInteractive)"
            + "+';dom-content-loaded-ms='+r(n.domContentLoadedEventEnd)"
            + "+';load-event-ms='+r(n.loadEventEnd)"
            + "+';redirect-count='+r(n.redirectCount)"
            + "+';transfer-size='+r(n.transferSize)"
            + "+';encoded-body-size='+r(n.encodedBodySize)"
            + "+';decoded-body-size='+r(n.decodedBodySize)"
            + "+';protocol='+(n.nextHopProtocol||'unknown');"
            + "})()";
        view.evaluateJavascript(
            script,
            result -> record(
                "navigation-timing",
                "sequence=" + load_sequence
                    + " reason=" + clean(reason)
                    + " metrics=" + clean(result)
            )
        );
    }

    private void kill_renderer() {
        if (web_view == null || Build.VERSION.SDK_INT < 29) {
            append_status("renderer termination unavailable");
            return;
        }
        WebViewRenderProcess renderer = web_view.getWebViewRenderProcess();
        if (renderer == null) {
            append_status("isolated renderer process unavailable");
            return;
        }
        record("renderer", "termination-requested renderer-id=" + task.renderer_id);
        append_status(renderer.terminate() ? "renderer termination sent" : "renderer termination refused");
    }

    private void kill_host_process() {
        persist_or_block(false, "host-termination-checkpoint");
        record("host", "termination-requested relaunch-required=true");
        receipt_writer.execute(() -> {
            sync_receipt();
            Process.killProcess(Process.myPid());
        });
    }

    private void confirm_authenticated_session() {
        task = task.confirm_authenticated(System.currentTimeMillis());
        persist_or_block(false, "session-confirmation");
        record("user", "authenticated-session-confirmed " + task_receipt("confirmed"));
    }

    private void mark_remote_site_blocked(String reason) {
        task = task.mark_remote_site_blocked(System.currentTimeMillis());
        persist_or_block(false, "remote-site-blocked");
        record(
            "user-step",
            "required=repeat-site-step reason=" + clean(reason) + " " + task_receipt("blocked")
        );
    }

    private void install_form_dirty_tracker(WebView view) {
        String script = "(function(){"
            + "if(window.__ib_long_view_form_tracker){return 'already';}"
            + "window.__ib_long_view_form_tracker=true;"
            + "var dirty=function(){try{IBLongView.formDirty();}catch(e){}};"
            + "document.addEventListener('input',dirty,true);"
            + "document.addEventListener('change',dirty,true);"
            + "return 'installed';"
            + "})()";
        view.evaluateJavascript(script, result -> record("forms", "tracker=" + clean(result)));
    }

    private void sample_page(String reason) {
        if (destroyed || web_view == null) {
            return;
        }
        String canary_script = "(function(){"
            + "if(!window.__ib_long_view_heap_canary){"
            + "window.__ib_long_view_heap_canary=String(Math.round(performance.timeOrigin))+'-'+String(Math.random()).slice(2);"
            + "}"
            + "return window.__ib_long_view_heap_canary;"
            + "})()";
        web_view.evaluateJavascript(canary_script, current_canary -> {
            String heap_observation;
            if (observed_heap_canary == null) {
                observed_heap_canary = current_canary;
                heap_observation = "not-comparable";
            } else if (observed_heap_canary.equals(current_canary)) {
                task = task.observe_heap(true, System.currentTimeMillis());
                heap_observation = "live-same";
            } else {
                observed_heap_canary = current_canary;
                task = task.observe_heap(false, System.currentTimeMillis());
                heap_observation = "recreated";
            }
            persist_or_block(false, "heap-observation");
            sample_page_counts(reason, heap_observation);
        });
    }

    private void sample_page_counts(String reason, String heap_observation) {
        String script = "(function(){"
            + "var r=performance.getEntriesByType('resource');"
            + "var controls=document.querySelectorAll('input,select,textarea,button').length;"
            + "return 'ready='+document.readyState+';resources='+r.length+';forms='+document.forms.length+';controls='+controls;"
            + "})()";
        web_view.evaluateJavascript(
            script,
            result -> record(
                "sample",
                "reason=" + reason + " heap=" + heap_observation + " metrics=" + clean(result)
            )
        );
    }

    private void persist_task(boolean navigation_event) throws IOException {
        store.save(task, DurableTaskStore.TabProtection.PROTECTED_AUTHENTICATED_TRANSACTION);
        if (navigation_event) {
            store.append_navigation(task);
        }
    }

    private void persist_or_block(boolean navigation_event, String operation) {
        try {
            long started = SystemClock.elapsedRealtime();
            persist_task(navigation_event);
            record(
                "storage",
                "operation=" + clean(operation)
                    + " ui-thread-ms=" + (SystemClock.elapsedRealtime() - started)
                    + " navigation-event=" + navigation_event
            );
        } catch (IOException exception) {
            record("storage-error", "operation=" + operation + " class=" + exception.getClass().getSimpleName());
            append_status("durable task write failed; do not trust restart recovery");
        }
    }

    private File create_receipt_file() {
        File root = new File(getFilesDir(), "receipts/" + task.task_id);
        if (!root.mkdirs() && !root.isDirectory()) {
            throw new IllegalStateException("could not create receipt directory");
        }
        return new File(root, run_identity + ".tsv");
    }

    private String device_and_artifact_receipt() {
        ActivityManager activity_manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        String provider = "unknown";
        if (WebView.getCurrentWebViewPackage() != null) {
            provider = WebView.getCurrentWebViewPackage().packageName
                + ":" + WebView.getCurrentWebViewPackage().versionName;
        }
        return "receipt-schema=ib-long-view-physical-v1"
            + " source-head=" + BuildConfig.IB_SOURCE_HEAD
            + " apk-version=" + BuildConfig.VERSION_NAME + ":" + BuildConfig.VERSION_CODE
            + " android=" + Build.VERSION.RELEASE + ":api-" + Build.VERSION.SDK_INT
            + " webview=" + provider
            + " low-ram=" + activity_manager.isLowRamDevice()
            + " run-id=" + run_identity;
    }

    private String task_receipt(String point) {
        return "point=" + point
            + " task-id=" + task.task_id
            + " tab-id=" + task.tab_id
            + " navigation-id=" + task.navigation_id
            + " host-pid=" + Process.myPid()
            + " host-process-id=" + task.host_process_id
            + " host-generation=" + task.host_generation
            + " activity-generation=" + task.activity_generation
            + " renderer-id=" + (task.renderer_id.isEmpty() ? "none" : task.renderer_id)
            + " renderer-generation=" + task.renderer_generation
            + " continuity=" + task.continuity.record_text
            + " session=" + task.session_result.record_text
            + " reconstruction=" + task.reconstruction_result.record_text
            + " address=" + task.navigation.recovery.record_text;
    }

    private void record(String event, String detail) {
        if (receipt_file == null) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - started_at;
        String clean_event = clean(event);
        String clean_detail = clean(detail);
        String line = elapsed + "\t" + clean_event + "\t" + clean_detail + "\n";
        receipt_writer.execute(() -> append_receipt_line(line));
        append_status(elapsed + "ms " + clean_event + " " + clean_detail);
    }

    private void append_receipt_line(String line) {
        try (FileOutputStream output = new FileOutputStream(receipt_file, true)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            runOnUiThread(
                () -> append_status("receipt write failed: " + exception.getClass().getSimpleName())
            );
        }
    }

    private void sync_receipt() {
        try (FileOutputStream output = new FileOutputStream(receipt_file, true)) {
            output.getFD().sync();
        } catch (IOException exception) {
            runOnUiThread(
                () -> append_status("receipt sync failed: " + exception.getClass().getSimpleName())
            );
        }
    }

    private void copy_receipt() {
        record("receipt", "copy-request secrets-inspected=not-automated");
        receipt_writer.execute(() -> {
            try {
                String receipt = new String(
                    java.nio.file.Files.readAllBytes(receipt_file.toPath()),
                    StandardCharsets.UTF_8
                );
                runOnUiThread(() -> {
                    ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("IB long-view receipt", receipt));
                    append_status("receipt copied; inspect it for unexpected secrets before sharing");
                });
            } catch (IOException exception) {
                runOnUiThread(
                    () -> append_status("receipt copy failed: " + exception.getClass().getSimpleName())
                );
            }
        });
    }

    private void show_fatal_state(String message) {
        TextView fatal = new TextView(this);
        fatal.setText(message);
        fatal.setTextSize(16);
        fatal.setPadding(dp(16), dp(16), dp(16), dp(16));
        setContentView(fatal);
    }

    private void append_status(String line) {
        if (status == null) {
            return;
        }
        CharSequence previous = status.getText();
        String combined = previous.length() == 0 ? line : previous + "\n" + line;
        String[] lines = combined.split("\n");
        int first = Math.max(0, lines.length - 10);
        StringBuilder bounded = new StringBuilder();
        for (int index = first; index < lines.length; index++) {
            if (bounded.length() > 0) {
                bounded.append('\n');
            }
            bounded.append(lines[index]);
        }
        status.setText(bounded.toString());
    }

    private void destroy_webview() {
        if (web_view == null) {
            return;
        }
        web_container.removeView(web_view);
        web_view.destroy();
        web_view = null;
    }

    private static String safe_target_identity(String url) {
        try {
            return DurableNavigation.from_user_url(url).neutral_url;
        } catch (IllegalArgumentException exception) {
            Uri parsed = Uri.parse(url);
            return parsed.getScheme() == null ? "opaque" : parsed.getScheme() + ":opaque";
        }
    }

    private static String clean(String text) {
        if (text == null) {
            return "null";
        }
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private LinearLayout controls_row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams weighted_button_params() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class PageBridge {
        @JavascriptInterface
        public void formDirty() {
            runOnUiThread(() -> {
                if (!form_dirty) {
                    form_dirty = true;
                    record("forms", "dirty=true values-read=false values-persisted=false");
                }
            });
        }
    }
}
