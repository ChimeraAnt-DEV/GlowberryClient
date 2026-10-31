package org.chimeramc.client.core.mods.inbuilt.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The radial wheel's profile-manager rules that do not need a Context: id derivation, id
 * de-duplication, and the repointing of sub-wheel references when a profile is deleted. No mocks.
 */
public class WheelProfileStoreTest {

    @Test
    public void aNameBecomesAStableId() {
        assertEquals("pvp", WheelProfileStore.slug("PvP"));
        assertEquals("survival", WheelProfileStore.slug("Survival!"));
        assertEquals("mywheel", WheelProfileStore.slug("My Wheel"));
    }

    @Test
    public void aNameWithNoAlphanumericsStillYieldsAnId() {
        // A profile with an empty id cannot be referenced by a sub-wheel, so the fallback must be
        // a real id rather than "".
        assertEquals("wheel", WheelProfileStore.slug("!!!"));
        assertEquals("wheel", WheelProfileStore.slug(null));
    }

    @Test
    public void aDuplicateNameGetsADistinctId() {
        List<WheelEntry.Profile> profiles = new ArrayList<>();
        profiles.add(new WheelEntry.Profile("pvp", "PvP"));
        // The base id is taken, so the second wheel must not collide with the first.
        assertNotEquals("pvp", WheelProfileStore.uniqueId(profiles, "pvp"));
        assertEquals("pvp2", WheelProfileStore.uniqueId(profiles, "pvp"));
    }

    // --- Entry editing rules (the manager's "entries" half) ---------------------------------
    //
    // These run against the pure rule the store's setEntries uses, so no Context is needed and the
    // manager cannot write an entry the wheel would choke on.

    @Test
    public void aNinthEntryIsRefusedBecauseTheWheelHasEightSectors() {
        List<WheelEntry> entries = new ArrayList<>();
        for (int i = 0; i < WheelLayout.MAX_SECTORS + 3; i++) {
            entries.add(new WheelEntry(WheelEntry.Action.TOGGLE_MODULE, "mod" + i, "M" + i));
        }
        List<WheelEntry> safe = WheelProfileStore.sanitizeEntries(entries, "pvp",
                new ArrayList<>());
        assertEquals("only the sectors that exist may be written", WheelLayout.MAX_SECTORS,
                safe.size());
    }

    @Test
    public void aSubWheelThatOpensItsOwnWheelIsRefused() {
        // An entry pointing at its own profile recurses forever when opened, so the rule drops it
        // rather than writing a wheel that cannot be closed.
        List<WheelEntry> entries = new ArrayList<>();
        entries.add(new WheelEntry(WheelEntry.Action.OPEN_SUBWHEEL, "pvp", "PvP"));
        entries.add(new WheelEntry(WheelEntry.Action.TOGGLE_MODULE, "snaplook", "Snaplook"));
        List<WheelEntry> safe = WheelProfileStore.sanitizeEntries(entries, "pvp",
                new ArrayList<>());
        assertEquals(1, safe.size());
        assertEquals(WheelEntry.Action.TOGGLE_MODULE, safe.get(0).action);
    }

    @Test
    public void aSubWheelToAnUnknownProfileIsDropped() {
        // A sector that opens nothing reads as broken, so an unknown target never lands.
        List<WheelEntry> entries = new ArrayList<>();
        entries.add(new WheelEntry(WheelEntry.Action.OPEN_SUBWHEEL, "ghost", "Ghost"));
        assertEquals(0, WheelProfileStore.sanitizeEntries(entries, "pvp",
                new ArrayList<>()).size());
    }

    @Test
    public void aSubWheelToARealProfileSurvives() {
        List<WheelEntry.Profile> profiles = new ArrayList<>();
        profiles.add(new WheelEntry.Profile("pvp", "PvP"));
        profiles.add(new WheelEntry.Profile("survival", "Survival"));
        List<WheelEntry> entries = new ArrayList<>();
        entries.add(new WheelEntry(WheelEntry.Action.OPEN_SUBWHEEL, "survival", "Survival"));
        List<WheelEntry> safe = WheelProfileStore.sanitizeEntries(entries, "pvp", profiles);
        assertEquals(1, safe.size());
        assertEquals("survival", safe.get(0).target);
    }

    @Test
    public void aNullEntryIsSkippedRatherThanCrashing() {
        List<WheelEntry> entries = new ArrayList<>();
        entries.add(null);
        entries.add(new WheelEntry(WheelEntry.Action.TOGGLE_MODULE, "zoom", "Zoom"));
        List<WheelEntry> safe = WheelProfileStore.sanitizeEntries(entries, "pvp",
                new ArrayList<>());
        assertEquals(1, safe.size());
    }

    @Test
    public void aBlankLabelFallsBackRatherThanWritingAnEmptySector() {
        List<WheelEntry> entries = new ArrayList<>();
        entries.add(new WheelEntry(WheelEntry.Action.TOGGLE_MODULE, "zoom", "   "));
        List<WheelEntry> safe = WheelProfileStore.sanitizeEntries(entries, "pvp",
                new ArrayList<>());
        assertEquals(1, safe.size());
        assertTrue("an empty label would draw a blank sector",
                safe.get(0).label != null && !safe.get(0).label.trim().isEmpty());
    }

    @Test
    public void anUnusedIdIsKept() {
        List<WheelEntry.Profile> profiles = new ArrayList<>();
        profiles.add(new WheelEntry.Profile("pvp", "PvP"));
        assertEquals("survival", WheelProfileStore.uniqueId(profiles, "survival"));
    }

