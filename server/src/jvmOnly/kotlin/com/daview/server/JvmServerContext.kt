package com.daview.server

import com.daview.server.db.JdbcSqlDatabase
import java.nio.file.Path

/**
 * The JVM flavour of [ServerContext]: same call shape as before, with the JDBC
 * driver filled in. Android builds its own with the platform SQLite instead.
 */
fun ServerContext(dataDir: Path): ServerContext = ServerContext(dataDir, JdbcSqlDatabase(dataDir))
