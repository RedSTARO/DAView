package com.daview.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy
import ch.qos.logback.core.util.FileSize
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private const val FILE_APPENDER = "FILE"

/**
 * Writes the log to `<data dir>/logs/daview.log` as well as to the console,
 * and returns that file — or null when it could not be set up, which must
 * never stop the app from starting.
 *
 * The installed desktop app has no console: jpackage's launcher starts the JVM
 * without one, so everything the library logged — a scan that failed, a sync
 * the storage refused, a config file that would not parse — went nowhere, and
 * there was nothing to look at afterwards. The current file and three older
 * ones of 2 MB each are kept, so the log cannot grow without bound.
 */
fun enableFileLogging(dataDir: Path): Path? = runCatching {
    val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return null
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    (root.getAppender(FILE_APPENDER) as? FileAppender<*>)?.let { return Path.of(it.file) }

    val directory = dataDir.resolve("logs")
    Files.createDirectories(directory)
    val file = directory.resolve("daview.log")

    val appender = RollingFileAppender<ILoggingEvent>().also {
        it.context = context
        it.name = FILE_APPENDER
        it.file = file.toString()
    }
    appender.encoder = PatternLayoutEncoder().also {
        it.context = context
        it.pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{28} - %msg%n"
        // Not the platform default: titles and paths are Chinese and Japanese,
        // and a Windows code page would turn them into question marks.
        it.charset = Charsets.UTF_8
        it.start()
    }
    appender.rollingPolicy = FixedWindowRollingPolicy().also {
        it.context = context
        it.setParent(appender)
        it.fileNamePattern = directory.resolve("daview.%i.log").toString()
        it.minIndex = 1
        it.maxIndex = 3
        it.start()
    }
    appender.triggeringPolicy = SizeBasedTriggeringPolicy<ILoggingEvent>().also {
        it.context = context
        it.maxFileSize = FileSize.valueOf("2MB")
        it.start()
    }
    appender.start()
    root.addAppender(appender)
    file
}.getOrNull()

/** Stops writing to the file again. For tests, which must not leave it open. */
internal fun disableFileLogging() {
    val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    root.getAppender(FILE_APPENDER)?.let {
        root.detachAppender(it)
        it.stop()
    }
}

/**
 * Logs an exception nothing caught before handing it on.
 *
 * Without a handler the thread simply ends, and in the installed app — no
 * console — not even the stack trace is seen: a background job dies and the
 * only symptom is that something stopped happening.
 */
fun logUncaughtExceptions() {
    val log = LoggerFactory.getLogger("com.daview.uncaught")
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching { log.error("uncaught exception on thread {}", thread.name, error) }
        previous?.uncaughtException(thread, error)
    }
}
