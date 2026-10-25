package org.chimeramc.client.core.antegg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Orders a batch of {@code .antegg} imports so a dependency is installed before the mod that needs
 * it (item G4, the "dependency ordering" half).
 *
 * <p>The launcher already refuses a package whose dependencies are unmet
 * ({@link AntEggLoader#missingDependencies}). That check is correct for a single import, but a
 * batch import — picking several packages at once, or a share that carries more than one — was
 * loaded in picker order. Selecting {@code lib.antegg} and {@code plugin.antegg} together could
 * therefore reject {@code plugin} merely because the picker listed it first, even though the
 * package it needs was in the same selection. Sorting the batch by its own dependency graph fixes
 * that without weakening the check: a dependency that is genuinely absent is still reported.
 *
 * <p>The rules are pure and JVM-only, so they are unit-tested without a device:
 *
 * <ul>
 *   <li><b>A dependency inside the batch orders first.</b> Only ids the batch itself provides can
 *       influence the order; a dependency already installed in the sandbox is satisfied wherever
 *       it sits, and a dependency the batch does not contain is left for the existing unmet-dep
 *       check to report.</li>
 *   <li><b>Every input is placed exactly once, even in a cycle.</b> A cycle is broken at the back
 *       edge, which makes the result deterministic rather than arbitrary; refusing to import a
 *       cyclic set would turn a dependency problem into a missing-mod problem.</li>
 *   <li><b>The order is stable.</b> Entries with no constraints among them keep their input order,
 *       so a plain multi-select imports exactly as it was picked.</li>
 *   <li><b>An unreadable package is unconstrained, not dropped.</b> It gets no id, so nothing can
 *       order against it, but it still appears in the result and is handed to the loader, which
 *       reports the real reason (a missing or malformed manifest).</li>
 * </ul>
 */
public final class AntEggLoadOrder {

    private AntEggLoadOrder() {}

    /**
     * A package's identity and its dependencies, parsed from the archive's manifest before anything
     * is extracted. {@link #id} is the manifest slug; {@link #dependencyIds} are the slugs of the
     * dependencies this package needs. An unreadable package yields an empty {@link #id} and no
     * dependencies.
     */
    public static final class Entry {
        public final int index;
        public final String id;
        public final List<String> dependencyIds;

        public Entry(int index, String id, List<String> dependencyIds) {
            this.index = index;
            this.id = id == null ? "" : id;
            this.dependencyIds = dependencyIds == null
                    ? Collections.emptyList() : dependencyIds;
        }
    }

    /**
     * Reads the id and dependency ids of a package. A package that cannot be read (a non-package, a
     * missing manifest, or a malformed one) yields an unconstrained entry rather than null, so the
     * caller can still hand it to the loader and let the real reason be reported.
     */
    public static Entry entryFor(int index, java.io.File archive) {
        if (archive == null) return new Entry(index, "", null);
        AntEggPackage.Result inspected = AntEggPackage.inspect(archive);
        if (inspected == null || !inspected.isValid() || inspected.manifest == null) {
            return new Entry(index, "", null);
        }
        AntEggManifest manifest = inspected.manifest;
        List<String> deps = new ArrayList<>();
        for (String dependency : manifest.dependencies) {
            DependencyRef ref = DependencyRef.parse(dependency);
            // A dependency that does not parse cannot be ordered against, but it is not this
            // class's job to reject it: AntEggLoader reports it as malformed at load time.
            if (ref != null && !ref.id.isEmpty()) {
                deps.add(ref.id);
            }
        }
        return new Entry(index, manifest.id(), deps);
    }

    /**
     * The input indices in the order they should be loaded, dependencies first.
     *
     * <p>Every input index appears exactly once in the result.
     */
    public static List<Integer> order(List<Entry> entries) {
        List<Integer> result = new ArrayList<>();
        if (entries == null || entries.isEmpty()) return result;

        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry == null || entry.id.isEmpty()) continue;
            // First wins: two packages claiming one id cannot both own it, and the one the user
            // picked first is the one a later duplicate would have overwritten anyway.
            byId.putIfAbsent(normalise(entry.id), entry);
        }

        Set<Integer> visited = new LinkedHashSet<>();
        Set<Integer> visiting = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry == null) continue;
            visit(entry, byId, visited, visiting, result);
        }
        return result;
    }

    private static void visit(Entry entry, Map<String, Entry> byId, Set<Integer> visited,
                              Set<Integer> visiting, List<Integer> out) {
        if (visited.contains(entry.index)) return;
        if (!visiting.add(entry.index)) {
            // A back edge: this entry is part of a cycle. Returning here breaks the cycle, and the
            // frames already on the stack still record their own entries, so every index is placed
            // exactly once.
            return;
        }
        for (String dependencyId : entry.dependencyIds) {
            Entry dependency = byId.get(normalise(dependencyId));
            if (dependency != null && dependency.index != entry.index) {
                visit(dependency, byId, visited, visiting, out);
            }
        }
        visiting.remove(entry.index);
        if (visited.add(entry.index)) {
            out.add(entry.index);
        }
    }

    private static String normalise(String id) {
        return id == null ? "" : id.toLowerCase(Locale.ROOT);
    }
}
