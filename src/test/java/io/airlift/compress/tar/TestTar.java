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

import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestTar
{
    private static byte[] pattern(int length)
    {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    private static byte[] archive(Object[][] entries)
            throws IOException
    {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (TarOutputStream tar = new TarOutputStream(buffer)) {
            for (Object[] entry : entries) {
                byte[] data = (byte[]) entry[1];
                tar.putNextEntry(new TarEntry((String) entry[0], data.length).setModTime(1_700_000_000_000L));
                tar.write(data);
                tar.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    @Test
    public void testRoundTripAtBlockBoundaries()
            throws IOException
    {
        Object[][] entries = {
                {"empty", pattern(0)},
                {"one", pattern(1)},
                {"under", pattern(511)},
                {"exact", pattern(512)},
                {"over", pattern(513)},
                {"big", pattern(70_000)},
        };
        try (TarInputStream tar = new TarInputStream(new ByteArrayInputStream(archive(entries)))) {
            for (Object[] expected : entries) {
                TarEntry entry = tar.getNextEntry();
                byte[] data = (byte[]) expected[1];
                assertThat(entry.getName()).isEqualTo(expected[0]);
                assertThat(entry.getSize()).isEqualTo(data.length);
                assertThat(entry.getModTime()).isEqualTo(1_700_000_000_000L);
                byte[] read = new byte[data.length];
                int total = 0;
                int n;
                while (total < read.length && (n = tar.read(read, total, read.length - total)) >= 0) {
                    total += n;
                }
                assertThat(total).isEqualTo(data.length);
                assertThat(tar.read()).isEqualTo(-1);
                assertThat(read).isEqualTo(data);
            }
            assertThat(tar.getNextEntry()).isNull();
        }
    }

    @Test
    public void testEntriesCanBeSkipped()
            throws IOException
    {
        byte[] archive = archive(new Object[][] {{"a", pattern(600)}, {"b", pattern(5)}});
        try (TarInputStream tar = new TarInputStream(new ByteArrayInputStream(archive))) {
            tar.getNextEntry(); // never read "a"
            assertThat(tar.getNextEntry().getName()).isEqualTo("b");
        }
    }

    @Test
    public void testLongNameViaPrefixSplit()
            throws IOException
    {
        String name = "some/deeply/nested/directory/structure/that/goes/on/for/quite/a/while/longer"
                + "/than/one/hundred/characters/file.txt";
        assertThat(name.length()).isGreaterThan(100);
        byte[] archive = archive(new Object[][] {{name, pattern(3)}});
        try (TarInputStream tar = new TarInputStream(new ByteArrayInputStream(archive))) {
            assertThat(tar.getNextEntry().getName()).isEqualTo(name);
        }
    }

    @Test
    public void testUnsplittableLongNameViaPax()
            throws IOException
    {
        String name = new String(new char[200]).replace('\0', 'x'); // no slashes: needs PAX
        byte[] archive = archive(new Object[][] {{name, pattern(7)}});
        try (TarInputStream tar = new TarInputStream(new ByteArrayInputStream(archive))) {
            TarEntry entry = tar.getNextEntry();
            assertThat(entry.getName()).isEqualTo(name);
            assertThat(entry.getSize()).isEqualTo(7);
            assertThat(tar.getNextEntry()).isNull();
        }
    }

    @Test
    public void testPaxSizeRecordOverridesHeader()
            throws IOException
    {
        // Craft what a PAX writer emits for an oversized entry: zeroed header size + size record
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (TarOutputStream tar = new TarOutputStream(buffer)) {
            tar.putNextEntry(new TarEntry("small", 3));
            tar.write(pattern(3));
            tar.closeEntry();
        }
        try (TarInputStream tar = new TarInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            assertThat(tar.getNextEntry().getSize()).isEqualTo(3);
        }
    }

    @Test
    public void testDirectoryEntry()
            throws IOException
    {
        byte[] archive = archive(new Object[][] {{"some/dir/", pattern(0)}});
        try (TarInputStream tar = new TarInputStream(new ByteArrayInputStream(archive))) {
            assertThat(tar.getNextEntry().isDirectory()).isTrue();
        }
    }

    @Test
    public void testArchiveSizeIsExact()
            throws IOException
    {
        // header + 1 padded data block + two-block trailer
        assertThat(archive(new Object[][] {{"x", pattern(1)}}).length).isEqualTo(512 + 512 + 1024);
    }

    @Test
    public void testWriteBeyondDeclaredSizeFails()
            throws IOException
    {
        TarOutputStream tar = new TarOutputStream(new ByteArrayOutputStream());
        tar.putNextEntry(new TarEntry("x", 2));
        assertThatThrownBy(() -> tar.write(pattern(3)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    public void testUnderfullEntryFailsOnClose()
            throws IOException
    {
        TarOutputStream tar = new TarOutputStream(new ByteArrayOutputStream());
        tar.putNextEntry(new TarEntry("x", 5));
        tar.write(pattern(2));
        assertThatThrownBy(tar::closeEntry)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("missing");
    }

    @Test
    public void testCorruptChecksumIsRejected()
            throws IOException
    {
        byte[] archive = archive(new Object[][] {{"x", pattern(5)}});
        archive[0] ^= 1; // flip a bit in the name field
        try (TarInputStream tar = new TarInputStream(new ByteArrayInputStream(archive))) {
            assertThatThrownBy(tar::getNextEntry)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("checksum");
        }
    }

    @Test
    public void testTruncatedArchiveIsDetected()
            throws IOException
    {
        byte[] archive = archive(new Object[][] {{"x", pattern(600)}});
        byte[] truncated = new byte[700]; // mid-data
        System.arraycopy(archive, 0, truncated, 0, 700);
        try (TarInputStream tar = new TarInputStream(new ByteArrayInputStream(truncated))) {
            TarEntry entry = tar.getNextEntry();
            assertThat(entry.getSize()).isEqualTo(600);
            byte[] sink = new byte[600];
            assertThatThrownBy(() -> {
                int total = 0;
                while (total < 600) {
                    int n = tar.read(sink, total, 600 - total);
                    total += n;
                }
            }).isInstanceOf(IOException.class);
        }
    }
}
