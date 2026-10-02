package net.tfminecraft.rpcharacters.paidchange;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

class DenarEconomyWalletTest {
    @TempDir static Path classes;

    /** Exact public OfflineModifier signatures from DenarEconomy, isolated like a plugin loader. */
    @BeforeAll static void compileExternalApi() throws Exception {
        Path enums = classes.resolve("net/tfminecraft/denareconomy/enums/Accounts.java");
        Path api = classes.resolve("net/tfminecraft/denareconomy/accounts/OfflineModifier.java");
        Files.createDirectories(enums.getParent()); Files.createDirectories(api.getParent());
        Files.writeString(enums, "package net.tfminecraft.denareconomy.enums; public enum Accounts { POUCH, BANK }");
        Files.writeString(api, """
            package net.tfminecraft.denareconomy.accounts;
            import java.util.UUID;
            import net.tfminecraft.denareconomy.enums.Accounts;
            public final class OfflineModifier {
                public static double pouch = 12.30, bank = 100.00, lastAmount;
                public static UUID lastPlayer;
                public static Accounts lastAccount;
                public static boolean failBalance, failApply;
                public static int reads, changes;
                public static double balance(UUID player, Accounts account) {
                    reads++; lastPlayer = player; lastAccount = account;
                    if (failBalance) throw new IllegalStateException("read failure");
                    return account == Accounts.BANK ? bank : pouch;
                }
                public static boolean apply(UUID player, Accounts account, double amount) {
                    changes++; lastPlayer = player; lastAccount = account; lastAmount = amount;
                    if (failApply) throw new IllegalStateException("write failure");
                    double current = account == Accounts.BANK ? bank : pouch;
                    if (current + amount < 0) return false;
                    if (account == Accounts.BANK) bank += amount; else pouch += amount;
                    return true;
                }
            }
            """);
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int exit = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                "--release", "21", "-d", classes.toString(), enums.toString(), api.toString());
        assertEquals(0, exit, diagnostics.toString(StandardCharsets.UTF_8));
    }

    @Test void absentOrDisabledPluginCannotReadOrChangeBalances() {
        PluginManager manager = mock(PluginManager.class); DenarEconomyWallet wallet = new DenarEconomyWallet(); UUID id = UUID.randomUUID();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            assertFalse(wallet.available()); assertEquals(BigDecimal.ZERO, wallet.balance(id, Account.POUCH)); assertFalse(wallet.withdraw(id, Account.POUCH, new BigDecimal("1"))); assertFalse(wallet.deposit(id, Account.BANK, new BigDecimal("1")));
            Plugin disabled = mock(Plugin.class); when(manager.getPlugin("DenarEconomy")).thenReturn(disabled); assertFalse(wallet.available());
        }
    }

    @Test void bindsPluginOwnedApiAndPassesExactUuidAccountAndSignedAmount() throws Exception {
        PluginManager manager = mock(PluginManager.class); UUID id = UUID.randomUUID();
        try (ExternalPlugin external = new ExternalPlugin(true); var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager); when(manager.getPlugin("DenarEconomy")).thenReturn(external.plugin);
            DenarEconomyWallet wallet = new DenarEconomyWallet(); assertTrue(wallet.available()); assertTrue(wallet.available());
            assertEquals(new BigDecimal("12.30"), wallet.balance(id, Account.POUCH)); assertEquals(id, external.get("lastPlayer")); assertEquals("POUCH", external.get("lastAccount").toString());
            assertEquals(new BigDecimal("100.00"), wallet.balance(id, Account.BANK)); assertEquals("BANK", external.get("lastAccount").toString());
            assertTrue(wallet.withdraw(id, Account.BANK, new BigDecimal("3.25"))); assertEquals(-3.25, external.get("lastAmount")); assertEquals(new BigDecimal("96.75"), wallet.balance(id, Account.BANK));
            assertTrue(wallet.deposit(id, Account.POUCH, new BigDecimal("2.70"))); assertEquals(2.70, external.get("lastAmount")); assertEquals(new BigDecimal("15.00"), wallet.balance(id, Account.POUCH));
            assertFalse(wallet.withdraw(id, Account.POUCH, new BigDecimal("1000"))); assertEquals(new BigDecimal("15.00"), wallet.balance(id, Account.POUCH));
            int changes = (int) external.get("changes"); assertFalse(wallet.withdraw(id, Account.BANK, BigDecimal.ZERO)); assertFalse(wallet.withdraw(id, Account.BANK, BigDecimal.ONE.negate())); assertFalse(wallet.deposit(id, Account.BANK, BigDecimal.ZERO)); assertFalse(wallet.deposit(id, Account.BANK, BigDecimal.ONE.negate())); assertEquals(changes, external.get("changes"));
            assertNotSame(getClass().getClassLoader(), external.api.getClassLoader());
        }
    }

    @Test void pluginReloadRebindsToNewLoaderAndDisabledBoundPluginRemainsUnavailable() throws Exception {
        PluginManager manager = mock(PluginManager.class); UUID id = UUID.randomUUID(); DenarEconomyWallet wallet = new DenarEconomyWallet();
        try (ExternalPlugin first = new ExternalPlugin(true); ExternalPlugin second = new ExternalPlugin(true); var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager); when(manager.getPlugin("DenarEconomy")).thenReturn(first.plugin); first.set("bank", 9.50); assertEquals(new BigDecimal("9.50"), wallet.balance(id, Account.BANK));
            first.enabled.set(false); assertFalse(wallet.available()); assertEquals(BigDecimal.ZERO, wallet.balance(id, Account.BANK));
            second.set("bank", 42.75); when(manager.getPlugin("DenarEconomy")).thenReturn(second.plugin); assertTrue(wallet.available()); assertEquals(new BigDecimal("42.75"), wallet.balance(id, Account.BANK)); assertTrue(wallet.deposit(id, Account.BANK, BigDecimal.ONE)); assertEquals(43.75, second.get("bank")); assertEquals(9.50, first.get("bank"));
        }
    }

    @Test void reflectionAndAccountFailuresFailClosedAndCanBeRetried() throws Exception {
        PluginManager manager = mock(PluginManager.class); UUID id = UUID.randomUUID();
        try (ExternalPlugin external = new ExternalPlugin(true); var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager); when(manager.getPlugin("DenarEconomy")).thenReturn(external.plugin); DenarEconomyWallet wallet = new DenarEconomyWallet();
            external.set("failBalance", true); assertEquals(BigDecimal.ZERO, wallet.balance(id, Account.POUCH)); external.set("failBalance", false);
            external.set("failApply", true); assertFalse(wallet.withdraw(id, Account.POUCH, BigDecimal.ONE)); assertFalse(wallet.deposit(id, Account.BANK, BigDecimal.ONE)); assertEquals(12.30, external.get("pouch")); assertEquals(100.0, external.get("bank"));
            external.set("failApply", false); assertTrue(wallet.deposit(id, Account.BANK, BigDecimal.ONE)); assertEquals(new BigDecimal("101.00"), wallet.balance(id, Account.BANK));
        }
    }

    @Test void missingApiDoesNotBindAndLaterCompatiblePluginCanRecover() throws Exception {
        PluginManager manager = mock(PluginManager.class); DenarEconomyWallet wallet = new DenarEconomyWallet();
        try (ExternalPlugin missing = new ExternalPlugin(false); ExternalPlugin present = new ExternalPlugin(true); var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager); when(manager.getPlugin("DenarEconomy")).thenReturn(missing.plugin); assertFalse(wallet.available()); assertEquals(BigDecimal.ZERO, wallet.balance(UUID.randomUUID(), Account.BANK));
            when(manager.getPlugin("DenarEconomy")).thenReturn(present.plugin); assertTrue(wallet.available()); assertEquals(new BigDecimal("12.30"), wallet.balance(UUID.randomUUID(), Account.POUCH));
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
            api = withApi ? Class.forName("net.tfminecraft.denareconomy.accounts.OfflineModifier", true, loader) : null;
        }
        Object get(String field) throws Exception { return api.getField(field).get(null); }
        void set(String field, Object value) throws Exception { api.getField(field).set(null, value); }
        @Override public void close() throws Exception { loader.close(); }
    }
}
