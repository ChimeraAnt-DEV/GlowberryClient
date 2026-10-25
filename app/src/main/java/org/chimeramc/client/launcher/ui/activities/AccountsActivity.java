package org.chimeramc.client.ui.activities;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.graphics.Bitmap;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.chimeramc.client.R;
import org.chimeramc.client.core.auth.MsftAccountStore;
import org.chimeramc.client.core.auth.MsftAuthManager;
import org.chimeramc.client.ui.adapter.AccountsAdapter;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.ui.dialogs.LoadingDialog;
import org.chimeramc.client.util.AccountTextUtils;
import org.chimeramc.client.util.PersonalizationManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import android.util.Pair;

import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;

public class AccountsActivity extends BaseActivity {

    private TextView gamertagText;
    private TextView privilegesText;
    private TextView xuidText;
    private TextView emptyStateText;
    private View emptyStateContainer;
    private Button bottomAddButton;
    private androidx.recyclerview.widget.RecyclerView accountsRecyclerView;
    private ImageButton leftAddButton;
    private com.microsoft.xbox.idp.toolkit.CircleImageView xboxAvatar;
    private ProgressBar avatarProgress;
    private View rightCardContainer;
    private String lastAvatarXuid;
    private final OkHttpClient avatarClient = AccountsActivity.buildLatencyTunedClient();

