package com.llama.llamalarm.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Small atomic JSON file store. Used instead of a database because the data set is tiny and
 * a plain file keeps migrations and inspection simple.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Volatile
    private var cache: T? = null

    fun read(): T {
        cache?.let { return it }
        val value = runCatching {
            if (!file.exists()) default()
            else json.decodeFromString(serializer, file.readText())
        }.getOrElse { default() }
        cache = value
        return value
    }

    fun write(value: T) {
        cache = value
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun fileExists(): Boolean = file.exists()
}
