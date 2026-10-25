import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/** Creates a separate Windows Native Image input JAR; never edits dependency caches. */
public final class TrimSkikoIcu {
    private static final String SKIKO_SHA256 = "5061ffbbfbad9fc8c6c8e0c1741b674c863293d3d071b061db9e6e0fc3ef1d38";
    private static final String ORIGINAL_SHA256 = "9ae98c06cbb0ea43c5cd6b5725310c008c65e46072421a1118cb88e1de9a8b92";
    private static final String TRIMMED_SHA256 = "0476927a7de914c89931a640d8dbfd39b14a1186d51377090631aca4828de468";
    private static final Set<String> OMIT = Set.of("coll", "curr", "lang", "region", "unit", "zone");
    private record Item(byte[] name, int start, int end) {}

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static byte[] trim(byte[] source) throws Exception {
        // Pin the audited Skiko 0.150.1 / ICU 74 data. An upstream update must
        // be reviewed and retested rather than silently receiving this filter.
        if (!sha256(source).equals(ORIGINAL_SHA256)) {
            throw new IllegalArgumentException("Unsupported ICU data; review the Skiko version and trimming policy");
        }
        ByteBuffer input = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = Short.toUnsignedInt(input.getShort(0));
        if (source[2] != (byte) 0xda || source[3] != 0x27 || source[8] != 0
                || input.getInt(12) != 0x446e6d43) {
            throw new IllegalArgumentException("Expected little-endian ICU CmnD data");
        }
        int count = input.getInt(headerSize);
        var kept = new ArrayList<Item>();
        for (int i = 0; i < count; i++) {
            int nameStart = headerSize + input.getInt(headerSize + 4 + i * 8);
            int nameEnd = nameStart;
            while (source[nameEnd] != 0) nameEnd++;
            String name = new String(source, nameStart, nameEnd - nameStart, StandardCharsets.US_ASCII);
            if (!name.startsWith("icudt74l/")) throw new IllegalArgumentException("Unexpected ICU package: " + name);
            String[] parts = name.split("/");
            if (OMIT.contains(parts[1])) continue;
            int start = headerSize + input.getInt(headerSize + 8 + i * 8);
            int end = i + 1 == count ? source.length : headerSize + input.getInt(headerSize + 16 + i * 8);
            kept.add(new Item((name + '\0').getBytes(StandardCharsets.US_ASCII), start, end));
        }
        if (count != 2235 || kept.size() != 724) throw new IllegalArgumentException("Unexpected ICU item counts");
        int nameOffset = 4 + 8 * kept.size();
        int dataOffset = nameOffset + kept.stream().mapToInt(item -> item.name.length).sum();
        dataOffset = (dataOffset + 15) & ~15;
        int outputSize = headerSize + dataOffset + kept.stream().mapToInt(item -> item.end - item.start).sum();
        ByteBuffer output = ByteBuffer.allocate(outputSize).order(ByteOrder.LITTLE_ENDIAN);
        output.put(source, 0, headerSize);
        output.putInt(kept.size());
        int nextData = dataOffset;
        for (Item item : kept) {
            output.putInt(nameOffset).putInt(nextData);
            nameOffset += item.name.length;
            nextData += item.end - item.start;
        }
        for (Item item : kept) output.put(item.name);
        while (output.position() < headerSize + dataOffset) output.put((byte) 0xaa);
        for (Item item : kept) output.put(source, item.start, item.end - item.start);
        byte[] result = output.array();
        if (!sha256(result).equals(TRIMMED_SHA256)) throw new IllegalStateException("Trimmed ICU checksum mismatch");
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: TrimSkikoIcu.java input.jar output.jar");
        Path source = Path.of(args[0]).toRealPath();
        Path target = Path.of(args[1]).toAbsolutePath().normalize();
        if (source.equals(target) || Files.exists(target) && Files.isSameFile(source, target)) {
            throw new IllegalArgumentException("Output JAR must differ from input JAR");
        }
        try (JarFile jar = new JarFile(source.toFile())) {
            JarEntry library = jar.getJarEntry("skiko-windows-x64.dll");
            if (library == null) throw new IllegalArgumentException("Windows Skiko DLL not found");
            try (var stream = jar.getInputStream(library)) {
                if (!sha256(stream.readAllBytes()).equals(SKIKO_SHA256)) {
                    throw new IllegalArgumentException("Unsupported Skiko DLL; review the ICU trimming policy");
                }
            }
            JarEntry icu = jar.getJarEntry("icudtl.dat");
            if (icu == null) throw new IllegalArgumentException("Windows Skiko ICU resource not found");
            byte[] original;
            try (var stream = jar.getInputStream(icu)) { original = stream.readAllBytes(); }
            byte[] trimmed = trim(original);
            Files.createDirectories(target.getParent());
            try (var output = new JarOutputStream(Files.newOutputStream(target))) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry item = entries.nextElement();
                    JarEntry copy = new JarEntry(item.getName());
                    copy.setTime(item.getTime());
                    output.putNextEntry(copy);
                    if (item.getName().equals("icudtl.dat")) output.write(trimmed);
                    else try (var stream = jar.getInputStream(item)) { stream.transferTo(output); }
                    output.closeEntry();
                }
            }
            System.out.printf("Windows ICU: %,d -> %,d bytes; SHA-256 %s%n", original.length, trimmed.length, sha256(trimmed));
        }
    }
}
