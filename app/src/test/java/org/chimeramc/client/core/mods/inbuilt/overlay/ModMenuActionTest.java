package org.chimeramc.client.core.mods.inbuilt.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pins the one-shot action catalog (M3).
 *
 * <p>An action has no enable state, so it must never be offered a switch. That invariant and the
 * uniqueness of the ids are what the adapter's row kind relies on.
 */
public class ModMenuActionTest {

    @Test
    public void everyActionIsAnActionRowAndNeverASwitch() {
        for (ModMenuAction action : ModMenuActionCatalog.all()) {
            assertEquals(action.getId(), ModMenuRowKind.ACTION, action.kind());
            assertFalse(action.getId() + " must not show a switch", action.kind().showsSwitch());
            assertTrue(action.getId() + " shows a chevron", action.kind().showsChevron());
        }
    }

    @Test
    public void theCatalogIsNotEmptyAndEveryIdIsUnique() {
        List<ModMenuAction> actions = ModMenuActionCatalog.all();
        assertFalse("a menu with no actions cannot teach the switch/action difference",
                actions.isEmpty());
        Set<String> ids = new HashSet<>();
        for (ModMenuAction action : actions) {
            assertTrue("duplicate action id " + action.getId(), ids.add(action.getId()));
        }
    }

    @Test
    public void everyActionHasATitleAndASection() {
        for (ModMenuAction action : ModMenuActionCatalog.all()) {
            assertTrue(action.getId() + " has a title", action.getTitleRes() != 0);
            assertNotNull(action.getId() + " has a section", action.getGroupId());
            assertFalse(action.getId() + " has a section",
                    action.getGroupId().trim().isEmpty());
        }
    }

    @Test
    public void theResetHudActionIsPresentBecauseItIsTheCanonicalExample() {
        ModMenuAction reset = ModMenuActionCatalog.byId(ModMenuActionCatalog.ACTION_RESET_HUD);
        assertNotNull("Reset HUD is the example the spec names", reset);
        // The section id is a module group id, so the action follows the same filter the module
        // list does; "hud" named no section and the row was never rendered.
        assertEquals("inbuilt", reset.getGroupId());
    }

    @Test
    public void byIdIsNullSafeAndReturnsNullForAnUnknownId() {
        assertNull(ModMenuActionCatalog.byId(null));
        assertNull(ModMenuActionCatalog.byId("no_such_action"));
        assertNotNull(ModMenuActionCatalog.byId(ModMenuActionCatalog.ACTION_RELOAD_MODULES));
    }

    @Test
    public void theWheelManagerActionExistsBecauseTheStoreHadNoScreen() {
        // The store could create, rename, reorder and delete but nothing opened it, so the wheel
        // was stuck on the built-in default set. The action is that screen's entry point.
        ModMenuAction manage = ModMenuActionCatalog.byId(ModMenuActionCatalog.ACTION_MANAGE_WHEEL);
        assertNotNull("the wheel profile manager needs an entry point", manage);
        assertEquals("inbuilt", manage.getGroupId());
        assertTrue("the action has a title", manage.getTitleRes() != 0);
        assertTrue("the action has an explanation", manage.getSubtitleRes() != 0);
    }

    @Test
    public void forGroupReturnsOnlyThatSectionsActions() {
        for (ModMenuAction action : ModMenuActionCatalog.all()) {
            for (ModMenuAction inGroup : ModMenuActionCatalog.forGroup(action.getGroupId())) {
                assertEquals(action.getGroupId(), inGroup.getGroupId());
            }
        }
        assertTrue("an unknown section has no actions",
                ModMenuActionCatalog.forGroup("no_such_section").isEmpty());
    }

    @Test
    public void theStableKeyIsNamespacedSoItCannotCollideWithAModule() {
        for (ModMenuAction action : ModMenuActionCatalog.all()) {
            assertTrue(action.getStableKey().startsWith("action:"));
        }
    }

    @Test
    public void everyActionCarriesAnIcon() {
        // The row draws the action's icon in its leading tile; a zero would crash the inflate.
        for (ModMenuAction action : ModMenuActionCatalog.all()) {
            assertTrue(action.getId() + " has an icon", action.getIconRes() != 0);
        }
    }

    @Test
    public void equalityIsByIdSoTheListIdentitySurvivesARebuild() {
        ModMenuAction a = ModMenuActionCatalog.byId(ModMenuActionCatalog.ACTION_RESET_HUD);
        ModMenuAction b = ModMenuActionCatalog.byId(ModMenuActionCatalog.ACTION_RESET_HUD);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(ModMenuActionCatalog.byId(ModMenuActionCatalog.ACTION_RELOAD_MODULES)));
    }
}
