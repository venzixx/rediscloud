package com.myredis.config;

/**
 * Server configuration with environment variable and CLI overrides.
 * Ideal for cloud deployment (Docker, Kubernetes, Render, Fly.io, GCP, AWS).
 */
public class ServerConfig {

    private final String host;
    private final int redisPort;
    private final int webPort;
    private final boolean aofEnabled;
    private final String aofPath;
    private final long expiryIntervalMillis;
    private final String publicHost;
    private final String vpcPrivateIp;

    public ServerConfig(String host, int redisPort, int webPort, boolean aofEnabled, String aofPath,
                        long expiryIntervalMillis, String publicHost, String vpcPrivateIp) {
        this.host = host;
        this.redisPort = redisPort;
        this.webPort = webPort;
        this.aofEnabled = aofEnabled;
        this.aofPath = aofPath;
        this.expiryIntervalMillis = expiryIntervalMillis;
        this.publicHost = publicHost;
        this.vpcPrivateIp = vpcPrivateIp != null ? vpcPrivateIp : detectVpcPrivateIp();
    }

    public ServerConfig(String host, int redisPort, int webPort, boolean aofEnabled, String aofPath, long expiryIntervalMillis) {
        this(host, redisPort, webPort, aofEnabled, aofPath, expiryIntervalMillis, null, null);
    }

    public static ServerConfig load() {
        String host = getEnvOrDefault("REDIS_HOST", "0.0.0.0");
        int redisPort = getIntEnvOrDefault("REDIS_PORT", 6379);

        // Many cloud platforms (Render, Heroku, Cloud Run) supply $PORT for the web server
        int webPort = getIntEnvOrDefault("PORT", getIntEnvOrDefault("WEB_PORT", 8080));

        boolean aofEnabled = Boolean.parseBoolean(getEnvOrDefault("AOF_ENABLED", "true"));
        String aofPath = getEnvOrDefault("AOF_PATH", "data/appendonly.aof");
        long expiryInterval = getLongEnvOrDefault("EXPIRY_INTERVAL_MS", 200L);

        String publicHost = getEnvOrDefault("PUBLIC_HOST", getEnvOrDefault("REDIS_PUBLIC_HOST", getEnvOrDefault("REDIS_PUBLIC_IP", null)));
        String vpcPrivateIp = detectVpcPrivateIp();

        return new ServerConfig(host, redisPort, webPort, aofEnabled, aofPath, expiryInterval, publicHost, vpcPrivateIp);
    }

    private static String getEnvOrDefault(String key, String def) {
        String val = System.getenv(key);
        if (val != null && !val.isBlank()) return val;
        String prop = System.getProperty(key);
        if (prop != null && !prop.isBlank()) return prop;
        return def;
    }

    private static int getIntEnvOrDefault(String key, int def) {
        String val = getEnvOrDefault(key, null);
        if (val != null) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static long getLongEnvOrDefault(String key, long def) {
        String val = getEnvOrDefault(key, null);
        if (val != null) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    public String getHost() {
        return host;
    }

    public int getRedisPort() {
        return redisPort;
    }

    public int getWebPort() {
        return webPort;
    }

    public boolean isAofEnabled() {
        return aofEnabled;
    }

    public String getAofPath() {
        return aofPath;
    }

    public long getExpiryIntervalMillis() {
        return expiryIntervalMillis;
    }

    public String getPublicHost() {
        return publicHost;
    }

    public String getVpcPrivateIp() {
        return vpcPrivateIp;
    }

    public static String detectVpcPrivateIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    java.net.NetworkInterface nif = interfaces.nextElement();
                    if (nif.isLoopback() || !nif.isUp()) continue;
                    java.util.Enumeration<java.net.InetAddress> addresses = nif.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress addr = addresses.nextElement();
                        if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
            java.net.InetAddress local = java.net.InetAddress.getLocalHost();
            if (local != null && !local.isLoopbackAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
}
