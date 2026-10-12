package net.tfminecraft.rpcharacters.paidchange;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.tools.ToolProvider;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Posting;
import net.tfminecraft.tlibs.character.CharacterRef;

class DenarEconomyWalletTest {
    @TempDir static Path classes;
    static final CharacterRef REF = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    static final String POUCH = "char:" + REF.characterId() + ":pouch", BANK = "char:" + REF.characterId() + ":bank";

    /** DenarEconomy 0.3.0's public Denars signatures over a tiny ledger, isolated like a plugin loader. */
    @BeforeAll static void compileExternalApi() throws Exception {
        Path result = classes.resolve("net/tfminecraft/denareconomy/api/Result.java");
        Path api = classes.resolve("net/tfminecraft/denareconomy/api/Denars.java");
        Files.createDirectories(api.getParent());
        Files.writeString(result, "package net.tfminecraft.denareconomy.api; public enum Result { APPLIED, REFUSED, CONFLICT, UNAVAILABLE }");
        Files.writeString(api, """
            package net.tfminecraft.denareconomy.api;
            import java.util.*;
            import net.tfminecraft.tlibs.character.CharacterRef;
            public final class Denars {
                public static final String MINT = "server:mint";
                public static boolean ready = true, failReady, failBalance, failTransfer, failStatus, closeOnStatus;
                public static final Map<String, Long> balances = new HashMap<>();
                public static final Map<String, Result> stored = new HashMap<>();
                public static final Map<String, String> legs = new HashMap<>();
                public static String lastFrom, lastTo, lastReason;
                public static int postings;
                public static boolean ready() { if (failReady) throw new IllegalStateException("ready failure"); return ready; }
                public static String pouch(CharacterRef c) { return "char:" + c.characterId() + ":pouch"; }
                public static String bank(CharacterRef c) { return "char:" + c.characterId() + ":bank"; }
                public static long balance(String account) {
                    if (failBalance) throw new IllegalStateException("read failure");
                    return balances.getOrDefault(account, 0L);
                }
                public static Result transfer(String opId, String reason, String from, String to, long cents) {
                    postings++; lastFrom = from; lastTo = to; lastReason = reason;
                    if (failTransfer) throw new IllegalStateException("write failure");
                    String fingerprint = reason + from + to + cents;
                    if (stored.containsKey(opId)) return fingerprint.equals(legs.get(opId)) ? stored.get(opId) : Result.CONFLICT;
                    Result result = !from.startsWith("server:") && balances.getOrDefault(from, 0L) < cents ? Result.REFUSED : Result.APPLIED;
                    if (result == Result.APPLIED) { balances.merge(from, -cents, Long::sum); balances.merge(to, cents, Long::sum); }
                    stored.put(opId, result); legs.put(opId, fingerprint); return result;
                }
                public static Optional<Result> status(String opId) {
                    if (failStatus) throw new IllegalStateException("status failure");
                    if (closeOnStatus) ready = false;
                    return Optional.ofNullable(stored.get(opId));
                }
            }
            """);
        Path tlibs = Path.of(CharacterRef.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int exit = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, "--release", "21",
                "-cp", tlibs.toString(), "-d", classes.toString(), result.toString(), api.toString());
        assertEquals(0, exit, diagnostics.toString(StandardCharsets.UTF_8));
    }

