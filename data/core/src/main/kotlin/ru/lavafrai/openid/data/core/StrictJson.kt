package ru.lavafrai.openid.data.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Strict JSON with duplicate-key rejection and bounded depth/size. Numbers keep their wire type. */
object StrictJson {
    data class Number(val raw: String) {
        val integral: Boolean get() = raw.none { it == '.' || it == 'e' || it == 'E' }

        fun long(): Long? = if (integral) raw.toLongOrNull() else null

        fun double(): Double = raw.toDouble().also { requireData(it.isFinite(), "JSON_NONFINITE_NUMBER") }
    }

    fun parse(bytes: ByteArray): Any? {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw DataException("JSON_INVALID")
        }
        return Reader(text).read()
    }

    fun encode(value: Any?): ByteArray = buildString { write(value) }.toByteArray(Charsets.UTF_8)

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: throw DataException("JSON_OBJECT_REQUIRED")

    fun integer(value: Any?): Long = (value as? Number)?.long() ?: throw DataException("JSON_INTEGER_REQUIRED")

    fun decimal(value: Any?): Double = (value as? Number)?.double() ?: throw DataException("JSON_NUMBER_REQUIRED")

    private fun StringBuilder.write(value: Any?) {
        when (value) {
            null -> append("null")

            is String -> {
                append('"')
                value.forEach { character ->
                    when (character) {
                        '"' -> append("\\\"")

                        '\\' -> append("\\\\")

                        else ->
                            if (character.code < 32) {
                                append("\\u" + character.code.toString(16).padStart(4, '0'))
                            } else {
                                append(character)
                            }
                    }
                }
                append('"')
            }

            is Boolean, is Long, is Int -> append(value)

            is Double -> {
                requireData(value.isFinite(), "JSON_NONFINITE_NUMBER")
                append(value)
            }

            is Number -> append(value.raw)

            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        append(',')
                    }
                    write(entry.key as String)
                    append(':')
                    write(entry.value)
                }
                append('}')
            }

            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) {
                        append(',')
                    }
                    write(item)
                }
                append(']')
            }

            else -> throw DataException("JSON_VALUE_UNSUPPORTED")
        }
    }

    private class Reader(private val text: String) {
        private var position = 0
        private var nodeCount = 0

        fun read(): Any? {
            val value = value(0)
            skipWhitespace()
            requireData(position == text.length, "JSON_INVALID")
            return value
        }

        private fun skipWhitespace() {
            while (position < text.length && text[position] in " \t\r\n") {
                position++
            }
        }

        private fun consume(character: Char): Boolean {
            skipWhitespace()
            val matches = position < text.length && text[position] == character
            if (matches) {
                position++
            }
            return matches
        }

        private fun value(depth: Int): Any? {
            requireData(depth <= 32 && ++nodeCount <= 500_000, "JSON_TOO_COMPLEX")
            skipWhitespace()
            requireData(position < text.length, "JSON_INVALID")
            return when (text[position]) {
                '{' -> {
                    position++
                    val result = linkedMapOf<String, Any?>()
                    if (!consume('}')) {
                        do {
                            skipWhitespace()
                            requireData(position < text.length && text[position] == '"', "JSON_INVALID")
                            val key = string()
                            requireData(!result.containsKey(key), "JSON_DUPLICATE_FIELD")
                            requireData(consume(':'), "JSON_INVALID")
                            result[key] = value(depth + 1)
                        } while (consume(','))
                    } else {
                        return result
                    }
                    requireData(consume('}'), "JSON_INVALID")
                    result
                }

                '[' -> {
                    position++
                    val result = mutableListOf<Any?>()
                    if (!consume(']')) {
                        do {
                            result += value(depth + 1)
                        } while (consume(','))
                    } else {
                        return result
                    }
                    requireData(consume(']'), "JSON_INVALID")
                    result
                }

                '"' -> string()

                't' -> readLiteral("true", true)

                'f' -> readLiteral("false", false)

                'n' -> readLiteral("null", null)

                '-', in '0'..'9' -> readNumber()

                else -> throw DataException("JSON_INVALID")
            }
        }

        private fun readLiteral(
            word: String,
            result: Any?,
        ): Any? {
            requireData(text.startsWith(word, position), "JSON_INVALID")
            position += word.length
            return result
        }

        private fun string(): String {
            position++
            val result = StringBuilder()
            while (position < text.length) {
                val character = text[position++]
                if (character == '"') {
                    return result.toString().also { validateUnicode(it) }
                }
                requireData(character.code >= 32, "JSON_INVALID")
                if (character != '\\') {
                    result.append(character)
                } else {
                    requireData(position < text.length, "JSON_INVALID")
                    result.append(
                        when (text[position++]) {
                            '"' -> '"'

                            '\\' -> '\\'

                            '/' -> '/'

                            'b' -> '\b'

                            'f' -> '\u000c'

                            'n' -> '\n'

                            'r' -> '\r'

                            't' -> '\t'

                            'u' -> {
                                requireData(position + 4 <= text.length, "JSON_INVALID")
                                val hexDigits = text.substring(position, position + 4)
                                requireData(
                                    hexDigits.all {
                                        it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
                                    },
                                    "JSON_INVALID",
                                )
                                val code = hexDigits.toInt(16)
                                position += 4
                                code.toChar()
                            }

                            else -> throw DataException("JSON_INVALID")
                        },
                    )
                }
            }
            throw DataException("JSON_INVALID")
        }

        private fun validateUnicode(value: String) {
            var index = 0
            while (index < value.length) {
                val character = value[index++]
                if (character.isHighSurrogate()) {
                    requireData(index < value.length && value[index++].isLowSurrogate(), "JSON_INVALID")
                } else {
                    requireData(!character.isLowSurrogate(), "JSON_INVALID")
                }
            }
        }

        private fun readNumber(): Number {
            val start = position
            if (text[position] == '-') {
                position++
            }
            requireData(position < text.length, "JSON_INVALID")
            if (text[position] == '0') {
                position++
            } else {
                requireData(text[position] in '1'..'9', "JSON_INVALID")
                while (position < text.length && text[position] in '0'..'9') {
                    position++
                }
            }
            if (position < text.length && text[position] == '.') {
                position++
                readDigits()
            }
            if (position < text.length && text[position] in "eE") {
                position++
                if (position < text.length && text[position] in "+-") {
                    position++
                }
                readDigits()
            }
            return Number(text.substring(start, position)).also { it.double() }
        }

        private fun readDigits() {
            val start = position
            while (position < text.length && text[position] in '0'..'9') {
                position++
            }
            requireData(position > start, "JSON_INVALID")
        }
    }
}
