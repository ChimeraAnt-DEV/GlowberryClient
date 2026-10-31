package org.chimeramc.client.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.chimeramc.client.R;
import org.chimeramc.client.core.content.ServerItem;
import org.chimeramc.client.core.content.ServerPongInfo;
import org.chimeramc.client.ui.components.ListDiff;
import org.chimeramc.client.ui.components.ListDiffDispatch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server rows carry a live ping line (G14). Ping results arrive asynchronously (from a background
 * executor in the host activity) keyed by {@code ip:port}, so a row can redraw without rebuilding
 * the whole list and a result does not get lost when the adapter is rebound.
 */
public class ServersAdapter extends RecyclerView.Adapter<ServersAdapter.ViewHolder> {

    private List<ServerItem> servers;
    private final OnServerClickListener listener;
    /** Ping outcomes per server key ("ip:port"); absent means "yet to ping". */
    private final Map<String, ServerPongInfo> pongs = new HashMap<>();
    private final Map<String, Boolean> pingFailures = new HashMap<>();
    /** The selected instance's version code, used for the compatibility verdict. */
    private String selectedVersionCode;

    public interface OnServerClickListener {
        void onDeleteClick(ServerItem server);
    }

    public ServersAdapter(List<ServerItem> servers, OnServerClickListener listener) {
        this.servers = servers;
        this.listener = listener;
    }

    /**
     * Replaces the list, animating only what changed.
     *
     * <p>Pongs are keyed by ip:port and stay valid across re-binds: a filter or a refresh does not
     * justify losing every ping line. A server genuinely deleted and re-added with the same address
     * re-uses the old reading, which is the better failure mode. The diff preserves that, because a
     * row that survives the update is a change rather than a remove and an insert.
     */
    public void updateData(List<ServerItem> newServers) {
        List<ServerItem> next = newServers != null ? newServers : java.util.Collections.emptyList();
        ListDiff.Result diff = ListDiff.compute(this.servers, next,
                ServersAdapter::keyFor,
                (old, fresh) -> old.name.equals(fresh.name));
        this.servers = next;
        ListDiffDispatch.apply(this, diff);
    }

    /** The compatibility verdict is per row, so every row's status line may have changed. */
    public void setSelectedVersionCode(String versionCode) {
        this.selectedVersionCode = versionCode;
        if (getItemCount() > 0) {
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    /** Called from the UI thread when a ping lands; redraws only the matching row. */
    public void onPingResult(ServerItem server, ServerPongInfo pong, boolean failed) {
        String key = keyFor(server);
        if (failed || pong == null) {
            pingFailures.put(key, Boolean.TRUE);
            pongs.remove(key);
        } else {
            pongs.put(key, pong);
            pingFailures.remove(key);
        }
        int index = indexOf(server);
        if (index >= 0) {
            notifyItemChanged(index);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_server, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ServerItem server = servers.get(position);
        holder.nameText.setText(server.name);
        holder.ipText.setText(server.ip + ":" + server.port);
        renderStatus(holder, server);

        holder.deleteButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteClick(server);
            }
        });

        org.chimeramc.client.util.PersonalizationManager pm =
                new org.chimeramc.client.util.PersonalizationManager(holder.itemView.getContext());
        pm.applyGlassToView(holder.itemView);
        pm.applyAccentToView(holder.itemView, holder.itemView.getContext());
    }

    private void renderStatus(ViewHolder holder, ServerItem server) {
        TextView status = holder.statusText;
        if (status == null) return;
        String key = keyFor(server);

        if (Boolean.TRUE.equals(pingFailures.get(key))) {
            setStatus(status, holder.itemView.getContext()
                    .getString(R.string.server_ping_offline), 0xFF8B949E);
            return;
        }
        ServerPongInfo pong = pongs.get(key);
        if (pong == null) {
            setStatus(status, holder.itemView.getContext()
                    .getString(R.string.server_ping_checking), 0xFF8B949E);
            return;
        }
        if (pong.parseError || !pong.isBedrock()) {
            setStatus(status, holder.itemView.getContext()
                    .getString(R.string.server_ping_offline), 0xFF8B949E);
            return;
        }

        String summary = pong.motd + " · " + pong.playersOnline + "/" + pong.maxPlayers;
        ServerPongInfo.Compatibility compatibility = pong.compatibilityWith(selectedVersionCode);
        if (compatibility == ServerPongInfo.Compatibility.COMPATIBLE) {
            setStatus(status, summary, 0xFF35DC84);
        } else if (compatibility == ServerPongInfo.Compatibility.MISMATCH) {
            String line = summary + " · " + holder.itemView.getContext()
                    .getString(R.string.server_ping_version_mismatch, pong.gameVersion);
            setStatus(status, line, 0xFFFFC944);
        } else {
            setStatus(status, summary, 0xFF8B949E);
        }
    }

    private static void setStatus(TextView status, String text, int color) {
        status.setText(text);
        status.setTextColor(color);
    }

    private int indexOf(ServerItem server) {
        if (servers == null) return -1;
        for (int i = 0; i < servers.size(); i++) {
            if (sameServer(servers.get(i), server)) return i;
        }
        return -1;
    }

    private static boolean sameServer(ServerItem a, ServerItem b) {
        return a.ip.equals(b.ip) && a.port == b.port && a.name.equals(b.name);
    }

    public static String keyFor(ServerItem server) {
        return server.ip + ":" + server.port;
    }

    @Override
    public int getItemCount() {
        return servers == null ? 0 : servers.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView nameText;
        TextView ipText;
        TextView statusText;
        Button deleteButton;

        ViewHolder(View view) {
            super(view);
            nameText = view.findViewById(R.id.server_name);
            ipText = view.findViewById(R.id.server_ip);
            statusText = view.findViewById(R.id.server_status);
            deleteButton = view.findViewById(R.id.server_delete_button);
        }
    }
}
