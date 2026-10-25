package org.chimeramc.client.core.minecraft

/**
 * Orders the shared libraries of one mod package so a library's dependencies are loaded before it
 * (item G3, the multi-`.so` half).
 *
 * The problem this solves: `System.load(<path>)` loads a single library, and Android's linker
 * resolves that library's `DT_NEEDED` entries by soname against the already-loaded set, not against
 * the directory the file sits in. A mod shipping `libentry.so` + `libhelper.so` where the entry
 * depends on the helper therefore fails with a bare `library "libhelper.so" not found` unless the
 * launcher loads the helper first. The launcher copies the whole package directory, so the files are
 * present; only the *order* was missing.
 *
 * The rules:
 *  - A library that another one needs is loaded first (a topological order).
 *  - A `DT_NEEDED` soname that no sibling provides is ignored: it is a platform library
 *    (`libc++_shared.so`, `liblog.so`, …) or genuinely absent, and either way it is not this
 *    package's job to satisfy it here. The `dlopen` attempt still speaks for the latter.
 *  - A dependency cycle is broken at the back edge rather than looping; every input is still
 *    returned exactly once.
 *  - A library with no readable dynamic section contributes no edges but is still returned, so a
 *    statically linked or malformed sibling cannot be silently dropped from the load list.
 *
 * Pure JVM, so the ordering rules are unit-tested without a device.
 */
object NativeLibraryLoadOrder {

    /** One library and the sonames it depends on. */
    data class Entry(
        /** The file name as it sits in the package, e.g. `libhelper.so`. */
        val fileName: String,
        /** The library's own `DT_SONAME`, or null when it declares none. */
        val soname: String?,
        /** Every `DT_NEEDED` soname. */
        val needed: List<String>
    )

    /**
     * The load order for [entries]: dependencies first, stable within the input order.
     *
     * @return the file names to load, each exactly once.
     */
    @JvmStatic
    fun order(entries: List<Entry>?): List<String> {
        if (entries.isNullOrEmpty()) return emptyList()

        // A dependency is only satisfiable by a sibling, so index by both the file name and the
        // declared soname: a library that needs "libhelper.so" and one that needs "libhelper.so.1"
        // both resolve when the sibling declares that soname.
        val providerByKey = HashMap<String, Int>()
        for ((index, entry) in entries.withIndex()) {
            providerByKey.putIfAbsent(entry.fileName, index)
            entry.soname?.let { providerByKey.putIfAbsent(it, index) }
        }

        val result = ArrayList<String>(entries.size)
        val state = IntArray(entries.size) // 0 = unvisited, 1 = in progress, 2 = done
        for (start in entries.indices) {
            if (state[start] == 0) visit(start, entries, providerByKey, state, result)
        }
        return result
    }

    private fun visit(
        index: Int,
        entries: List<Entry>,
        providerByKey: Map<String, Int>,
        state: IntArray,
        result: MutableList<String>
    ) {
        if (state[index] == 2) return
        // A back edge is a cycle; breaking it here keeps every entry in the result exactly once.
        if (state[index] == 1) return
        state[index] = 1

        val entry = entries[index]
        for (needed in entry.needed) {
            val provider = providerByKey[needed] ?: continue
            if (provider == index) continue // a self-reference is not a dependency
            visit(provider, entries, providerByKey, state, result)
        }

        state[index] = 2
        result.add(entry.fileName)
    }
}
