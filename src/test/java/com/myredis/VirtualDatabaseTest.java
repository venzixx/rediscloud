package com.myredis;

import com.myredis.security.AclEngine;
import com.myredis.security.User;
import com.myredis.stats.ServerMetrics;
import com.myredis.storage.StorageEngine;
import com.myredis.storage.VirtualDatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class VirtualDatabaseTest {

    private StorageEngine rootStorage;
    private VirtualDatabaseManager vdbManager;
    private AclEngine aclEngine;

    @BeforeEach
    public void setup() {
        rootStorage = new StorageEngine(new ServerMetrics());
        vdbManager = new VirtualDatabaseManager(rootStorage);
        aclEngine = new AclEngine();
    }

    @Test
    public void testVirtualDatabaseIsolation() {
        StorageEngine aliceStorage = vdbManager.getStorageForUser("alice");
        StorageEngine bobStorage = vdbManager.getStorageForUser("bob");

        assertNotSame(rootStorage, aliceStorage);
        assertNotSame(rootStorage, bobStorage);
        assertNotSame(aliceStorage, bobStorage);

        // Alice sets key
        aliceStorage.set("apikey", "ALICE_SECRET_123", null, false, false);
        assertEquals("ALICE_SECRET_123", aliceStorage.get("apikey"));

        // Bob must NOT see Alice's key
        assertNull(bobStorage.get("apikey"));

        // Root storage must NOT see Alice's key
        assertNull(rootStorage.get("apikey"));

        // Bob sets key
        bobStorage.set("apikey", "BOB_SECRET_456", null, false, false);
        assertEquals("BOB_SECRET_456", bobStorage.get("apikey"));
        assertEquals("ALICE_SECRET_123", aliceStorage.get("apikey"));
    }

    @Test
    public void testDatabaseNameResolution() {
        StorageEngine se1 = vdbManager.getStorageForUser("alice");
        StorageEngine se2 = vdbManager.getStorageByDbName("vdb_alice");
        StorageEngine se3 = vdbManager.getStorageByDbName("alice");

        assertSame(se1, se2);
        assertSame(se2, se3);

        assertSame(rootStorage, vdbManager.getStorageByDbName("db0"));
        assertSame(rootStorage, vdbManager.getStorageByDbName("default"));
        assertSame(rootStorage, vdbManager.getStorageForUser("admin"));
    }

    @Test
    public void testTenantAccountCreationAndUrls() {
        User user = aclEngine.createTenantAccount("devteam", "myPassword99", null);
        assertNotNull(user);
        assertEquals("devteam", user.getUsername());
        assertEquals("myPassword99", user.getPassword());
        assertTrue(user.getApiToken().startsWith("red_api_"));
        assertEquals("vdb_devteam", user.getVirtualDbName());

        Map<String, Object> map = user.toMap("127.0.0.1", 6379, 8080);
        assertEquals("devteam", map.get("username"));
        assertEquals("vdb_devteam", map.get("virtualDb"));

        @SuppressWarnings("unchecked")
        Map<String, String> urls = (Map<String, String>) map.get("connectionUrls");
        assertNotNull(urls);
        assertEquals("redis://devteam:myPassword99@127.0.0.1:6379", urls.get("redisUrl"));
        assertEquals("redis://:" + user.getApiToken() + "@127.0.0.1:6379", urls.get("tokenUrl"));
        assertEquals("redis://devteam:myPassword99@127.0.0.1:6379", urls.get("prisma"));
        assertEquals("redis://devteam:myPassword99@127.0.0.1:6379", urls.get("ioredis"));
        assertEquals("http://127.0.0.1:8080/v1", urls.get("restUrl"));
        assertTrue(urls.get("cliCommand").contains("redis-cli -u redis://devteam:myPassword99@127.0.0.1:6379"));
    }

    @Test
    public void testSingleTokenAndUserPassAuthentication() {
        User user = aclEngine.createTenantAccount("prisma_app", "secretPass", "red_api_custom_token");

        // Authenticate via token
        User authByToken = aclEngine.authenticateSingleTokenOrPass("red_api_custom_token");
        assertNotNull(authByToken);
        assertEquals("prisma_app", authByToken.getUsername());

        // Authenticate via user:password
        User authByPair = aclEngine.authenticateSingleTokenOrPass("prisma_app:secretPass");
        assertNotNull(authByPair);
        assertEquals("prisma_app", authByPair.getUsername());

        // Authenticate via standard user & pass
        User authStandard = aclEngine.authenticate("prisma_app", "secretPass");
        assertNotNull(authStandard);
        assertEquals("prisma_app", authStandard.getUsername());
    }

    @Test
    public void testDatabaseListing() {
        vdbManager.getStorageForUser("charlie");
        vdbManager.getStorageForUser("dave");

        List<Map<String, Object>> list = vdbManager.listDatabases();
        assertTrue(list.size() >= 3); // default, admin, charlie, dave

        boolean hasCharlie = list.stream().anyMatch(m -> "charlie".equals(m.get("tenant")));
        boolean hasDave = list.stream().anyMatch(m -> "dave".equals(m.get("tenant")));
        assertTrue(hasCharlie);
        assertTrue(hasDave);
    }
}
