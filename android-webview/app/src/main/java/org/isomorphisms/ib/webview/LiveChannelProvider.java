package org.isomorphisms.ib.webview;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Issue #85 live-channel fixture. Binder transfers one reliable socket-pair
 * descriptor; the ping/pong exchange itself uses the descriptor, not Binder
 * calls, TCP, or a named filesystem endpoint.
 */
public final class LiveChannelProvider extends ContentProvider {
    public static final String AUTHORITY = "org.isomorphisms.ib.webview.live";
    public static final Uri CHANNEL_URI = Uri.parse(
        "content://" + AUTHORITY + "/channel/ping-v1"
    );

    private static final byte[] PING = "ping\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PONG = "pong\n".getBytes(StandardCharsets.US_ASCII);

    private File receipt_file;
    private String provider_process_id;

    @Override
    public boolean onCreate() {
        provider_process_id = "live-provider-" + UUID.randomUUID();
        receipt_file = new File(
            getContext().getFilesDir(),
            "receipts/live-channel-provider.tsv"
        );
        File parent = receipt_file.getParentFile();
        if (!parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("could not create live-channel receipt directory");
        }
        record(
            "provider-start",
            "provider_pid=" + Process.myPid()
                + " provider_uid=" + Process.myUid()
                + " provider_process_id=" + provider_process_id
        );
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
        throws FileNotFoundException {
        require_channel_uri(uri);
        if (!"rw".equals(mode) && !"rwt".equals(mode)) {
            throw new FileNotFoundException("live channel requires rw descriptor mode");
        }

        final int calling_uid = Binder.getCallingUid();
        final String calling_package =
            getCallingPackage() == null ? "unknown" : getCallingPackage();

        try {
            ParcelFileDescriptor[] pair =
                ParcelFileDescriptor.createReliableSocketPair();
            ParcelFileDescriptor caller = pair[0];
            ParcelFileDescriptor provider = pair[1];

            record(
                "open",
                "calling_uid=" + calling_uid
                    + " calling_package=" + calling_package
                    + " provider_pid=" + Process.myPid()
                    + " provider_uid=" + Process.myUid()
            );

            Thread server = new Thread(
                () -> serve(provider, calling_uid, calling_package),
                "ib-live-channel"
            );
            server.setDaemon(true);
            server.start();
            return caller;
        } catch (IOException exception) {
            throw new FileNotFoundException(
                "could not create live channel: "
                    + exception.getClass().getSimpleName()
            );
        }
    }

    private void serve(
        ParcelFileDescriptor peer,
        int calling_uid,
        String calling_package
    ) {
        try {
            FileInputStream input = new FileInputStream(peer.getFileDescriptor());
            FileOutputStream output = new FileOutputStream(peer.getFileDescriptor());

            byte[] request = read_exact(input, PING.length);
            if (!Arrays.equals(request, PING)) {
                record(
                    "protocol-error",
                    "calling_uid=" + calling_uid
                        + " calling_package=" + calling_package
                        + " reason=unexpected-request"
                );
                peer.closeWithError("unexpected live-channel request");
                return;
            }

            output.write(PONG);
            output.flush();
            record(
                "exchange",
                "status=pass calling_uid=" + calling_uid
                    + " calling_package=" + calling_package
            );
            record(
                "hold",
                "status=waiting-for-caller-close calling_uid=" + calling_uid
            );

            int value = input.read();
            if (value < 0) {
                record(
                    "caller-close",
                    "status=eof calling_uid=" + calling_uid
                        + " calling_package=" + calling_package
                );
            } else {
                record(
                    "caller-close",
                    "status=unexpected-byte-" + value
                        + " calling_uid=" + calling_uid
                );
            }
        } catch (IOException exception) {
            record(
                "channel-error",
                "class=" + exception.getClass().getSimpleName()
                    + " calling_uid=" + calling_uid
            );
        } finally {
            try {
                peer.close();
            } catch (IOException ignored) {
            }
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

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selection_args,
        String sort_order
    ) {
        require_channel_uri(uri);
        String calling_package =
            getCallingPackage() == null ? "unknown" : getCallingPackage();
        MatrixCursor cursor = new MatrixCursor(new String[] {
            "provider_pid",
            "provider_uid",
            "provider_process_id",
            "calling_uid",
            "calling_package"
        });
        cursor.addRow(new Object[] {
            Process.myPid(),
            Process.myUid(),
            provider_process_id,
            Binder.getCallingUid(),
            calling_package
        });
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        require_channel_uri(uri);
        return "application/vnd.isomorphisms.ib.live-channel";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("live channel has no insert");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selection_args) {
        throw new UnsupportedOperationException("live channel has no delete");
    }

    @Override
    public int update(
        Uri uri,
        ContentValues values,
        String selection,
        String[] selection_args
    ) {
        throw new UnsupportedOperationException("live channel has no update");
    }

    private static void require_channel_uri(Uri uri) {
        if (!CHANNEL_URI.equals(uri)) {
            throw new IllegalArgumentException("unknown live-channel URI");
        }
    }

    private synchronized void record(String event, String detail) {
        String line = System.currentTimeMillis() + "\t" + event + "\t" + detail + "\n";
        try (FileOutputStream output = new FileOutputStream(receipt_file, true)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (IOException ignored) {
        }
    }
}
