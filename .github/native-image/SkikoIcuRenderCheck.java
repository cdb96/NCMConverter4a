import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.jetbrains.skia.*;
import org.jetbrains.skia.paragraph.*;

/** Raster/layout regression probe, run in separate JVMs with original and trimmed ICU. */
public final class SkikoIcuRenderCheck {
    private static void set(TextStyle style, String name, Object value) throws Exception {
        for (var method : TextStyle.class.getMethods()) {
            if (method.getName().equals(name) && method.getReturnType() == void.class) {
                method.invoke(style, value);
                return;
            }
        }
        throw new NoSuchMethodException(name);
    }
    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
    private static void compare(String original, String candidate) throws Exception {
        var a = Files.readAllLines(java.nio.file.Path.of(original));
        var b = Files.readAllLines(java.nio.file.Path.of(candidate));
        if (a.size() != b.size()) throw new IllegalStateException("Probe case counts differ");
        int differences = 0;
        for (int row = 0; row < a.size(); row++) {
            String[] left = a.get(row).split("\\|", -1);
            String[] right = b.get(row).split("\\|", -1);
            if (left.length != right.length) {
                System.out.println("Different field counts: " + left[0] + " " + left.length + " / " + right.length);
                differences++;
            }
            for (int field = 0; field < Math.min(left.length, right.length); field++) {
                if (!left[field].equals(right[field])) {
                    if (differences < 20) System.out.println("Different " + left[0] + " field " + field + ": " + left[field] + " / " + right[field]);
                    differences++;
                }
            }
        }
        if (differences != 0) throw new IllegalStateException("Text probe has " + differences + " differing fields");
        System.out.println("Text probe results match: " + a.size() + " cases");
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--compare")) {
            compare(args[1], args[2]);
            return;
        }
        java.nio.file.Path out = java.nio.file.Path.of(args[0]);
        Files.createDirectories(out);
        String[][] cases = {
            {"zh", "中文音乐转换：周杰伦《晴天》、陈奕迅。你好，世界！繁體中文測試，標點、換行與選字。"},
            {"ja", "日本語の音楽ファイルを変換します。ひらがな・カタカナ、漢字、句読点を確認する。"},
            {"ko", "한국어 음악 파일을 변환합니다. 한글 글꼴과 줄바꿈을 확인합니다."},
            {"en", "NCM Converter — résumé, naïve, cafe\u0301; A\u030Angstro\u0308m. File 01 (2026)."},
            {"emoji", "音楽 🎵 👩‍💻 👨‍👩‍👧‍👦 🏳️‍🌈 👍🏽 🇨🇳 🇯🇵 1️⃣ ❤️ smile 😀 end"},
            {"ar", "تحويل ملفات الموسيقى العربية؛ مرحباً بالعالم! abc 123 نهاية النص."},
            {"he", "המרת קבצי מוזיקה בעברית, שלום עולם! abc 123 בדיקת טקסט."},
            {"th", "ทดสอบการแปลงไฟล์เพลงภาษาไทยและการตัดคำโดยไม่มีช่องว่างระหว่างคำ"},
            {"hi", "संगीत फ़ाइल रूपांतरण परीक्षण। हिन्दी पाठ और संयुक्त अक्षर क्ष त्र ज्ञ।"},
            {"my", "မြန်မာဘာသာစာသားနှင့်တေးဂီတဖိုင်ပြောင်းလဲခြင်းစမ်းသပ်မှု"},
            {"km", "សាកល្បងបំប្លែងឯកសារតន្ត្រីនិងការបំបែកពាក្យជាភាសាខ្មែរ"},
            {"mixed", "中文(日本語) العربية עברית English\n第二行\t路径 C:\\音乐\\song.ncm\n末尾\u200b零宽空格\u00a0NBSP"}
        };
        StringBuilder result = new StringBuilder();
        try (FontCollection fonts = new FontCollection()) {
            fonts.setDefaultFontManager(FontMgr.Companion.getDefault());
            for (String[] entry : cases) for (int width : new int[]{120, 250, 500}) for (Direction direction : Direction.values()) {
                try (TextStyle text = new TextStyle(); ParagraphStyle style = new ParagraphStyle()) {
                    set(text, "setFontSize", 24f);
                    set(text, "setColor", 0xff18242f);
                    set(text, "setLocale", entry[0].equals("mixed") || entry[0].equals("emoji") ? "en" : entry[0]);
                    style.setTextStyle(text);
                    style.setDirection(direction);
                    try (ParagraphBuilder builder = new ParagraphBuilder(style, fonts)) {
                        builder.addText(entry[1]);
                        try (Paragraph paragraph = builder.build(); Surface surface = Surface.Companion.makeRasterN32Premul(width + 16, 1100)) {
                            paragraph.layout(width);
                            if (paragraph.getHeight() <= 0 || paragraph.getLineNumber() <= 0) throw new IllegalStateException("Empty paragraph");
                            surface.getCanvas().clear(0xffffffff);
                            paragraph.paint(surface.getCanvas(), 8, 8);
                            try (Image image = surface.makeImageSnapshot(); Data data = image.encodeToData(EncodedImageFormat.PNG, 100, 0)) {
                                byte[] png = data.getBytes();
                                String name = entry[0] + "-" + width + "-" + direction;
                                Files.write(out.resolve(name + ".png"), png);
                                result.append(name).append('|').append(sha(png)).append('|').append(paragraph.getHeight()).append('|').append(paragraph.getMaxIntrinsicWidth());
                                for (LineMetrics line : paragraph.getLineMetrics()) result.append('|').append(line.toString());
                                for (int i = 0; i < entry[1].length(); i++) result.append('|').append(paragraph.getWordBoundary(i));
                                result.append('\n');
                            }
                        }
                    }
                }
            }
        }
        Files.writeString(out.resolve("results.txt"), result);
        String embedded;
        try (var stream = SkikoIcuRenderCheck.class.getResourceAsStream("/icudtl.dat")) {
            if (stream == null) throw new IllegalStateException("Missing embedded ICU data");
            embedded = sha(stream.readAllBytes());
        }
        try (var files = Files.walk(java.nio.file.Path.of(System.getProperty("skiko.data.path")))) {
            var extracted = files.filter(path -> path.getFileName().toString().equals("icudtl.dat")).toList();
            if (extracted.size() != 1 || !sha(Files.readAllBytes(extracted.getFirst())).equals(embedded)) {
                throw new IllegalStateException("Probe did not extract the expected ICU data into its isolated cache");
            }
        }
        System.out.println("cases=" + (cases.length * 3 * 2) + " resultSha256=" + sha(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
