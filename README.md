# Redis in Java (From Scratch) + Cloud Web Console

A production-grade, high-concurrency **Redis clone built entirely in Java 25** from scratch, featuring native **RESP2 protocol support**, **Java Virtual Threads (Project Loom)**, **in-memory data structures**, **active/passive TTL expiration**, **AOF crash durability**, and an **embedded modern Web Dashboard & REST API**.

---

## 🌟 Key Features

- **Standard Wire Protocol (RESP2)**: Compatible with official Redis clients (`redis-cli`, Jedis, Lettuce, `redis-py`, etc.) on port `6379`.
- **Project Loom Virtual Threads**: High-throughput thread-per-connection concurrency model without thread pool exhaustion.
- **Rich Data Structures**:
  - **Strings**: `SET`, `GET`, `MSET`, `MGET`, `INCR`, `DECR`, `INCRBY`, `DECRBY`, `APPEND`, `STRLEN`
  - **Hashes**: `HSET`, `HGET`, `HDEL`, `HGETALL`, `HEXISTS`, `HLEN`, `HKEYS`, `HVALS`
  - **Lists**: `LPUSH`, `RPUSH`, `LPOP`, `RPOP`, `LLEN`, `LRANGE`, `LINDEX`
  - **Sets**: `SADD`, `SREM`, `SMEMBERS`, `SISMEMBER`, `SCARD`
- **Key & Expiration Management**:
  - `DEL`, `EXISTS`, `EXPIRE`, `PEXPIRE`, `TTL`, `PTTL`, `PERSIST`, `TYPE`, `KEYS`, `RENAME`
  - Dual eviction: **passive eviction on access** + **proactive background sweeper**.
- **Append-Only File (AOF) Persistence**:
  - Writes mutating commands to disk in real-time.
  - Automatically replays operations on startup to recover state.
- **Integrated Web Console & REST API (Port 8080)**:
  - 📊 **Real-time Telemetry**: Active clients, memory footprint, hit rate, and total commands.
  - 🔍 **Data Explorer**: Search, filter, and inspect keys with type badges and live TTL.
  - ➕ **Key Manager**: Create, edit, and delete keys of any data type directly in the browser.
  - 💻 **Web CLI**: Interactive terminal emulator in the browser with command history (Up/Down arrows) and shortcuts.
- **Cloud & Container Ready**:
  - Multi-stage Dockerfile and Docker Compose setup for deployment to GCP, AWS, Render, Fly.io, or Railway.

---

## 🏗️ Architecture

```
                    ┌──────────────────────────────────────────────┐
                    │               Java 25 Process                │
                    │                                              │
redis-cli ─────────►│ Port 6379: TCP Server (Virtual Threads)      │
(RESP2 Protocol)    │      │                                       │
                    │      ▼                                       │
                    │ ┌──────────────────────────────────────────┐ │
                    │ │         RESP2 Parser & Encoder           │ │
                    │ └────────────────────┬─────────────────────┘ │
                    │                      ▼                       │
                    │ ┌──────────────────────────────────────────┐ │
                    │ │             Command Router               │ │
                    │ └────────────────────┬─────────────────────┘ │
                    │                      ▼                       │
                    │ ┌──────────────────────────────────────────┐ │
                    │ │         Thread-Safe Memory Store         │ │
                    │ │  - Strings, Hashes, Lists, Sets          │ │
                    │ │  - Background TTL Sweeper                │ │
                    │ │  - AOF Write Logger & Replay             │ │
                    │ │  - Live Telemetry Metrics                │ │
                    │ └────────────────────▲─────────────────────┘ │
                    │                      │                       │
Web Browser ───────►│ Port 8080: Embedded HTTP Web Console       │
(HTTP / REST)       │  - Data Explorer & Key Inspector           │
                    │  - In-Browser Redis CLI Terminal           │
                    │  - Live Telemetry & Metrics Gauges         │
                    └──────────────────────────────────────────────┘
```

---

## 🚀 Quick Start

### Prerequisites
- **Java 25** (or Java 21+)
- **Maven 3.8+** (or Docker)

### 1. Run Locally with Maven

```bash
# Clone and build
git clone https://github.com/your-username/redis-java.git
cd redis-java

# Compile and start server
mvn compile exec:java
```

Once running:
- **Redis TCP Server** will be listening on `127.0.0.1:6379`
- **Web Dashboard** will be accessible at [http://localhost:8080](http://localhost:8080)

---

### 2. Connect with `redis-cli`

You can use the official `redis-cli` or any TCP client (like `telnet` / `netcat`):

```bash
# Connect using official redis-cli
redis-cli -p 6379

127.0.0.1:6379> PING
PONG

127.0.0.1:6379> SET user:101 "Alice" EX 60
OK

127.0.0.1:6379> GET user:101
"Alice"

127.0.0.1:6379> TTL user:101
(integer) 58

127.0.0.1:6379> HSET user:profile name "Bob" email "bob@example.com"
(integer) 2

127.0.0.1:6379> HGETALL user:profile
1) "name"
2) "Bob"
3) "email"
4) "bob@example.com"

127.0.0.1:6379> LPUSH notifications "Welcome" "Verify email"
(integer) 2

127.0.0.1:6379> LRANGE notifications 0 -1
1) "Verify email"
2) "Welcome"
```

Open [http://localhost:8080](http://localhost:8080) in your browser and you'll immediately see all keys appear in real-time!

---

### 3. Run with Docker & Docker Compose (Cloud Ready)

```bash
# Build and run containerized server
docker compose up -d

# Check logs
docker compose logs -f
```

---

## 📡 REST API Reference

The embedded HTTP server exposes a REST API for programmatic access:

| Endpoint | Method | Description |
|:---|:---|:---|
| `/api/stats` | `GET` | Live server telemetry (keys, memory, commands, hit rate, uptime). |
| `/api/keys` | `GET` | List keys with filtering (`?pattern=*&type=string`). |
| `/api/key?key={name}` | `GET` | Detailed metadata and value inspection of a specific key. |
| `/api/key` | `POST` | Create or update a key (`{ key, type, value, ttl }`). |
| `/api/key?key={name}` | `DELETE` | Delete a key. |
| `/api/exec` | `POST` | Execute raw Redis command string (`{ "command": "SET foo bar" }`). |
| `/api/flush` | `POST` | Flush entire database (`FLUSHDB`). |

---

## 🧪 Running Tests

A comprehensive suite of unit and integration tests verifies the protocol parser, storage engine, data structures, and command router:

```bash
mvn test
```

---

## ⚙️ Configuration (Environment Variables)

| Variable | Default | Description |
|:---|:---|:---|
| `REDIS_HOST` | `0.0.0.0` | Bind IP address for both TCP and Web interfaces. |
| `REDIS_PORT` | `6379` | TCP port for Redis clients. |
| `WEB_PORT` / `PORT` | `8080` | HTTP port for Web Dashboard and REST API. |
| `AOF_ENABLED` | `true` | Enable Append-Only File persistence. |
| `AOF_PATH` | `data/appendonly.aof` | Disk path for the AOF log file. |
| `EXPIRY_INTERVAL_MS`| `200` | Period in milliseconds for active key eviction sweeper. |
