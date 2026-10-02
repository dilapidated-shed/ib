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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Separate-UID caller shim for the IB issue #84 physical experiment. */
public final class ResultReaderActivity extends Activity {
    private static final Uri EXPECTED_URI = Uri.parse(
        "content://org.isomorphisms.ib.webview.results/result/hello-v1"
    );

    private TextView status;
    private File receipt_file;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        receipt_file = new File(getFilesDir(), "durable-result-reader.tsv");
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
        if (getIntent().getBooleanExtra("read_twice", false)) {
            read_twice(requested);
        } else {
            read_once(requested, "launch");
        }
    }

    private void build_ui() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView heading = new TextView(this);
        heading.setText("IB durable result reader");
        heading.setTextSize(20);
        root.addView(heading);

        Button once = button("Read once");
        once.setOnClickListener(view -> read_once(EXPECTED_URI, "button"));
        root.addView(once);

        Button twice = button("Open two independent readers");
        twice.setOnClickListener(view -> read_twice(EXPECTED_URI));
        root.addView(twice);

        Button copy = button("Copy reader receipt");
        copy.setOnClickListener(view -> copy_receipt());
        root.addView(copy);

        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);

        setContentView(root);
    }

    private void read_once(Uri uri, String reason) {
        try {
            String metadata = query_metadata(uri);
            byte[] bytes = read_descriptor(open(uri));
            record(
                "read",
                "reason=" + reason
                    + " bytes=" + bytes.length
                    + " payload=" + quoted(bytes)
                    + " " + metadata
            );
        } catch (SecurityException exception) {
            record(
                "permission-denied",
                "reopen=ib://durable-result-fixture class="
                    + exception.getClass().getSimpleName()
            );
        } catch (IOException | RuntimeException exception) {
            record("read-failed", "class=" + exception.getClass().getSimpleName());
        }
    }

    private void read_twice(Uri uri) {
        try (
            ParcelFileDescriptor first = open(uri);
            ParcelFileDescriptor second = open(uri)
        ) {
            String metadata = query_metadata(uri);
            byte[] first_bytes = read_descriptor(first);
            byte[] second_bytes = read_descriptor(second);
            boolean complete = Arrays.equals(first_bytes, "hello\n".getBytes(StandardCharsets.UTF_8))
                && Arrays.equals(second_bytes, first_bytes);
            record(
                "two-readers",
                "first-bytes=" + first_bytes.length
                    + " second-bytes=" + second_bytes.length
                    + " independent-complete=" + complete
                    + " first=" + quoted(first_bytes)
                    + " second=" + quoted(second_bytes)
                    + " " + metadata
            );
        } catch (SecurityException exception) {
            record(
                "permission-denied",
                "during=two-readers reopen=ib://durable-result-fixture"
            );
        } catch (IOException | RuntimeException exception) {
            record("two-readers-failed", "class=" + exception.getClass().getSimpleName());
        }
    }

    private ParcelFileDescriptor open(Uri uri) throws IOException {
        ParcelFileDescriptor descriptor =
            getContentResolver().openFileDescriptor(uri, "r");
        if (descriptor == null) {
            throw new IOException("provider returned no descriptor");
        }
        return descriptor;
    }

    private String query_metadata(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
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

    private static byte[] read_descriptor(ParcelFileDescriptor descriptor) throws IOException {
        try (
            InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
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
                ClipData.newPlainText("IB durable-result reader receipt", receipt)
            );
            append_status("reader receipt copied");
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
