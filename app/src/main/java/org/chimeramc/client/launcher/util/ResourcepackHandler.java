package org.chimeramc.client.util;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;

import org.chimeramc.client.R;
import org.chimeramc.client.core.minecraft.MinecraftImportIntents;
import org.chimeramc.client.core.minecraft.MinecraftLauncher;
import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.core.versions.VersionManager;
import org.chimeramc.client.ui.dialogs.CustomAlertDialog;

import java.util.concurrent.ExecutorService;

public class ResourcepackHandler {

    private static final String TAG = "ResourcepackHandler";

    private final Activity activity;
    private final MinecraftLauncher minecraftLauncher;
    private final ExecutorService executor;

    public ResourcepackHandler(Activity activity, MinecraftLauncher minecraftLauncher,
                               ExecutorService executor) {
        this.activity = activity;
        this.minecraftLauncher = minecraftLauncher;
        this.executor = executor;
    }

    public void checkIntentForResourcepack() {
        Intent intent = activity.getIntent();
        if (!MinecraftImportIntents.isMinecraftResourceIntent(activity, intent)) {
            return;
        }
        if (MinecraftImportIntents.forwardToRunningMinecraft(activity, intent)) {
            return;
        }

        Uri data = intent.getData();
        if (data != null) {
            String displayPath = data.getPath() != null ? data.getPath() : data.toString();
            new CustomAlertDialog(activity)
                    .setTitleText(activity.getString(R.string.content_detected_title))
                    .setMessage(activity.getString(R.string.content_detected_message, displayPath))
                    .setPositiveButton(activity.getString(R.string.launch_now), (d) -> launchMinecraft(intent))
                    .setNegativeButton(activity.getString(R.string.launch_later), null)
                    .show();
        }
    }

    /**
     * Launches the game for an incoming content intent, off the UI thread.
     *
     * <p>The version lookup touches the instance store, so it belongs on a worker - but this is a
     * launch path, and it had no failure handling of any kind. A throw from the lookup, or a pool
     * already shut down under the caller, meant the "Launch now" button produced no launch and no
     * message: the dialog simply closed. Both are now named, and the empty {@code runOnUiThread}
     * that used to sit at the end (a no-op callback with nothing in it) is gone.
     */
    private void launchMinecraft(Intent intent) {
        try {
            executor.execute(() -> {
                GameVersion currentVersion;
                try {
                    currentVersion = VersionManager.get(activity).getSelectedVersion();
                } catch (Throwable t) {
                    showLaunchFailure(t);
                    return;
                }
                minecraftLauncher.launch(intent, currentVersion);
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            showLaunchFailure(rejected);
        }
    }

    private void showLaunchFailure(Throwable t) {
        Log.e(TAG, "Could not start the game for the incoming content", t);
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            new CustomAlertDialog(activity)
                    .setTitleText(activity.getString(R.string.launch_failure_title_unexpected))
                    .setMessage(activity.getString(R.string.launch_failure_message_unexpected))
                    .setPositiveButton(activity.getString(android.R.string.ok), null)
                    .show();
        });
    }
}
