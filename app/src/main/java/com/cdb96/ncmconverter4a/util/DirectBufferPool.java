package com.cdb96.ncmconverter4a.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
public class DirectBufferPool {
    private static int poolSize = 0;
    private static final int BIG_BUFFER_SIZE = 8 * 1024 * 1024;
    private static final int TIMEOUT = 5;
    private static final LinkedTransferQueue<Slot> freeSlots = new LinkedTransferQueue<>();

    private static final ByteBuffer bigBuffer = ByteBuffer.allocateDirect(BIG_BUFFER_SIZE);
    public static class Slot implements AutoCloseable{
        public ByteBuffer buffer;
        Slot(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void close() {
            freeSlots.offer(this);
        }
    }


    static {
        updateSlotBuffer(1);
    }

    public static void updateSlotBuffer(int newPoolSize) {
        if (poolSize == newPoolSize) {
            return;
        }
        freeSlots.clear();
        int temp = BIG_BUFFER_SIZE / newPoolSize;
        int slotBufferSize = temp - temp % 16;

        for (int i = 0; i < newPoolSize; i++) {
            int offset = i * slotBufferSize;
            bigBuffer.position(offset).limit(offset + slotBufferSize);
            ByteBuffer slice = bigBuffer.slice();

            freeSlots.add(new Slot(slice));
        }

        poolSize = newPoolSize;
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
