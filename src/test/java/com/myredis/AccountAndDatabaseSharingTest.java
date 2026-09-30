package com.myredis;

import com.myredis.security.Account;
import com.myredis.security.AccountManager;
import com.myredis.security.Session;
import com.myredis.stats.ServerMetrics;
import com.myredis.storage.DatabaseInstance;
import com.myredis.storage.StorageEngine;
import com.myredis.storage.VirtualDatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AccountAndDatabaseSharingTest {

    private AccountManager accountManager;
    private VirtualDatabaseManager vdbManager;

    @BeforeEach
    public void setUp() {
        accountManager = new AccountManager();
        vdbManager = new VirtualDatabaseManager(new StorageEngine(new ServerMetrics()));
    }

    @Test
    public void testEmailRegistrationAndLogin() {
        Account acc = accountManager.register("testuser@example.com", "Password123!", "Test User");
        assertNotNull(acc);
        assertEquals("testuser@example.com", acc.getEmail());
        assertEquals("Test User", acc.getName());

        // Password matching
        assertTrue(acc.verifyPassword("Password123!"));
        assertFalse(acc.verifyPassword("WrongPassword"));

        // Login creating session
        Session session = accountManager.login("testuser@example.com", "Password123!");
        assertNotNull(session);
        assertEquals("testuser@example.com", session.getEmail());

        // Session lookup
        Account resolved = accountManager.getAccountForSession(session.getToken());
        assertNotNull(resolved);
        assertEquals("testuser@example.com", resolved.getEmail());
    }

    @Test
    public void testGoogleSignIn() {
        Session session = accountManager.loginWithGoogle("googleuser@gmail.com", "Google User", "https://avatar.url");
        assertNotNull(session);
        assertEquals("googleuser@gmail.com", session.getEmail());

        Account resolved = accountManager.getAccountForSession(session.getToken());
        assertNotNull(resolved);
        assertEquals("googleuser@gmail.com", resolved.getEmail());
        assertEquals("google", resolved.getProvider());
    }

    @Test
    public void testMultiDatabaseCreationAndOwnership() {
        String owner = "alex@rediscloud.dev";
        DatabaseInstance db = vdbManager.createDatabase(owner, "Analytics Production");
        assertNotNull(db);
        assertEquals(owner, db.getOwnerEmail());
        assertEquals("Analytics Production", db.getName());
        assertEquals("OWNER", db.getUserRole(owner));
        assertTrue(db.canAdmin(owner));
        assertTrue(db.canWrite(owner));
        assertTrue(db.canRead(owner));

        // Listed under Alex's databases
        Map<String, List<DatabaseInstance>> myAndShared = vdbManager.getCategorizedDatabases(owner);
        assertTrue(myAndShared.get("myDatabases").stream().anyMatch(d -> d.getId().equals(db.getId())));
    }

    @Test
    public void testDatabaseSharingAndPermissions() {
        String owner = "alex@rediscloud.dev";
        String collaborator = "sarah@company.io";

        DatabaseInstance db = vdbManager.createDatabase(owner, "Shared Cache");
        
        // Before sharing
        assertNull(db.getUserRole(collaborator));
        assertFalse(db.canRead(collaborator));
        assertFalse(db.canWrite(collaborator));

        // Share as VIEWER
        vdbManager.shareDatabase(db.getId(), owner, collaborator, "VIEWER");
        assertEquals("VIEWER", db.getUserRole(collaborator));
        assertTrue(db.canRead(collaborator));
        assertFalse(db.canWrite(collaborator));
        assertFalse(db.canAdmin(collaborator));

        // Collaborator sees it in "sharedWithMe"
        Map<String, List<DatabaseInstance>> sarahDbs = vdbManager.getCategorizedDatabases(collaborator);
        assertTrue(sarahDbs.get("sharedWithMe").stream().anyMatch(d -> d.getId().equals(db.getId())));

        // Upgrade to EDITOR
        vdbManager.shareDatabase(db.getId(), owner, collaborator, "EDITOR");
        assertEquals("EDITOR", db.getUserRole(collaborator));
        assertTrue(db.canRead(collaborator));
        assertTrue(db.canWrite(collaborator));
        assertFalse(db.canAdmin(collaborator));

        // Unshare
        vdbManager.unshareDatabase(db.getId(), owner, collaborator);
        assertNull(db.getUserRole(collaborator));
        assertFalse(db.canRead(collaborator));
    }

    @Test
    public void testShareLinkAndJoin() {
        String owner = "alex@rediscloud.dev";
        String joiner = "newdev@startup.io";

        DatabaseInstance db = vdbManager.createDatabase(owner, "Team Service");
        db.setShareLinkEnabled(true, "EDITOR");
        String shareLinkToken = db.getShareLinkToken();
        assertNotNull(shareLinkToken);

        // Join via share link token
        DatabaseInstance joinedDb = vdbManager.joinByShareLink(shareLinkToken, joiner);
        assertNotNull(joinedDb);
        assertEquals(db.getId(), joinedDb.getId());
        assertEquals("EDITOR", joinedDb.getUserRole(joiner));
    }

    @Test
    public void testWireAuthenticationResolution() {
        String owner = "alex@rediscloud.dev";
        DatabaseInstance db = vdbManager.createDatabase(owner, "Prisma DB", "db_prisma_live", "secret_pass_123");

        // Two-part AUTH <user/dbId> <pass>
        DatabaseInstance authByPair = vdbManager.resolveDatabaseByAuth("db_prisma_live", "secret_pass_123");
        assertNotNull(authByPair);
        assertEquals(db.getId(), authByPair.getId());

        // Single-token AUTH <apiToken>
        DatabaseInstance authByToken = vdbManager.resolveDatabaseByAuth(null, db.getApiToken());
        assertNotNull(authByToken);
        assertEquals(db.getId(), authByToken.getId());

        // Single-token AUTH <password>
        DatabaseInstance authByPassOnly = vdbManager.resolveDatabaseByAuth(null, "secret_pass_123");
        assertNotNull(authByPassOnly);
        assertEquals(db.getId(), authByPassOnly.getId());
    }
}
