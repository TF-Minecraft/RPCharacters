package net.tfminecraft.rpcharacters.body;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** The durable file steps behind body writes; tests replace them to fail at each step. */
interface BodyFiles {
    BodyFiles DISK = new BodyFiles() {
        @Override
        public void write(Path file, byte[] bytes) throws IOException {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void syncDirectory(Path directory) throws IOException {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    };

    /** Creates a new file and forces its bytes to disk. */
    void write(Path file, byte[] bytes) throws IOException;

    /** Atomically replaces the target. */
    void move(Path source, Path target) throws IOException;

    /** Forces a directory's entries to disk. */
    void syncDirectory(Path directory) throws IOException;
}
