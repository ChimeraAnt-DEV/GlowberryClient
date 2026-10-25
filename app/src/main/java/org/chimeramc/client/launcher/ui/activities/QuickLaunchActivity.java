package org.chimeramc.client.ui.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;

import android.widget.Spinner;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.content.ServerItem;
import org.chimeramc.client.core.content.ServerManager;
import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.core.versions.VersionManager;
import org.chimeramc.client.settings.FeatureSettings;
import org.chimeramc.client.ui.adapter.QuickLaunchAdapter;
import org.chimeramc.client.util.MinecraftUriHandler;
import org.chimeramc.client.util.LauncherStorage;

import java.util.concurrent.RejectedExecutionException;
import org.chimeramc.client.ui.animation.DynamicAnim;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class QuickLaunchActivity extends BaseActivity {
    // The write of a new server entry is IO, so it runs off the UI thread; owning the worker
    // means it is torn down with the screen rather than posting its toast to a dead Activity.
    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private RecyclerView quickActionsRecycler;
    private QuickLaunchAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_launch);
        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));

        setupViews();
        loadQuickActions();
    }

    private void setupViews() {
        quickActionsRecycler = findViewById(R.id.quick_actions_recycler);
        quickActionsRecycler.setLayoutManager(new LinearLayoutManager(this));
        
        adapter = new QuickLaunchAdapter();
        adapter.setOnActionClickListener(this::handleQuickAction);
        quickActionsRecycler.setAdapter(adapter);
    }

    private void loadQuickActions() {
        List<QuickLaunchAdapter.QuickLaunchItem> items = new ArrayList<>();

        items.add(new QuickLaunchAdapter.QuickLaunchItem(
                getString(R.string.quick_launch_how_to_play),
                getString(R.string.quick_launch_how_to_play_desc),
                QuickLaunchAdapter.ActionType.HOW_TO_PLAY
        ));

        items.add(new QuickLaunchAdapter.QuickLaunchItem(
                getString(R.string.quick_launch_servers_tab),
                getString(R.string.quick_launch_servers_tab_desc),
                QuickLaunchAdapter.ActionType.SERVERS_TAB
        ));

        items.add(new QuickLaunchAdapter.QuickLaunchItem(
                getString(R.string.quick_launch_profile),
                getString(R.string.quick_launch_profile_desc),
                QuickLaunchAdapter.ActionType.PROFILE_SCREEN
        ));

        items.add(new QuickLaunchAdapter.QuickLaunchItem(
                getString(R.string.quick_launch_store_home),
                getString(R.string.quick_launch_store_home_desc),
                QuickLaunchAdapter.ActionType.STORE_HOME
        ));

        items.add(new QuickLaunchAdapter.QuickLaunchItem(
                getString(R.string.quick_launch_minecoins),
                getString(R.string.quick_launch_minecoins_desc),
                QuickLaunchAdapter.ActionType.MINECOIN_OFFERS
        ));

        items.add(new QuickLaunchAdapter.QuickLaunchItem(
                getString(R.string.quick_launch_marketplace_pass),
                getString(R.string.quick_launch_marketplace_pass_desc),
                QuickLaunchAdapter.ActionType.MARKETPLACE_PASS
        ));

        items.add(new QuickLaunchAdapter.QuickLaunchItem(
                getString(R.string.quick_launch_connect_server),
                getString(R.string.quick_launch_connect_server_desc),
                QuickLaunchAdapter.ActionType.CONNECT_SERVER
        ));

        items.add(new QuickLaunchAdapter.QuickLaunchItem(
                getString(R.string.quick_launch_add_server),
                getString(R.string.quick_launch_add_server_desc),
                QuickLaunchAdapter.ActionType.ADD_SERVER
        ));

        items.add(new QuickLaunchAdapter.QuickLaunchItem(
                getString(R.string.quick_launch_realm_invite),
                getString(R.string.quick_launch_realm_invite_desc),
                QuickLaunchAdapter.ActionType.REALM_INVITE
        ));

        items.add(new QuickLaunchAdapter.QuickLaunchItem(
                getString(R.string.quick_launch_load_world),
                getString(R.string.quick_launch_load_world_desc),
                QuickLaunchAdapter.ActionType.LOAD_WORLD
        ));

        items.add(new QuickLaunchAdapter.QuickLaunchItem(
                getString(R.string.quick_launch_command),
                getString(R.string.quick_launch_command_desc),
                QuickLaunchAdapter.ActionType.SLASH_COMMAND
        ));

        items.add(new QuickLaunchAdapter.QuickLaunchItem(
                getString(R.string.quick_launch_custom_uri),
                getString(R.string.quick_launch_custom_uri_desc),
                QuickLaunchAdapter.ActionType.CUSTOM_URI
        ));

        adapter.updateItems(items);
    }

    private void handleQuickAction(QuickLaunchAdapter.ActionType actionType) {
        switch (actionType) {
            case HOW_TO_PLAY:
                launchWithUri(MinecraftUriHandler.buildShowHowToPlay());
                break;
            case SERVERS_TAB:
                launchWithUri(MinecraftUriHandler.buildOpenServersTab());
                break;
            case PROFILE_SCREEN:
                launchWithUri(MinecraftUriHandler.buildShowProfileScreen());
                break;
            case STORE_HOME:
                launchWithUri(MinecraftUriHandler.buildOpenStore());
                break;
            case MINECOIN_OFFERS:
                launchWithUri(MinecraftUriHandler.buildShowMinecoinOffers());
                break;
            case MARKETPLACE_PASS:
                showMarketplacePassDialog();
                break;
            case CONNECT_SERVER:
                showConnectServerDialog();
                break;
            case ADD_SERVER:
                showAddServerDialog();
                break;
            case REALM_INVITE:
                showRealmInviteDialog();
                break;
            case LOAD_WORLD:
                showLoadWorldDialog();
                break;
            case SLASH_COMMAND:
                showCommandDialog();
                break;
            case CUSTOM_URI:
                showCustomUriDialog();
                break;
        }
    }

    private void launchWithUri(Uri uri) {
        Intent intent = new Intent(this, IntentHandler.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(uri);
        startActivity(intent);
    }

    private void showMarketplacePassDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_marketplace_pass, null);
        Spinner tabSpinner = dialogView.findViewById(R.id.tab_spinner);
        
        String[] tabs = {"Home", "Content", "Faq", "Subscribe"};
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this, 
                android.R.layout.simple_spinner_item, tabs);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        tabSpinner.setAdapter(spinnerAdapter);

        new org.chimeramc.client.ui.dialogs.CustomAlertDialog(this)
                .setTitleText(getString(R.string.quick_launch_marketplace_pass))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.launch), v -> {
                    String selectedTab = tabs[tabSpinner.getSelectedItemPosition()];
                    launchWithUri(MinecraftUriHandler.buildOpenCsbPdpScreen(selectedTab));
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showConnectServerDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_connect_server, null);
        EditText serverIpEdit = dialogView.findViewById(R.id.server_ip_edit);
        EditText serverPortEdit = dialogView.findViewById(R.id.server_port_edit);
        serverPortEdit.setText("19132");

        new org.chimeramc.client.ui.dialogs.CustomAlertDialog(this)
                .setTitleText(getString(R.string.quick_launch_connect_server))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.connect), v -> {
                    String ip = serverIpEdit.getText().toString().trim();
                    String portStr = serverPortEdit.getText().toString().trim();
                    
                    if (TextUtils.isEmpty(ip)) {
                        Toast.makeText(this, R.string.server_ip_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    int port = 19132;
                    if (!TextUtils.isEmpty(portStr)) {
                        try {
                            port = Integer.parseInt(portStr);
                        } catch (NumberFormatException e) {
                            Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    
                    launchWithUri(MinecraftUriHandler.buildConnectServer(ip, port));
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showAddServerDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_server, null);
        EditText serverNameEdit = dialogView.findViewById(R.id.server_name_edit);
        EditText serverIpEdit = dialogView.findViewById(R.id.server_ip_edit);
        EditText serverPortEdit = dialogView.findViewById(R.id.server_port_edit);
        serverPortEdit.setText("19132");

        new org.chimeramc.client.ui.dialogs.CustomAlertDialog(this)
                .setTitleText(getString(R.string.quick_launch_add_server))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.add), v -> {
                    String name = serverNameEdit.getText().toString().trim();
                    String ip = serverIpEdit.getText().toString().trim();
                    String portStr = serverPortEdit.getText().toString().trim();
                    
                    if (TextUtils.isEmpty(name) || TextUtils.isEmpty(ip)) {
                        Toast.makeText(this, R.string.server_details_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    int port = 19132;
                    if (!TextUtils.isEmpty(portStr)) {
                        try {
                            port = Integer.parseInt(portStr);
                        } catch (NumberFormatException e) {
                            Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    
                    // Persist the server directly rather than asking the game to add it via a
                    // deep link. The old path handed a minecraft:// URI to IntentHandler, which
                    // (when a session was already running) forwarded it to MinecraftActivity as
                    // an extra the running game never read — so "Add server" failed with nothing
                    // to show for it when the game was not in the foreground. Writing the game's
                    // own external_servers.txt is the file it reads on the server-list screen.
                    addServerToFile(name, ip, port);
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    /**
     * Writes a server into the selected instance's {@code external_servers.txt}.
     *
     * <p>Resolves the same game-data root the content screens use, so the server lands where the
     * game will look for it. A missing instance or storage location is reported rather than
     * silently doing nothing.
     */
    private void addServerToFile(String name, String ip, int port) {
        VersionManager vm = VersionManager.get(this);
        GameVersion version = vm.getSelectedVersion();
        // Servers live in the shared game data root too, so adding one does not actually require a
        // selected instance. The guard used to reject the action with an unrelated "no version"
        // skin string, which is why "add server" failed outright before choosing an instance.
        String profileId = version != null
                ? version.getStorageProfileId()
                : LauncherStorage.INSTALLED_MINECRAFT_PROFILE_ID;
        boolean isolated = version != null && version.versionIsolation;
        FeatureSettings.StorageType storageType = LauncherStorage.normalizeContentStorageType(
                LauncherStorage.readSavedContentStorageType(this), isolated);
        File gameDataDir = LauncherStorage.getContentGameDataDir(this, profileId, storageType);
        if (gameDataDir == null) {
            Toast.makeText(this, R.string.server_add_failed, Toast.LENGTH_LONG).show();
            return;
        }
        File serverFile = new File(new File(gameDataDir, "minecraftpe"), "external_servers.txt");

        final ServerItem server = new ServerItem(name, ip, port);
        if (worker.isShutdown()) return;
        try {
            worker.execute(() -> {
                ServerManager.AddResult result = ServerManager.writeServerToFile(serverFile, server);
                // A write failure used to report "that server is already in your list", which sent
                // the user looking for a duplicate instead of at the storage problem that occurred.
                int message = result == ServerManager.AddResult.ADDED
                        ? R.string.server_added
                        : result == ServerManager.AddResult.DUPLICATE
                                ? R.string.server_exists
                                : R.string.server_add_failed;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                });
            });
        } catch (RejectedExecutionException ignored) {
            // The screen was destroyed between the check and the submit.
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void showRealmInviteDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_realm_invite, null);
        EditText inviteCodeEdit = dialogView.findViewById(R.id.invite_code_edit);

        new org.chimeramc.client.ui.dialogs.CustomAlertDialog(this)
                .setTitleText(getString(R.string.quick_launch_realm_invite))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.join), v -> {
                    String inviteCode = inviteCodeEdit.getText().toString().trim();
                    
                    if (TextUtils.isEmpty(inviteCode)) {
                        Toast.makeText(this, R.string.invite_code_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    launchWithUri(MinecraftUriHandler.buildAcceptRealmInvite(inviteCode));
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showLoadWorldDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_load_world, null);
        EditText worldNameEdit = dialogView.findViewById(R.id.world_name_edit);

        new org.chimeramc.client.ui.dialogs.CustomAlertDialog(this)
                .setTitleText(getString(R.string.quick_launch_load_world))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.load), v -> {
                    String worldName = worldNameEdit.getText().toString().trim();
                    
                    if (TextUtils.isEmpty(worldName)) {
                        Toast.makeText(this, R.string.world_name_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    launchWithUri(MinecraftUriHandler.buildConnectLocalWorld(worldName));
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showCommandDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_slash_command, null);
        EditText commandEdit = dialogView.findViewById(R.id.command_edit);

        new org.chimeramc.client.ui.dialogs.CustomAlertDialog(this)
                .setTitleText(getString(R.string.quick_launch_command))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.execute), v -> {
                    String command = commandEdit.getText().toString().trim();
                    
                    if (TextUtils.isEmpty(command)) {
                        Toast.makeText(this, R.string.command_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    if (command.startsWith("/")) {
                        command = command.substring(1);
                    }
                    
                    launchWithUri(MinecraftUriHandler.buildSlashCommand(command));
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showCustomUriDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_custom_uri, null);
        EditText uriEdit = dialogView.findViewById(R.id.uri_edit);
        uriEdit.setText("minecraft://");

        new org.chimeramc.client.ui.dialogs.CustomAlertDialog(this)
                .setTitleText(getString(R.string.quick_launch_custom_uri))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.launch), v -> {
                    String uriStr = uriEdit.getText().toString().trim();
                    
                    if (TextUtils.isEmpty(uriStr) || !uriStr.startsWith("minecraft://")) {
                        Toast.makeText(this, R.string.invalid_minecraft_uri, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    try {
                        Uri uri = Uri.parse(uriStr);
                        launchWithUri(uri);
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.invalid_minecraft_uri, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }
}
