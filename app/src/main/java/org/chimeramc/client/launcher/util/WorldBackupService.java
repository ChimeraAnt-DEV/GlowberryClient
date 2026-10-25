package org.chimeramc.client.util;

import org.chimeramc.client.util.SafeExecutor;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.chimeramc.client.R;
import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.core.versions.VersionManager;
import org.chimeramc.client.settings.FeatureSettings;
import org.chimeramc.client.ui.activities.MainActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that performs a scheduled world backup run (G13).
 *
 * <p>Fired either by the {@link WorldBackupScheduler} alarm or by the Settings "Back up now"
 * button. It resolves the game-data roots for every installed version (the same
 * {@code getContentGameDataDir} resolution the content screens use, so the worlds enumerated here
 * are exactly the worlds the game sees), runs the pure {@link WorldBackupPlan} against the SAF
 * destination, prunes stale files, records the outcome, and reschedules the next run. The service
 * exits as soon as the run finishes — it exists to hold foreground status while a copy is in
 * flight, not to linger.
 */
public class WorldBackupService extends Service {

    private static final String TAG = "WorldBackupService";
    public static final String ACTION_RUN = "org.chimeramc.client.action.WORLD_BACKUP_RUN";
    private static final String CHANNEL_ID = "world_backups";
    private static final int NOTIFICATION_ID = 0xB15E;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        if (!ACTION_RUN.equals(intent == null ? null : intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!SafeExecutor.submit(executor, this::runBackupNow)) {
            // onDestroy shut the pool down; the service is going away with it.
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void runBackupNow() {
        WorldBackupConfig config = new WorldBackupConfig(getApplicationContext());
        String result;
        try {
            if (!config.hasDestinationPermission()) {
                result = getString(R.string.world_backup_status_no_destination);
                notifyFinished(config, result);
                return;
            }

            List<File> roots = resolveGameDataRoots();
            List<WorldBackupPlan.WorldEntry> worlds = WorldBackupDiscovery.discover(roots);

            Uri treeUri = config.getFolderUri();
            List<String> existing = WorldBackupStore.listBackupNames(this, treeUri);
            WorldBackupPlan plan = WorldBackupPlan.plan(worlds, existing, config.getRetention());

            WorldBackupStore.RunResult run =
                    WorldBackupStore.run(this, treeUri, plan.getWorldsToBackup(),
                            plan.getFilesToDelete(), System.currentTimeMillis());

            long now = System.currentTimeMillis();
            config.setLastRunAt(now);
            String summary = getString(R.string.world_backup_result,
                    run.backedUp, run.deleted);
            config.setLastResult(summary);
            result = summary;

            // The run is done but the next slot has to move forward so the schedule tracks wall
            // clock instead of every alarm delivery jitter.
            WorldBackupScheduler.reschedule(this);
        } catch (Exception e) {
            Log.e(TAG, "Scheduled world backup failed", e);
            result = getString(R.string.world_backup_status_failed, e.getMessage());
            config.setLastResult(result);
        }
        notifyFinished(config, result);
        stopSelf();
    }

    private List<File> resolveGameDataRoots() {
        List<File> roots = new ArrayList<>();
        VersionManager versionManager = VersionManager.get(this);
        FeatureSettings.StorageType saved = LauncherStorage.readSavedContentStorageType(this);

        List<GameVersion> versions = versionManager.getInstalledVersions();
        if (versions != null) {
            for (GameVersion version : versions) {
                if (version == null) continue;
                File root = LauncherStorage.getActiveGameDataDir(
                        this, version.getStorageProfileId(), version.versionIsolation, saved);
                if (root != null) {
                    roots.add(root);
                }
            }
        }

        // No installed versions yet — fall back to the shared installed profile root so the
        // wizard's imported worlds are still covered.
        if (roots.isEmpty()) {
            File shared = LauncherStorage.getActiveGameDataDir(
                    this, LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID, false, saved);
            if (shared != null) {
                roots.add(shared);
            }
        }
        return roots;
    }

    // ---------------------------------------------------------------------------------------

    private void startForegroundCompat() {
        Notification notification = buildNotification(getString(R.string.world_backup_running)).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void notifyFinished(WorldBackupConfig config, String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        Notification notif = buildNotification(text)
                .setOnlyAlertOnce(true)
                .setWhen(System.currentTimeMillis())
                .build();
        manager.notify(NOTIFICATION_ID, notif);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private NotificationCompat.Builder buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        android.app.PendingIntent contentIntent = android.app.PendingIntent.getActivity(this, 0, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_glowberry_mono)
                .setContentTitle(getString(R.string.world_backup_notification_title))
                .setContentText(text)
                .setContentIntent(contentIntent);
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.world_backup_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}