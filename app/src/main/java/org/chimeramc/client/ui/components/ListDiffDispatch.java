package org.chimeramc.client.ui.components;

import androidx.recyclerview.widget.RecyclerView;

/**
 * Turns a {@link ListDiff.Result} into the adapter notifications it stands for (items 12, 141-143).
 *
 * <p>It exists as its own class so the mapping from a diff to a notify call is written once. The
 * mapping is the part worth testing - a REMOVE must be a range removal, because that is what tells
 * the adapter the positions of the rows *after* the removed run have shifted; an
 * {@code notifyItemRemoved} in a loop would rebind the wrong rows - so the mapping is expressed
 * against a {@link Sink} rather than directly against {@link RecyclerView.Adapter}.
 * {@code RecyclerView.Adapter} cannot be exercised in a JVM test (its notify methods go through
 * {@code android.database.Observable}, which is not mocked off-device), while the mapping can be,
 * and the adapter is then just one implementation of the sink.
 */
public final class ListDiffDispatch {

    private ListDiffDispatch() {
    }

    /** The five things a list can be told. */
    public interface Sink {
        void onFullRedraw();

        void onRemoved(int position, int count);

        void onInserted(int position, int count);

        void onMoved(int from, int to);

        void onChanged(int position, int count);
    }

    /**
     * Applies the diff to the adapter, or calls {@code notifyDataSetChanged} when the diff could
     * not express the change. The fallback is deliberate and rare: it happens only for a reorder
     * that is not a single move.
     */
    public static void apply(RecyclerView.Adapter<?> adapter, ListDiff.Result result) {
        apply(new Sink() {
            @Override
            public void onFullRedraw() {
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onRemoved(int position, int count) {
                adapter.notifyItemRangeRemoved(position, count);
            }

            @Override
            public void onInserted(int position, int count) {
                adapter.notifyItemRangeInserted(position, count);
            }

            @Override
            public void onMoved(int from, int to) {
                adapter.notifyItemMoved(from, to);
            }

            @Override
            public void onChanged(int position, int count) {
                adapter.notifyItemRangeChanged(position, count);
            }
        }, result);
    }

    /** The mapping itself, in the order the sink must receive it. */
    public static void apply(Sink sink, ListDiff.Result result) {
        if (result == null) return;
        if (result.fullRedraw) {
            sink.onFullRedraw();
            return;
        }
        for (ListDiff.Op op : result.ops) {
            switch (op.kind) {
                case REMOVE:
                    sink.onRemoved(op.position, op.count);
                    break;
                case INSERT:
                    sink.onInserted(op.position, op.count);
                    break;
                case MOVE:
                    sink.onMoved(op.from, op.position);
                    break;
                case CHANGE:
                    sink.onChanged(op.position, op.count);
                    break;
                default:
                    break;
            }
        }
    }
}
