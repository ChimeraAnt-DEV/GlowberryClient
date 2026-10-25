package org.chimeramc.client.core.minecraft

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the parts of an ELF shared library the loader needs beyond the header: its `DT_SONAME` and
 * the list of `DT_NEEDED` sonames (item G3).
 *
 * Why this is needed: `System.load(<absolute path>)` loads one library, but Android's linker
 * resolves that library's `DT_NEEDED` entries by soname against the already-loaded set and the
 * platform search path — **not** against the directory the library sits in. A mod that ships
 * `libentry.so` plus `libhelper.so` therefore fails at `dlopen` with a bare
 * `library "libhelper.so" not found` even though the file is right there in the package, unless the
 * launcher loads the sibling first. To load siblings in a working order the launcher has to know the
 * dependency edges, and that is what this reader produces.
 *
 * Pure JVM (no Android runtime), so it is unit-tested against synthetic ELF images.
 */
object ElfLibraryReader {

    /** The dependency facts one library declares. */
    data class DynamicInfo(
        /** `DT_SONAME`, or null when the library declares none. */
        val soname: String?,
        /** Every `DT_NEEDED` soname, in the order the dynamic section lists them. */
        val needed: List<String>
    )

    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2

    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L
    private const val DT_SONAME = 14L

    /** A guard against a corrupt header pointing at an enormous table. */
    private const val MAX_ENTRIES = 4096

    /**
     * Reads the dynamic facts of [file], or null when it is not a parseable ELF or declares no
     * dynamic section.
     *
     * A null result is not an error here: a statically linked or malformed library simply has no
     * dependency edges to contribute, and the caller falls back to loading it in file order.
     */
    @JvmStatic
    fun read(file: File): DynamicInfo? {
        val header = ElfHeaderValidator.read(file) ?: return null
        val is64 = header.is64Bit
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val phoff: Long
                val phentsize: Int
                val phnum: Int
                if (is64) {
                    phoff = readU32(raf, 32L).toLong() or (readU32(raf, 36L).toLong() shl 32)
                    phentsize = readU16(raf, 54)
                    phnum = readU16(raf, 56)
                } else {
                    phoff = readU32(raf, 28L).toLong()
                    phentsize = readU16(raf, 42)
                    phnum = readU16(raf, 44)
                }
                if (phoff <= 0L || phentsize <= 0 || phnum <= 0) return null
                val count = minOf(phnum, MAX_ENTRIES)

                var dynamicOffset = -1L
                var dynamicSize = -1L
                // The virtual-address -> file-offset map, so DT_STRTAB's vaddr can be translated.
                val loads = ArrayList<LongArray>() // {vaddr, offset, filesz}
                for (i in 0 until count) {
                    val base = phoff + i.toLong() * phentsize
                    val type = readU32(raf, base)
                    if (is64) {
                        val offset = readU64(raf, base + 8)
                        val vaddr = readU64(raf, base + 16)
                        val filesz = readU64(raf, base + 32)
                        when (type) {
                            PT_DYNAMIC -> { dynamicOffset = offset; dynamicSize = filesz }
                            PT_LOAD -> loads.add(longArrayOf(vaddr, offset, filesz))
                        }
                    } else {
                        val offset = readU32(raf, base + 4).toLong()
                        val vaddr = readU32(raf, base + 8).toLong()
                        val filesz = readU32(raf, base + 16).toLong()
                        when (type) {
                            PT_DYNAMIC -> { dynamicOffset = offset; dynamicSize = filesz }
                            PT_LOAD -> loads.add(longArrayOf(vaddr, offset, filesz))
                        }
                    }
                }
                if (dynamicOffset < 0L || dynamicSize <= 0L) return null

                val entrySize = if (is64) 16 else 8
                val entries = minOf((dynamicSize / entrySize).toInt(), MAX_ENTRIES)

                var strtabVaddr = -1L
                val neededOffsets = ArrayList<Long>()
                var sonameOffset = -1L
                for (i in 0 until entries) {
                    val at = dynamicOffset + i.toLong() * entrySize
                    val tag = if (is64) readU64(raf, at) else readU32(raf, at).toLong()
                    val value = if (is64) readU64(raf, at + 8) else readU32(raf, at + 4).toLong()
                    when (tag) {
                        DT_NULL -> break
                        DT_STRTAB -> strtabVaddr = value
                        DT_NEEDED -> neededOffsets.add(value)
                        DT_SONAME -> sonameOffset = value
                    }
                }
                if (strtabVaddr < 0L) return null
                val strtabOffset = vaddrToOffset(strtabVaddr, loads) ?: return null

                val needed = neededOffsets.mapNotNull { offset ->
                    readCString(raf, strtabOffset + offset)
                }
                val soname = if (sonameOffset >= 0L) {
                    readCString(raf, strtabOffset + sonameOffset)
                } else {
                    null
                }
                DynamicInfo(soname, needed)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Translates a virtual address to a file offset through the PT_LOAD segments. */
    private fun vaddrToOffset(vaddr: Long, loads: List<LongArray>): Long? {
        for (seg in loads) {
            val (segVaddr, segOffset, segFilesz) = Triple(seg[0], seg[1], seg[2])
            if (vaddr >= segVaddr && vaddr < segVaddr + segFilesz) {
                return segOffset + (vaddr - segVaddr)
            }
        }
        return null
    }

    /** Reads a NUL-terminated ASCII string at [offset], capped so a corrupt table cannot run away. */
    private fun readCString(raf: RandomAccessFile, offset: Long): String? {
        if (offset < 0L || offset >= raf.length()) return null
        raf.seek(offset)
        val out = StringBuilder()
        while (out.length < 512) {
            val b = raf.read()
            if (b < 0 || b == 0) break
            out.append(b.toChar())
        }
        return if (out.isEmpty()) null else out.toString()
    }

    private fun readU16(raf: RandomAccessFile, offset: Long): Int {
        raf.seek(offset)
        val b0 = raf.readUnsignedByte()
        val b1 = raf.readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun readU32(raf: RandomAccessFile, offset: Long): Int {
        raf.seek(offset)
        var value = 0
        for (i in 0 until 4) value = value or (raf.readUnsignedByte() shl (8 * i))
        return value
    }

    private fun readU64(raf: RandomAccessFile, offset: Long): Long {
        raf.seek(offset)
        var value = 0L
        for (i in 0 until 8) value = value or (raf.readUnsignedByte().toLong() shl (8 * i))
        return value
    }
}
