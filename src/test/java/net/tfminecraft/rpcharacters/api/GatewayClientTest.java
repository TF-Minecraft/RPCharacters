package net.tfminecraft.rpcharacters.api;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Loads the bridge against an isolated, public TFMCWeb API fixture, as Bukkit does across plugins. */
class GatewayClientTest {
    @TempDir Path folder;
    URLClassLoader loader;
    Class<?> client, web, gateway;

    @BeforeEach void setup() throws Exception {
        Path webSource = folder.resolve("TFMCWeb.java"), gatewaySource = folder.resolve("ProvinceSystemGateway.java");
        Files.writeString(webSource, """
                package net.tfminecraft.tfmcweb;
                public final class TFMCWeb {
                    public static String realm = " dev ";
                    public static RuntimeException failure;
                    public static String getRealmId() { if (failure != null) throw failure; return realm; }
                }
                """);
        Files.writeString(gatewaySource, """
                package net.tfminecraft.tfmcweb.api;
                public final class ProvinceSystemGateway {
                    public static final class GatewayResult {
                        public boolean ok; public String body; public String error;
                    }
                    public static final class BytesResult {
                        public boolean ok; public byte[] data; public String error;
                    }
                    public static GatewayResult result = new GatewayResult();
                    public static BytesResult bytes = new BytesResult();
                    public static RuntimeException failure;
                    public static Object[] arguments;
                    public static GatewayResult request(String method, String path, String body) {
                        arguments = new Object[]{method,path,body};
                        if (failure != null) throw failure; return result;
                    }
                    public static GatewayResult requestBytes(String method, String path, byte[] body, String type) {
                        arguments = new Object[]{method,path,body,type};
                        if (failure != null) throw failure; return result;
                    }
                    public static BytesResult download(String path) {
                        arguments = new Object[]{path};
                        if (failure != null) throw failure; return bytes;
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", folder.toString(), webSource.toString(), gatewaySource.toString()));
        loader = newLoader(false);
        client = loader.loadClass(GatewayClient.class.getName());
        web = loader.loadClass("net.tfminecraft.tfmcweb.TFMCWeb");
        gateway = loader.loadClass("net.tfminecraft.tfmcweb.api.ProvinceSystemGateway");
    }

    URLClassLoader newLoader(boolean missingDependency) throws Exception {
        return new URLClassLoader(new URL[]{folder.toUri().toURL(), GatewayClient.class.getProtectionDomain().getCodeSource().getLocation()}, getClass().getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                boolean dependency = name.startsWith("net.tfminecraft.tfmcweb.");
                if (dependency && missingDependency) throw new ClassNotFoundException(name);
                if (dependency || name.startsWith(GatewayClient.class.getName())) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) found = findClass(name);
                    if (resolve) resolveClass(found);
                    return found;
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    @AfterEach void cleanup() throws Exception { loader.close(); }
    Object field(Object result, String name) throws Exception { return result.getClass().getField(name).get(result); }
    void set(Object result, String name, Object value) throws Exception { result.getClass().getField(name).set(result, value); }
    Object request(String method, String path, String body) throws Exception { return client.getMethod("request", String.class, String.class, String.class).invoke(null, method, path, body); }
    Object download(String path) throws Exception { return client.getMethod("download", String.class).invoke(null, path); }

    @Test void realmIsTrimmedAndMissingOrFailingRealmNeverDefaultsToMain() throws Exception {
        assertEquals("dev", client.getMethod("realmId").invoke(null));
        for (String missing : new String[]{null, " "}) {
            web.getField("realm").set(null, missing);
            assertNull(client.getMethod("realmId").invoke(null));
        }
        web.getField("failure").set(null, new IllegalStateException("disabled"));
        assertNull(client.getMethod("realmId").invoke(null));
    }

    @Test void stringAndBinaryRequestsPreserveArgumentsAndResults() throws Exception {
        Object external = gateway.getField("result").get(null);
        set(external, "ok", true); set(external, "body", "{\"id\":7}");
        Object result = request("POST", "/characters", "{}");
        assertEquals(true, field(result, "ok")); assertEquals("{\"id\":7}", field(result, "body")); assertNull(field(result, "error"));
        assertArrayEquals(new Object[]{"POST", "/characters", "{}"}, (Object[]) gateway.getField("arguments").get(null));
        byte[] bytes = {1, 2, 3};
        set(external, "body", null);
        result = client.getMethod("requestBytes", String.class, String.class, byte[].class, String.class)
                .invoke(null, "PUT", "/skin", bytes, "image/png");
        assertEquals("", field(result, "body"));
        assertArrayEquals(new Object[]{"PUT", "/skin", bytes, "image/png"}, (Object[]) gateway.getField("arguments").get(null));
        set(external, "ok", false); set(external, "error", "denied");
        assertEquals("denied", field(request("GET", "/", null), "error"));
        set(external, "error", null);
        assertEquals("request failed", field(request("GET", "/", null), "error"));
    }

    @Test void binaryDownloadsPreserveBytesAndExplainFailure() throws Exception {
        Object external = gateway.getField("bytes").get(null); byte[] bytes = {0, -1, 3};
        set(external, "ok", true); set(external, "data", bytes);
        Object result = download("/skin");
        assertEquals(true, field(result, "ok")); assertSame(bytes, field(result, "data")); assertNull(field(result, "error"));
        assertArrayEquals(new Object[]{"/skin"}, (Object[]) gateway.getField("arguments").get(null));
        set(external, "ok", false); set(external, "error", "offline");
        assertEquals("offline", field(download("/skin"), "error"));
        set(external, "error", null);
        assertEquals("download failed", field(download("/skin"), "error"));
    }

    @Test void dependencyExceptionsBecomeFailuresWithUsefulRootCause() throws Exception {
        gateway.getField("failure").set(null, new IllegalStateException("unavailable"));
        Object result = request("GET", "/", null);
        assertEquals(false, field(result, "ok")); assertNull(field(result, "body"));
        assertEquals("TFMCWeb gateway unavailable: unavailable", field(result, "error"));
        gateway.getField("failure").set(null, new IllegalArgumentException());
        result = client.getMethod("requestBytes", String.class, String.class, byte[].class, String.class)
                .invoke(null, "PUT", "/skin", new byte[0], "image/png");
        assertEquals("TFMCWeb gateway unavailable: IllegalArgumentException", field(result, "error"));
        assertEquals("TFMCWeb gateway unavailable: IllegalArgumentException", field(download("/skin"), "error"));
    }

    @Test void absentPluginProducesExplicitFailuresFromEveryPublicOperation() throws Exception {
        try (var missing = newLoader(true)) {
            Class<?> type = missing.loadClass(GatewayClient.class.getName());
            assertNull(type.getMethod("realmId").invoke(null));
            Object result = type.getMethod("request", String.class, String.class, String.class).invoke(null, "GET", "/", null);
            assertEquals(false, field(result, "ok")); assertTrue(field(result, "error").toString().contains("ProvinceSystemGateway"));
            result = type.getMethod("download", String.class).invoke(null, "/skin");
            assertEquals(false, field(result, "ok")); assertNull(field(result, "data"));
        }
        assertEquals("", GatewayClient.Result.success(null).body);
        assertEquals("failed", GatewayClient.Result.fail("failed").error);
        assertNull(GatewayClient.BytesDownload.fail("failed").data);
    }
}
