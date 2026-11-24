package ru.lavafrai.openid.data.repositories.maximport.impl

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import ru.lavafrai.openid.data.core.DataException

/**
 * Reads `shared_prefs` preference maps the same way the reference exporter does:
 * only the depth-1 `<map>` root, depth-2 `<string>` text values and `<int>`/`<long>` attributes.
 * A DOCTYPE or an unknown entity fails the file, mirroring the strict reference behavior.
 */
internal object XmlPrefsCodec {
    fun parse(
        raw: ByteArray,
        limit: Int = 4_096,
    ): List<PrefRecord> {
        val text = try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
        } catch (_: Exception) {
            throw DataException("MAX_PREFS_XML_INVALID")
        }
        val records = mutableListOf<PrefRecord>()
        var depth = 0
        var field: String? = null
        val buffer = StringBuilder()
        var index = 0
        while (index < text.length && records.size < limit) {
            val open = text.indexOf('<', index)
            if (open < 0) break
            if (depth == 2 && field != null) appendText(text, index, open, buffer)
            when {
                text.startsWith("<!--", open) -> index = requireEnd(text, "-->", open + 4)

                text.startsWith("<![CDATA[", open) -> {
                    val end = requireEnd(text, "]]>", open + 9)
                    if (depth == 2 && field != null) buffer.append(text, open + 9, end)
                    index = end + 3
                }

                text.startsWith("<?", open) -> index = requireEnd(text, "?>", open + 2) + 2

                text.startsWith("<!", open) -> throw DataException("MAX_PREFS_XML_INVALID")

                text.startsWith("</", open) -> {
                    val close = text.indexOf('>', open)
                    if (close < 0) throw DataException("MAX_PREFS_XML_INVALID")
                    depth--
                    if (depth == 1 && field != null) {
                        val value = buffer.toString()
                        if (value.isNotEmpty()) records += PrefRecord(field, value)
                        field = null
                        buffer.clear()
                    }
                    index = close + 1
                }

                else -> {
                    val close = findTagEnd(text, open)
                    if (close < 0) throw DataException("MAX_PREFS_XML_INVALID")
                    val selfClosing = text[close - 1] == '/'
                    val header = text.substring(open + 1, if (selfClosing) close - 1 else close)
                    val name = header.substringBefore(' ').lowercase()
                    val attributes = parseAttributes(header.substringAfter(' ', ""))
                    if (selfClosing) {
                        val key = attributes["name"]
                        val value = attributes["value"]?.toLongOrNull()
                        if (depth == 1 && name in NUMERIC_TAGS && key != null && value != null) {
                            records += PrefRecord(key, value)
                        }
                    } else {
                        depth++
                        if (depth == 1 && name != "map") return emptyList()
                        if (depth == 2) {
                            field = if (name == "string") attributes["name"] else null
                            buffer.clear()
                        }
                    }
                    index = close + 1
                }
            }
        }
        return records
    }

    private val NUMERIC_TAGS = setOf("int", "long")

    private fun appendText(
        source: String,
        start: Int,
        end: Int,
        buffer: StringBuilder,
    ) {
        var index = start
        while (index < end) {
            val character = source[index]
            if (character != '&') {
                buffer.append(character)
                index++
                continue
            }
            val semi = source.indexOf(';', index + 1)
            if (semi < 0 || semi >= end) throw DataException("MAX_PREFS_XML_INVALID")
            buffer.append(decodeEntity(source.substring(index + 1, semi)))
            index = semi + 1
        }
    }

    private fun decodeEntity(entity: String): Char = when {
        entity == "amp" -> '&'

        entity == "lt" -> '<'

        entity == "gt" -> '>'

        entity == "quot" -> '"'

        entity == "apos" -> '\''

        entity.length > 2 && (entity[0] == '#' && (entity[1] == 'x' || entity[1] == 'X')) ->
            entity.substring(2).toIntOrNull(16)?.toChar() ?: throw DataException("MAX_PREFS_XML_INVALID")

        entity.length > 1 && entity[0] == '#' ->
            entity.substring(1).toIntOrNull()?.toChar() ?: throw DataException("MAX_PREFS_XML_INVALID")

        else -> throw DataException("MAX_PREFS_XML_INVALID")
    }

    private fun findTagEnd(
        text: String,
        open: Int,
    ): Int {
        var quote: Char? = null
        for (index in open + 1 until text.length) {
            val character = text[index]
            if (quote != null) {
                if (character == quote) quote = null
            } else {
                when (character) {
                    '"', '\'' -> quote = character
                    '>' -> return index
                }
            }
        }
        return -1
    }

    private fun requireEnd(
        text: String,
        marker: String,
        from: Int,
    ): Int {
        val end = text.indexOf(marker, from)
        if (end < 0) throw DataException("MAX_PREFS_XML_INVALID")
        return end
    }

    private fun parseAttributes(source: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < source.length) {
            while (index < source.length && source[index].isWhitespace()) index++
            if (index >= source.length) break
            val equals = source.indexOf('=', index)
            if (equals < 0) break
            val name = source.substring(index, equals).trim()
            var cursor = equals + 1
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
            if (cursor >= source.length) break
            val quote = source[cursor]
            if (quote != '"' && quote != '\'') break
            val end = source.indexOf(quote, cursor + 1)
            if (end < 0) throw DataException("MAX_PREFS_XML_INVALID")
            val value = StringBuilder()
            appendText(source, cursor + 1, end, value)
            result[name] = value.toString()
            index = end + 1
        }
        return result
    }
}
