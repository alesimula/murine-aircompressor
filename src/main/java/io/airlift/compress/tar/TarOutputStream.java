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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Writes USTAR (POSIX.1-1988) archives with the java.util.zip.ZipOutputStream
 * call sequence: putNextEntry, write the data, closeEntry, repeat, close.
 * <p>
 * tar stores each entry's size in its header: each entrymust be written in full before it's closed.
 */
public class TarOutputStream
        extends FilterOutputStream
{
    private final byte[] block = new byte[512];
    private long remaining;
    private int padding;
    private boolean entryOpen;
    private boolean finished;

    public TarOutputStream(OutputStream out)
    {
        super(out);
    }

    public void putNextEntry(TarEntry entry)
            throws IOException
    {
        if (entryOpen) {
            throw new IOException("Previous entry was not closed");
        }
        if (finished) {
            throw new IOException("Archive is finished");
        }
        TarEntry header = entry;
        if (!entry.fitsUstar()) {
            // PAX (POSIX.1-2001): a preceding 'x' entry carries the values that don't fit the ustar fields;
            // the real header holds truncated fallbacks
            writePaxHeader(entry);
            String shortName = entry.getName().substring(0, Math.min(entry.getName().length(), 100));
            if (entry.isDirectory() && !shortName.endsWith("/")) {
                shortName = shortName.substring(0, 99) + "/";
            }
            header = new TarEntry(shortName, entry.getSize(), entry.getMode()).setModTime(entry.getModTime());
        }
        header.writeHeader(block);
        out.write(block);
        remaining = entry.getSize();
        padding = (int) (-remaining & 511);
        entryOpen = true;
    }

    @Override
    public void write(int b)
            throws IOException
    {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] buffer, int offset, int length)
            throws IOException
    {
        if (!entryOpen) {
            throw new IOException("No open entry");
        }
        if (length > remaining) {
            throw new IOException("Write exceeds the declared entry size");
        }
        out.write(buffer, offset, length);
        remaining -= length;
    }

    /**
     * Verifies the entry was written in full and pads it to the block boundary.ù
     */
    public void closeEntry()
            throws IOException
    {
        if (!entryOpen) {
            return;
        }
        if (remaining != 0) {
            throw new IOException(remaining + " bytes missing from the current entry");
        }
        Arrays.fill(block, 0, padding, (byte) 0);
        out.write(block, 0, padding);
        entryOpen = false;
    }

    // A PAX record is "<len> <key>=<value>\n" where len counts the whole record,
    // its own digits included, so it is grown to account for digit-count changes
    private static void paxRecord(StringBuilder records, String key, String value)
    {
        int base = key.length() + value.getBytes(UTF_8).length + 3; // space, '=', '\n'
        int total = base + Integer.toString(base).length();
        total = base + Integer.toString(total).length();
        records.append(total).append(' ').append(key).append('=').append(value).append('\n');
    }

    // the pax entry carries its own ustar header, so the name it borrows has to survive one:
    // strip to 7 bits and replace what a reader would misparse
    private static String paxHeaderName(String entryName)
    {
        StringBuilder stripped = new StringBuilder(entryName.length());
        for (int i = 0; i < entryName.length(); i++) {
            char c = (char) (entryName.charAt(i) & 0x7F);
            stripped.append(c == 0 || c == '/' || c == '\\' ? '_' : c);
        }
        String name = "./PaxHeaders/" + stripped;
        return name.length() > 99 ? name.substring(0, 99) : name;
    }

    private void writePaxHeader(TarEntry entry)
            throws IOException
    {
        StringBuilder records = new StringBuilder();
        paxRecord(records, "path", entry.getName());
        if (entry.getSize() > TarEntry.MAX_OCTAL) {
            paxRecord(records, "size", Long.toString(entry.getSize()));
        }
        byte[] data = records.toString().getBytes(UTF_8);
        String name = entry.getName();
        TarEntry pax = new TarEntry(paxHeaderName(name), data.length).setType('x');
        pax.writeHeader(block);
        out.write(block);
        out.write(data);
        int pad = -data.length & 511;
        Arrays.fill(block, 0, pad, (byte) 0);
        out.write(block, 0, pad);
    }

    /**
     * Writes the end-of-archive marker (two zero blocks). Called by close().
     */
    public void finish()
            throws IOException
    {
        if (entryOpen) {
            throw new IOException("Current entry was not closed");
        }
        if (finished) {
            return;
        }
        Arrays.fill(block, (byte) 0);
        out.write(block);
        out.write(block);
        finished = true;
    }

    @Override
    public void close()
            throws IOException
    {
        if (!finished) {
            finish();
        }
        super.close();
    }
}
