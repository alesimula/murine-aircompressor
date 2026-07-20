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
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

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
     * Parses a header block, verifying its checksum. {@code nameCharset} decodes the ustar
     * name fields, which carry no encoding of their own.
     */
    TarEntry(byte[] header, Charset nameCharset)
            throws IOException
    {
        long stored = parseOctal(header, 148, 8);
        if (stored != checksum(header, false) && stored != checksum(header, true)) {
            throw new IOException("Corrupt tar header (bad checksum)");
        }
        String prefix = parseString(header, 345, 155, nameCharset);
        String suffix = parseString(header, 0, 100, nameCharset);
        name = prefix.isEmpty() ? suffix : prefix + "/" + suffix;
        mode = (int) parseOctalOrBinary(header, 100, 8);
        size = parseOctalOrBinary(header, 124, 12);
        if (size < 0) {
            throw new IOException("Corrupt tar header (negative entry size)");
        }
        modTime = parseOctalOrBinary(header, 136, 12) * 1000;
        type = header[156] == 0 ? TYPE_FILE : (char) header[156];
    }

    /**
     * Fills a 512-byte block with this entry's header, encoding the name with {@code nameCharset}.
     * {@code lossyName} substitutes characters the charset cannot represent instead of rejecting
     * them, and is for headers whose true name is carried in an accompanying PAX record.
     */
    void writeHeader(byte[] block, Charset nameCharset, boolean lossyName)
    {
        Arrays.fill(block, (byte) 0);
        String suffix = name;
        if (encodedLength(suffix, nameCharset) > 100) {
            // ustar name split: prefix field holds the leading directories
            int slash = splitPoint(name, nameCharset);
            if (slash < 0) {
                throw new IllegalArgumentException("Entry name too long for ustar: " + name);
            }
            writeString(block, 345, 155, name.substring(0, slash), nameCharset, lossyName);
            suffix = name.substring(slash + 1);
        }
        writeString(block, 0, 100, suffix, nameCharset, lossyName);
        writeOctal(block, 100, 8, mode);
        writeOctal(block, 108, 8, 0); // uid
        writeOctal(block, 116, 8, 0); // gid
        // Oversized entries carry their real size in a PAX record; the header field
        // is zeroed like bsdtar does, and readers take the PAX value
        writeOctal(block, 124, 12, size > MAX_OCTAL ? 0 : size);
        writeOctal(block, 136, 12, modTime / 1000);
        block[156] = (byte) type;
        writeString(block, 257, 8, "ustar\000" + "00", US_ASCII, false);
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

    /**
     * Resolves this entry's name against {@code parentPath}, rejecting names that escape it.
     * Callers extracting to disk should use this instead of {@link #getName()}: entry names come
     * from the archive and may contain "../" or be absolute.
     *
     * @throws IOException if the name would resolve outside {@code parentPath}
     */
    public Path resolveIn(Path parentPath)
            throws IOException
    {
        Path resolved = parentPath.resolve(name).normalize();
        if (!resolved.startsWith(parentPath)) {
            throw new IOException("Zip slip '" + parentPath + "' + '" + name + "' -> '" + resolved + "'");
        }
        return resolved;
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
    boolean fitsUstar(Charset nameCharset)
    {
        return size <= MAX_OCTAL && nameFitsUstar(nameCharset);
    }

    /**
     * True when the name fits the ustar name field, on its own or split across the prefix field.
     */
    boolean nameFitsUstar(Charset nameCharset)
    {
        return encodedLength(name, nameCharset) <= 100 || splitPoint(name, nameCharset) >= 0;
    }

    /**
     * True when the name is plain 7-bit ASCII, and so needs no encoding declared for it. ustar
     * leaves the character set of its fields unspecified, so a non-ASCII name is only unambiguous
     * when it is also carried in a PAX record, which POSIX.1-2001 defines as UTF-8.
     */
    boolean hasAsciiName()
    {
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
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
    private static int splitPoint(String name, Charset nameCharset)
    {
        for (int i = name.indexOf('/'); i >= 0; i = name.indexOf('/', i + 1)) {
            if (encodedLength(name.substring(0, i), nameCharset) <= 155
                    && encodedLength(name.substring(i + 1), nameCharset) <= 100) {
                return i;
            }
        }
        return -1;
    }

    // the header fields are sized in bytes, and one character can take several of them
    static int encodedLength(String value, Charset nameCharset)
    {
        return value.getBytes(nameCharset).length;
    }

    /**
     * Truncates to at most {@code maxBytes} once encoded, cutting between characters. Charsets that
     * shift state between characters are measured conservatively, so the result may be shorter than
     * it strictly has to be, never longer.
     */
    static String truncate(String value, int maxBytes, Charset nameCharset)
    {
        if (encodedLength(value, nameCharset) <= maxBytes) {
            return value;
        }
        int bytes = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width = encodedLength(new String(Character.toChars(codePoint)), nameCharset);
            if (bytes + width > maxBytes) {
                break;
            }
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    /**
     * Rejects charsets that cannot be used for ustar header fields: the fields are NUL terminated
     * and the format's own syntax (the '/' separators, the magic, the octal digits) is ASCII, so a
     * charset that renders ASCII as anything other than single-byte ASCII corrupts the header.
     */
    static Charset checkNameCharset(Charset nameCharset)
    {
        if (nameCharset == null) {
            throw new IllegalArgumentException("nameCharset is null");
        }
        byte[] probe = "A/z0.".getBytes(nameCharset);
        if (!Arrays.equals(probe, new byte[] {'A', '/', 'z', '0', '.'})) {
            throw new IllegalArgumentException("Charset does not encode ASCII as single-byte ASCII: " + nameCharset);
        }
        return nameCharset;
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

    private static boolean isOctalDigit(byte b)
    {
        return b >= '0' && b <= '7';
    }

    // GNU base-256 (high bit of the first byte) or plain octal
    private static long parseOctalOrBinary(byte[] block, int offset, int length)
            throws IOException
    {
        if ((block[offset] & 0x80) == 0) {
            return parseOctal(block, offset, length);
        }
        boolean negative = block[offset] == (byte) 0xFF;
        if (length < 9) {
            return parseBinaryLong(block, offset, length, negative);
        }
        return parseBinaryBigInteger(block, offset, length, negative);
    }

    private static long parseOctal(byte[] block, int offset, int length)
            throws IOException
    {
        if (length < 2) {
            throw new IOException("Corrupt tar header (octal field shorter than 2 bytes)");
        }
        int start = offset;
        int end = offset + length;
        if (block[start] == 0) {
            return 0; // all-NUL means "field not set"
        }
        while (start < end && block[start] == ' ') {
            start++;
        }
        // spec wants a trailing NUL or space, but some writers use it for an extra digit
        while (start < end && (block[end - 1] == 0 || block[end - 1] == ' ')) {
            end--;
        }
        long value = 0;
        for (; start < end; start++) {
            byte current = block[start];
            if (!isOctalDigit(current)) {
                throw new IOException("Corrupt tar header (bad octal digit)");
            }
            value = (value << 3) + (current - '0');
        }
        return value;
    }

    private static long parseBinaryLong(byte[] block, int offset, int length, boolean negative)
            throws IOException
    {
        if (length >= 9) {
            throw new IOException("Corrupt tar header (binary field exceeds long range)");
        }
        long value = 0;
        for (int i = 1; i < length; i++) {
            value = (value << 8) + (block[offset + i] & 0xFF);
        }
        if (negative) {
            // 2's complement
            value--;
            value ^= (1L << ((length - 1) * 8)) - 1;
        }
        return negative ? -value : value;
    }

    private static long parseBinaryBigInteger(byte[] block, int offset, int length, boolean negative)
            throws IOException
    {
        byte[] remainder = new byte[length - 1];
        System.arraycopy(block, offset + 1, remainder, 0, length - 1);
        BigInteger value = new BigInteger(remainder);
        if (negative) {
            // 2's complement
            value = value.add(BigInteger.valueOf(-1)).not();
        }
        if (value.bitLength() > 63) {
            throw new IOException("Corrupt tar header (binary field exceeds long range)");
        }
        return negative ? -value.longValue() : value.longValue();
    }

    private static String parseString(byte[] block, int offset, int length, Charset charset)
    {
        int end = offset;
        while (end < offset + length && block[end] != 0) {
            end++;
        }
        return new String(block, offset, end - offset, charset);
    }

    private static void writeOctal(byte[] block, int offset, int length, long value)
    {
        // (length - 1) zero-padded digits, NUL terminated
        for (int i = offset + length - 2; i >= offset; i--) {
            block[i] = (byte) ('0' + (value & 7));
            value >>>= 3;
        }
    }

    private static void writeString(byte[] block, int offset, int length, String value, Charset charset, boolean lossy)
    {
        byte[] bytes = encode(value, charset, lossy);
        if (bytes.length > length) {
            throw new IllegalArgumentException("Value too long for tar field: " + value);
        }
        System.arraycopy(bytes, 0, block, offset, bytes.length);
    }

    /**
     * Checks that {@code name} can be written, so that a name that cannot is rejected before any of
     * the entry reaches the stream. See {@link #encode} for what {@code lossy} allows through.
     *
     * @throws IllegalArgumentException if the name cannot be encoded
     */
    static void checkName(String name, Charset nameCharset, boolean lossy)
    {
        encode(name, nameCharset, lossy);
    }

    /**
     * Malformed input, meaning a string that is not well-formed Unicode, is always rejected: no
     * encoding can carry it, PAX included. A character the charset simply has no room for is
     * rejected too unless {@code lossy}, in which case it is replaced, normally with '?'; that is
     * for the ustar fields of an entry whose PAX record already carries the name in full.
     */
    private static byte[] encode(String value, Charset charset, boolean lossy)
    {
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(lossy ? CodingErrorAction.REPLACE : CodingErrorAction.REPORT);
        try {
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        }
        catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Name cannot be encoded as " + charset + ": " + value);
        }
    }
}
