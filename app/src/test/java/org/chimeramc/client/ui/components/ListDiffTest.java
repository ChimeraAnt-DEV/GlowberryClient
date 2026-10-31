package org.chimeramc.client.ui.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The rules a diff has to get right, pinned without a device (items 12, 141-143, 202).
 *
 * <p>These are the cases that decide whether a list animates one row or redraws itself. They are
 * also the cases that crash the adapter when they are wrong, which is why the wrong-index paths
 * are asserted rather than assumed: a diff that reports a change at a position it has already
 * removed is an {@code IndexOutOfBoundsException} inside {@code RecyclerView}, on a screen the
 * user is looking at.
 */
public class ListDiffTest {

    /** A row whose identity is its id and whose content is its label. */
    private static final class Row {
        final String id;
        final String label;

        Row(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return id + "=" + label;
        }
    }

    private static final ListDiff.KeyFn<Row> KEY = row -> row.id;
    private static final ListDiff.ContentFn<Row> CONTENT =
            (a, b) -> Objects.equals(a.label, b.label);

    private static List<Row> rows(String... specs) {
        List<Row> out = new ArrayList<>();
        for (String spec : specs) {
            int eq = spec.indexOf('=');
            out.add(new Row(spec.substring(0, eq), spec.substring(eq + 1)));
        }
        return out;
    }

    private static List<String> render(List<ListDiff.Op> ops) {
        List<String> out = new ArrayList<>();
        for (ListDiff.Op op : ops) out.add(op.toString());
        return out;
    }

    @Test
    public void anIdenticalListProducesNothing() {
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2"), rows("a=1", "b=2"), KEY, CONTENT);
        assertFalse(r.fullRedraw);
        assertTrue(r.isEmpty());
    }

    @Test
    public void anEmptyListToAnEmptyListProducesNothing() {
        ListDiff.Result r = ListDiff.compute(Collections.emptyList(), Collections.emptyList(),
                KEY, CONTENT);
        assertTrue(r.isEmpty());
    }

    @Test
    public void aNullListIsTreatedAsEmpty() {
        assertTrue(ListDiff.compute(null, Collections.emptyList(), KEY, CONTENT).isEmpty());
        assertTrue(ListDiff.compute(Collections.emptyList(), null, KEY, CONTENT).isEmpty());
    }

    @Test
    public void oneRowAppendedIsASingleInsertAtTheEnd() {
        ListDiff.Result r = ListDiff.compute(rows("a=1"), rows("a=1", "b=2"), KEY, CONTENT);
        assertFalse(r.fullRedraw);
        assertEquals(List.of("INSERT@1"), render(r.ops));
    }

    @Test
    public void oneRowPrependedIsASingleInsertAtTheStart() {
        ListDiff.Result r = ListDiff.compute(rows("b=2"), rows("a=1", "b=2"), KEY, CONTENT);
        assertEquals(List.of("INSERT@0"), render(r.ops));
    }

