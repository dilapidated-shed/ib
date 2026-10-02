package org.isomorphisms.ib.webview;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** User-visible grant/receipt fixture for issue #85. */
public final class LiveChannelFixtureActivity extends Activity {
    private static final String READER_PACKAGE = "org.isomorphisms.ib.resultreader";

    private TextView status;
    private File provider_receipt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        provider_receipt = new File(
            getFilesDir(),
            "receipts/live-channel-provider.tsv"
        );
        build_ui();
        grant_channel();
    }

    private void build_ui() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView heading = new TextView(this);
        heading.setText("IB live PFD channel fixture");
        heading.setTextSize(20);
        root.addView(heading);

        TextView instructions = new TextView(this);
        instructions.setText(
            "Grant fixture:\n"
                + "termux-open-url ib://live-channel-fixture\n\n"
                + "Reader shim:\n"
                + "termux-open-url " + LiveChannelProvider.CHANNEL_URI
        );
        instructions.setTextIsSelectable(true);
        root.addView(instructions);

        Button grant = button("Grant reader live-channel access");
        grant.setOnClickListener(view -> grant_channel());
        root.addView(grant);

        Button copy = button("Copy provider receipt");
        copy.setOnClickListener(view -> copy_receipt());
        root.addView(copy);

        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);

        setContentView(root);
    }

    private void grant_channel() {
        try {
            int reader_uid = getPackageManager().getPackageUid(READER_PACKAGE, 0);
            grantUriPermission(
                READER_PACKAGE,
                LiveChannelProvider.CHANNEL_URI,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
            append_status(
                "source-head=" + BuildConfig.IB_SOURCE_HEAD
                    + " android=" + Build.VERSION.RELEASE + ":api-" + Build.VERSION.SDK_INT
                    + " provider-package=" + getPackageName()
                    + " provider-uid=" + Process.myUid()
                    + " reader-package=" + READER_PACKAGE
                    + " reader-uid=" + reader_uid
                    + " grant=read-write"
            );
        } catch (PackageManager.NameNotFoundException exception) {
            append_status("install result-reader APK before granting channel");
        }
    }

    private void copy_receipt() {
        try {
            String receipt = provider_receipt.exists()
                ? new String(
                    java.nio.file.Files.readAllBytes(provider_receipt.toPath()),
                    StandardCharsets.UTF_8
                )
                : "";
            ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(
                ClipData.newPlainText("IB live-channel provider receipt", receipt)
            );
            append_status("provider receipt copied");
        } catch (IOException exception) {
            append_status("receipt copy failed: " + exception.getClass().getSimpleName());
        }
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private void append_status(String line) {
        if (status == null) {
            return;
        }
        CharSequence previous = status.getText();
        status.setText(previous.length() == 0 ? line : previous + "\n" + line);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
