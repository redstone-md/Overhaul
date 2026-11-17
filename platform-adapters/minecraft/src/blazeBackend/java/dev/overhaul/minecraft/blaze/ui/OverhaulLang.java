package dev.overhaul.minecraft.blaze.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The screen's text in the game's language. A resource pack that carries the key wins; otherwise the text comes from
 * the mod's own {@code assets/overhaul/lang} files, read from the jar, so it never depends on the mod's resources
 * being loaded as a pack. A language without a file reads the English one.
 */
public final class OverhaulLang {
    private static final Logger LOG = LoggerFactory.getLogger(OverhaulLang.class);
    private static final String FALLBACK = "en_us";
    private static final Map<String, Map<String, String>> FILES = new HashMap<>();

    private OverhaulLang() {
    }

    public static String get(String key, Object... arguments) {
        String text = find(key);
        return text == null ? key : format(text, arguments);
    }

    /** The text for a key, or {@code fallback} when no language file has it. */
    public static String or(String key, String fallback) {
        String text = find(key);
        return text == null ? fallback : format(text);
    }

    /** Every text is a format string, so a literal percent sign is written doubled in the files. */
    private static String format(String text, Object... arguments) {
        try {
            return String.format(text, arguments);
        } catch (IllegalFormatException malformed) {
            return text;
        }
    }

    private static String find(String key) {
        Language loaded = Language.getInstance();
        if (loaded.has(key)) return loaded.getOrDefault(key);
        String text = file(Minecraft.getInstance().options.languageCode).get(key);
        return text != null ? text : file(FALLBACK).get(key);
    }

    private static synchronized Map<String, String> file(String code) {
        return FILES.computeIfAbsent(code == null ? FALLBACK : code, OverhaulLang::read);
    }

    private static Map<String, String> read(String code) {
        Map<String, String> texts = new HashMap<>();
        if (!code.matches("[a-z0-9_]+")) return texts;
        try (InputStream stream = OverhaulLang.class.getResourceAsStream("/assets/overhaul/lang/" + code + ".json")) {
            if (stream == null) return texts;
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject file = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : file.entrySet()) texts.put(entry.getKey(), entry.getValue().getAsString());
            }
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Cannot read the {} language file", code, failure);
        }
        return texts;
    }
}