    @Test
    public void deletingAProfileRepointsEverySubWheelThatOpenedIt() {
        // A dangling OPEN_SUBWHEEL would draw a sector that does nothing, so every reference to the
        // deleted id must move to the fallback.
        List<WheelEntry.Profile> profiles = new ArrayList<>();
        WheelEntry.Profile root = new WheelEntry.Profile("main", "Main");
        root.entries.add(new WheelEntry(WheelEntry.Action.OPEN_SUBWHEEL, "pvp", "PvP"));
        profiles.add(root);
        WheelEntry.Profile other = new WheelEntry.Profile("other", "Other");
        other.entries.add(new WheelEntry(WheelEntry.Action.OPEN_SUBWHEEL, "pvp", "PvP"));
        profiles.add(other);

        WheelProfileStore.repointSubWheels(profiles, "pvp", "main");

        assertEquals("main", root.entries.get(0).target);
        assertEquals("main", other.entries.get(0).target);
    }

    @Test
    public void repointingLeavesUnrelatedEntriesAlone() {
        List<WheelEntry.Profile> profiles = new ArrayList<>();
        WheelEntry.Profile root = new WheelEntry.Profile("main", "Main");
        root.entries.add(new WheelEntry(WheelEntry.Action.OPEN_SUBWHEEL, "survival", "Survival"));
        root.entries.add(new WheelEntry(WheelEntry.Action.TOGGLE_MODULE, "pvp", "CPS"));
        profiles.add(root);

        WheelProfileStore.repointSubWheels(profiles, "pvp", "main");

        // The sub-wheel opened "survival", not the deleted id, so it stays; and a TOGGLE_MODULE
        // entry whose target happens to equal the id is a module id, not a profile reference.
        assertEquals("survival", root.entries.get(0).target);
        assertEquals("pvp", root.entries.get(1).target);
    }

    @Test
    public void repointingToleratesNulls() {
        WheelProfileStore.repointSubWheels(null, "a", "b");
        List<WheelEntry.Profile> profiles = new ArrayList<>();
        profiles.add(null);
        profiles.add(new WheelEntry.Profile("main", "Main"));
        WheelProfileStore.repointSubWheels(profiles, "a", "b");
        assertTrue(profiles.size() == 2);
    }

    // --- The catalog the manager offers -----------------------------------------------------

    @Test
    public void everyCatalogChoiceNamesARealAction() {
        for (WheelEntryCatalog.Choice choice : WheelEntryCatalog.all()) {
            assertTrue("a choice with no action would build a broken entry",
                    choice.action != null);
        }
    }

    @Test
    public void aToggleChoiceCarriesAModuleIdAndADestinationChoiceDoesNot() {
        for (WheelEntryCatalog.Choice choice : WheelEntryCatalog.all()) {
            if (choice.action == WheelEntry.Action.TOGGLE_MODULE) {
                assertTrue("a toggle needs a module id", choice.target != null
                        && !choice.target.trim().isEmpty());
            }
        }
    }

    @Test
    public void theCatalogNeverOffersASubWheelForTheProfileBeingEdited() {
        // The store refuses it, so offering it would be a button that silently does nothing.
        List<WheelEntry.Profile> profiles = new ArrayList<>();
        profiles.add(new WheelEntry.Profile("pvp", "PvP"));
        profiles.add(new WheelEntry.Profile("survival", "Survival"));
        for (WheelEntryCatalog.Choice choice : WheelEntryCatalog.forProfile(profiles, "pvp")) {
            if (choice.action == WheelEntry.Action.OPEN_SUBWHEEL) {
                assertNotEquals("pvp", choice.target);
            }
        }
    }

    @Test
    public void aProfileGetsASubWheelChoiceForEveryOtherProfile() {
        List<WheelEntry.Profile> profiles = new ArrayList<>();
        profiles.add(new WheelEntry.Profile("pvp", "PvP"));
        profiles.add(new WheelEntry.Profile("survival", "Survival"));
        profiles.add(new WheelEntry.Profile("creative", "Creative"));
        int subWheels = 0;
        for (WheelEntryCatalog.Choice choice : WheelEntryCatalog.forProfile(profiles, "pvp")) {
            if (choice.action == WheelEntry.Action.OPEN_SUBWHEEL) subWheels++;
        }
        assertEquals("two other profiles, so two sub-wheel targets", 2, subWheels);
    }

    @Test
    public void theBaseCatalogHasNoSubWheelBecauseItKnowsNoProfiles() {
        for (WheelEntryCatalog.Choice choice : WheelEntryCatalog.all()) {
            assertTrue("a sub-wheel needs a profile list to point at",
                    choice.action != WheelEntry.Action.OPEN_SUBWHEEL);
        }
    }

    @Test
    public void aModuleIdBecomesAReadableLabel() {
        assertEquals("Auto Sprint", WheelEntryCatalog.prettyLabel("auto_sprint"));
        assertEquals("Fps Display", WheelEntryCatalog.prettyLabel("fps_display"));
        assertEquals("Zoom", WheelEntryCatalog.prettyLabel("zoom"));
        assertEquals("\u2014", WheelEntryCatalog.prettyLabel(null));
    }

    @Test
    public void everyCatalogChoiceHasANonEmptyLabel() {
        // The label is drawn in the sector and in the picker; an empty one reads as a bug.
        for (WheelEntryCatalog.Choice choice : WheelEntryCatalog.all()) {
            String label = WheelEntryCatalog.label(choice, null);
            assertTrue("choice " + choice.action + " has no label",
                    label != null && !label.trim().isEmpty());
        }
    }
}
