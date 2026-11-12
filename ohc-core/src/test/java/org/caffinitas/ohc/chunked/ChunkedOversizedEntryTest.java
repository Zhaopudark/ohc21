/*
 *      Copyright (C) 2014 Robert Stupp, Koeln, Germany, robert-stupp.de
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.caffinitas.ohc.chunked;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.caffinitas.ohc.CacheSerializer;
import org.caffinitas.ohc.OHCache;
import org.caffinitas.ohc.OHCacheBuilder;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Regression test for the chunked off-heap out-of-bounds write (CWE-787) in
 * {@link OffHeapChunkedMap#putEntry}: an entry whose serialized size exceeds a single
 * chunk's data area used to be copied past the chunk (and past the segment allocation for
 * the last chunk), corrupting off-heap memory and crashing the JVM. The fix refuses such
 * oversized entries instead of writing out of bounds.
 */
public class ChunkedOversizedEntryTest
{
    @AfterMethod(alwaysRun = true)
    public void deinit()
    {
        Uns.clearUnsDebugForTest();
    }

    // Minimal 4-byte int key serializer (small fixed size, so it never dominates entryBytes).
    private static final CacheSerializer<Integer> INT_KEY = new CacheSerializer<Integer>()
    {
        public void serialize(Integer k, ByteBuffer buf)
        {
            buf.putInt(k);
        }

        public Integer deserialize(ByteBuffer buf)
        {
            return buf.getInt();
        }

        public int serializedSize(Integer k)
        {
            return 4;
        }
    };

    // value = 4-byte length prefix + utf8 bytes (variable size).
    private static final CacheSerializer<String> STR_VALUE = new CacheSerializer<String>()
    {
        public void serialize(String v, ByteBuffer buf)
        {
            byte[] b = v.getBytes(StandardCharsets.UTF_8);
            buf.putInt(b.length);
            buf.put(b);
        }

        public String deserialize(ByteBuffer buf)
        {
            byte[] b = new byte[buf.getInt()];
            buf.get(b);
            return new String(b, StandardCharsets.UTF_8);
        }

        public int serializedSize(String v)
        {
            return 4 + v.getBytes(StandardCharsets.UTF_8).length;
        }
    };

    private static OHCache<Integer, String> cache(int chunkSize)
    {
        // variable-size chunked cache; no maxEntrySize configured (0 = disabled), matching the
        // default that made the oversized-entry write reachable.
        return OHCacheBuilder.<Integer, String>newBuilder()
                             .keySerializer(INT_KEY)
                             .valueSerializer(STR_VALUE)
                             .chunkSize(chunkSize)
                             .segmentCount(4)
                             .capacity(4L * 1024 * 1024)
                             .build();
    }

    private static String repeat(char c, int n)
    {
        char[] a = new char[n];
        java.util.Arrays.fill(a, c);
        return new String(a);
    }

    @Test
    public void oversizedEntryIsRefusedWithoutCrash() throws Exception
    {
        int chunkSize = 256;
        try (OHCache<Integer, String> cache = cache(chunkSize))
        {
            // variable entry size = entryOffData(24) + keyLen(4) + valueLen(4 + strBytes).
            // value of 1000 chars -> entryBytes ~ 1032 >> chunkSize(256).
            String tooBig = repeat('x', 1000);
            assertFalse(cache.put(1, tooBig), "oversized entry must be refused, not written out of bounds");
            assertNull(cache.get(1), "refused entry must not be present");

            // a normal entry that fits within the chunk still works after the refused oversized put
            assertTrue(cache.put(2, "hello"), "normal entry should be stored");
            assertEquals(cache.get(2), "hello");

            // a value just under the chunk limit should still succeed (chunkSize - 24 - 4 - 4 = 224)
            assertTrue(cache.put(3, repeat('y', 200)), "entry fitting in a chunk should be stored");
            assertEquals(cache.get(3), repeat('y', 200));

            // if we reached here without a JVM crash, the OOB is fixed
        }
    }

    @Test
    public void oversizedEntryRefusedAcrossManyPuts() throws Exception
    {
        int chunkSize = 128;
        try (OHCache<Integer, String> cache = cache(chunkSize))
        {
            String big = repeat('z', 500);   // entryBytes ~ 532 > 128
            for (int i = 0; i < 1000; i++)
                assertFalse(cache.put(i, big), "no oversized entry may be written");

            // cache remains usable for entries that fit (chunkSize - 24 - 4 - 4 = 96)
            assertTrue(cache.put(100000, "ok"));
            assertEquals(cache.get(100000), "ok");
        }
    }
}
