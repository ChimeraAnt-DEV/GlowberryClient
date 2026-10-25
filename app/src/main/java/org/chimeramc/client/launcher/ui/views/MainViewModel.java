package org.chimeramc.client.ui.views;

import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;

import org.chimeramc.client.core.mods.Mod;
import org.chimeramc.client.core.mods.ModManager;
import org.chimeramc.client.core.versions.GameVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class MainViewModel extends ViewModel {

    private static final String TAG = "MainViewModel";

    private final ModManager modManager;
    private final MutableLiveData<List<Mod>> modsLiveData = new MutableLiveData<>();
    private final ExecutorService modsExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong refreshGeneration = new AtomicLong();
    private final Observer<Void> modsChangedObserver = trigger -> refreshMods();

    public MainViewModel(ModManager modManager) {
        this.modManager = modManager;
        modManager.getModsChangedLiveData().observeForever(modsChangedObserver);
        refreshMods();
    }

    public void refreshMods() {
        loadMods(false);
    }

    public void reloadMods() {
        loadMods(true);
    }

    /**
     * Reads the instance's mod list on the worker and publishes it.
     *
     * <p>The Mods tab and the hero card's "N active" stat are both driven by this LiveData, so a
     * discovery failure that skipped {@code postValue} left both showing the previous instance's
     * list with nothing to say why. A failure now publishes an empty list, which is the same thing
     * an instance with no mods produces, so the UI always reflects a completed read.
     */
    private void loadMods(boolean invalidateCache) {
        long generation = refreshGeneration.incrementAndGet();
        try {
            modsExecutor.execute(() -> {
                List<Mod> mods;
                try {
                    mods = invalidateCache ? modManager.reloadMods() : modManager.getMods();
                } catch (Throwable t) {
                    Log.w(TAG, "Mod discovery failed; publishing an empty list", t);
                    mods = new ArrayList<>();
                }
                if (generation == refreshGeneration.get()) {
                    modsLiveData.postValue(mods);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // The ViewModel was cleared under the caller; settle the observer rather than leaving
            // it on a value that will never arrive.
            modsLiveData.postValue(new ArrayList<>());
        }
    }

    public void removeMod(Mod mod) {
        modManager.deleteMod(mod.getId());
        refreshMods();
    }

    public void setCurrentVersion(GameVersion version) {
        modManager.setCurrentVersion(version);
        refreshMods();
    }

    public LiveData<List<Mod>> getModsLiveData() {
        return modsLiveData;
    }

    public void setModEnabled(String fileName, boolean enabled) {
        new Thread(() -> modManager.setModEnabled(fileName, enabled)).start();
    }

    public void reorderMods(List<Mod> reorderedMods) {
        new Thread(() -> {
            modManager.reorderMods(reorderedMods);
            refreshMods();
        }).start();
    }

    @Override
    protected void onCleared() {
        modManager.getModsChangedLiveData().removeObserver(modsChangedObserver);
        refreshGeneration.incrementAndGet();
        modsExecutor.shutdownNow();
        super.onCleared();
    }
}
