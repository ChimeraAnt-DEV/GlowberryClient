package org.chimeramc.client.ui.components;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The list-update decision, as a pure function (items 12, 141-143, 202).
 *
 * <p>{@code notifyDataSetChanged} was called from 37 sites. It is not a performance hint that got
 * skipped: it tells the adapter that nothing is known about what changed, so every visible row is
 * rebound and every animation the list could have played is thrown away. Toggling one mod's switch
 * rebuilt the whole grid, and a drag-reorder redrew the list under the finger that was dragging it.
 *
 * <p>{@code DiffUtil} is the usual answer, and it is the right one for a large list computed on a
 * background thread. This is the smaller synchronous version, for two reasons:
 *
 * <ul>
 *   <li>the overlay lists run while a game is rendering, so an async diff that lands a frame later
 *       is a flicker rather than a smooth update. This computes in one pass on the caller's thread,
 *       and its cost is bounded by the prefix/suffix trim, so the common case — one row changed —
 *       is O(1) rather than O(n);
 *   <li>it is Android-free, so the rules are unit-tested. A diff that emits a wrong index is a
 *       crash inside the adapter, and the case that produces it (an item both removed and
 *       re-inserted, or two items moving past each other) is exactly the one that is never
 *       exercised by hand.
 * </ul>
 *
 * <p>The caller supplies identity (what makes two rows the same row) and content (what makes two
 * versions of that row draw the same). Identity must be stable across updates or the diff is
 * meaningless: a changed identity is an insert and a remove, never a change.
 *
 * <p>Operations are emitted in the order an adapter must consume them: removals (positions in the
 * list being replaced), then insertions, then moves, then changes (positions in the new list).
 * That is the order {@code DiffUtil.dispatchUpdatesTo} uses, and for the same reason — a removal
 * shifts every later position, so a change emitted before it would name the wrong row.
 */
public final class ListDiff {

    /** What to tell the adapter. */
    public static final class Result {
        /** True when the change cannot be expressed as moves/changes and a full redraw is needed. */
        public final boolean fullRedraw;
        public final List<Op> ops;

        Result(boolean fullRedraw, List<Op> ops) {
            this.fullRedraw = fullRedraw;
            this.ops = Collections.unmodifiableList(ops);
        }

        public boolean isEmpty() {
            return !fullRedraw && ops.isEmpty();
        }
    }

    /** One adapter notification. */
    public static final class Op {
        public enum Kind { REMOVE, INSERT, MOVE, CHANGE }

        public final Kind kind;
        /**
         * The position the adapter call takes: in the list being replaced for REMOVE, in the new
         * list for INSERT and CHANGE, and the destination for MOVE.
         */
        public final int position;
        /** The source position, for MOVE only. */
        public final int from;
        /** The count, always at least 1. */
        public final int count;

        private Op(Kind kind, int position, int from, int count) {
            this.kind = kind;
            this.position = position;
            this.from = from;
            this.count = count;
        }

