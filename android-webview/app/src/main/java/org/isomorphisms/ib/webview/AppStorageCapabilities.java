package org.isomorphisms.ib.webview;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;

/** Read-only app-UID view of storage roots that Android gives directly to IB. */
final class AppStorageCapabilities {
    private AppStorageCapabilities() {}

    static String report(Context context) {
        StringBuilder output = new StringBuilder();
        line(output, "schema", "ib-app-storage-capabilities-v1", "", "", "");
        field(output, "app_uid", "measured", "android-app", Integer.toString(Process.myUid()));
        field(output, "package", "reported", "android-app", context.getPackageName());
        field(output, "source_head", "reported", "android-app", BuildConfig.IB_SOURCE_HEAD);
        field(
            output,
            "apk_version",
            "reported",
            "android-app",
            BuildConfig.VERSION_NAME + ":" + BuildConfig.VERSION_CODE
        );
        field(output, "product_model", "reported", "android-app", Build.MODEL);
        field(output, "build_fingerprint", "reported", "android-app", Build.FINGERPRINT);
        field(output, "android_release", "reported", "android-app", Build.VERSION.RELEASE);
        field(
            output,
            "android_sdk",
            "reported",
            "android-app",
            Integer.toString(Build.VERSION.SDK_INT)
        );
        field(
            output,
            "supported_abis",
            "reported",
            "android-app",
            String.join(",", Build.SUPPORTED_ABIS)
        );

        File internal = context.getFilesDir();
        root(
            output,
            "internal-files",
            internal,
            "internal",
            false,
            false,
            null
        );

        StorageManager storage_manager =
            (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        File[] external_roots = context.getExternalFilesDirs(null);
        boolean found_removable_bulk = false;

        for (int index = 0; index < external_roots.length; index++) {
            File root = external_roots[index];
            if (root == null) {
                line(
                    output,
                    "app_storage_root",
                    "external-files-" + index,
                    "unknown",
                    "android-app",
                    "unavailable"
                );
                continue;
            }

            String state;
            boolean removable;
            boolean emulated;
            try {
                state = Environment.getExternalStorageState(root);
                removable = Environment.isExternalStorageRemovable(root);
                emulated = Environment.isExternalStorageEmulated(root);
            } catch (IllegalArgumentException | SecurityException exception) {
                line(
                    output,
                    "app_storage_root",
                    "external-files-" + index,
                    "unknown",
                    "android-app",
                    "framework-observation-failed=" + exception.getClass().getSimpleName()
                );
                continue;
            }

            StorageVolume volume = null;
            try {
                volume = storage_manager.getStorageVolume(root);
            } catch (IllegalArgumentException | SecurityException exception) {
                // The app-specific root remains useful even when volume metadata is denied.
            }

            root(
                output,
                "external-files-" + index,
                root,
                state,
                removable,
                emulated,
                volume
            );

            if (!found_removable_bulk
                && removable
                && Environment.MEDIA_MOUNTED.equals(state)
                && root.canWrite()) {
                found_removable_bulk = true;
                field(
                    output,
                    "app_bulk_candidate_root",
                    "reported",
                    "android-app",
                    root.getAbsolutePath()
                );
                capacity_fields(output, "app_bulk_candidate", root);
            }
        }

        if (!found_removable_bulk) {
            field(
                output,
                "app_bulk_candidate_root",
                "unknown",
                "android-app",
                "no-mounted-writable-removable-app-root-reported"
            );
        }

        field(
            output,
            "writer_evidence",
            "reported",
            "android-app",
            "framework-root-plus-canWrite; no test write performed"
        );
        return output.toString();
    }

    private static void root(
        StringBuilder output,
        String name,
        File root,
        String state,
        boolean removable,
        boolean emulated,
        StorageVolume volume
    ) {
        String uuid = volume == null || volume.getUuid() == null ? "none" : volume.getUuid();
        String value =
            "path=" + clean(root.getAbsolutePath())
                + " state=" + clean(state)
                + " removable=" + removable
                + " emulated=" + emulated
                + " can-write=" + root.canWrite()
                + " volume-uuid=" + clean(uuid);
        line(output, "app_storage_root", name, "reported", "android-app", value);
        capacity_fields(output, name, root);
    }

    private static void capacity_fields(StringBuilder output, String name, File root) {
        try {
            StatFs stat = new StatFs(root.getAbsolutePath());
            line(
                output,
                "app_storage_capacity",
                name,
                "measured",
                "android-app",
                "total-bytes=" + stat.getTotalBytes()
                    + " available-bytes=" + stat.getAvailableBytes()
            );
        } catch (IllegalArgumentException exception) {
            line(
                output,
                "app_storage_capacity",
                name,
                "unknown",
                "android-app",
                "stat-failed=" + exception.getClass().getSimpleName()
            );
        }
    }

    private static void field(
        StringBuilder output,
        String name,
        String status,
        String authority,
        String value
    ) {
        line(output, "field", name, status, authority, value);
    }

    private static void line(
        StringBuilder output,
        String kind,
        String name,
        String status,
        String authority,
        String value
    ) {
        output
            .append(clean(kind)).append('\t')
            .append(clean(name)).append('\t')
            .append(clean(status)).append('\t')
            .append(clean(authority)).append('\t')
            .append(clean(value)).append('\n');
    }

    private static String clean(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
