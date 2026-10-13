package net.tfminecraft.rpcharacters.estate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real disk writes that can be made to fail, recording each step's order. */
final class FlakyFiles implements DurableFiles {
    boolean failWrites;
    boolean failMoves;
    boolean failAppends;
    final List<String> steps = new ArrayList<>();

    @Override
    public void write(Path file, byte[] bytes) throws IOException {
        steps.add("write");
        if (failWrites) throw new IOException("disk full");
        DISK.write(file, bytes);
    }

    @Override
    public void move(Path source, Path target) throws IOException {
        steps.add("move " + target.getFileName());
        if (failMoves) throw new IOException("move failed");
        DISK.move(source, target);
    }

    @Override
    public void append(Path file, byte[] bytes) throws IOException {
        steps.add("append " + file.getFileName());
        if (failAppends) throw new IOException("log failed");
        DISK.append(file, bytes);
    }

    @Override
    public void syncDirectory(Path directory) throws IOException {
        steps.add("sync " + directory.getFileName());
        DISK.syncDirectory(directory);
    }
}
