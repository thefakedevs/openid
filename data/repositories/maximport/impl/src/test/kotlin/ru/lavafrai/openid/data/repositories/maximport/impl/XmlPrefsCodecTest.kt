package ru.lavafrai.openid.data.repositories.maximport.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import ru.lavafrai.openid.data.core.DataException

class XmlPrefsCodecTest {
    @Test
    fun `reads string int and long entries`() {
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="auth.token">synthetic-token</string>
                <int name="digitalid-botid" value="42" />
                <long name="digitalid-botid-old" value="7" />
                <boolean name="other" value="true" />
            </map>
        """.trimmed()
        val records = XmlPrefsCodec.parse(xml.toByteArray())
        assertEquals(
            listOf(
                PrefRecord("auth.token", "synthetic-token"),
                PrefRecord("digitalid-botid", 42L),
                PrefRecord("digitalid-botid-old", 7L),
            ),
            records,
        )
    }

    @Test
    fun `decodes entities and cdata`() {
        val xml = "<map><string name=\"k\">a&lt;b&gt;c&amp;d&#65;<![CDATA[x<y>]]></string></map>"
        val records = XmlPrefsCodec.parse(xml.toByteArray())
        assertEquals(1, records.size)
        assertEquals("a<b>c&dAx<y>", records[0].value)
    }

    @Test
    fun `a non-map root yields no records`() {
        val records = XmlPrefsCodec.parse("<other><string name=\"auth.token\">x</string></other>".toByteArray())
        assertEquals(emptyList(), records)
    }

    @Test
    fun `doctype fails the file`() {
        val xml = "<!DOCTYPE map SYSTEM \"x\"><map><string name=\"k\">v</string></map>"
        assertFailsWith<DataException> { XmlPrefsCodec.parse(xml.toByteArray()) }
    }

    @Test
    fun `unknown entity fails the file`() {
        val xml = "<map><string name=\"k\">v&nope;</string></map>"
        assertFailsWith<DataException> { XmlPrefsCodec.parse(xml.toByteArray()) }
    }

    @Test
    fun `quotes inside attributes are supported`() {
        val xml = "<map><string name='auth.tok&#x65;n'>v&gt;</string></map>"
        val records = XmlPrefsCodec.parse(xml.toByteArray())
        assertEquals(PrefRecord("auth.token", "v>"), records.single())
    }

    private fun String.trimmed(): String = trimIndent().trim()
}