    @Test void absentOrDisabledPluginCannotReadOrChangeBalances() {
        PluginManager manager = mock(PluginManager.class); DenarEconomyWallet wallet = new DenarEconomyWallet();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            assertFalse(wallet.available()); assertEquals(0L, wallet.balance(REF, Account.POUCH));
            assertEquals(Posting.UNAVAILABLE, wallet.withdraw("op", DenarWallet.PAID_CHANGE, REF, Account.POUCH, 1));
            assertEquals(Posting.UNAVAILABLE, wallet.deposit("op", DenarWallet.REFUND, REF, Account.BANK, 1));
            assertEquals(Optional.of(Posting.UNAVAILABLE), wallet.status("op"));
            Plugin disabled = mock(Plugin.class); when(manager.getPlugin("DenarEconomy")).thenReturn(disabled); assertFalse(wallet.available());
        }
    }

    @Test void postsCharacterAccountsAgainstTheMintAndReplaysOperationIds() throws Exception {
        PluginManager manager = mock(PluginManager.class);
        try (ExternalPlugin external = new ExternalPlugin(true); var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager); when(manager.getPlugin("DenarEconomy")).thenReturn(external.plugin);
            external.balances().put(POUCH, 1_230L); external.balances().put(BANK, 10_000L);
            DenarEconomyWallet wallet = new DenarEconomyWallet(); assertTrue(wallet.available()); assertTrue(wallet.available());
            assertEquals(1_230L, wallet.balance(REF, Account.POUCH)); assertEquals(10_000L, wallet.balance(REF, Account.BANK));

            assertEquals(Posting.APPLIED, wallet.withdraw("paid:1", DenarWallet.PAID_CHANGE, REF, Account.BANK, 325));
            assertEquals(BANK, external.get("lastFrom")); assertEquals("server:mint", external.get("lastTo")); assertEquals("rpc:paid-change", external.get("lastReason"));
            assertEquals(Posting.APPLIED, wallet.withdraw("paid:1", DenarWallet.PAID_CHANGE, REF, Account.BANK, 325));
            assertEquals(9_675L, wallet.balance(REF, Account.BANK));
            assertEquals(Posting.APPLIED, wallet.deposit("refund:1", DenarWallet.REFUND, REF, Account.POUCH, 270));
            assertEquals("server:mint", external.get("lastFrom")); assertEquals(POUCH, external.get("lastTo"));
            assertEquals(Posting.CONFLICT, wallet.deposit("refund:1", DenarWallet.REFUND, REF, Account.POUCH, 271));
            assertEquals(1_500L, wallet.balance(REF, Account.POUCH));
            assertEquals(Posting.REFUSED, wallet.withdraw("paid:2", DenarWallet.CLASS_PICK, REF, Account.POUCH, 100_000));
            assertEquals(1_500L, wallet.balance(REF, Account.POUCH));

            assertEquals(Optional.of(Posting.APPLIED), wallet.status("paid:1"));
            assertEquals(Optional.of(Posting.REFUSED), wallet.status("paid:2"));
            assertEquals(Optional.empty(), wallet.status("never"));

            int postings = (int) external.get("postings");
            assertEquals(Posting.REFUSED, wallet.withdraw("x", DenarWallet.PAID_CHANGE, REF, Account.BANK, 0));
            assertEquals(Posting.REFUSED, wallet.deposit("x", DenarWallet.REFUND, REF, Account.BANK, -1));
            assertEquals(postings, external.get("postings"));
            assertNotSame(getClass().getClassLoader(), external.api.getClassLoader());
        }
    }

    @Test void pluginReloadRebindsToNewLoaderAndDisabledBoundPluginRemainsUnavailable() throws Exception {
        PluginManager manager = mock(PluginManager.class); DenarEconomyWallet wallet = new DenarEconomyWallet();
        try (ExternalPlugin first = new ExternalPlugin(true); ExternalPlugin second = new ExternalPlugin(true); var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager); when(manager.getPlugin("DenarEconomy")).thenReturn(first.plugin);
            first.balances().put(BANK, 950L); assertEquals(950L, wallet.balance(REF, Account.BANK));
            first.enabled.set(false); assertFalse(wallet.available()); assertEquals(0L, wallet.balance(REF, Account.BANK));
            second.balances().put(BANK, 4_275L); when(manager.getPlugin("DenarEconomy")).thenReturn(second.plugin);
            assertTrue(wallet.available()); assertEquals(4_275L, wallet.balance(REF, Account.BANK));
            assertEquals(Posting.APPLIED, wallet.deposit("r", DenarWallet.REFUND, REF, Account.BANK, 100));
            assertEquals(4_375L, second.balances().get(BANK)); assertEquals(950L, first.balances().get(BANK));
        }
    }

    @Test void failuresAndAClosedLedgerFailClosed() throws Exception {
        PluginManager manager = mock(PluginManager.class);
        try (ExternalPlugin external = new ExternalPlugin(true); var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager); when(manager.getPlugin("DenarEconomy")).thenReturn(external.plugin);
            DenarEconomyWallet wallet = new DenarEconomyWallet(); external.balances().put(POUCH, 500L);
            assertEquals(Posting.APPLIED, wallet.withdraw("paid", DenarWallet.PAID_CHANGE, REF, Account.POUCH, 100));
            external.set("failBalance", true); assertEquals(0L, wallet.balance(REF, Account.POUCH)); external.set("failBalance", false);
            external.set("failTransfer", true); assertEquals(Posting.UNAVAILABLE, wallet.deposit("r", DenarWallet.REFUND, REF, Account.POUCH, 1));
            assertEquals(400L, external.balances().get(POUCH)); external.set("failTransfer", false);
            external.set("failStatus", true); assertEquals(Optional.of(Posting.UNAVAILABLE), wallet.status("paid")); external.set("failStatus", false);
            external.set("closeOnStatus", true); assertEquals(Optional.of(Posting.UNAVAILABLE), wallet.status("paid"));
            assertFalse(wallet.available()); assertEquals(Optional.of(Posting.UNAVAILABLE), wallet.status("paid"));
            external.set("closeOnStatus", false); external.set("ready", true);
            external.set("failReady", true); assertFalse(wallet.available()); external.set("failReady", false);
            assertEquals(Optional.of(Posting.APPLIED), wallet.status("paid"));
        }
    }

    @Test void missingApiDoesNotBindAndLaterCompatiblePluginCanRecover() throws Exception {
        PluginManager manager = mock(PluginManager.class); DenarEconomyWallet wallet = new DenarEconomyWallet();
        try (ExternalPlugin missing = new ExternalPlugin(false); ExternalPlugin present = new ExternalPlugin(true); var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager); when(manager.getPlugin("DenarEconomy")).thenReturn(missing.plugin);
            assertFalse(wallet.available()); assertEquals(0L, wallet.balance(REF, Account.BANK));
            when(manager.getPlugin("DenarEconomy")).thenReturn(present.plugin); present.balances().put(POUCH, 1_230L);
            assertTrue(wallet.available()); assertEquals(1_230L, wallet.balance(REF, Account.POUCH));
        }
    }

    private static final class ExternalPlugin implements AutoCloseable {
        final URLClassLoader loader;
        final AtomicBoolean enabled = new AtomicBoolean(true);
        final Plugin plugin;
        final Class<?> api;
        ExternalPlugin(boolean withApi) throws Exception {
            loader = new URLClassLoader(withApi ? new URL[]{classes.toUri().toURL()} : new URL[0], DenarEconomyWalletTest.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.startsWith("net.tfminecraft.denareconomy.")) {
                        synchronized (getClassLoadingLock(name)) { Class<?> result = findLoadedClass(name); if (result == null) result = findClass(name); if (resolve) resolveClass(result); return result; }
                    }
                    return super.loadClass(name, resolve);
                }
            };
            plugin = (Plugin) Proxy.newProxyInstance(loader, new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> enabled.get();
                case "getName", "toString" -> "DenarEconomy fixture";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
            api = withApi ? Class.forName("net.tfminecraft.denareconomy.api.Denars", true, loader) : null;
        }
        Object get(String field) throws Exception { return api.getField(field).get(null); }
        void set(String field, Object value) throws Exception { api.getField(field).set(null, value); }
        @SuppressWarnings("unchecked") Map<String, Long> balances() throws Exception { return (Map<String, Long>) get("balances"); }
        @Override public void close() throws Exception { loader.close(); }
    }
}