    private static OkHttpClient buildLatencyTunedClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        org.chimeramc.client.settings.LowLatencyNetworkManager.configure(builder);
        return builder.build();
    }

    private final AccountsAdapter adapter = new AccountsAdapter();
    private ActivityResultLauncher<Intent> loginLauncher;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * Submits account/avatar work, tolerating an executor {@link #onDestroy()} has shut down.
     *
     * <p>The account list can hand a click through as the screen closes, and the avatar load is
     * kicked off from a rebind. Submitting to a shut-down executor throws on the calling (main)
     * thread, so the last click before leaving the screen could crash the app.
     */
    private void runOnExecutor(Runnable work) {
        try {
            executor.execute(work);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            android.util.Log.w("AccountsActivity",
                    "Account task dropped: the screen is gone", rejected);
        }
    }
    private LoadingDialog loadingDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accounts);


        gamertagText = findViewById(R.id.gamertag_text);
        privilegesText = findViewById(R.id.privileges_text);
        xuidText = findViewById(R.id.xuid_text);
        emptyStateText = findViewById(R.id.empty_state_text);
        emptyStateContainer = findViewById(R.id.empty_state_container);
        accountsRecyclerView = findViewById(R.id.accounts_recycler_view);
        bottomAddButton = findViewById(R.id.bottom_add_button);
        xboxAvatar = findViewById(R.id.xbox_avatar);
        avatarProgress = findViewById(R.id.avatar_progress);
        rightCardContainer = findViewById(R.id.right_card_container);
        applyAccountAccent();

        loginLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null
                    && result.getData().getBooleanExtra(MsftLoginActivity.EXTRA_LOGIN_COMPLETED, false)) {
                String name = result.getData().getStringExtra(MsftLoginActivity.EXTRA_LOGIN_NAME);
                String statusName = name != null ? name : getString(R.string.not_signed_in);
                Toast.makeText(this, getString(R.string.ms_login_success, statusName), Toast.LENGTH_SHORT).show();
            }
            refreshUI();
            refreshNavAccountUI();
        });

        View.OnClickListener addAction = v -> loginLauncher.launch(new Intent(this, MsftLoginActivity.class));
        if (bottomAddButton != null) bottomAddButton.setOnClickListener(addAction);
        if (bottomAddButton != null) DynamicAnim.applyPressScale(bottomAddButton);

        adapter.setOnAccountActionListener(new AccountsAdapter.OnAccountActionListener() {
            @Override
            public void onSetActive(MsftAccountStore.MsftAccount account) {
                MsftAccountStore.setActive(AccountsActivity.this, account.id);

                boolean withinSevenDays = AccountTextUtils.isRecentlyUpdated(account, 7);

                if (withinSevenDays) {
                    runOnUiThread(() -> {
                        org.chimeramc.client.util.DialogUtils.dismissQuietly(loadingDialog);
                        String statusName = AccountTextUtils.displayNameOrNotSigned(AccountsActivity.this, account);
                        Toast.makeText(AccountsActivity.this, getString(R.string.ms_login_success, statusName), Toast.LENGTH_SHORT).show();
                        refreshUI();
                    });
                    return;
                }

                loadingDialog = org.chimeramc.client.util.DialogUtils.ensure(AccountsActivity.this, loadingDialog);
                org.chimeramc.client.util.DialogUtils.showWithMessage(loadingDialog, getString(R.string.ms_login_auth_xbox_device));

                runOnExecutor(() -> {
                    try {
                        BedrockAuthManager authManager = MsftAuthManager.refreshAndAuth(account);
                        MsftAuthManager.saveAccountOrThrow(AccountsActivity.this, authManager);
                        MsftAccountStore.setActive(AccountsActivity.this, account.id);
                        String minecraftUsername = authManager.getMinecraftCertificateChain().getUpToDate().getIdentityDisplayName();

                        runOnUiThread(() -> {
                            org.chimeramc.client.util.DialogUtils.dismissQuietly(loadingDialog);
                            String statusName = minecraftUsername != null ? minecraftUsername : getString(R.string.not_signed_in);
                            Toast.makeText(AccountsActivity.this, getString(R.string.ms_login_success, statusName), Toast.LENGTH_SHORT).show();
                            refreshUI();
                        });
                    } catch (Throwable e) {
                        // Throwable, not Exception: this handler is the only thing that dismisses the
                        // "signing in" dialog, so an Error escaping it left that dialog up forever
                        // with no sign-in and no message.
                        runOnUiThread(() -> {
                            org.chimeramc.client.util.DialogUtils.dismissQuietly(loadingDialog);
                                Toast.makeText(AccountsActivity.this, getString(R.string.ms_login_failed_detail, MsftAuthManager.describeError(e)), Toast.LENGTH_LONG).show();
                                refreshUI();
                        });
                    }
                });
            }

            @Override
            public void onDelete(MsftAccountStore.MsftAccount account) {
                new org.chimeramc.client.ui.dialogs.CustomAlertDialog(AccountsActivity.this)
                        .setTitleText(getString(R.string.delete_account_title))
                        .setMessage(getString(R.string.delete_account_confirm))
                        .setPositiveButton(getString(R.string.ms_delete), v -> {
                            MsftAccountStore.remove(AccountsActivity.this, account.id);
                            Toast.makeText(AccountsActivity.this, R.string.ms_delete, Toast.LENGTH_SHORT).show();
                            refreshUI();
                        })
                        .setNegativeButton(getString(android.R.string.cancel), null)
                        .show();
            }
        });

        accountsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        accountsRecyclerView.setAdapter(adapter);
        accountsRecyclerView.post(() -> DynamicAnim.staggerRecyclerChildren(accountsRecyclerView));

        refreshUI();
    }

    private void applyAccountAccent() {
        PersonalizationManager pm = new PersonalizationManager(this);
        int accent = pm.getAccentColor();
        if (accent == 0) {
            accent = getColor(R.color.primary);
        }
        if (avatarProgress != null) {
            avatarProgress.setIndeterminateTintList(ColorStateList.valueOf(accent));
        }
        if (bottomAddButton != null) {
            bottomAddButton.setBackgroundTintList(ColorStateList.valueOf(accent));
            bottomAddButton.setTextColor(Color.WHITE);
        }
    }

    private MsftAccountStore.MsftAccount getActiveAccount() {
        List<MsftAccountStore.MsftAccount> list = MsftAccountStore.list(this);
        for (MsftAccountStore.MsftAccount a : list) if (a.active) return a;
        return null;
    }



    private void refreshUI() {
        boolean enabled = org.chimeramc.client.settings.FeatureSettings.getInstance().isLauncherManagedMcLoginEnabled();
        View mainContent = findViewById(R.id.main_content_container);
        View disabledState = findViewById(R.id.disabled_state_container);
        
        if (mainContent != null) mainContent.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (disabledState != null) disabledState.setVisibility(enabled ? View.GONE : View.VISIBLE);
        
        if (!enabled) return;
        
        MsftAccountStore.MsftAccount active = getActiveAccount();
        if (active == null) {
            gamertagText.setText(getString(R.string.not_signed_in));
            privilegesText.setText("");
            xuidText.setText("");
            if (xboxAvatar != null) {
                xboxAvatar.setImageDrawable(null);
                lastAvatarXuid = null;
                if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
            }
            if (rightCardContainer != null) rightCardContainer.setVisibility(View.GONE);
        } else {
            String displayName = AccountTextUtils.displayNameOrNotSigned(this, active);
            gamertagText.setText(displayName);
            privilegesText.setText(getString(R.string.accounts_active_privilege));
            xuidText.setText(active.xuid != null ? active.xuid : "");
            loadXboxAvatar(active);
            if (rightCardContainer != null) rightCardContainer.setVisibility(View.VISIBLE);
        }

        List<MsftAccountStore.MsftAccount> list = MsftAccountStore.list(this);
        adapter.updateAccounts(list);

        boolean isEmpty = list == null || list.isEmpty();
        if (emptyStateContainer != null) emptyStateContainer.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        
        refreshNavAccountUI();
    }

    @Override
    protected void onNavAccountChanged() {
        refreshUI();
    }

    private void loadXboxAvatar(MsftAccountStore.MsftAccount active) {
        if (xboxAvatar == null) return;
        String url = AccountTextUtils.sanitizeUrl(active != null ? active.xboxAvatarUrl : null);
        if (url == null) {
            if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
            xboxAvatar.setImageDrawable(null);
            lastAvatarXuid = null;
            return;
        }
        xboxAvatar.setImageDrawable(null);
        if (avatarProgress != null) avatarProgress.setVisibility(View.VISIBLE);
        runOnExecutor(() -> {
            try {
                try (Response imgResp = avatarClient.newCall(new Request.Builder().url(url).build()).execute()) {
                    Bitmap bmp = (imgResp.isSuccessful() && imgResp.body() != null) ? android.graphics.BitmapFactory.decodeStream(imgResp.body().byteStream()) : null;
                    runOnUiThread(() -> {
                        if (bmp != null) {
                            xboxAvatar.setImageBitmap(bmp);
                        }
                        if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
                    });
                }
            } catch (Throwable e) {
                // Throwable, not Exception: decoding the avatar can exhaust the heap and that is an
                // Error, which skipped this handler and left the avatar spinner turning forever.
                runOnUiThread(() -> {
                    if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        // The account/avatar work runs on an Activity-scoped executor; without this its thread
        // outlives the screen.
        executor.shutdownNow();
        super.onDestroy();
    }
}
