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
import android.os.Debug;
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

/** The live WebView adapter for one browser-owned protected long-view task. */
public final class LongViewActivity extends Activity {
    private static final String DEFAULT_URL =
        "https://console.cloud.google.com/auth/scopes?project=cockswain&authuser=5";
    private static final String HEAVY_RESULT_ID = "longview-heavy-v1";
    private static final byte[] HEAVY_RESULT_BYTES = (
        "schema\tib-longview-useful-v1\n"
            + "fixture\theavy-v1\n"
            + "status\tuseful\n"
    ).getBytes(StandardCharsets.UTF_8);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable periodic_sample = new Runnable() {
        @Override
        public void run() {
            sample_page("periodic");
            handler.postDelayed(this, 5000);
        }
    };

    private DurableTaskStore store;
    private DurableResultStore result_store;
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
    private int last_progress_bucket = -1;
    private boolean form_dirty;
    private boolean destroyed;
    private FixtureServer heavy_fixture_server;
    private boolean first_useful_recorded;
    private long app_data_bytes_at_heavy_start;
    private long cumulative_offscreen_ms;
    private int peak_host_pss_kib;

    @Override
    protected void onCreate(Bundle saved_instance_state) {
        super.onCreate(saved_instance_state);
        started_at = SystemClock.elapsedRealtime();
        run_identity = "run-" + UUID.randomUUID();
        store = new DurableTaskStore(getFilesDir().toPath());
        result_store = new DurableResultStore(getFilesDir().toPath());

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

        receipt_file = create_receipt_file();
        build_ui();
        record("run", device_and_artifact_receipt());
        record("task", task_receipt("discovered"));
        record(
            "session-profile",
            "owner=webview-default-profile available-to-replacement-renderer=true authenticated=not-proven"
        );
        record(
            "security",
            "query-fragment-persisted=false form-values-persisted=false cookies-copied=false"
        );
        report_retained_heavy_result("startup");

        attach_webview(new_task);
        if (task.navigation.recovery == DurableNavigation.Recovery.UNAVAILABLE) {
            mark_remote_site_blocked("no-safe-navigation");
        } else {
            web_view.loadUrl(volatile_initial_url);
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
            cumulative_offscreen_ms += offscreen_duration;
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
        close_heavy_fixture();
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

        LinearLayout acceptance_row = controls_row();
        Button heavy_fixture = button("Heavy fixture");
        heavy_fixture.setOnClickListener(view -> start_heavy_fixture());
        acceptance_row.addView(heavy_fixture, weighted_button_params());
        Button retained_result = button("Retained result");
        retained_result.setOnClickListener(view -> report_retained_heavy_result("button"));
        acceptance_row.addView(retained_result, weighted_button_params());
        root.addView(acceptance_row);

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
        WebView view = new WebView(this);
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
                    record("progress", "percent=" + bucket);
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
                record("navigation", "started " + safe_target_identity(url));
            }

            @Override
            public void onPageCommitVisible(WebView committed_view, String url) {
                super.onPageCommitVisible(committed_view, url);
                task = task.page_committed(System.currentTimeMillis());
                persist_or_block(false, "page-commit");
                record("paint", "commit-visible " + task_receipt("commit"));
                install_form_dirty_tracker(committed_view);
                sample_page("commit-visible");
            }

            @Override
            public void onPageFinished(WebView finished_view, String url) {
                super.onPageFinished(finished_view, url);
                record("navigation", "page-finished " + safe_target_identity(url));
                install_form_dirty_tracker(finished_view);
                sample_page("page-finished");
                if (heavy_fixture_server != null
                    && heavy_fixture_server.heavy_url().equals(url)) {
                    record("heavy-metrics", heavy_metrics("page-finished"));
                }
            }

            @Override
            public void onReceivedError(
                WebView error_view,
                WebResourceRequest request,
                WebResourceError error
            ) {
                super.onReceivedError(error_view, request, error);
                if (request.isForMainFrame()) {
                    record("error", "main-frame code=" + error.getErrorCode());
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
        web_view.loadUrl(requested);
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
        web_view.loadUrl(task.navigation.neutral_url);
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
        handler.postDelayed(() -> Process.killProcess(Process.myPid()), 250);
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

    private void start_heavy_fixture() {
        close_heavy_fixture();
        FixtureServer server = new FixtureServer();
        try {
            server.start();
            heavy_fixture_server = server;
            first_useful_recorded = false;
            app_data_bytes_at_heavy_start = directory_bytes(getDataDir());
            peak_host_pss_kib = Debug.getPss();
            String url = server.heavy_url();
            begin_browser_navigation(url, "heavy-fixture");
            url_input.setText(url);
            form_dirty = false;
            if (web_view == null) {
                attach_webview(false);
            }
            record(
                "heavy-fixture",
                "started useful-result-id=" + HEAVY_RESULT_ID
                    + " irrelevant-assets=8 asset-bytes-min=262144"
                    + " " + heavy_metrics("before-load")
            );
            web_view.loadUrl(url);
        } catch (IOException | IllegalArgumentException exception) {
            server.close();
            if (heavy_fixture_server == server) {
                heavy_fixture_server = null;
            }
            record(
                "heavy-fixture",
                "start-failed class=" + exception.getClass().getSimpleName()
            );
        }
    }

    private void on_first_useful(String marker) {
        if (first_useful_recorded
            || !"heavy-v1".equals(marker)
            || heavy_fixture_server == null
            || web_view == null
            || !heavy_fixture_server.heavy_url().equals(web_view.getUrl())) {
            return;
        }

        first_useful_recorded = true;
        peak_host_pss_kib = Math.max(peak_host_pss_kib, Debug.getPss());
        try {
            result_store.commit_immutable(HEAVY_RESULT_ID, HEAVY_RESULT_BYTES);
            record(
                "first-useful",
                "marker=heavy-v1 durable-result=committed " + heavy_metrics("first-useful")
            );
        } catch (IOException | RuntimeException exception) {
            record(
                "first-useful",
                "marker=heavy-v1 durable-result=failed class="
                    + exception.getClass().getSimpleName()
                    + " " + heavy_metrics("first-useful")
            );
        }
    }

    private void report_retained_heavy_result(String reason) {
        if (result_store == null) {
            return;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(
                result_store.require_result(HEAVY_RESULT_ID)
            );
            boolean exact = java.util.Arrays.equals(bytes, HEAVY_RESULT_BYTES);
            record(
                "retained-result",
                "reason=" + clean(reason)
                    + " result-id=" + HEAVY_RESULT_ID
                    + " bytes=" + bytes.length
                    + " exact=" + exact
                    + " reacquired=false"
            );
        } catch (IOException | RuntimeException exception) {
            record(
                "retained-result",
                "reason=" + clean(reason)
                    + " result-id=" + HEAVY_RESULT_ID
                    + " status=absent"
            );
        }
    }

    private String heavy_metrics(String point) {
        peak_host_pss_kib = Math.max(peak_host_pss_kib, Debug.getPss());
        long served_bytes = heavy_fixture_server == null
            ? 0
            : heavy_fixture_server.served_body_bytes();
        long responses = heavy_fixture_server == null
            ? 0
            : heavy_fixture_server.served_response_count();
        long app_data_bytes = directory_bytes(getDataDir());
        long growth = Math.max(0, app_data_bytes - app_data_bytes_at_heavy_start);
        return "point=" + clean(point)
            + " served-body-bytes=" + served_bytes
            + " served-responses=" + responses
            + " peak-host-pss-kib=" + peak_host_pss_kib
            + " renderer-pss=not-measured"
            + " app-data-growth-bytes=" + growth
            + " cumulative-offscreen-ms=" + cumulative_offscreen_ms;
    }

    private static long directory_bytes(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0;
        File[] children = file.listFiles();
        if (children == null) {
            return 0;
        }
        for (File child : children) {
            total += directory_bytes(child);
        }
        return total;
    }

    private void close_heavy_fixture() {
        if (heavy_fixture_server == null) {
            return;
        }
        heavy_fixture_server.close();
        heavy_fixture_server = null;
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
        if (heavy_fixture_server != null) {
            peak_host_pss_kib = Math.max(peak_host_pss_kib, Debug.getPss());
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
            persist_task(navigation_event);
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
        String line = elapsed + "\t" + clean(event) + "\t" + clean(detail) + "\n";
        try (FileOutputStream output = new FileOutputStream(receipt_file, true)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (IOException exception) {
            append_status("receipt write failed: " + exception.getClass().getSimpleName());
            return;
        }
        append_status(elapsed + "ms " + clean(event) + " " + clean(detail));
    }

    private void copy_receipt() {
        record("receipt", "copy-request secrets-inspected=not-automated");
        try {
            String receipt = new String(
                java.nio.file.Files.readAllBytes(receipt_file.toPath()),
                StandardCharsets.UTF_8
            );
            ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("IB long-view receipt", receipt));
            append_status("receipt copied; inspect it for unexpected secrets before sharing");
        } catch (IOException exception) {
            append_status("receipt copy failed: " + exception.getClass().getSimpleName());
        }
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

        @JavascriptInterface
        public void firstUseful(String marker) {
            runOnUiThread(() -> on_first_useful(marker));
        }
    }
}
