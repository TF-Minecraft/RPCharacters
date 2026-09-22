package net.tfminecraft.rpcharacters.party;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persistent membership; invitations remain short-lived session state. */
final class PartyStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    PartyStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    List<Party> load() throws IOException {
        if (!Files.exists(file)) return List.of();
        try {
            SavedParties saved = GSON.fromJson(Files.readString(file), SavedParties.class);
            if (saved == null || saved.version != 1 || saved.parties == null) {
                throw new IllegalArgumentException("Invalid party file format");
            }
            List<Party> parties = new ArrayList<>();
            Set<UUID> ids = new HashSet<>(), members = new HashSet<>();
            for (SavedParty entry : saved.parties) {
                if (entry == null || entry.id == null || !ids.add(entry.id)
                        || entry.name == null || entry.name.isBlank() || entry.leader == null
                        || entry.members == null || !entry.members.contains(entry.leader)) {
                    throw new IllegalArgumentException("Invalid party record");
                }
                Party party = new Party(entry.id, entry.name, entry.leader);
                for (UUID member : entry.members) {
                    if (member == null || !members.add(member)) {
                        throw new IllegalArgumentException("Duplicate or invalid party member");
                    }
                    party.addMember(member);
                }
                parties.add(party);
            }
            return parties;
        } catch (RuntimeException e) {
            throw new IOException("Cannot load parties from " + file + "; file was not changed", e);
        }
    }

    void save(Collection<Party> parties) throws IOException {
        List<SavedParty> records = parties.stream().map(p -> new SavedParty(
                p.getId(), p.getName(), p.getLeaderId(), new ArrayList<>(p.getMemberIds()))).toList();
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "parties-", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(new SavedParties(1, records)));
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private record SavedParties(int version, List<SavedParty> parties) {}
    private record SavedParty(UUID id, String name, UUID leader, List<UUID> members) {}
}
