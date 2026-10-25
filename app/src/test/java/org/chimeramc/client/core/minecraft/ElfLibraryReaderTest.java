package org.chimeramc.client.core.minecraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pins the ELF dynamic-section reader (item G3, the multi-{@code .so} half).
 *
 * <p>The tests build real 64-bit and 32-bit ELF images (program headers, a PT_LOAD, a PT_DYNAMIC and
 * a string table) rather than mocking the reader, because the whole point of the class is that it
 * parses the actual file format. A synthetic image with the same layout as a real shared object is
 * what proves the offsets.
 */
public class ElfLibraryReaderTest {

    // --- A minimal but real ELF64 image ---------------------------------------------------------

    private static final long EHDR_SIZE = 64;
    private static final long PHDR_SIZE = 56;
    private static final int PHNUM = 2; // PT_LOAD + PT_DYNAMIC

    /**
     * Builds a 64-bit little-endian ELF with one PT_LOAD covering the whole file and one
     * PT_DYNAMIC whose entries reference {@code soname} and {@code needed}.
     *
     * <p>Layout: ehdr | 2 phdrs | .dynstr | .dynamic. The PT_LOAD maps file offset 0 to vaddr 0, so
     * DT_STRTAB's vaddr is just its file offset — the same identity mapping a real PIE uses at its
     * load bias of 0.
     */
    private static byte[] elf64(String soname, List<String> needed) {
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int sonameOff = -1;
        List<Integer> neededOffs = new ArrayList<>();
        // The first string is the empty one at offset 0.
        strings.write(0);
        if (soname != null) {
            sonameOff = strings.size();
            writeCStr(strings, soname);
        }
        for (String name : needed) {
            neededOffs.add(strings.size());
            writeCStr(strings, name);
        }
        byte[] dynstr = strings.toByteArray();

        int dynEntryCount = needed.size() + (soname == null ? 0 : 1) + 2; // + STRTAB + NULL
        long dynSize = (long) dynEntryCount * 16;

        long dynstrOffset = EHDR_SIZE + PHDR_SIZE * PHNUM;
        long dynstrVaddr = dynstrOffset;
        long dynamicOffset = dynstrOffset + dynstr.length;
        long total = dynamicOffset + dynSize;

        ByteBuffer b = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        // e_ident
        b.put((byte) 0x7f).put((byte) 'E').put((byte) 'L').put((byte) 'F');
        b.put((byte) 2);   // ELFCLASS64
        b.put((byte) 1);   // ELFDATA2LSB
        b.put((byte) 1);   // EI_VERSION
        b.put(new byte[9]);
        b.putShort((short) 3);          // e_type ET_DYN
        b.putShort((short) 183);        // e_machine EM_AARCH64
        b.putInt(1);                    // e_version
        b.putLong(0);                   // e_entry
        b.putLong(0);                   // e_phoff (patched below)
        b.putLong(0);                   // e_shoff
        b.putInt(0);                    // e_flags
        b.putShort((short) EHDR_SIZE);  // e_ehsize
        b.putShort((short) PHDR_SIZE);  // e_phentsize
        b.putShort((short) PHNUM);      // e_phnum
        b.putShort((short) 0);          // e_shentsize
        b.putShort((short) 0);          // e_shnum
        b.putShort((short) 0);          // e_shstrndx

        // PT_LOAD: covers the whole file, vaddr == offset.
        putPhdr64(b, 1, 0, 0, total, total);
        // PT_DYNAMIC.
        putPhdr64(b, 2, dynamicOffset, dynamicOffset, dynSize, dynSize);

        b.position((int) dynstrOffset);
        b.put(dynstr);

        b.position((int) dynamicOffset);
        for (int off : neededOffs) putDyn64(b, 1, off);          // DT_NEEDED
        if (sonameOff >= 0) putDyn64(b, 14, sonameOff);          // DT_SONAME
        putDyn64(b, 5, dynstrVaddr);                             // DT_STRTAB
        putDyn64(b, 0, 0);                                       // DT_NULL

        // Patch e_phoff.
        b.putLong(32, EHDR_SIZE);
        return b.array();
    }

    private static void putPhdr64(ByteBuffer b, int type, long offset, long vaddr, long filesz,
                                  long memsz) {
        b.putInt(type);
        b.putInt(4);      // p_flags R
        b.putLong(offset);
        b.putLong(vaddr);
        b.putLong(vaddr); // p_paddr
        b.putLong(filesz);
        b.putLong(memsz);
        b.putLong(8);     // p_align
    }

    private static void putDyn64(ByteBuffer b, long tag, long value) {
        b.putLong(tag);
        b.putLong(value);
    }

    private static void writeCStr(ByteArrayOutputStream out, String s) {
        for (int i = 0; i < s.length(); i++) out.write(s.charAt(i));
        out.write(0);
    }

    private static File temp(byte[] content, String name) throws IOException {
        File file = File.createTempFile(name, ".so");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
        return file;
    }

    // --- Tests ----------------------------------------------------------------------------------

    @Test
    public void readsTheSonameAndEveryNeededEntry() throws Exception {
        File lib = temp(elf64("libhelper.so", Arrays.asList("liblog.so", "libc.so")), "libhelper");
        ElfLibraryReader.DynamicInfo info = ElfLibraryReader.read(lib);
        assertNotNull(info);
        assertEquals("libhelper.so", info.getSoname());
        assertEquals(Arrays.asList("liblog.so", "libc.so"), info.getNeeded());
    }

    @Test
    public void aLibraryWithNoSonameStillReportsItsDependencies() throws Exception {
        File lib = temp(elf64(null, Arrays.asList("libonly.so")), "libnosoname");
        ElfLibraryReader.DynamicInfo info = ElfLibraryReader.read(lib);
        assertNotNull(info);
        assertNull(info.getSoname());
        assertEquals(Arrays.asList("libonly.so"), info.getNeeded());
    }

    @Test
    public void aLibraryWithNoDependenciesReportsAnEmptyList() throws Exception {
        File lib = temp(elf64("libstandalone.so", new ArrayList<>()), "libstandalone");
        ElfLibraryReader.DynamicInfo info = ElfLibraryReader.read(lib);
        assertNotNull(info);
        assertEquals("libstandalone.so", info.getSoname());
        assertTrue(info.getNeeded().isEmpty());
    }

    @Test
    public void aFileThatIsNotAnElfReadsAsNull() throws Exception {
        File notElf = File.createTempFile("notelf", ".so");
        notElf.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(notElf)) {
            out.write("definitely not an ELF file".getBytes("UTF-8"));
        }
        assertNull(ElfLibraryReader.read(notElf));
    }

    @Test
    public void aTruncatedElfReadsAsNullRatherThanThrowing() throws Exception {
        byte[] full = elf64("libx.so", Arrays.asList("liby.so"));
        byte[] truncated = Arrays.copyOf(full, 20);
        File file = temp(truncated, "truncated");
        assertNull(ElfLibraryReader.read(file));
    }

    @Test
    public void aMissingFileReadsAsNull() {
        assertNull(ElfLibraryReader.read(new File("/nonexistent/libmissing.so")));
    }
}