        @Override
        public String toString() {
            return kind + "@" + position + (kind == Kind.MOVE ? "from" + from : "")
                    + (count > 1 ? "x" + count : "");
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Op)) return false;
            Op other = (Op) o;
            return kind == other.kind && position == other.position
                    && from == other.from && count == other.count;
        }

        @Override
        public int hashCode() {
            return (kind.hashCode() * 31 + position) * 31 + from;
        }
    }

    /** Identity of a row: the same row across updates. */
    public interface KeyFn<T> {
        Object key(T item);
    }

    /** Whether two versions of the same row draw identically. */
    public interface ContentFn<T> {
        boolean sameContent(T oldItem, T newItem);
    }

    private ListDiff() {
    }

    /**
     * A content function for an immutable value: two rows with the same key always draw the same,
     * because a changed row is a new object carrying a new key.
     */
    public static <T> ContentFn<T> identityContent() {
        return (a, b) -> true;
    }

    /**
     * Diffs {@code oldList} against {@code nextList}.
     *
     * <p>Trim the unchanged prefix and suffix, then decide what the remainder is:
     *
     * <ul>
     *   <li>nothing left — a run of content changes;
     *   <li>only one side has anything left — a single insert or remove at the trim point;
     *   <li>the same keys in the same order — nothing structural, only content changes;
     *   <li>exactly one item moved — a MOVE, which is the one reorder worth animating;
     *   <li>anything else — a REMOVE of the old middle followed by an INSERT of the new one. This
     *       is always correct, just less granular than a move, which is why it is the fallback
     *       rather than a guess at a move sequence. A wrong move corrupts the adapter's view of the
     *       list permanently; a remove and an insert is merely a rebuild of the affected rows.
     * </ul>
     *
     * <p>Operations come out in the order an adapter must consume them: removals (positions in the
     * list being replaced), then insertions, then moves, then changes (positions in the new list).
     * A removal shifts every later index, so a change emitted before it would name the wrong row.
     */
    public static <T> Result compute(List<T> oldList, List<T> nextList,
                                     KeyFn<T> keyFn, ContentFn<T> contentFn) {
        List<T> oldItems = oldList == null ? Collections.emptyList() : oldList;
        List<T> nextItems = nextList == null ? Collections.emptyList() : nextList;

        int prefix = 0;
        int maxPrefix = Math.min(oldItems.size(), nextItems.size());
        while (prefix < maxPrefix && sameKey(oldItems.get(prefix), nextItems.get(prefix), keyFn)) {
            prefix++;
        }

        int suffix = 0;
        int maxSuffix = Math.min(oldItems.size(), nextItems.size()) - prefix;
        while (suffix < maxSuffix
                && sameKey(oldItems.get(oldItems.size() - 1 - suffix),
                           nextItems.get(nextItems.size() - 1 - suffix), keyFn)) {
            suffix++;
        }

        int oldMiddle = oldItems.size() - prefix - suffix;
        int newMiddle = nextItems.size() - prefix - suffix;

        // The two middles can be compared index-by-index only when they hold the same rows in the
        // same order; otherwise the comparison has to go through the key.
        boolean sameLength = oldMiddle == newMiddle;
        boolean orderAligned = sameLength
                && (oldMiddle == 0 || sameOrder(oldItems, nextItems, prefix, oldMiddle, keyFn));
        List<Op> changes = orderAligned
                ? alignedChanges(oldItems, nextItems, prefix, suffix, oldMiddle, contentFn)
                : changesByKey(oldItems, nextItems, keyFn, contentFn);

        if (oldMiddle == 0 && newMiddle == 0) {
            return new Result(false, changes);
        }
        if (oldMiddle == 0) {
            return new Result(false, withStructural(changes,
                    new Op(Op.Kind.INSERT, prefix, 0, newMiddle)));
        }
        if (newMiddle == 0) {
            return new Result(false, withStructural(changes,
                    new Op(Op.Kind.REMOVE, prefix, 0, oldMiddle)));
        }
        if (sameLength) {
            int[] move = singleMove(oldItems, nextItems, prefix, oldMiddle, keyFn);
            if (move != null) {
                return new Result(false, withStructural(changes,
                        new Op(Op.Kind.MOVE, move[1], move[0], 1)));
            }
        }

        List<Op> ops = new ArrayList<>();
        ops.add(new Op(Op.Kind.REMOVE, prefix, 0, oldMiddle));
        ops.add(new Op(Op.Kind.INSERT, prefix, 0, newMiddle));
        ops.addAll(changes);
        return new Result(false, ops);
    }

    /**
     * Diffs two lists whose order may change without their size changing — a drag-reorder.
     *
     * <p>Returns a single MOVE when exactly one item moved, content changes when the order is the
     * same, and a full redraw otherwise. A reorder that is not one move is reported as a redraw
     * rather than a remove-and-insert of every row, because rebuilding every row to show a
     * rearrangement is exactly the animation a redraw already gives.
     */
    public static <T> Result computeReorder(List<T> oldList, List<T> nextList,
                                            KeyFn<T> keyFn, ContentFn<T> contentFn) {
        List<T> oldItems = oldList == null ? Collections.emptyList() : oldList;
        List<T> nextItems = nextList == null ? Collections.emptyList() : nextList;

        if (oldItems.size() != nextItems.size()) {
            return new Result(true, Collections.emptyList());
        }
        if (oldItems.isEmpty()) {
            return new Result(false, Collections.emptyList());
        }

        boolean orderAligned = sameOrder(oldItems, nextItems, 0, oldItems.size(), keyFn);
        List<Op> changes = orderAligned
                ? alignedChanges(oldItems, nextItems, 0, 0, oldItems.size(), contentFn)
                : changesByKey(oldItems, nextItems, keyFn, contentFn);

        int[] move = singleMove(oldItems, nextItems, 0, oldItems.size(), keyFn);
        if (move == null) {
            // Same length and not a single move: either the order is the same, or it is a genuine
            // reorder that this diff will not guess at.
            return orderAligned
                    ? new Result(false, changes)
                    : new Result(true, Collections.emptyList());
        }
        return new Result(false, withStructural(changes,
                new Op(Op.Kind.MOVE, move[1], move[0], 1)));
    }

    /** The structural op first, then the changes: an adapter must see the shift before the change. */
    private static List<Op> withStructural(List<Op> changes, Op structural) {
        List<Op> ops = new ArrayList<>(changes.size() + 1);
        ops.add(structural);
        ops.addAll(changes);
        return ops;
    }

    /**
     * The content changes at index-aligned positions: the trimmed prefix and suffix, which are the
     * same rows in both lists, and the middle when it is key-aligned too.
     *
     * <p>This is the cheap path and the one every ordinary update takes — a row's label changed, or
     * rows were appended — and it allocates nothing unless there is a change to report.
     */
    private static <T> List<Op> alignedChanges(List<T> oldItems, List<T> nextItems,
                                               int prefix, int suffix, int middleLength,
                                               ContentFn<T> contentFn) {
        List<Op> out = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            if (!contentFn.sameContent(oldItems.get(i), nextItems.get(i))) {
                out.add(new Op(Op.Kind.CHANGE, i, 0, 1));
            }
        }
        for (int i = prefix; i < prefix + middleLength; i++) {
            if (!contentFn.sameContent(oldItems.get(i), nextItems.get(i))) {
                out.add(new Op(Op.Kind.CHANGE, i, 0, 1));
            }
        }
        for (int k = 0; k < suffix; k++) {
            int oldIndex = oldItems.size() - 1 - k;
            int newIndex = nextItems.size() - 1 - k;
            if (!contentFn.sameContent(oldItems.get(oldIndex), nextItems.get(newIndex))) {
                out.add(new Op(Op.Kind.CHANGE, newIndex, 0, 1));
            }
        }
        return out;
    }

    /**
     * The content changes matched by key, at their positions in the new list.
     *
     * <p>Used only when the order differs, where an index-aligned comparison would pair a row with
     * its neighbour. Only a key present in both lists can be a change; a key that is new is an
     * insert and one that is gone is a removal, neither of which is reported here. A duplicate key
     * resolves to the first occurrence, which is the only sensible reading of "the same row".
     */
    private static <T> List<Op> changesByKey(List<T> oldItems, List<T> nextItems,
                                             KeyFn<T> keyFn, ContentFn<T> contentFn) {
        if (oldItems.isEmpty() || nextItems.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Object, T> byKey = new HashMap<>(oldItems.size() * 2);
        for (T item : oldItems) {
            byKey.putIfAbsent(keyFn.key(item), item);
        }
        List<Op> out = new ArrayList<>();
        for (int i = 0; i < nextItems.size(); i++) {
            T oldItem = byKey.get(keyFn.key(nextItems.get(i)));
            if (oldItem != null && !contentFn.sameContent(oldItem, nextItems.get(i))) {
                out.add(new Op(Op.Kind.CHANGE, i, 0, 1));
            }
        }
        return out;
    }

    /**
     * Returns {@code {from, to}} when the segment differs from the old one by exactly one item
     * moving, and null otherwise. The two segments must be the same length.
     *
     * <p>Two candidates are tried, because the moved item sits at one end of the mismatched run.
     * Trying only one direction would miss a move in the other direction, and a missed move costs a
     * rebuild of the middle rather than a wrong animation.
     */
    private static <T> int[] singleMove(List<T> oldItems, List<T> nextItems,
                                        int start, int length, KeyFn<T> keyFn) {
        Map<Object, Integer> oldIndex = new HashMap<>(length * 2);
        for (int i = start; i < start + length; i++) {
            oldIndex.put(keyFn.key(oldItems.get(i)), i);
        }

        int firstMismatch = -1;
        int lastMismatch = -1;
        for (int i = start; i < start + length; i++) {
            Integer from = oldIndex.get(keyFn.key(nextItems.get(i)));
            if (from == null) return null;
            if (from != i) {
                if (firstMismatch < 0) firstMismatch = i;
                lastMismatch = i;
            }
        }
        if (firstMismatch < 0) return null;

        int[] candidate = tryMove(nextItems, start, length, keyFn, oldIndex,
                oldIndex.get(keyFn.key(nextItems.get(lastMismatch))), lastMismatch);
        if (candidate != null) return candidate;
        return tryMove(nextItems, start, length, keyFn, oldIndex,
                oldIndex.get(keyFn.key(nextItems.get(firstMismatch))), firstMismatch);
    }

    /**
     * Verifies the whole segment against the permutation that moves old position {@code from} to
     * new position {@code to}: everything between them shifts one place, and nothing else moves.
     */
    private static <T> int[] tryMove(List<T> nextItems, int start, int length, KeyFn<T> keyFn,
                                     Map<Object, Integer> oldIndex, Integer movedFrom, int movedTo) {
        if (movedFrom == null || movedFrom == movedTo) return null;
        for (int i = start; i < start + length; i++) {
            Integer from = oldIndex.get(keyFn.key(nextItems.get(i)));
            if (from == null) return null;
            int expected;
            if (i == movedTo) {
                expected = movedFrom;
            } else if (movedTo > movedFrom && i >= movedFrom && i < movedTo) {
                // Moved right: the rows it passed shift left by one.
                expected = i + 1;
            } else if (movedTo < movedFrom && i > movedTo && i <= movedFrom) {
                // Moved left: the rows it passed shift right by one.
                expected = i - 1;
            } else {
                expected = i;
            }
            if (from != expected) return null;
        }
        return new int[]{movedFrom, movedTo};
    }

    private static <T> boolean sameOrder(List<T> oldItems, List<T> nextItems,
                                         int start, int length, KeyFn<T> keyFn) {
        for (int i = start; i < start + length; i++) {
            if (!sameKey(oldItems.get(i), nextItems.get(i), keyFn)) return false;
        }
        return true;
    }

    private static <T> boolean sameKey(T a, T b, KeyFn<T> keyFn) {
        Object ka = keyFn.key(a);
        Object kb = keyFn.key(b);
        return ka == null ? kb == null : ka.equals(kb);
    }
}
