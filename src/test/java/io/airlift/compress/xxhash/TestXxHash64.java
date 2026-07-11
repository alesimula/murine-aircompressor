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
package io.airlift.compress.xxhash;

public class TestXxHash64
        extends AbstractTestXxHash64
{
    @Override
    protected XxHash64Hasher createHasher()
    {
        return XxHash64Hasher.create();
    }

    @Override
    protected XxHash64Hasher createHasher(long seed)
    {
        return XxHash64Hasher.create(seed);
    }

    @Override
    protected long hash(byte[] input)
    {
        return XxHash64Hasher.hash(input);
    }

    @Override
    protected long hash(byte[] input, long seed)
    {
        return XxHash64Hasher.hash(input, seed);
    }

    @Override
    protected long hash(byte[] input, int offset, int length)
    {
        return XxHash64Hasher.hash(input, offset, length);
    }

    @Override
    protected long hash(long value)
    {
        return XxHash64Hasher.hash(value);
    }

    @Override
    protected long hash(long value, long seed)
    {
        return XxHash64Hasher.hash(value, seed);
    }
}
