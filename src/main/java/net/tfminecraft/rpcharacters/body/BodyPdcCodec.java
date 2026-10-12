package net.tfminecraft.rpcharacters.body;

import java.io.IOException;

import org.bukkit.persistence.PersistentDataContainer;

/** Exact persistent-data bytes. Paper writes the raw NBT, so every tag type survives unchanged. */
interface BodyPdcCodec {
    BodyPdcCodec PAPER = new BodyPdcCodec() {
        @Override
        public byte[] write(PersistentDataContainer container) throws IOException {
            try {
                return container.serializeToBytes();
            } catch (RuntimeException error) {
                throw new IOException("Character body persistent data could not be saved", error);
            }
        }

        @Override
        public void read(PersistentDataContainer target, byte[] bytes) throws IOException {
            try {
                target.readFromBytes(bytes, true);
            } catch (RuntimeException error) {
                throw new IOException("Character body persistent data could not be read", error);
            }
        }
    };

    byte[] write(PersistentDataContainer container) throws IOException;

    void read(PersistentDataContainer target, byte[] bytes) throws IOException;
}
