package org.isomorphisms.ib.webview;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Phone-visible preparation and process-death control for issue #84. */
public final class DurableResultFixtureActivity extends Activity {
    private static final String READER_PACKAGE = "org.isomorphisms.ib.resultreader";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private File receipt_file;
    private DurableResultStore store;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DurableResultStore(getFilesDir().toPath());
        receipt_file = new File(getFilesDir(), "receipts/durable-result-fixture.tsv");
        File parent = receipt_file.getParentFile();
        if (!parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("could not create fixture receipt directory");
        }
        build_ui();
        record(
            "fixture",
            "source-head=" + BuildConfig.IB_SOURCE_HEAD
                + " apk-version=" + BuildConfig.VERSION_NAME + ":" + BuildConfig.VERSION_CODE
                + " android=" + Build.VERSION.RELEASE + ":api-" + Build.VERSION.SDK_INT
                + " package=" + getPackageName()
                + " uid=" + Process.myUid()
                + " pid=" + Process.myPid()
        );
        prepare_result_and_grant();
    }

    private void build_ui() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView heading = new TextView(this);
        heading.setText("IB durable result fixture");
        heading.setTextSize(20);
        root.addView(heading);

        TextView instructions = new TextView(this);
        instructions.setText(
            "Termux entry:\n"
                + "termux-open-url ib://durable-result-fixture\n\n"
                + "Reader entry after the result is granted:\n"
                + "termux-open-url " + DurableResultProvider.RESULT_URI
        );
        instructions.setTextIsSelectable(true);
        instructions.setPadding(0, dp(8), 0, dp(8));
        root.addView(instructions);

        Button prepare = button("Commit result and grant reader");
        prepare.setOnClickListener(view -> prepare_result_and_grant());
        root.addView(prepare);

        Button kill = button("Kill IB host");
        kill.setOnClickListener(view -> kill_host());
        root.addView(kill);

        Button copy = button("Copy fixture receipt");
        copy.setOnClickListener(view -> copy_receipt());
        root.addView(copy);

        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);

        setContentView(root);
    }

    private void prepare_result_and_grant() {
        try {
            Path result = store.commit_fixture();
            int reader_uid = package_uid(READER_PACKAGE);
            grantUriPermission(
                READER_PACKAGE,
                DurableResultProvider.RESULT_URI,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
            record(
                "prepared",
                "result-id=" + DurableResultStore.RESULT_ID
                    + " bytes=" + java.nio.file.Files.size(result)
                    + " storage=app-private-files"
                    + " reader-package=" + READER_PACKAGE
                    + " reader-uid=" + reader_uid
                    + " uri=" + DurableResultProvider.RESULT_URI
                    + " grant=read"
            );
            append_status("result committed and reader grant issued");
        } catch (PackageManager.NameNotFoundException exception) {
            record("reader-missing", "package=" + READER_PACKAGE);
            append_status("install the result-reader APK, then tap prepare again");
        } catch (IOException | RuntimeException exception) {
            record("prepare-failed", "class=" + exception.getClass().getSimpleName());
            append_status("prepare failed: " + exception.getClass().getSimpleName());
        }
    }

    private int package_uid(String package_name) throws PackageManager.NameNotFoundException {
        return getPackageManager().getPackageUid(package_name, 0);
    }

    private void kill_host() {
        record(
            "host-kill",
            "requested=true pid=" + Process.myPid()
                + " durable-result-committed=true relaunch-required=true"
        );
        append_status("IB host termination requested");
        handler.postDelayed(() -> Process.killProcess(Process.myPid()), 250);
    }

    private void record(String event, String detail) {
        String line = System.currentTimeMillis() + "\t" + event + "\t" + detail + "\n";
        try (FileOutputStream output = new FileOutputStream(receipt_file, true)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (IOException exception) {
            append_status("receipt write failed: " + exception.getClass().getSimpleName());
            return;
        }
        append_status(event + " " + detail);
    }

    private void copy_receipt() {
        try {
            String receipt = new String(
                java.nio.file.Files.readAllBytes(receipt_file.toPath()),
                StandardCharsets.UTF_8
            );
            ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(
                ClipData.newPlainText("IB durable-result fixture receipt", receipt)
            );
            append_status("fixture receipt copied");
        } catch (IOException exception) {
            append_status("receipt copy failed: " + exception.getClass().getSimpleName());
        }
    }

    private void append_status(String line) {
        if (status == null) {
            return;
        }
        CharSequence previous = status.getText();
        status.setText(previous.length() == 0 ? line : previous + "\n" + line);
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
