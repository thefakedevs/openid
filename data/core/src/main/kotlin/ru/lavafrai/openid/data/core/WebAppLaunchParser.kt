package ru.lavafrai.openid.data.core

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import ru.lavafrai.openid.models.max.WebAppLaunch

object WebAppLaunchParser {
    fun parse(
        url: String,
        accountId: Long,
        webUserAgent: String,
    ): WebAppLaunch {
        requireData(url.length <= 200_000 && url.none { it.code < 32 || it.code == 127 }, "WEBAPP_URL_INVALID")
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            throw DataException("WEBAPP_URL_INVALID")
        }
        requireData(
            uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("digital-id.max.ru", ignoreCase = true) &&
                uri.port in setOf(-1, 443) && uri.rawUserInfo == null,
            "WEBAPP_ORIGIN_UNEXPECTED",
        )
        val webAppData = AccountCodec.requireNonEmptyString(parseParameters(uri.rawFragment ?: "")["WebAppData"])
        requireData(webAppData.all { it.code in 32..126 }, "WEBAPPDATA_MISSING_OR_BAD_HEADER")
        val fields = parseParameters(webAppData)
        val authDate = fields["auth_date"]?.toLongOrNull() ?: throw DataException("WEBAPPDATA_REQUIRED_FIELDS_MISSING")
        val userJson = fields["user"] ?: throw DataException("WEBAPPDATA_REQUIRED_FIELDS_MISSING")
        val user = StrictJson.obj(StrictJson.parse(userJson.toByteArray(Charsets.UTF_8)))
        requireData(StrictJson.integer(user["id"]) == accountId && accountId > 0, "WEBAPP_USER_ACCOUNT_MISMATCH")
        requireData(
            authDate > 0 && !fields["query_id"].isNullOrEmpty() && !fields["hash"].isNullOrEmpty(),
            "WEBAPPDATA_REQUIRED_FIELDS_MISSING",
        )
        return WebAppLaunch(url, webAppData, accountId, authDate, webUserAgent)
    }

    private fun parseParameters(text: String): Map<String, String> {
        val values = text.split('&')
        requireData(values.size <= 128, "WEBAPPDATA_PARSE_FAILED")
        val result = linkedMapOf<String, String>()
        for (part in values) {
            if (part.isEmpty()) continue
            val field = part.split('=', limit = 2)
            val key = decodeParameter(field[0])
            requireData(!result.containsKey(key), "WEBAPPDATA_DUPLICATE_PARAMETER")
            result[key] = decodeParameter(field.getOrElse(1) { "" })
        }
        return result
    }

    private fun decodeParameter(value: String): String = try {
        // URLDecoder is lenient with malformed UTF-8: reject it using our strict decoder afterwards.
        val bytes = ByteArrayOutputStream()
        var position = 0
        while (position < value.length) {
            val character = value[position++]
            when (character) {
                '+' -> bytes.write(32)

                '%' -> {
                    requireData(position + 2 <= value.length, "WEBAPPDATA_PARSE_FAILED")
                    val digits = value.substring(position, position + 2)
                    requireData(
                        digits.all {
                            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
                        },
                        "WEBAPPDATA_PARSE_FAILED",
                    )
                    val decodedByte = digits.toInt(16)
                    bytes.write(decodedByte)
                    position += 2
                }

                else -> {
                    requireData(character.code < 128, "WEBAPPDATA_PARSE_FAILED")
                    bytes.write(character.code)
                }
            }
        }
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    } catch (_: Exception) {
        throw DataException("WEBAPPDATA_PARSE_FAILED")
    }
}
