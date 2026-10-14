package org.chimeramc.client.launcher.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.junit.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Pins the controller-profile persistence rule. The store round-trips through JSON, so
 * {@code getProfiles} returns <b>new</b> objects each call — the exact reason the old
 * {@code saveProfiles(type, getProfiles(type))} lost a slider edit. These tests reproduce the
 * round-trip with a fake JSON-backed store and assert the edit survives.
 */
public class ControllerProfileListTest {

    /** A minimal stand-in for the SharedPreferences-backed store: JSON in, JSON out. */
    private static final class FakeStore {
        private static final Type LIST_TYPE = new TypeToken<List<ControllerProfile>>() {}.getType();
        private final Gson gson = new Gson();
        private String json;

        List<ControllerProfile> getProfiles() {
            if (json == null) {
                List<ControllerProfile> fresh = new ArrayList<>();
                fresh.add(new ControllerProfile("Default"));
                return fresh;
            }
            List<ControllerProfile> parsed = gson.fromJson(json, LIST_TYPE);
            return parsed == null ? new ArrayList<>() : parsed;
        }

        void saveProfiles(List<ControllerProfile> profiles) {
            json = gson.toJson(profiles);
        }
    }

    @Test
    public void getProfilesReturnsFreshObjectsEachCall() {
        FakeStore store = new FakeStore();
        store.saveProfiles(store.getProfiles());
        ControllerProfile a = store.getProfiles().get(0);
        ControllerProfile b = store.getProfiles().get(0);
        assertNotSame("the store must deserialise new objects each call (the bug's root cause)",
                a, b);
    }

    @Test
    public void savingAFreshReadLosesTheEdit() {
        // Reproduces the bug: edit one object, then save a fresh read — the edit is lost.
        FakeStore store = new FakeStore();
        store.saveProfiles(store.getProfiles());
        ControllerProfile edited = store.getProfiles().get(0);
        edited.setRightDeadZone(0.42f);
        store.saveProfiles(store.getProfiles());  // the buggy line
        assertEquals("a fresh-read save must lose the edit (this is the bug)",
                ControllerProfile.DEFAULT_DEAD_ZONE, store.getProfiles().get(0).getRightDeadZone(),
                1e-6);
    }

    @Test
    public void replacingTheSlotThenSavingKeepsTheEdit() {
        // The fix: replace the slot with the edited object, then save that list.
        FakeStore store = new FakeStore();
        store.saveProfiles(store.getProfiles());
        ControllerProfile edited = store.getProfiles().get(0);
        edited.setRightDeadZone(0.42f);
        List<ControllerProfile> list = ControllerProfileList.replaceAt(store.getProfiles(), 0, edited);
        store.saveProfiles(list);
        assertEquals(0.42f, store.getProfiles().get(0).getRightDeadZone(), 1e-6);
    }

    @Test
    public void replaceAtReplacesInRange() {
        List<ControllerProfile> list = new ArrayList<>();
        list.add(new ControllerProfile("A"));
        list.add(new ControllerProfile("B"));
        ControllerProfile edited = new ControllerProfile("B2");
        ControllerProfileList.replaceAt(list, 1, edited);
        assertEquals(2, list.size());
        assertSame(edited, list.get(1));
    }

    @Test
    public void replaceAtAppendsWhenSlotIsOutOfRange() {
        List<ControllerProfile> list = new ArrayList<>();
        list.add(new ControllerProfile("A"));
        ControllerProfile extra = new ControllerProfile("B");
        ControllerProfileList.replaceAt(list, 5, extra);
        assertEquals(2, list.size());
        assertSame(extra, list.get(1));
    }

    @Test
    public void replaceAtClampsANegativeSlotToZero() {
        List<ControllerProfile> list = new ArrayList<>();
        list.add(new ControllerProfile("A"));
        ControllerProfile edited = new ControllerProfile("A2");
        ControllerProfileList.replaceAt(list, -3, edited);
        assertSame(edited, list.get(0));
    }

    @Test
    public void replaceAtHandlesNullInputs() {
        List<ControllerProfile> list = new ArrayList<>();
        assertEquals(list, ControllerProfileList.replaceAt(list, 0, null));
        assertEquals(null, ControllerProfileList.replaceAt(null, 0, new ControllerProfile("x")));
    }

    @Test
    public void clampSlotStaysInRange() {
        assertEquals(0, ControllerProfileList.clampSlot(0, 0));
        assertEquals(0, ControllerProfileList.clampSlot(-1, 3));
        assertEquals(2, ControllerProfileList.clampSlot(9, 3));
        assertEquals(1, ControllerProfileList.clampSlot(1, 3));
    }

    @Test
    public void aFullRoundTripKeepsEveryEditedField() {
        FakeStore store = new FakeStore();
        store.saveProfiles(store.getProfiles());
        ControllerProfile edited = store.getProfiles().get(0);
        edited.setName("Racer");
        edited.setLeftDeadZone(0.2f);
        edited.setRightDeadZone(0.3f);
        edited.setRightStickSensitivity(1.8f);
        edited.setRumbleStrength(70);
        edited.setVibrationEnabled(true);
        store.saveProfiles(ControllerProfileList.replaceAt(store.getProfiles(), 0, edited));

        ControllerProfile reloaded = store.getProfiles().get(0);
        assertEquals("Racer", reloaded.getName());
        assertEquals(0.2f, reloaded.getLeftDeadZone(), 1e-6);
        assertEquals(0.3f, reloaded.getRightDeadZone(), 1e-6);
        assertEquals(1.8f, reloaded.getRightStickSensitivity(), 1e-6);
        assertEquals(70, reloaded.getRumbleStrength());
        assertTrue(reloaded.isVibrationEnabled());
    }
}
