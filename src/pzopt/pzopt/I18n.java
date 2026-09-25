package pzopt;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import zombie.core.Language;
import zombie.core.Translator;

/**
 * pzopt's own UI translations (issue #21). The game's Translator only reads a fixed list of file names and
 * clears its maps on every reload, so pzopt keeps its strings in {@code media/pzopt/translate/<LANG>.json}
 * (flat key -> text, the B42 format) and looks them up here, keyed by the game's language code. The English
 * text stays at the call site (Lua tables, Java literals) as the source and the per-key fallback, so a
 * language needs no file at all and a file may hold only part of the keys. Lookup order is the game's own
 * {@link Translator#forLanguageStack}: current language, its base (ES_MX -> ES), then EN (= the fallback).
 */
public final class I18n {
   private static Language loaded;
   private static Map<String, String> table = Map.of();

   private I18n() {}

   /** The text of {@code key} in the game's language, else {@code english}; {@code %1}..{@code %9} take args. */
   public static String text(String key, String english, Object... args) {
      String s = table().getOrDefault(key, english);
      if (s == null) {
         s = key;
      }
      if (args.length == 0 || s.indexOf('%') < 0) {
         return s;
      }
      // one pass, so a value that itself holds "%2" (an error message) is never substituted again
      StringBuilder b = new StringBuilder(s.length() + 16);
      for (int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         int n = i + 1 < s.length() ? s.charAt(i + 1) - '0' : -1;
         if (c == '%' && n >= 1 && n <= args.length) {
            b.append(args[n - 1]);
            i++;
         } else {
            b.append(c);
         }
      }
      return b.toString();
   }

   /** The game's language code, e.g. "EN", "CH" (Traditional Chinese), "CN", "JP", "KO". */
   public static String language() {
      Language l = Translator.getLanguage();
      return l == null ? "EN" : l.name();
   }

   /** True when pzopt text is shown translated: the ASCII-only Code* bitmap fonts cannot draw it. */
   public static boolean needsUiFont() {
      return !language().equals("EN") && !table().isEmpty();
   }

   private static synchronized Map<String, String> table() {
      Language now = Translator.getLanguage();
      if (now != loaded) {
         Map<String, String> m = new HashMap<>();
         // the stack runs most specific first; the first file holding a key wins
         Translator.forLanguageStack(lang -> read(lang.name(), m));
         table = m;
         loaded = now;
      }
      return table;
   }

   private static void read(String lang, Map<String, String> into) {
      if (lang.equals("EN")) {
         return; // English lives at the call sites
      }
      File f = GifTextures.resolve("media/pzopt/translate/" + lang + ".json");
      if (f == null || !f.isFile()) {
         return;
      }
      try {
         JSONObject o = new JSONObject(Files.readString(f.toPath()));
         for (String k : o.keySet()) {
            into.putIfAbsent(k, o.getString(k));
         }
         Log.info("i18n: " + o.length() + " strings from " + f.getName());
      } catch (Exception e) {
         Log.warn("i18n: cannot read " + f + ": " + e);
      }
   }
}
