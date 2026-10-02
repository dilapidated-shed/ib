package org.isomorphisms.ib.webview;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Narrow issue #84 adapter: private durable storage exposed by per-open descriptors. */
public final class DurableResultProvider extends ContentProvider {
    public static final String AUTHORITY = "org.isomorphisms.ib.webview.results";
    public static final Uri RESULT_URI = Uri.parse(
        "content://" + AUTHORITY + "/result/" + DurableResultStore.RESULT_ID
    );

    private DurableResultStore store;
    private long provider_generation;
    private String provider_process_id;

    @Override
    public boolean onCreate() {
        store = new DurableResultStore(getContext().getFilesDir().toPath());
        provider_process_id = "provider-" + UUID.randomUUID();
        try {
            provider_generation = store.next_provider_generation();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("could not record provider generation", exception);
        }
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) && !"rt".equals(mode)) {
            throw new FileNotFoundException("durable result is read-only");
        }
        Path result = require_fixture_uri(uri);
        try {
            return ParcelFileDescriptor.open(
                result.toFile(),
                ParcelFileDescriptor.MODE_READ_ONLY
            );
        } catch (FileNotFoundException exception) {
            throw exception;
        }
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selection_args,
        String sort_order
    ) {
        Path result = require_fixture_uri(uri);
        MatrixCursor cursor = new MatrixCursor(new String[] {
            "result_id",
            "byte_count",
            "provider_pid",
            "provider_uid",
            "provider_generation",
            "provider_process_id",
            "calling_uid",
            "calling_package"
        });
        try {
            String calling_package = getCallingPackage();
            cursor.addRow(new Object[] {
                DurableResultStore.RESULT_ID,
                java.nio.file.Files.size(result),
                Process.myPid(),
                Process.myUid(),
                provider_generation,
                provider_process_id,
                Binder.getCallingUid(),
                calling_package == null ? "unknown" : calling_package
            });
        } catch (IOException exception) {
            throw new IllegalStateException("durable result metadata unavailable", exception);
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        require_fixture_uri(uri);
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("durable results are not inserted through provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selection_args) {
        throw new UnsupportedOperationException("durable results are not deleted through provider");
    }

    @Override
    public int update(
        Uri uri,
        ContentValues values,
        String selection,
        String[] selection_args
    ) {
        throw new UnsupportedOperationException("durable results are immutable through provider");
    }

    private Path require_fixture_uri(Uri uri) {
        if (!RESULT_URI.equals(uri)) {
            throw new IllegalArgumentException("unknown durable result URI");
        }
        try {
            return store.require_result(DurableResultStore.RESULT_ID);
        } catch (IOException exception) {
            throw new IllegalStateException("durable result is not committed", exception);
        }
    }
}
