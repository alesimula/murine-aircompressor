/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.compress.tar;

import java.io.IOException;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * A single entry of a USTAR (POSIX.1-1988) archive. Knows how to read and
 * write its own 512-byte header; the streams only handle framing.
 */
public class TarEntry
{
    public static final char TYPE_FILE = '0';
    public static final char TYPE_DIRECTORY = '5';

    // Largest value an 11-octal-digit field can hold (8 GiB - 1); bigger sizes
    // are written through a PAX record and read through PAX or GNU base-256.
    static final long MAX_OCTAL = 077777777777L;

    private String name;
    private long size;
    private long modTime = System.currentTimeMillis();
    private int mode;
    private char type;

    public TarEntry(String name, long size)
    {
        this.name = name;
        this.type = name.endsWith("/") ? TYPE_DIRECTORY : TYPE_FILE;
        this.mode = type == TYPE_DIRECTORY ? 0755 : 0644;
        setSize(size);
    }

    /**
     * @param mode unix permission bits, e.g. 0644
     */
    public TarEntry(String name, long size, int mode)
    {
        this(name, size);
        this.mode = mode;
    }

    /**
     * Parses a header block, verifying its checksum.
     */
    TarEntry(byte[] header)
            throws IOException
    {
        long stored = parseOctal(header, 148, 8);
        if (stored != checksum(header, false) && stored != checksum(header, true)) {
            throw new IOException("Corrupt tar header (bad checksum)");
        }
        String prefix = parseString(header, 345, 155);
        name = prefix.isEmpty() ? parseString(header, 0, 100) : prefix + "/" + parseString(header, 0, 100);
        mode = (int) parseOctal(header, 100, 8);
        size = parseOctal(header, 124, 12);
        modTime = parseOctal(header, 136, 12) * 1000;
        type = header[156] == 0 ? TYPE_FILE : (char) header[156];
    }

    /**
     * Fills a 512-byte block with this entry's header.
     */
    void writeHeader(byte[] block)
    {
        Arrays.fill(block, (byte) 0);
        String suffix = name;
        if (suffix.length() > 100) {
            // ustar name split: prefix field holds the leading directories
            int slash = splitPoint(name);
            if (slash < 0) {
                throw new IllegalArgumentException("Entry name too long for ustar: " + name);
            }
            writeString(block, 345, 155, name.substring(0, slash));
            suffix = name.substring(slash + 1);
        }
        writeString(block, 0, 100, suffix);
        writeOctal(block, 100, 8, mode);
        writeOctal(block, 108, 8, 0); // uid
        writeOctal(block, 116, 8, 0); // gid
        // Oversized entries carry their real size in a PAX record; the header field
        // is zeroed like bsdtar does, and readers take the PAX value
        writeOctal(block, 124, 12, size > MAX_OCTAL ? 0 : size);
        writeOctal(block, 136, 12, modTime / 1000);
        block[156] = (byte) type;
        writeString(block, 257, 8, "ustar\000" + "00");
        // checksum is computed with its own field read as spaces
        Arrays.fill(block, 148, 156, (byte) ' ');
        long sum = checksum(block, false);
        writeOctal(block, 148, 7, sum); // 6 digits + NUL
        block[155] = ' '; // historic terminator: 6 digits, NUL, space
    }

    public String getName()
    {
        return name;
    }

    public long getSize()
    {
        return size;
    }

    public TarEntry setSize(long size)
    {
        if (size < 0) {
            throw new IllegalArgumentException("Invalid entry size: " + size);
        }
        this.size = size;
        return this;
    }

    /**
     * Modification time in milliseconds; stored with second precision.
     */
    public long getModTime()
    {
        return modTime;
    }

    public TarEntry setModTime(long millis)
    {
        this.modTime = millis;
        return this;
    }

    /**
     * Unix permission bits, e.g. 0644.
     */
    public int getMode()
    {
        return mode;
    }

    public TarEntry setMode(int mode)
    {
        this.mode = mode;
        return this;
    }

    public boolean isDirectory()
    {
        return type == TYPE_DIRECTORY;
    }

    public char getType()
    {
        return type;
    }

    /**
     * True when name and size fit plain ustar fields; otherwise a PAX header is needed.
     */
    boolean fitsUstar()
    {
        return size <= MAX_OCTAL && (name.getBytes(US_ASCII).length <= 100 || splitPoint(name) >= 0);
    }

    TarEntry setType(char type)
    {
        this.type = type;
        return this;
    }

    TarEntry setName(String name)
    {
        this.name = name;
        return this;
    }

    // ustar name split: index of the '/' whose prefix and suffix fit their fields, or -1
    private static int splitPoint(String name)
    {
        for (int i = name.indexOf('/'); i >= 0; i = name.indexOf('/', i + 1)) {
            if (i <= 155 && name.length() - i - 1 <= 100) {
                return i;
            }
        }
        return -1;
    }

    private static long checksum(byte[] header, boolean signed)
    {
        long sum = 0;
        for (int i = 0; i < 512; i++) {
            byte value = (i >= 148 && i < 156) ? (byte) ' ' : header[i];
            sum += signed ? value : value & 0xFF;
        }
        return sum;
    }

    private static long parseOctal(byte[] block, int offset, int length)
            throws IOException
    {
        if ((block[offset] & 0x80) != 0) {
            // GNU base-256: high bit marks a big-endian binary field
            long value = block[offset] & 0x7F;
            for (int i = offset + 1; i < offset + length; i++) {
                value = (value << 8) | (block[i] & 0xFF);
            }
            return value;
        }
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            int b = block[i];
            if (b == 0 || b == ' ') {
                if (value > 0) {
                    break;
                }
                continue; // leading padding
            }
            if (b < '0' || b > '7') {
                throw new IOException("Corrupt tar header (bad octal digit)");
            }
            value = (value << 3) + (b - '0');
        }
        return value;
    }

    private static String parseString(byte[] block, int offset, int length)
    {
        int end = offset;
        while (end < offset + length && block[end] != 0) {
            end++;
        }
        return new String(block, offset, end - offset, US_ASCII);
    }

    private static void writeOctal(byte[] block, int offset, int length, long value)
    {
        // (length - 1) zero-padded digits, NUL terminated
        for (int i = offset + length - 2; i >= offset; i--) {
            block[i] = (byte) ('0' + (value & 7));
            value >>>= 3;
        }
    }

    private static void writeString(byte[] block, int offset, int length, String value)
    {
        byte[] bytes = value.getBytes(US_ASCII);
        if (bytes.length > length) {
            throw new IllegalArgumentException("Value too long for tar field: " + value);
        }
        System.arraycopy(bytes, 0, block, offset, bytes.length);
    }
}
