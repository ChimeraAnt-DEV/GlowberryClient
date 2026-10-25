package org.chimeramc.client.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import org.chimeramc.client.R;
import org.chimeramc.client.ui.fragments.InstallationsFragment;
import org.chimeramc.client.ui.fragments.InstancesFragment;
import org.chimeramc.client.ui.fragments.ReplayFragment;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.util.PersonalizationManager;

/**
 * The Library destination (U1/U3): one screen for the three "things you own" surfaces.
 *
 * <p>Instances, Install (the package-source version list) and Replay were three top-level tabs,
 * which pushed the bar past what a phone can show. They are the same kind of thing — a list you
 * browse and act on — so they share this host with an inner segmented control, the way
 * {@link CustomizeActivity} hosts Controller/Skins. Every screen keeps its own fragment, so no
 * behaviour moved; only the chrome changed.
 *
 * <p>The activity also owns intent forwarding: a VIEW intent for a backup belongs to the activity,
 * and a fragment is never given {@code onNewIntent}, so a second one is handed to the Instances
 * fragment rather than being silently dropped.
 */
public class LibraryActivity extends BaseActivity {

    private static final String TAG_INSTANCES = "library_instances";
    private static final String TAG_INSTALL = "library_install";
    private static final String TAG_REPLAY = "library_replay";

    /** Which inner tab to open on create. */
    public static final String EXTRA_INITIAL_TAB = "library_initial_tab";
    public static final int TAB_INSTANCES = 0;
    public static final int TAB_INSTALL = 1;
    public static final int TAB_REPLAY = 2;

    private TextView instancesTab;
    private TextView installTab;
    private TextView replayTab;
    private int accent;

    /** Opens the Library on a specific inner tab. */
    public static void open(Context context, int tab) {
        Intent intent = new Intent(context, LibraryActivity.class);
        intent.putExtra(EXTRA_INITIAL_TAB, tab);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);
        setActiveNavTab(R.id.nav_tab_library);

        PersonalizationManager pm = new PersonalizationManager(this);
        accent = pm.getAccentColor();
        View root = findViewById(R.id.library_root);
        if (root != null) {
            pm.applyAccentToView(root, this);
        }

        instancesTab = findViewById(R.id.library_tab_instances);
        installTab = findViewById(R.id.library_tab_install);
        replayTab = findViewById(R.id.library_tab_replay);
        instancesTab.setOnClickListener(v -> showTab(TAB_INSTANCES));
        installTab.setOnClickListener(v -> showTab(TAB_INSTALL));
        replayTab.setOnClickListener(v -> showTab(TAB_REPLAY));
        DynamicAnim.applyPressScale(instancesTab);
        DynamicAnim.applyPressScale(installTab);
        DynamicAnim.applyPressScale(replayTab);

        // A backup handed to the launcher as a VIEW intent belongs to the Instances tab, so the
        // intent decides the tab rather than the extra when one is present.
        boolean restoreBackup = getIntent() != null && getIntent().getBooleanExtra(
                InstancesFragment.EXTRA_RESTORE_BACKUP_ON_OPEN, false);
        int initialTab = restoreBackup
                ? TAB_INSTANCES
                : (getIntent() == null
                        ? TAB_INSTANCES
                        : getIntent().getIntExtra(EXTRA_INITIAL_TAB, TAB_INSTANCES));
        if (savedInstanceState == null) {
            showTab(initialTab);
        } else {
            styleTabs(visibleTab());
        }
        if (restoreBackup) {
            // The fragment is created by the transaction above; deliver the intent once it is
            // attached, because a fragment created in onCreate has no view yet.
            getSupportFragmentManager().executePendingTransactions();
            forwardBackupIntent(getIntent());
        }
    }

    /** The tab whose fragment is currently attached, defaulting to Instances. */
    private int visibleTab() {
        if (getSupportFragmentManager().findFragmentByTag(TAG_REPLAY) != null) return TAB_REPLAY;
        if (getSupportFragmentManager().findFragmentByTag(TAG_INSTALL) != null) return TAB_INSTALL;
        return TAB_INSTANCES;
    }

    private void showTab(int tab) {
        String tag;
        Fragment fragment;
        switch (tab) {
            case TAB_REPLAY:
                tag = TAG_REPLAY;
                fragment = new ReplayFragment();
                break;
            case TAB_INSTALL:
                tag = TAG_INSTALL;
                fragment = new InstallationsFragment();
                break;
            case TAB_INSTANCES:
            default:
                tag = TAG_INSTANCES;
                fragment = new InstancesFragment();
                break;
        }
        if (getSupportFragmentManager().findFragmentByTag(tag) == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    .replace(R.id.library_container, fragment, tag)
                    .commitAllowingStateLoss();
        }
        styleTabs(tab);
    }

    private void styleTabs(int active) {
        styleTab(instancesTab, active == TAB_INSTANCES);
        styleTab(installTab, active == TAB_INSTALL);
        styleTab(replayTab, active == TAB_REPLAY);
    }

    private void styleTab(TextView tab, boolean active) {
        if (tab == null) return;
        if (active) {
            tab.setTextColor(accent != 0 ? accent : getColor(R.color.on_surface));
            tab.setTypeface(tab.getTypeface(), android.graphics.Typeface.BOLD);
        } else {
            tab.setTextColor(getColor(R.color.text_secondary));
            tab.setTypeface(tab.getTypeface(), android.graphics.Typeface.NORMAL);
        }
    }

    /**
     * The Replay tab owns the controller keys the panel's hint names, so the host must not take
     * them for tab switching. The other two tabs keep bumper navigation.
     */
    @Override
    protected boolean shouldHandleNavKeys() {
        return visibleTab() != TAB_REPLAY;
    }

    /**
     * Forwards a hardware key press to the Replay fragment while it is showing.
     *
     * <p>The panel displays a controller hint on this screen, so without this forward the hint
     * would advertise buttons that do nothing. Consumed presses do not reach the base handler, so a
     * bumper cycles clips rather than switching top-level tabs.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && visibleTab() == TAB_REPLAY) {
            Fragment replay = getSupportFragmentManager().findFragmentByTag(TAG_REPLAY);
            if (replay instanceof ReplayFragment
                    && ((ReplayFragment) replay).handleHardwareKey(event.getKeyCode())) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        forwardBackupIntent(intent);
    }

    /**
     * Hands a backup VIEW intent to the Instances fragment.
     *
     * <p>The fragment cannot see it on its own: only the activity is given {@code onNewIntent}.
     */
    private void forwardBackupIntent(Intent intent) {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(TAG_INSTANCES);
        if (fragment instanceof InstancesFragment) {
            ((InstancesFragment) fragment).handleBackupOpenIntent(intent);
        }
    }
}
