package org.chimeramc.client.ui.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The mapping from a diff to the adapter calls it stands for (items 12, 141-143).
 *
 * <p>It is a thin class, but it is the one that decides whether a change reaches the adapter as
 * "these rows moved" or as "everything is unknown", so the call sequence is asserted directly. The
 * mapping is exercised through {@link ListDiffDispatch.Sink} rather than a RecyclerView adapter:
 * {@code RecyclerView.Adapter}'s notify methods go through {@code android.database.Observable},
 * which is not mocked in a JVM test, and the mapping is the part that carries the rule.
 */
public class ListDiffDispatchTest {

    /** Records the calls the mapping makes, in order. */
    private static final class Recorder implements ListDiffDispatch.Sink {
        final List<String> events = new ArrayList<>();

        @Override
        public void onFullRedraw() {
            events.add("changed");
        }

        @Override
        public void onRemoved(int position, int count) {
            events.add("remove@" + position + "x" + count);
        }

        @Override
        public void onInserted(int position, int count) {
            events.add("insert@" + position + "x" + count);
        }

        @Override
        public void onMoved(int from, int to) {
            events.add("move@" + from + "->" + to);
        }

        @Override
        public void onChanged(int position, int count) {
            events.add("change@" + position + "x" + count);
        }
    }

    private static List<String> dispatch(ListDiff.Result result) {
        Recorder recorder = new Recorder();
        ListDiffDispatch.apply(recorder, result);
        return recorder.events;
    }

    @Test
    public void aRemoveRunBecomesOneRangeRemovedCall() {
        assertEquals(List.of("remove@1x1"), dispatch(ListDiff.compute(
                Arrays.asList("a", "b", "c"),
                Arrays.asList("a", "c"),
                Object::toString, ListDiff.identityContent())));
    }

    @Test
    public void anInsertRunBecomesOneRangeInsertedCall() {
        assertEquals(List.of("insert@1x2"), dispatch(ListDiff.compute(
                Arrays.asList("a"),
                Arrays.asList("a", "b", "c"),
                Object::toString, ListDiff.identityContent())));
    }

    @Test
    public void aChangeBecomesOneRangeChangedCall() {
        // A constant key makes the two rows "the same row", so the differing content is a change
        // rather than a remove and an insert.
        assertEquals(List.of("change@1x1"), dispatch(ListDiff.compute(
                Arrays.asList("a", "b"),
                Arrays.asList("a", "c"),
                row -> "row",
                (a, b) -> a.equals(b))));
    }

    @Test
    public void aMoveBecomesOneMovedCall() {
        assertEquals(List.of("move@0->2"), dispatch(ListDiff.compute(
                Arrays.asList("a", "b", "c"),
                Arrays.asList("b", "c", "a"),
                Object::toString, ListDiff.identityContent())));
    }

    @Test
    public void aFullRedrawBecomesOneChangedCall() {
        ListDiff.Result redraw = ListDiff.computeReorder(
                Arrays.asList("a", "b", "c"),
                Arrays.asList("c", "b", "a"),
                Object::toString, ListDiff.identityContent());
        assertTrue(redraw.fullRedraw);

        assertEquals(List.of("changed"), dispatch(redraw));
    }

    @Test
    public void anEmptyDiffProducesNoCallsAtAll() {
        assertTrue(dispatch(ListDiff.compute(
                Arrays.asList("a", "b"),
                Arrays.asList("a", "b"),
                Object::toString, ListDiff.identityContent())).isEmpty());
    }

    @Test
    public void theStructuralCallComesBeforeTheChangeItShifts() {
        assertEquals(List.of("remove@0x2", "insert@0x1"), dispatch(ListDiff.compute(
                Arrays.asList("a", "b", "c", "d"),
                Arrays.asList("e", "c", "d"),
                Object::toString, ListDiff.identityContent())));
    }

    @Test
    public void aNullResultIsIgnoredRatherThanThrowing() {
        Recorder recorder = new Recorder();
        ListDiffDispatch.apply(recorder, null);
        assertTrue(recorder.events.isEmpty());
    }
}
