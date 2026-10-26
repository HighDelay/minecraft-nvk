package dev.nvkbridge.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class MachO {
    private static final int X86_64 = 0x01000007;
    private static final int DYLIB = 6;

    private MachO() {}

    static boolean isX86Dylib(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (channel.size() < 32) return false;
            ByteBuffer header = read(channel, 0, 32);
            int magic = header.getInt(0);
            if (magic == 0xcffaedfe || magic == 0xfeedfacf) {
                return isThinDylib(header);
            }
            boolean fat64 = magic == 0xcafebabf || magic == 0xbfbafeca;
            if (!fat64 && magic != 0xcafebabe && magic != 0xbebafeca) return false;
            ByteOrder order = magic == 0xbebafeca || magic == 0xbfbafeca
                ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            header.order(order);
            int count = header.getInt(4);
            int stride = fat64 ? 32 : 20;
            if (count < 1 || count > 64 || 8L + (long) count * stride > channel.size()) return false;
            for (int i = 0; i < count; i++) {
                ByteBuffer entry = read(channel, 8L + (long) i * stride, stride).order(order);
                if (entry.getInt(0) != X86_64) continue;
                long offset = fat64 ? entry.getLong(8) : Integer.toUnsignedLong(entry.getInt(8));
                long size = fat64 ? entry.getLong(16) : Integer.toUnsignedLong(entry.getInt(12));
                if (offset < 0 || size < 32 || offset > channel.size() - size) return false;
                return isThinDylib(read(channel, offset, 32));
            }
            return false;
        }
    }

    private static boolean isThinDylib(ByteBuffer header) {
        int magic = header.getInt(0);
        if (magic != 0xcffaedfe && magic != 0xfeedfacf) return false;
        header.order(magic == 0xcffaedfe ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        return header.getInt(4) == X86_64 && header.getInt(12) == DYLIB;
    }

    private static ByteBuffer read(FileChannel channel, long offset, int size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, offset + buffer.position());
            if (count < 0) throw new IOException("Truncated Mach-O header");
        }
        return buffer.flip();
    }
}
