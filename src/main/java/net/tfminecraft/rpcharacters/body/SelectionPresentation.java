package net.tfminecraft.rpcharacters.body;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Connection-local presentation; never changes the player's authoritative body or abilities. */
final class SelectionPresentation implements AutoCloseable {
    static final int MAX_MESSAGES = 100;
    static final int MAX_CHARS = 128 * 1024;

    interface Transport {
        void mode(Player player, boolean hidden);
        void clear(Player player);
        void fence(Player player, int id);
        void chat(Player player, String json);
        void close();
    }

    private enum State { DEFERRED, DRAINING, LIVE, CLOSED }

    private static final class Session {
        final Player player;
        final ArrayDeque<String> messages = new ArrayDeque<>();
        State state = State.DEFERRED;
        int chars;
        long omitted;
        boolean hidden;
        boolean interactive;
        Integer fence;

        Session(Player player) { this.player = player; }
    }

    private final Plugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger fences = new AtomicInteger(ThreadLocalRandom.current().nextInt());
    private Transport transport;

    SelectionPresentation(Plugin plugin, Transport transport) {
        this.plugin = plugin;
        this.transport = transport;
    }

    static SelectionPresentation create(Plugin plugin, boolean enabled) {
        SelectionPresentation presentation = new SelectionPresentation(plugin, null);
        if (!enabled) return presentation;
        Plugin protocol = Bukkit.getPluginManager().getPlugin("ProtocolLib");
        if (protocol == null || !protocol.isEnabled()) {
            plugin.getLogger().warning("ProtocolLib is unavailable; selection HUD hiding and delayed chat are disabled.");
            return presentation;
        }
        try {
            presentation.transport = new SelectionHudPackets(plugin, presentation);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            plugin.getLogger().log(Level.WARNING, "Selection HUD hiding is unavailable; keeping the normal interface.", error);
        }
        return presentation;
    }

    boolean available() { return transport != null; }

    void begin(Player player) {
        if (!available()) return;
        Session previous = sessions.put(player.getUniqueId(), new Session(player));
        if (previous != null) discard(previous);
    }

    private Session session(Player player) {
        UUID owner;
        try {
            owner = player.getUniqueId();
        } catch (UnsupportedOperationException error) {
            // ProtocolLib's pre-login TemporaryPlayer has no UUID yet.
            return null;
        }
        Session session = sessions.get(owner);
        return session != null && session.player == player ? session : null;
    }

    boolean holds(Player player) {
        Session session = session(player);
        if (session == null) return false;
        synchronized (session) { return session.state == State.DEFERRED || session.state == State.DRAINING; }
    }

    /** LocalPlayer can re-enable flying each tick even after an authoritative abilities packet. */
    boolean blockFlight(Player player) {
        Session session = session(player);
        if (session == null) return false;
        synchronized (session) { return session.hidden || session.fence != null; }
    }

    boolean acknowledge(Player player, int id) {
        Session session = session(player);
        if (session == null) return false;
        synchronized (session) {
            if (session.fence == null || session.fence != id) return false;
            session.fence = null;
            if (session.state == State.LIVE) sessions.remove(player.getUniqueId(), session);
            return true;
        }
    }

    boolean transientPacket(Player player) {
        Session session = session(player);
        if (session == null) return false;
        synchronized (session) {
            return session.state == State.DEFERRED && !session.interactive;
        }
    }

    /** Runs from ProtocolLib's sending thread; retains immutable JSON, never a mutable packet. */
    boolean chat(Player player, String json) {
        Session session = session(player);
        if (session == null || json == null) return false;
        synchronized (session) {
            if (session.state == State.LIVE || session.state == State.CLOSED || session.interactive) return false;
            if (json.length() > MAX_CHARS) {
                session.omitted++;
                return true;
            }
            while (session.messages.size() >= MAX_MESSAGES || session.chars + json.length() > MAX_CHARS) {
                session.chars -= session.messages.removeFirst().length();
                session.omitted++;
            }
            session.messages.addLast(json);
            session.chars += json.length();
            return true;
        }
    }

    /** Called only after recovery has captured the outgoing body and a real respawn has finished. */
    void mask(Player player) {
        Session session = session(player);
        if (session == null) return;
        synchronized (session) {
            if (session.state != State.DEFERRED) return;
            session.hidden = true;
            session.interactive = false;
            session.fence = null;
        }
        try {
            transport.mode(player, true);
            transport.clear(player);
        } catch (RuntimeException error) {
            failOpen(error);
        }
    }

