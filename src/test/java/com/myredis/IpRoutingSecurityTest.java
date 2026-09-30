package com.myredis;

import com.myredis.stats.ServerMetrics;
import com.myredis.storage.DatabaseInstance;
import com.myredis.storage.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class IpRoutingSecurityTest {

    private DatabaseInstance db;

    @BeforeEach
    public void setup() {
        StorageEngine storage = new StorageEngine(new ServerMetrics());
        db = new DatabaseInstance("test_db", "Production VPC Cache", "admin@company.com", "pass123", "token_abc", storage);
    }

    @Test
    public void testDefaultPublicAccess() {
        // By default, a database instance allows open public routing (0.0.0.0/0)
        assertTrue(db.isPublicAccess());
        assertTrue(db.isIpAllowed("54.210.12.34"));
        assertTrue(db.isIpAllowed("198.51.100.5"));
        assertTrue(db.isIpAllowed("10.0.1.5"));
        assertTrue(db.isIpAllowed("127.0.0.1"));
        assertTrue(db.isIpAllowed("::1"));
    }

    @Test
    public void testVpcCidrRestriction() {
        // Restrict to internal VPC subnet 10.0.0.0/16
        db.setAllowedIps(List.of("10.0.0.0/16"));
        assertFalse(db.isPublicAccess());

        // Allowed inside VPC
        assertTrue(db.isIpAllowed("10.0.1.50"));
        assertTrue(db.isIpAllowed("10.0.254.1"));

        // Denied outside VPC
        assertFalse(db.isIpAllowed("10.1.0.1"));
        assertFalse(db.isIpAllowed("192.168.1.1"));
        assertFalse(db.isIpAllowed("54.210.12.34"));

        // Loopback is always permitted for local administration
        assertTrue(db.isIpAllowed("127.0.0.1"));
        assertTrue(db.isIpAllowed("::1"));
    }

    @Test
    public void testSpecificIpAndSubnetAllowlist() {
        // Allow a specific corporate NAT IP and a /24 subnet
        db.setAllowedIps(List.of("203.0.113.50", "192.168.5.0/24"));

        assertTrue(db.isIpAllowed("203.0.113.50"));
        assertFalse(db.isIpAllowed("203.0.113.51"));

        assertTrue(db.isIpAllowed("192.168.5.10"));
        assertTrue(db.isIpAllowed("192.168.5.250"));
        assertFalse(db.isIpAllowed("192.168.6.1"));
    }

    @Test
    public void testEmptyListRestoresDefaultPublicAccess() {
        db.setAllowedIps(List.of("10.0.0.0/16"));
        assertFalse(db.isPublicAccess());

        db.setAllowedIps(List.of());
        assertTrue(db.isPublicAccess());
        assertTrue(db.isIpAllowed("54.210.12.34"));
    }

    @Test
    public void testMetadataSerializationPreservesIpRules() {
        db.setAllowedIps(List.of("172.31.0.0/16", "34.200.1.2"));
        Map<String, Object> meta = db.toMetadataMap();

        @SuppressWarnings("unchecked")
        List<String> savedIps = (List<String>) meta.get("allowedIps");
        assertNotNull(savedIps);
        assertTrue(savedIps.contains("172.31.0.0/16"));
        assertTrue(savedIps.contains("34.200.1.2"));

        // Restore from metadata
        DatabaseInstance restored = DatabaseInstance.fromMetadataMap(meta, new StorageEngine(new ServerMetrics()));
        assertNotNull(restored);
        assertTrue(restored.isIpAllowed("172.31.5.10"));
        assertTrue(restored.isIpAllowed("34.200.1.2"));
        assertFalse(restored.isIpAllowed("10.0.0.1"));
    }
}
