package co.jaspero.remo.tv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * A tiny protobuf encoder/decoder: the TV protocol only needs varints, strings, bytes and nested
 * messages, so this replaces the protobuf runtime and code generation.
 */
class ProtoWriter {
    private val buf = ByteArrayOutputStream()

    fun int(field: Int, value: Long): ProtoWriter = apply {
        tag(field, 0)
        rawVarint(value)
    }

    fun int(field: Int, value: Int): ProtoWriter = int(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = int(field, if (value) 1L else 0L)

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        tag(field, 2)
        rawVarint(value.size.toLong())
        buf.write(value)
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(block).toByteArray())

    fun toByteArray(): ByteArray = buf.toByteArray()

    private fun tag(field: Int, wireType: Int) = rawVarint(((field shl 3) or wireType).toLong())

    private fun rawVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                buf.write(v.toInt())
                return
            }
            buf.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    companion object {
        /** A message prefixed with its varint length, as both TV ports frame them. */
        fun frame(payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(payload.size + 5)
            var v = payload.size.toLong()
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
            out.write(payload)
            return out.toByteArray()
        }
    }
}

fun proto(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(block).toByteArray()

/** A decoded message: field number to the values seen (Long for varints/fixed, ByteArray for length-delimited). */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {
    fun has(field: Int) = fields.containsKey(field)
    fun long(field: Int): Long = fields[field]?.lastOrNull() as? Long ?: 0L
    fun int(field: Int): Int = long(field).toInt()
    fun bool(field: Int): Boolean = long(field) != 0L
    fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray
    fun string(field: Int): String = bytes(field)?.toString(Charsets.UTF_8) ?: ""
    fun message(field: Int): ProtoMessage? = bytes(field)?.let(::parse)
    fun messages(field: Int): List<ProtoMessage> = fields[field].orEmpty().filterIsInstance<ByteArray>().map(::parse)

    override fun toString(): String = fields.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
        "$k=" + v.joinToString(",") { if (it is ByteArray) "[${it.size}b]" else it.toString() }
    }

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IOException("Truncated varint")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IOException("Varint too long")
                }
            }
            while (pos < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 7).toInt()) {
                    0 -> varint()
                    1 -> {
                        if (pos + 8 > data.size) throw IOException("Truncated fixed64")
                        var v = 0L
                        for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                        pos += 8
                        v
                    }
                    2 -> {
                        val len = varint().toInt()
                        if (len < 0 || pos + len > data.size) throw IOException("Truncated field")
                        data.copyOfRange(pos, pos + len).also { pos += len }
                    }
                    5 -> {
                        if (pos + 4 > data.size) throw IOException("Truncated fixed32")
                        var v = 0L
                        for (i in 0 until 4) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                        pos += 4
                        v
                    }
                    else -> throw IOException("Unsupported wire type in key $key")
                }
                fields.getOrPut(field) { ArrayList(1) }.add(value)
            }
            return ProtoMessage(fields)
        }

        /** Reads one varint-length-prefixed message. Throws EOFException when the stream ends. */
        fun readDelimited(input: InputStream): ProtoMessage {
            var len = 0
            var shift = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("Connection closed by the TV")
                len = len or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 28) throw IOException("Message length too long")
            }
            if (len > 1 shl 20) throw IOException("Message too large: $len")
            val data = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = input.read(data, read, len - read)
                if (n < 0) throw EOFException("Connection closed by the TV")
                read += n
            }
            return parse(data)
        }
    }
}
