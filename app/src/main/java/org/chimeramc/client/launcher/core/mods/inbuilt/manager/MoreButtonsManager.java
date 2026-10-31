package org.chimeramc.client.core.mods.inbuilt.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.chimeramc.client.core.mods.inbuilt.model.MoreButtonConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.chimeramc.client.util.AppLog;

public final class MoreButtonsManager {
    private static final String TAG = "MoreButtonsManager";
    private static final String PREFS = "more_buttons_prefs";
    private static final String KEY_BUTTONS = "buttons";
    public static final int MAX_BUTTONS = 32;
    public static final int MAX_SVG_BYTES = 256 * 1024;
    private static volatile MoreButtonsManager instance;
    private final SharedPreferences prefs;
    private final File iconDirectory;

    private MoreButtonsManager(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        iconDirectory = new File(app.getFilesDir(), "more_buttons/icons");
        if (!iconDirectory.exists()) iconDirectory.mkdirs();
    }

    public static MoreButtonsManager getInstance(Context context) {
        if (instance == null) {
            synchronized (MoreButtonsManager.class) {
                if (instance == null) instance = new MoreButtonsManager(context);
            }
        }
        return instance;
    }

    public synchronized List<MoreButtonConfig> getButtons() {
        ParsedButtons parsed = parseButtons(prefs.getString(KEY_BUTTONS, "[]"), this::readSvgFile);
        if (parsed.needsMigration) write(parsed.buttons);
        return parsed.buttons;
    }

    /** Reads a stored SVG by file name; injected so the parser is testable without a Context. */
    interface SvgFileReader {
        String read(String fileName);
    }

    /** The result of parsing the stored list: the buttons and whether they need rewriting. */
    static final class ParsedButtons {
        final List<MoreButtonConfig> buttons;
        final boolean needsMigration;

        ParsedButtons(List<MoreButtonConfig> buttons, boolean needsMigration) {
            this.buttons = buttons;
            this.needsMigration = needsMigration;
        }
    }

