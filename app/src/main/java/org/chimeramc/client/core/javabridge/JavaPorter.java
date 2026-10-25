package org.chimeramc.client.core.javabridge;

import org.chimeramc.client.core.antegg.AntEggManifest;
import org.chimeramc.client.core.antegg.AntEggPackage;
import org.chimeramc.client.core.antegg.AntEggPackageWriter;
import org.chimeramc.client.core.antegg.ModHash;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Orchestrates a port: inspect, assess, refuse cheats, decompile, ask the LLM, package.
 *
 * <p>The steps are ordered so the cheap, local decisions happen before the expensive or
 * irreversible ones. The inspection and the portability score are computed from the jar alone, the
 * cheat check refuses before anything is decompiled, and the network call is last — so a mod that
 * cannot be ported, or must not be, never reaches the LLM. That ordering is also what lets the UI
 * show a score and a diff for free, with no consent and no network.
 *
 * <p>The decompiler and the LLM client are injected, so the whole flow is exercisable with fakes in
 * a unit test: no jar is decompiled and no request is sent when the fakes are supplied.
 */
public final class JavaPorter {

    /** How many classes to decompile and how much source to send; bounded for cost and context. */
    public static final int MAX_CLASSES = 40;
    public static final int MAX_SOURCE_CHARS = 60000;

    private final Decompiler decompiler;
    private final LlmClient llmClient;

    public JavaPorter(Decompiler decompiler, LlmClient llmClient) {
        this.decompiler = decompiler;
        this.llmClient = llmClient;
    }

    /** The result of an assessment or a port. */
    public static final class Result {
        public final boolean success;
        /** The assessment; always present, even on refusal. */
        public final PortabilityReport report;
        /** The generated package file, when a port completed. */
        public final File packageFile;
        /** The generated entry-point source, for the review screen. */
        public final String generatedSource;
        /** The generated manifest, when a port completed. */
        public final AntEggManifest manifest;
        /** The refusal pattern when the mod was refused as a cheat, else null. */
        public final String refusedCheat;
        public final String error;

        private Result(boolean success, PortabilityReport report, File packageFile,
                       String generatedSource, AntEggManifest manifest, String refusedCheat,
                       String error) {
            this.success = success;
            this.report = report;
            this.packageFile = packageFile;
            this.generatedSource = generatedSource;
            this.manifest = manifest;
            this.refusedCheat = refusedCheat;
            this.error = error;
        }

        static Result refused(PortabilityReport report, String pattern) {
            return new Result(false, report, null, null, null, pattern, null);
        }

        /**
         * A failure result with the cause named.
         *
         * <p>Public so a caller that guards the port call itself (the activity converts a thrown
         * {@code OutOfMemoryError} from the decompiler into a result rather than letting it escape
         * and strand its loading overlay) can build the same shape.
         */
        public static Result failure(PortabilityReport report, String error) {
            return new Result(false, report, null, null, null, null, error);
        }

        static Result ok(PortabilityReport report, File packageFile, String generatedSource,
                         AntEggManifest manifest) {
            return new Result(true, report, packageFile, generatedSource, manifest, null, null);
        }

        public boolean wasRefusedAsCheat() {
            return refusedCheat != null;
        }
    }

    /**
     * Assesses a mod with no decompilation and no network call.
     *
     * <p>This is what the import screen calls first: it is safe, local, and produces everything the
     * user needs to decide whether to port.
     */
    public Assessment assess(File jar) {
        JarInspector.Inspection inspection = JarInspector.inspect(jar);
        PortabilityReport report = PortabilityReport.of(inspection);
        CheatPatternDetector.Verdict verdict = inspection.isValid() && inspection.manifest != null
                ? CheatPatternDetector.check(inspection.manifest)
                : CheatPatternDetector.clean();
        return new Assessment(inspection, report, verdict);
    }

    /** The local, network-free assessment of a mod. */
    public static final class Assessment {
        public final JarInspector.Inspection inspection;
        public final PortabilityReport report;
        public final CheatPatternDetector.Verdict cheatVerdict;

        Assessment(JarInspector.Inspection inspection, PortabilityReport report,
                   CheatPatternDetector.Verdict cheatVerdict) {
            this.inspection = inspection;
            this.report = report;
            this.cheatVerdict = cheatVerdict;
        }

        /** True when the mod is a known cheat; the porter must refuse it. */
        public boolean isRefused() {
            return cheatVerdict.refused;
        }

        public boolean canPort() {
            return !isRefused() && report.canPort();
        }
    }

