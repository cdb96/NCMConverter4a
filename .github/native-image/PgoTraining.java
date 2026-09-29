import com.cdb96.ncmconverter4a.converter.NCMConverter;
import com.cdb96.ncmconverter4a.converter.NcmFileInfo;
import com.cdb96.ncmconverter4a.io.BinaryInput;
import com.cdb96.ncmconverter4a.io.BinaryOutput;
import com.cdb96.ncmconverter4a.jni.KGMDecrypt;
import com.cdb96.ncmconverter4a.jni.RC4Decrypt;
import java.util.Arrays;

/** Runs small deterministic conversion workloads before the GUI startup profile. */
public final class PgoTraining {
    public static void run() {
        byte[] key = {1, 3, 5, 7, 9};
        for (String format : new String[] {"mp3", "flac", "m4a"}) {
            byte[] plain = makePayload(format);
            byte[] encrypted = plain.clone();
            long context = RC4Decrypt.create(key);
            try {
                RC4Decrypt.decrypt(context, encrypted, encrypted.length);
            } finally {
                RC4Decrypt.destroy(context);
            }

            for (int iteration = 0; iteration < 3; iteration++) {
                int[] position = {0};
                long[] outputBytes = {0};
                BinaryInput input = (buffer, offset, length) -> {
                    if (position[0] == encrypted.length) return -1;
                    int count = Math.min(length, encrypted.length - position[0]);
                    System.arraycopy(encrypted, position[0], buffer, offset, count);
                    position[0] += count;
                    return count;
                };
                BinaryOutput output = (buffer, offset, length) -> outputBytes[0] += length;
                NCMConverter.INSTANCE.writeAudio(
                    input, output,
                    new NcmFileInfo(key, new byte[0], "PGO Song", "PGO Album", "PGO Artist", format),
                    false, 256 * 1024);
                if (position[0] != encrypted.length || outputBytes[0] < 1024 * 1024) {
                    throw new IllegalStateException("PGO NCM training failed for " + format);
                }
            }
        }

        byte[] kgmPayload = new byte[1024 * 1024];
        long context = KGMDecrypt.create(new byte[17]);
        try {
            int next = KGMDecrypt.decrypt(context, kgmPayload, 0, kgmPayload.length);
            if (next != kgmPayload.length) throw new IllegalStateException("PGO KGM training failed");
        } finally {
            KGMDecrypt.destroy(context);
        }
        System.out.println("PGO conversion training passed");
    }

    private static byte[] makePayload(String format) {
        byte[] audio = new byte[1024 * 1024];
        for (int index = 0; index < audio.length; index++) audio[index] = (byte) (index * 29 + 11);
        byte[] header = switch (format) {
            case "mp3" -> new byte[] {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0};
            case "flac" -> {
                byte[] flac = new byte[4 + 4 + 34];
                System.arraycopy(new byte[] {'f', 'L', 'a', 'C', (byte) 0x80, 0, 0, 34}, 0,
                    flac, 0, 8);
                yield flac;
            }
            case "m4a" -> new byte[] {
                0, 0, 0, 16, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 0, 0,
                0, 16, 0, 8, 'm', 'd', 'a', 't'
            };
            default -> throw new IllegalArgumentException(format);
        };
        byte[] payload = Arrays.copyOf(header, header.length + audio.length);
        System.arraycopy(audio, 0, payload, header.length, audio.length);
        return payload;
    }
}