    /**
     * Parses the stored button list, skipping an entry that cannot be read.
     *
     * <p>A single corrupt entry must not lose every button: an earlier version wrapped the whole
     * loop in one catch, so one malformed record silently emptied the list and the player lost
     * every button they had configured. Each entry is now parsed on its own and a bad one is
     * reported and dropped, leaving the rest intact.
     */
    static ParsedButtons parseButtons(String raw, SvgFileReader reader) {
        List<MoreButtonConfig> result = new ArrayList<>();
        JSONArray array;
        try {
            array = new JSONArray(raw);
        } catch (Exception error) {
            warn("Stored button list is not valid JSON; starting from an empty list", error);
            return new ParsedButtons(result, false);
        }
        boolean needsMigration = false;
        for (int i = 0; i < array.length() && result.size() < MAX_BUTTONS; i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) {
                warn("Skipping button entry " + i + ": it is not a JSON object", null);
                continue;
            }
            try {
                MoreButtonConfig cfg = MoreButtonConfig.fromJson(object);
                if (!"glfw".equals(object.optString("key_format", ""))) needsMigration = true;
                if (cfg.normalSvgFile.isEmpty() && !cfg.normalSvg.isEmpty()) needsMigration = true;
                if (cfg.pressedSvgFile.isEmpty() && !cfg.pressedSvg.isEmpty()) needsMigration = true;
                if (!cfg.normalSvgFile.isEmpty()) cfg.normalSvg = reader.read(cfg.normalSvgFile);
                if (!cfg.pressedSvgFile.isEmpty()) cfg.pressedSvg = reader.read(cfg.pressedSvgFile);
                result.add(cfg);
            } catch (Exception error) {
                warn("Skipping corrupt button entry " + i, error);
            }
        }
        return new ParsedButtons(result, needsMigration);
    }

    public synchronized MoreButtonConfig getButton(String id) {
        if (id == null) return null;
        for (MoreButtonConfig cfg : getButtons()) {
            if (id.equals(cfg.id)) return cfg;
        }
        return null;
    }

    public synchronized boolean saveButton(MoreButtonConfig button) {
        if (button == null || button.id == null || button.id.isEmpty()) return false;
        if (!withinLimit(button.normalSvg) || !withinLimit(button.pressedSvg)) return false;
        List<MoreButtonConfig> buttons = new ArrayList<>(getButtons());
        int index = -1;
        for (int i = 0; i < buttons.size(); i++) {
            if (button.id.equals(buttons.get(i).id)) {
                index = i;
                break;
            }
        }
        if (index < 0 && buttons.size() >= MAX_BUTTONS) return false;
        MoreButtonConfig stored = button.copy();
        if (!persistSvgFiles(stored)) return false;
        if (index >= 0) buttons.set(index, stored); else buttons.add(stored);
        return write(buttons);
    }

    public synchronized void deleteButton(String id) {
        if (id == null) return;
        List<MoreButtonConfig> buttons = new ArrayList<>(getButtons());
        MoreButtonConfig removed = null;
        for (MoreButtonConfig cfg : buttons) {
            if (id.equals(cfg.id)) {
                removed = cfg;
                break;
            }
        }
        buttons.removeIf(button -> id.equals(button.id));
        write(buttons);
        if (removed != null) {
            deleteFile(removed.normalSvgFile);
            deleteFile(removed.pressedSvgFile);
        }
    }

    private boolean persistSvgFiles(MoreButtonConfig button) {
        try {
            if (button.normalSvg != null && !button.normalSvg.isEmpty()) {
                button.normalSvgFile = safeFileName(button.id + "_normal.svg");
                writeSvgFile(button.normalSvgFile, button.normalSvg);
            }
            if (button.pressedSvg != null && !button.pressedSvg.isEmpty()) {
                button.pressedSvgFile = safeFileName(button.id + "_pressed.svg");
                writeSvgFile(button.pressedSvgFile, button.pressedSvg);
            } else {
                deleteFile(button.pressedSvgFile);
                button.pressedSvgFile = "";
            }
            return true;
        } catch (Exception error) {
            warn("Failed to persist button icon files", error);
            return false;
        }
    }

    private boolean write(List<MoreButtonConfig> buttons) {
        try {
            JSONArray array = new JSONArray();
            for (MoreButtonConfig button : buttons) {
                MoreButtonConfig stored = button.copy();
                if (!stored.normalSvg.isEmpty() && stored.normalSvgFile.isEmpty()) {
                    if (!persistSvgFiles(stored)) return false;
                }
                if (!stored.pressedSvg.isEmpty() && stored.pressedSvgFile.isEmpty()) {
                    if (!persistSvgFiles(stored)) return false;
                }
                array.put(stored.toJson());
            }
            // apply() updates the in-memory value synchronously and writes to disk in the
            // background, so the caller still observes the new value immediately; commit() blocked
            // the calling thread (the editor dialog) on a disk write for no benefit.
            prefs.edit().putString(KEY_BUTTONS, array.toString()).apply();
            return true;
        } catch (Exception error) {
            warn("Failed to persist the button list", error);
            return false;
        }
    }

    private boolean withinLimit(String svg) {
        return svg == null || svg.getBytes(StandardCharsets.UTF_8).length <= MAX_SVG_BYTES;
    }

    private String readSvgFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "";
        File file = new File(iconDirectory, safeFileName(fileName));
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_SVG_BYTES) return "";
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_SVG_BYTES) return "";
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception error) {
            warn("Failed to read button icon " + fileName, error);
            return "";
        }
    }

    private void writeSvgFile(String fileName, String svg) throws Exception {
        File file = new File(iconDirectory, safeFileName(fileName));
        byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SVG_BYTES) throw new IllegalArgumentException("SVG is too large");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes);
            output.flush();
        }
    }

    private void deleteFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) return;
        File file = new File(iconDirectory, safeFileName(fileName));
        if (file.isFile()) file.delete();
    }

    private String safeFileName(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Reports a failure without ever failing itself.
     *
     * <p>{@code android.util.Log} is not present in a JVM unit test, where calling it throws; a
     * diagnostic must never be the thing that takes the caller down, so the call is guarded.
     */
    private static void warn(String message, Throwable cause) {
        try {
            if (cause == null) {
                Log.w(TAG, message);
            } else {
                Log.w(TAG, message, cause);
            }
        } catch (Throwable ignored) {
            // Off-device; nothing to report to.
        }
    }

    public static String recolorSvg(String svg, String color) {
        if (svg == null || svg.trim().isEmpty()) return "";
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            try { factory.setXIncludeAware(false); } catch (Exception ignored) { AppLog.warnOnce(TAG, "xml-xinclude", "The XML parser refused to disable XInclude", ignored); }
            try { factory.setExpandEntityReferences(false); } catch (Exception ignored) { AppLog.warnOnce(TAG, "xml-entities", "The XML parser refused to disable entity expansion", ignored); }
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { AppLog.warnOnce(TAG, "xml-doctype", "The XML parser refused to disallow DOCTYPE declarations", ignored); }
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) { AppLog.warnOnce(TAG, "xml-extgen", "The XML parser refused to disable external general entities", ignored); }
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) { AppLog.warnOnce(TAG, "xml-extparam", "The XML parser refused to disable external parameter entities", ignored); }
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
            Element root = document.getDocumentElement();
            if (root == null) return svg;
            recolorElement(root, color, false);
            root.setAttribute("color", color);
            if (!root.hasAttribute("fill")) root.setAttribute("fill", color);

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (Exception error) {
            warn("SVG recolour fell back to text replacement", error);
            return recolorSvgFallback(svg, color);
        }
    }

    private static void recolorElement(Element element, String color, boolean insideDefinition) {
        String tag = element.getLocalName();
        if (tag == null || tag.isEmpty()) tag = element.getTagName();
        String lower = tag == null ? "" : tag.toLowerCase();
        boolean definition = insideDefinition || lower.equals("defs") || lower.equals("clippath")
                || lower.equals("mask") || lower.equals("lineargradient") || lower.equals("radialgradient")
                || lower.equals("pattern") || lower.equals("filter");

        if (!definition) {
            recolorPaintAttribute(element, "fill", color);
            recolorPaintAttribute(element, "stroke", color);
            if (element.hasAttribute("color")) element.setAttribute("color", color);
            if (element.hasAttribute("style")) {
                element.setAttribute("style", recolorStyle(element.getAttribute("style"), color));
            }
        }

        if (lower.equals("style")) {
            String css = element.getTextContent();
            if (css != null) element.setTextContent(recolorStyle(css, color));
        }

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) recolorElement((Element) child, color, definition);
        }
    }

    private static void recolorPaintAttribute(Element element, String attribute, String color) {
        if (!element.hasAttribute(attribute)) return;
        String value = element.getAttribute(attribute).trim();
        if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("transparent")) return;
        element.setAttribute(attribute, color);
    }

    private static String recolorStyle(String style, String color) {
        if (style == null || style.isEmpty()) return style;
        String out = style.replaceAll("(?i)(fill\\s*:\\s*)(?!none(?:\\s*[;}]|$)|transparent(?:\\s*[;}]|$))[^;}]+", "$1" + color);
        out = out.replaceAll("(?i)(stroke\\s*:\\s*)(?!none(?:\\s*[;}]|$)|transparent(?:\\s*[;}]|$))[^;}]+", "$1" + color);
        out = out.replaceAll("(?i)currentColor", color);
        return out;
    }

    private static String recolorSvgFallback(String svg, String color) {
        String out = svg.replaceAll("(?i)currentColor", color);
        out = out.replaceAll("(?i)(fill\\s*=\\s*[\"'])(?!none[\"']|transparent[\"'])[^\"']*([\"'])", "$1" + color + "$2");
        out = out.replaceAll("(?i)(stroke\\s*=\\s*[\"'])(?!none[\"']|transparent[\"'])[^\"']*([\"'])", "$1" + color + "$2");
        out = out.replaceAll("(?i)(fill\\s*:\\s*)(?!none|transparent)[^;\"'}]+", "$1" + color);
        out = out.replaceAll("(?i)(stroke\\s*:\\s*)(?!none|transparent)[^;\"'}]+", "$1" + color);
        int svgStart = out.toLowerCase().indexOf("<svg");
        int svgEnd = svgStart >= 0 ? out.indexOf('>', svgStart) : -1;
        if (svgStart >= 0 && svgEnd > svgStart) {
            String tag = out.substring(svgStart, svgEnd);
            if (!tag.matches("(?is).*\\sfill\\s*=.*")) {
                out = out.substring(0, svgEnd) + " fill=\"" + color + "\"" + out.substring(svgEnd);
            }
        }
        return out;
    }

    public static boolean looksLikeSvg(String svg) {
        if (svg == null) return false;
        String lower = svg.trim().toLowerCase();
        return lower.contains("<svg") && lower.contains("</svg>")
                && !lower.contains("<script")
                && !lower.contains("<foreignobject")
                && !lower.contains("<!doctype")
                && !lower.contains("javascript:")
                && !lower.contains("@import")
                && !lower.matches("(?s).*\\b(?:href|xlink:href)\\s*=\\s*[\"']\\s*(?:https?:|file:).*")
                && !lower.matches("(?s).*url\\(\\s*[\"']?(?:https?:|file:).*");
    }
}