    @Test
    public void oneRowRemovedFromTheMiddleIsASingleRemove() {
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2", "c=3"), rows("a=1", "c=3"),
                KEY, CONTENT);
        assertEquals(List.of("REMOVE@1"), render(r.ops));
    }

    @Test
    public void aChangedLabelIsASingleChangeAndNotARemoveAndInsert() {
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2", "c=3"), rows("a=1", "b=9", "c=3"),
                KEY, CONTENT);
        assertFalse(r.fullRedraw);
        assertEquals(List.of("CHANGE@1"), render(r.ops));
    }

    @Test
    public void aChangedIdentityIsARemoveAndAnInsertNotAChange() {
        ListDiff.Result r = ListDiff.compute(rows("a=1"), rows("b=1"), KEY, CONTENT);
        assertEquals(List.of("REMOVE@0", "INSERT@0"), render(r.ops));
    }

    @Test
    public void movingTheFirstRowToTheEndIsASingleMove() {
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2", "c=3"), rows("b=2", "c=3", "a=1"),
                KEY, CONTENT);
        assertFalse(r.fullRedraw);
        assertEquals(List.of("MOVE@2from0"), render(r.ops));
    }

    @Test
    public void movingTheLastRowToTheStartIsASingleMove() {
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2", "c=3"), rows("c=3", "a=1", "b=2"),
                KEY, CONTENT);
        assertEquals(List.of("MOVE@0from2"), render(r.ops));
    }

    @Test
    public void aDragOnePlaceDownIsASingleMove() {
        // "b" was at 1 and is now at 2, so the op names the destination and the source.
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2", "c=3", "d=4"),
                rows("a=1", "c=3", "b=2", "d=4"), KEY, CONTENT);
        assertEquals(List.of("MOVE@2from1"), render(r.ops));
    }

    @Test
    public void aDragOnePlaceUpIsASingleMove() {
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2", "c=3", "d=4"),
                rows("a=1", "d=4", "b=2", "c=3"), KEY, CONTENT);
        assertEquals(List.of("MOVE@1from3"), render(r.ops));
    }

    @Test
    public void aGenuineReversalBecomesARemoveRunAndAnInsertRun() {
        // Not one move, so the middle is rebuilt: a removal of the old rows then an insertion of
        // the new ones. That is exact and never guesses at a move sequence.
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2", "c=3"), rows("c=3", "b=2", "a=1"),
                KEY, CONTENT);
        assertFalse(r.fullRedraw);
        assertEquals(List.of("REMOVE@0x3", "INSERT@0x3"), render(r.ops));
    }

    @Test
    public void anInsertAndARemoveOfTheSameLengthReportsBothInAdapterOrder() {
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2"), rows("c=3", "d=4"), KEY, CONTENT);
        assertFalse(r.fullRedraw);
        assertEquals(List.of("REMOVE@0x2", "INSERT@0x2"), render(r.ops));
    }

    @Test
    public void anInsertedMiddleReportsTheRemoveBeforeTheInsert() {
        ListDiff.Result r = ListDiff.compute(rows("a=1", "z=9"), rows("a=1", "b=2", "c=3", "z=9"),
                KEY, CONTENT);
        assertEquals(List.of("INSERT@1x2"), render(r.ops));
    }

    @Test
    public void aChangedHeadAndTailAreReportedAtTheirOwnPositions() {
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2", "c=3", "d=4"),
                rows("a=9", "b=2", "c=3", "d=9"), KEY, CONTENT);
        assertEquals(List.of("CHANGE@0", "CHANGE@3"), render(r.ops));
    }

    @Test
    public void aChangedPrefixIsFoundEvenWhenTheSuffixAlsoChanged() {
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2", "c=3", "d=4", "e=5"),
                rows("a=9", "b=2", "c=3", "d=4", "e=9"), KEY, CONTENT);
        assertEquals(List.of("CHANGE@0", "CHANGE@4"), render(r.ops));
    }

    @Test
    public void everyOperationNamesAPositionInsideTheNewListExceptRemovals() {
        List<List<Row>> oldLists = List.of(
                rows("a=1", "b=2", "c=3"),
                rows("a=1"),
                rows("a=1", "b=2", "c=3", "d=4"));
        List<List<Row>> newLists = List.of(
                rows("c=3", "a=1", "b=2"),
                rows("a=1", "b=2"),
                rows("a=1", "c=3", "d=4", "e=5"));
        for (int i = 0; i < oldLists.size(); i++) {
            List<Row> oldList = oldLists.get(i);
            List<Row> newList = newLists.get(i);
            ListDiff.Result r = ListDiff.compute(oldList, newList, KEY, CONTENT);
            if (r.fullRedraw) continue;
            for (ListDiff.Op op : r.ops) {
                int bound = op.kind == ListDiff.Op.Kind.REMOVE ? oldList.size() : newList.size();
                assertTrue("op " + op + " is outside 0.." + bound + " for case " + i,
                        op.position >= 0 && op.position + op.count <= bound);
                if (op.kind == ListDiff.Op.Kind.MOVE) {
                    assertTrue("move source " + op + " is outside the old list for case " + i,
                            op.from >= 0 && op.from < oldList.size());
                }
            }
        }
    }

    @Test
    public void theDiffNeverRedrawsWhenOnlyContentChanged() {
        // A content-only change must never reset: that is the whole point of the change (item 202).
        for (int i = 0; i < 5; i++) {
            List<Row> oldList = rows("a=1", "b=2", "c=3", "d=4", "e=5");
            List<Row> newList = rows("a=1", "b=2", "c=3", "d=4", "e=5");
            newList.set(i, new Row(newList.get(i).id, "changed"));
            ListDiff.Result r = ListDiff.compute(oldList, newList, KEY, CONTENT);
            assertFalse("row " + i + " should be a change, not a redraw", r.fullRedraw);
            assertEquals(List.of("CHANGE@" + i), render(r.ops));
        }
    }

    @Test
    public void reorderReportsOneMoveForADrag() {
        ListDiff.Result r = ListDiff.computeReorder(rows("a=1", "b=2", "c=3", "d=4"),
                rows("a=1", "c=3", "d=4", "b=2"), KEY, CONTENT);
        assertFalse(r.fullRedraw);
        assertEquals(List.of("MOVE@3from1"), render(r.ops));
    }

    @Test
    public void reorderReportsNothingForTheSameOrder() {
        ListDiff.Result r = ListDiff.computeReorder(rows("a=1", "b=2"), rows("a=1", "b=2"),
                KEY, CONTENT);
        assertTrue(r.isEmpty());
    }

    @Test
    public void reorderReportsAChangeWhenTheOrderIsTheSameButTheContentIsNot() {
        ListDiff.Result r = ListDiff.computeReorder(rows("a=1", "b=2"), rows("a=1", "b=9"),
                KEY, CONTENT);
        assertFalse(r.fullRedraw);
        assertEquals(List.of("CHANGE@1"), render(r.ops));
    }

    @Test
    public void reorderFallsBackToARedrawWhenTheSizeChanged() {
        assertTrue(ListDiff.computeReorder(rows("a=1"), rows("a=1", "b=2"), KEY, CONTENT).fullRedraw);
    }

    @Test
    public void reorderFallsBackToARedrawForAGenuineReversal() {
        assertTrue(ListDiff.computeReorder(rows("a=1", "b=2", "c=3"), rows("c=3", "b=2", "a=1"),
                KEY, CONTENT).fullRedraw);
    }

    @Test
    public void aNullKeyIsMatchedAgainstANullKeyRatherThanThrowing() {
        // Every row shares the key, so the row is "the same row" and its content change is what is
        // reported. The point is that a null key compares equal to a null key instead of throwing.
        ListDiff.KeyFn<Row> nullKey = row -> null;
        ListDiff.Result r = ListDiff.compute(rows("a=1"), rows("b=2"), nullKey, CONTENT);
        assertFalse(r.fullRedraw);
        assertEquals(List.of("CHANGE@0"), render(r.ops));
    }

    @Test
    public void aLongListWithOneChangeDoesNotTouchTheOtherRows() {
        List<Row> oldList = new ArrayList<>();
        List<Row> newList = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            oldList.add(new Row("id" + i, "v"));
            newList.add(new Row("id" + i, i == 250 ? "changed" : "v"));
        }
        ListDiff.Result r = ListDiff.compute(oldList, newList, KEY, CONTENT);
        assertEquals(List.of("CHANGE@250"), render(r.ops));
    }

    @Test
    public void identityContentTreatsEverySameKeyAsUnchanged() {
        ListDiff.Result r = ListDiff.compute(rows("a=1"), rows("a=2"), KEY,
                ListDiff.identityContent());
        assertTrue(r.isEmpty());
    }

    @Test
    public void opsCompareByValueSoATestCanAssertThemDirectly() {
        ListDiff.Result a = ListDiff.compute(rows("a=1"), rows("a=1", "b=2"), KEY, CONTENT);
        ListDiff.Result b = ListDiff.compute(rows("x=1"), rows("x=1", "y=2"), KEY, CONTENT);
        assertEquals(a.ops, b.ops);
        assertEquals(a.ops.get(0).hashCode(), b.ops.get(0).hashCode());
    }

    @Test
    public void theResultIsImmutableSoACallerCannotCorruptIt() {
        ListDiff.Result r = ListDiff.compute(rows("a=1"), rows("a=1", "b=2"), KEY, CONTENT);
        try {
            r.ops.add(null);
            assertTrue("the op list must be unmodifiable", false);
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void aMoveWithAChangedContentReportsBoth() {
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2", "c=3"),
                rows("b=2", "c=9", "a=1"), KEY, CONTENT);
        assertEquals(List.of("MOVE@2from0", "CHANGE@1"), render(r.ops));
    }

    @Test
    public void anEmptyListToANonEmptyListIsOneInsert() {
        ListDiff.Result r = ListDiff.compute(Collections.emptyList(), rows("a=1", "b=2"),
                KEY, CONTENT);
        assertEquals(List.of("INSERT@0x2"), render(r.ops));
    }

    @Test
    public void aNonEmptyListToAnEmptyListIsOneRemove() {
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2"), Collections.emptyList(),
                KEY, CONTENT);
        assertEquals(List.of("REMOVE@0x2"), render(r.ops));
    }

    @Test
    public void trailingRowsAppendedAreASingleInsertNotAChangeEach() {
        ListDiff.Result r = ListDiff.compute(rows("a=1"),
                rows("a=1", "b=2", "c=3", "d=4"), KEY, CONTENT);
        assertEquals(List.of("INSERT@1x3"), render(r.ops));
    }

    @Test
    public void aListUsedAsItsOwnReplacementProducesNothing() {
        List<Row> list = rows("a=1", "b=2", "c=3");
        assertTrue(ListDiff.compute(list, list, KEY, CONTENT).isEmpty());
    }

    @Test
    public void rowsThatOnlyDifferByIdentityAreInsertedAtTheirOwnPlace() {
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2", "c=3"),
                rows("a=1", "x=9", "b=2", "c=3"), KEY, CONTENT);
        assertEquals(List.of("INSERT@1"), render(r.ops));
    }

    @Test
    public void aRemovalAndAnInsertAreReportedBeforeTheChangesTheyShift() {
        // A removal shifts every later index, so a change emitted before it would name the wrong
        // row. The structural ops therefore come first, and the change to the surviving row after.
        ListDiff.Result r = ListDiff.compute(rows("a=1", "b=2", "c=3", "d=4"),
                rows("e=5", "c=3", "d=9"), KEY, CONTENT);
        assertFalse(r.fullRedraw);
        assertEquals(List.of("REMOVE@0x2", "INSERT@0", "CHANGE@2"), render(r.ops));
    }

    @Test
    public void identityContentNeverReportsAChange() {
        // The rule that makes the function worth having: a row with the same key is never a change,
        // so an immutable value object needs no per-field comparison. The row that is genuinely new
        // is still reported, as an insert, which is the half that must not be swallowed.
        ListDiff.Result r = ListDiff.compute(Arrays.asList("a", "b"), Arrays.asList("a", "c"),
                Object::toString, ListDiff.identityContent());
        assertFalse(r.fullRedraw);
        assertEquals(List.of("REMOVE@1", "INSERT@1"), render(r.ops));
    }
}
