package org.isomorphisms.ib.resultreader;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Separate-UID live-channel caller shim for issue #85.
 *
 * Termux can launch this Android shim; the shim, not the Termux POSIX process,
 * receives the ParcelFileDescriptor. That distinction is part of the result.
 */
public final class LiveChannelActivity extends Activity {
    private static final Uri EXPECTED_URI = Uri.parse(
        "content://org.isomorphisms.ib.webview.live/channel/ping-v1"
    );
    private static final byte[] PING = "ping\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PONG = "pong\n".getBytes(StandardCharsets.US_ASCII);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;
    private File receipt_file;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        receipt_file = new File(getFilesDir(), "live-channel-reader.tsv");
        build_ui();

        Uri requested = getIntent().getData();
        if (requested == null) {
            requested = EXPECTED_URI;
        }

        record(
            "reader",
            "source-head=" + BuildConfig.IB_SOURCE_HEAD
                + " android=" + Build.VERSION.RELEASE + ":api-" + Build.VERSION.SDK_INT
                + " package=" + getPackageName()
                + " uid=" + Process.myUid()
                + " pid=" + Process.myPid()
                + " caller-shim=separate-android-app"
        );

        if (!EXPECTED_URI.equals(requested)) {
            record("refused", "reason=unexpected-uri uri=" + safe(requested));
            return;
        }

        boolean hold = getIntent().getBooleanExtra("hold", false);
        start_exchange(requested, hold, "launch");
    }

    private void build_ui() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView heading = new TextView(this);
        heading.setText("IB live PFD channel reader");
        heading.setTextSize(20);
        root.addView(heading);

        Button once = button("Ping once");
        once.setOnClickListener(
            view -> start_exchange(EXPECTED_URI, false, "button-once")
        );
        root.addView(once);

        Button hold = button("Ping and hold for peer death");
        hold.setOnClickListener(
            view -> start_exchange(EXPECTED_URI, true, "button-hold")
        );
        root.addView(hold);

        Button copy = button("Copy live-channel receipt");
        copy.setOnClickListener(view -> copy_receipt());
        root.addView(copy);

        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);

        setContentView(root);
    }

    private void start_exchange(Uri uri, boolean hold, String reason) {
        executor.execute(() -> exchange(uri, hold, reason));
    }

    private void exchange(Uri uri, boolean hold, String reason) {
        ParcelFileDescriptor descriptor = null;
        try {
            String metadata = query_metadata(uri);
            descriptor = getContentResolver().openFileDescriptor(uri, "rw");
            if (descriptor == null) {
                throw new IOException("provider returned no live descriptor");
            }

            FileInputStream input =
                new FileInputStream(descriptor.getFileDescriptor());
            FileOutputStream output =
                new FileOutputStream(descriptor.getFileDescriptor());

            output.write(PING);
            output.flush();
            byte[] response = read_exact(input, PONG.length);
            boolean pass = Arrays.equals(response, PONG);
            record(
                "exchange",
                "reason=" + reason
                    + " status=" + (pass ? "pass" : "wrong-payload")
                    + " response=" + quoted(response)
                    + " " + metadata
            );
            if (!pass) {
                return;
            }

            if (hold) {
                record("hold", "status=waiting-for-provider-close");
                int value = input.read();
                if (value < 0) {
                    String peer_status = "eof-clean";
                    try {
                        descriptor.checkError();
                    } catch (IOException exception) {
                        peer_status =
                            "eof-peer-error-" + exception.getClass().getSimpleName();
                    }
                    record("provider-close", "status=" + peer_status);
                } else {
                    record(
                        "provider-close",
                        "status=unexpected-byte-" + value
                    );
                }
            }
        } catch (SecurityException exception) {
            record(
                "permission-denied",
                "reopen=ib://live-channel-fixture class="
                    + exception.getClass().getSimpleName()
            );
        } catch (IOException | RuntimeException exception) {
            record(
                "exchange-failed",
                "class=" + exception.getClass().getSimpleName()
            );
        } finally {
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String query_metadata(Uri uri) {
        try (Cursor cursor =
                 getContentResolver().query(uri, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return "provider-metadata=missing";
            }
            StringBuilder result = new StringBuilder("provider-metadata=");
            for (int index = 0; index < cursor.getColumnCount(); index++) {
                if (index > 0) {
                    result.append(',');
                }
                result.append(cursor.getColumnName(index))
                    .append('=')
                    .append(cursor.getString(index));
            }
            return result.toString();
        }
    }

    private static byte[] read_exact(FileInputStream input, int count)
        throws IOException {
        byte[] result = new byte[count];
        int offset = 0;
        while (offset < count) {
            int got = input.read(result, offset, count - offset);
            if (got < 0) {
                throw new IOException(
                    "peer closed after " + offset + " of " + count + " bytes"
                );
            }
            offset += got;
        }
        return result;
    }

    private synchronized void record(String event, String detail) {
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
                ClipData.newPlainText("IB live-channel reader receipt", receipt)
            );
            append_status("live-channel receipt copied");
        } catch (IOException exception) {
            append_status("receipt copy failed: " + exception.getClass().getSimpleName());
        }
    }

    private static String quoted(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8)
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static String safe(Uri uri) {
        return uri == null ? "null" : uri.toString().replace("\n", "");
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private void append_status(String line) {
        runOnUiThread(() -> {
            if (status == null) {
                return;
            }
            CharSequence previous = status.getText();
            status.setText(previous.length() == 0 ? line : previous + "\n" + line);
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