    /**
     * Performs a port and writes a {@code .AntEgg} package.
     *
     * <p>Refusals and failures return a {@link Result} with the report attached, never throw: the
     * caller shows the reason. A mod that fails the cheat check or the portability check is refused
     * before the decompiler runs, so no source is extracted and no request is sent.
     */
    public Result port(File jar, File outputDir) {
        Assessment assessment = assess(jar);
        PortabilityReport report = assessment.report;

        if (assessment.isRefused()) {
            return Result.refused(report, assessment.cheatVerdict.pattern);
        }
        if (!report.canPort()) {
            return Result.failure(report, "this mod cannot be ported");
        }
        JavaModManifest modManifest = assessment.inspection.manifest;

        List<String> classEntries = JarInspector.classEntries(jar);
        Decompiler.Result decompiled = decompiler.decompile(jar, classEntries, MAX_CLASSES);
        if (!decompiled.success) {
            return Result.failure(report, decompiled.error);
        }

        List<String> targets = JavaApiMapping.targetsFor(collectSymbols(decompiled.source));
        String prompt = PortPrompt.userPrompt(decompiled.source, modManifest, targets,
                MAX_SOURCE_CHARS);
        LlmClient.Result reply = llmClient.complete(PortPrompt.systemInstruction(), prompt, true);
        if (!reply.success) {
            return Result.failure(report, reply.error);
        }

        PortPrompt.Answer answer = PortPrompt.parse(reply.reply);
        if (answer == null) {
            return Result.failure(report, "the LLM reply was not in the expected format");
        }
        // A model that was told to refuse a cheat and did so returns a comment; treat an empty or
        // comment-only body as a refusal rather than packaging an empty mod.
        if (answer.source.trim().isEmpty()) {
            return Result.failure(report, "the LLM produced no code");
        }

        AntEggManifest manifest = buildManifest(modManifest, answer);
        // Item G12: a ported package is pinned from the moment it is written. The pin is the
        // SHA-256 of the entry bytes (what PACKAGE_FILES.add stores), so the loader can verify a
        // ported mod byte-for-byte against what the porter actually shipped — a user cannot be
        // served a package whose entry was swapped after generation.
        String entryHash = ModHash.sha256(answer.source.getBytes(StandardCharsets.UTF_8));
        manifest = manifest.withSha256(entryHash == null ? "" : entryHash);
        File destination = new File(outputDir, manifest.id() + AntEggPackage.EXTENSION);
        AntEggPackageWriter.PackageFiles files = new AntEggPackageWriter.PackageFiles()
                .add(manifest.entryPoint, answer.source);
        AntEggPackageWriter.Result written =
                AntEggPackageWriter.write(manifest, files, destination);
        if (!written.success) {
            return Result.failure(report, written.error);
        }
        return Result.ok(report, written.file, answer.source, manifest);
    }

    /** Builds the .AntEgg manifest for a ported mod, always marked as AI-ported. */
    static AntEggManifest buildManifest(JavaModManifest source, PortPrompt.Answer answer) {
        String name = source == null || source.name.isEmpty() ? "Ported Mod" : source.name;
        String id = source == null || source.id.isEmpty() ? JavaModManifest.slug(name) : source.id;
        String version = source == null || source.version.isEmpty() ? "1.0.0" : sanitizeVersion(source.version);
        String entry = PortPrompt.entryPointName(answer.language, id);
        String description = (source == null || source.description.isEmpty())
                ? "AI-ported from a Java Edition mod."
                : "AI-ported from a Java Edition mod. Original: " + source.description;
        String originMod = source == null ? "" : (source.id + (source.version.isEmpty()
                ? "" : " " + source.version));
        String originAuthor = source == null ? "" : source.author;
        // The manifest type is the runtime, not the language: C++ output is a native .so mod, Lua
        // output is a script mod. Passing the language here would fail manifest validation, since
        // "lua"/"cpp" are not a valid type and the entry-point extension would not match.
        String type = answer.isCpp() ? AntEggManifest.TYPE_NATIVE : AntEggManifest.TYPE_SCRIPT;
        return AntEggManifest.ported(name, version, "JavaBridge (AI port)", type,
                entry, description, originMod, originAuthor);
    }

    /** The manifest's version must be a semantic version or the loader rejects the package. */
    static String sanitizeVersion(String version) {
        if (AntEggManifest.isSemanticVersion(version)) return version;
        // Strip anything that is not a digit or a dot, then pad to three components.
        String cleaned = version == null ? "" : version.replaceAll("[^0-9.]", "");
        if (cleaned.isEmpty() || !cleaned.matches(".*\\d.*")) {
            return "1.0.0";
        }
        String[] parts = cleaned.split("\\.", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append('.');
            String part = i < parts.length && !parts[i].isEmpty() ? parts[i] : "0";
            sb.append(part);
        }
        return AntEggManifest.isSemanticVersion(sb.toString()) ? sb.toString() : "1.0.0";
    }

    /** Finds which mapped API symbols appear in the decompiled source, for the prompt. */
    static List<String> collectSymbols(String source) {
        List<String> found = new java.util.ArrayList<>();
        if (source == null) return found;
        for (JavaApiMapping.Entry entry : JavaApiMapping.entries()) {
            if (JavaApiMapping.sourceContains(source, entry.source)) {
                found.add(entry.source);
            }
        }
        return found;
    }
}
