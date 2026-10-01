package org.isomorphisms.ib.webview;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Phone-visible read-only app-UID leg of the #95 capability report. */
public final class DeviceCapabilitiesActivity extends Activity {
    private String report;

    @Override
    protected void onCreate(Bundle saved_instance_state) {
        super.onCreate(saved_instance_state);
        report = AppStorageCapabilities.report(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView heading = new TextView(this);
        heading.setText("IB device capabilities — app UID");
        heading.setTextSize(19);
        heading.setPadding(dp(12), dp(12), dp(12), dp(6));
        root.addView(heading);

        Button copy = new Button(this);
        copy.setAllCaps(false);
        copy.setText("Copy capability report");
        copy.setOnClickListener(view -> copy_report());
        root.addView(copy);

        TextView body = new TextView(this);
        body.setText(report);
        body.setTextSize(11);
        body.setTextIsSelectable(true);
        body.setPadding(dp(12), dp(6), dp(12), dp(12));
        root.addView(
            body,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        );

        setContentView(root);
    }

    private void copy_report() {
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(
            ClipData.newPlainText("IB app storage capabilities", report)
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
