package ru.lavafrai.openid.data.repositories.maximport.impl

/**
 * Reads the live file set out of a LevelDB `MANIFEST-*` (a write-ahead-log formatted stream of
 * VersionEdit records, see leveldb/doc/impl.md and db/version_edit.cc). Files left in the directory
 * by an interrupted cleanup, such as a stale `.log` or an obsolete table, are not part of the
 * database's current state and must not take part in resolving values.
 */
internal object LevelDbManifest {
    class Live(
        /** Logs numbered below this have been flushed into tables and are obsolete. */
        val logNumber: Long,
        val tables: Set<Long>,
    )

    private const val BLOCK_SIZE = 32_768
    private const val HEADER_SIZE = 7
    private const val FULL = 1
    private const val FIRST = 2
    private const val MIDDLE = 3
    private const val LAST = 4

    private const val TAG_COMPARATOR = 1
    private const val TAG_LOG_NUMBER = 2
    private const val TAG_NEXT_FILE = 3
    private const val TAG_LAST_SEQUENCE = 4
    private const val TAG_COMPACT_POINTER = 5
    private const val TAG_DELETED_FILE = 6
    private const val TAG_NEW_FILE = 7
    private const val TAG_PREV_LOG_NUMBER = 9

    /** Null when the manifest is truncated, malformed or has no log number: its state is unknown. */
    fun parse(raw: ByteArray): Live? {
        val records = records(raw) ?: return null
        var logNumber = -1L
        val tables = mutableSetOf<Long>()
        for (record in records) {
            val cursor = Cursor(record)
            while (cursor.hasMore) {
                when (cursor.varint32() ?: return null) {
                    TAG_COMPARATOR -> cursor.lengthPrefixed() ?: return null

                    TAG_LOG_NUMBER -> logNumber = maxOf(logNumber, cursor.varint64() ?: return null)

                    TAG_NEXT_FILE, TAG_LAST_SEQUENCE, TAG_PREV_LOG_NUMBER -> cursor.varint64() ?: return null

                    TAG_COMPACT_POINTER -> {
                        cursor.varint32() ?: return null
                        cursor.lengthPrefixed() ?: return null
                    }

                    TAG_DELETED_FILE -> {
                        cursor.varint32() ?: return null
                        tables -= cursor.varint64() ?: return null
                    }

                    TAG_NEW_FILE -> {
                        cursor.varint32() ?: return null
                        val number = cursor.varint64() ?: return null
                        cursor.varint64() ?: return null
                        cursor.lengthPrefixed() ?: return null
                        cursor.lengthPrefixed() ?: return null
                        tables += number
                    }

                    else -> return null
                }
            }
        }
        return if (logNumber >= 0) Live(logNumber, tables) else null
    }

    private fun records(raw: ByteArray): List<ByteArray>? {
        val records = mutableListOf<ByteArray>()
        var pending: MutableList<ByteArray>? = null
        var position = 0
        while (position < raw.size) {
            val blockEnd = minOf(raw.size.toLong(), (position / BLOCK_SIZE + 1L) * BLOCK_SIZE).toInt()
            if (blockEnd - position < HEADER_SIZE) {
                position = blockEnd
                continue
            }
            val length = (raw[position + 4].toInt() and 0xFF) or ((raw[position + 5].toInt() and 0xFF) shl 8)
            val type = raw[position + 6].toInt() and 0xFF
            if (type == 0 && length == 0) {
                position = blockEnd
                continue
            }
            val start = position + HEADER_SIZE
            if (length > blockEnd - start) return null
            val fragment = raw.copyOfRange(start, start + length)
            position = start + length
            when (type) {
                FULL -> {
                    if (pending != null) return null
                    records += fragment
                }

                FIRST -> {
                    if (pending != null) return null
                    pending = mutableListOf(fragment)
                }

                MIDDLE -> (pending ?: return null) += fragment

                LAST -> {
                    val parts = pending ?: return null
                    parts += fragment
                    records += parts.fold(ByteArray(0)) { acc, part -> acc + part }
                    pending = null
                }

                else -> return null
            }
        }
        return if (pending == null) records else null
    }

    private class Cursor(private val data: ByteArray) {
        private var position = 0
        val hasMore: Boolean get() = position < data.size

        fun varint32(): Int? = varint(5)?.takeIf { it <= Int.MAX_VALUE }?.toInt()

        fun varint64(): Long? = varint(10)

        fun lengthPrefixed(): ByteArray? {
            val length = varint32() ?: return null
            if (length < 0 || length > data.size - position) return null
            return data.copyOfRange(position, position + length).also { position += length }
        }

        private fun varint(maxBytes: Int): Long? {
            var result = 0L
            for (index in 0 until maxBytes) {
                if (position >= data.size) return null
                val byte = data[position++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl (7 * index))
                if (byte and 0x80 == 0) return result.takeIf { it >= 0 }
            }
            return null
        }
    }
}
