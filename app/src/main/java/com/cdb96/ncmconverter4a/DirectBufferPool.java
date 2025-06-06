package com.cdb96.ncmconverter4a;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
public class DirectBufferPool {
    private static final int MAX_THREADS = 8;
    private static final int BUFFER_SIZE = 512 * 1024;

    private static final int TIMEOUT = 5;
    private static final LinkedTransferQueue<Slot> freeSlots = new LinkedTransferQueue<>();


    public static class Slot {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        public void release() {
            buffer.clear();
            freeSlots.offer(this);
        }
    }

    static {
        for (int i = 0; i < MAX_THREADS; i++) {
            freeSlots.add(new Slot());
        }
    }

    public static Slot acquireDirectBuffer() throws InterruptedException {
        return freeSlots.poll(TIMEOUT, TimeUnit.SECONDS);
    }

    public static void safeWrite(FileChannel outputChannel, ByteBuffer byteBuffer) throws IOException {
        byteBuffer.flip();
        outputChannel.write(byteBuffer);
        byteBuffer.clear();
    }
}