    void refresh(Player player) {
        Session session = session(player);
        if (session == null) return;
        synchronized (session) {
            if (!session.hidden || session.interactive || session.state != State.DEFERRED) return;
        }
        mask(player);
    }

    /** Explicit chat-based linking/help flow. Previously held ordinary messages still wait for Play. */
    void interact(Player player) {
        Session session = session(player);
        if (session == null) return;
        int fence = fences.incrementAndGet();
        synchronized (session) {
            session.hidden = false;
            session.interactive = true;
            session.fence = fence;
        }
        try {
            transport.mode(player, false);
            transport.fence(player, fence);
        } catch (RuntimeException error) {
            failOpen(error);
        }
    }

    /** Main-thread handoff. Arrivals during draining join the same FIFO; sends never hold its lock. */
    void release(Player player) {
        Session session = session(player);
        if (session == null) return;
        int fence = fences.incrementAndGet();
        synchronized (session) {
            if (session.state != State.DEFERRED) return;
            session.state = State.DRAINING;
            session.interactive = false;
            session.hidden = false;
            session.fence = fence;
        }
        try {
            transport.mode(player, false);
            transport.fence(player, fence);
            drain(session, false);
        } catch (RuntimeException error) {
            failOpen(error);
        }
    }

    private void drain(Session session, boolean closing) {
        // A busy async producer must not monopolize the server thread. New gameplay chat waits behind this drain.
        for (int count = 0; count <= MAX_MESSAGES && sessions.get(session.player.getUniqueId()) == session; count++) {
            String json;
            synchronized (session) {
                if (session.omitted > 0) {
                    json = "{\"text\":\"" + session.omitted + " earlier messages omitted while choosing a character.\",\"color\":\"gray\"}";
                    session.omitted = 0;
                } else if (!session.messages.isEmpty()) {
                    json = session.messages.removeFirst();
                    session.chars -= json.length();
                } else {
                    session.state = State.LIVE;
                    if (session.fence == null) sessions.remove(session.player.getUniqueId(), session);
                    return;
                }
            }
            transport.chat(session.player, json);
        }
        if (sessions.get(session.player.getUniqueId()) != session) return;
        synchronized (session) {
            if (session.messages.isEmpty() && session.omitted == 0) {
                session.state = State.LIVE;
                if (session.fence == null) sessions.remove(session.player.getUniqueId(), session);
                return;
            }
        }
        if (closing) {
            long omitted;
            synchronized (session) {
                omitted = session.omitted + session.messages.size();
                session.state = State.LIVE;
                sessions.remove(session.player.getUniqueId(), session);
            }
            if (omitted > 0) transport.chat(session.player, "{\"text\":\"" + omitted + " later messages omitted during shutdown.\",\"color\":\"gray\"}");
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try { drain(session, false); }
                catch (RuntimeException error) { failOpen(error); }
            });
        }
    }

    void quit(Player player) {
        Session session = session(player);
        if (session != null && sessions.remove(player.getUniqueId(), session)) discard(session);
    }

    private static void discard(Session session) {
        synchronized (session) {
            session.state = State.CLOSED;
            session.messages.clear();
            session.chars = 0;
        }
    }

    private void failOpen(RuntimeException error) {
        plugin.getLogger().log(Level.WARNING, "Selection presentation failed; restoring the normal interface.", error);
        close();
    }

    @Override public void close() {
        if (!available()) return;
        // Keep the listener installed during draining, so asynchronous arrivals cannot overtake the queue.
        for (Session session : List.copyOf(sessions.values())) {
            if (session.player.isOnline()) {
                try {
                    synchronized (session) { session.state = State.DRAINING; session.interactive = false; }
                    transport.mode(session.player, false);
                } catch (RuntimeException error) {
                    plugin.getLogger().log(Level.WARNING, "Could not restore selection presentation for " + session.player.getUniqueId(), error);
                }
                try {
                    drain(session, true);
                } catch (RuntimeException error) {
                    plugin.getLogger().log(Level.WARNING, "Could not release delayed chat for " + session.player.getUniqueId(), error);
                }
            }
            discard(session);
        }
        sessions.clear();
        try { transport.close(); }
        catch (RuntimeException error) { plugin.getLogger().log(Level.WARNING, "Could not unregister selection packet listener", error); }
        finally { transport = null; }
    }
}
