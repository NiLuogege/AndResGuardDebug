/**
 * Copyright 2014 Ryszard Wiśniewski <brut.alll@gmail.com>
 * Copyright 2016 sim sun <sunsj1231@gmail.com>
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.mm.androlib.res.decoder;

import com.tencent.mm.androlib.AndrolibException;
import com.tencent.mm.util.ExtDataInput;
import com.tencent.mm.util.ExtDataOutput;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author shwenzhang
 */
public class StringBlock {

    private static final CharsetDecoder UTF16LE_DECODER = Charset.forName("UTF-16LE").newDecoder();
    private static final CharsetDecoder UTF8_DECODER = Charset.forName("UTF-8").newDecoder();
    private static final Logger LOGGER = Logger.getLogger(StringBlock.class.getName());

    // ResChunk_header = header.type (0x0001) + header.headerSize (0x001C)
    private static final int CHUNK_STRINGPOOL_TYPE = 0x001C0001;
    private static final int UTF8_FLAG = 0x00000100;
    private static final int CHUNK_NULL_TYPE = 0x00000000;
    private static final byte NULL = 0;

    private int[] m_stringOffsets;
    private byte[] m_strings;
    private int[] m_styleOffsets;
    private int[] m_styles;
    private boolean m_isUTF8;
    private int[] m_stringOwns;

    private StringBlock() {
    }

    /**
     * Reads whole (including chunk type) string block from stream. Stream must
     * be at the chunk type.
     *
     * @param reader reader
     * @return stringblock
     * @throws IOException ioexcetpion
     */
    public static StringBlock read(ExtDataInput reader) throws IOException {
        //跳过RES_STRING_POOL_TYPE 和 头大小 这俩加起来就是 CHUNK_STRINGPOOL_TYPE 的值
        reader.skipCheckChunkTypeInt(CHUNK_STRINGPOOL_TYPE, CHUNK_NULL_TYPE);
        //块大小
        int chunkSize = reader.readInt();
        //字符串个数
        int stringCount = reader.readInt();
        //style个数
        int styleCount = reader.readInt();
        //标记个数
        int flags = reader.readInt();
        //字符串起始位置
        int stringsOffset = reader.readInt();
        //style起始位置
        int stylesOffset = reader.readInt();

        System.out.printf("字符串值--》块大小= %s 字符串个数= %s style个数= %s 标记个数= %s 字符串起始位是= %s style起始位置= %s \n"
                , chunkSize, stringCount, styleCount, flags, stringsOffset, stylesOffset);

        StringBlock block = new StringBlock();
        //是否是utf8编码
        block.m_isUTF8 = (flags & UTF8_FLAG) != 0;
        //获取字符串偏移数组
        block.m_stringOffsets = reader.readIntArray(stringCount);
//        for (int i = 0; i < stringCount; i++) {
//            System.out.print(block.m_stringOffsets[i] + "\n");
//        }
        //创建 存储字符串的 数组,并用-1装满
        block.m_stringOwns = new int[stringCount];
        Arrays.fill(block.m_stringOwns, -1);
//        for (int i = 0; i < stringCount; i++) {
//            System.out.print(block.m_stringOwns[i] + "\n");
//        }


        if (styleCount != 0) {
            //获取字符串样式偏移数组
            block.m_styleOffsets = reader.readIntArray(styleCount);
        }
        {
            //size = 整个字符串所占字节大小
            int size = ((stylesOffset == 0) ? chunkSize : stylesOffset) - stringsOffset;

            if ((size % 4) != 0) {
                throw new IOException("String data size is not multiple of 4 (" + size + ").");
            }

            //创建 存储字符串的数组,并填满
            block.m_strings = new byte[size];
            reader.readFully(block.m_strings);

//            for (int i = 0; i < size; i++) {
//                System.out.print(block.m_strings[i] + "\n");
//            }
        }
        if (stylesOffset != 0) {
            //size = 整个style所占字节大小
            int size = (chunkSize - stylesOffset);
            if ((size % 4) != 0) {
                throw new IOException("Style data size is not multiple of 4 (" + size + ").");
            }

            //创建 存储字符串样式的数组,并填满
            block.m_styles = reader.readIntArray(size / 4);
        }
        return block;
    }

    /**
     *
     * @param reader
     * @param out
     * @param specNames  存储的是 已经混淆过的 资源名称字符串池
     * @param curSpecNameToPos  传进来就是空的 用于记录。。
     * @return
     * @throws IOException
     * @throws AndrolibException
     */
    public static int writeSpecNameStringBlock(
            ExtDataInput reader, ExtDataOutput out, Map<String, Set<String>> specNames, Map<String, Integer> curSpecNameToPos)
            throws IOException, AndrolibException {
        //读 type+头大小
        int type = reader.readInt();
        // 读 块大小
        int chunkSize = reader.readInt();
        // 读 字符串个数
        int stringCount = reader.readInt();
        // 读 style 个数
        int styleOffsetCount = reader.readInt();

        //这里的 styleOffsetCount 肯定为0吗？ 为啥呢？
        if (styleOffsetCount != 0) {
            throw new AndrolibException(String.format("writeSpecNameStringBlock styleOffsetCount != 0  styleOffsetCount %d",
                    styleOffsetCount
            ));
        }

        //读 flags
        int flags = reader.readInt();
        boolean isUTF8 = (flags & UTF8_FLAG) != 0;
        // 读字符串起始位置
        int stringsOffset = reader.readInt();
        //读 style 起始位置
        int stylesOffset = reader.readInt();
        // 读 字符串偏移数组
        reader.readIntArray(stringCount);
        // 获取字符串 长度
        int size = ((stylesOffset == 0) ? chunkSize : stylesOffset) - stringsOffset;

        if ((size % 4) != 0) {
            throw new IOException("String data size is not multiple of 4 (" + size + ").");
        }
        //因为style个数==0 所以style偏移数组肯定也不存在 这里直接 读字符串池
        byte[] temp_strings = new byte[size];
        reader.readFully(temp_strings);
        int totalSize = 0;

        //写入 type + 头大小
        out.writeCheckInt(type, CHUNK_STRINGPOOL_TYPE);
        totalSize += 4;
        stringCount = specNames.keySet().size();
        System.out.println("String pool size: " + stringCount);

        totalSize += 6 * 4 + 4 * stringCount;

        //读字符串起始位置
        stringsOffset = totalSize;

        //字符串偏移数组
        int[] stringOffsets = new int[stringCount];
        // make twice size buffer for avoiding out of bounds error
        // 初始化两倍的数组 防止数组越界
        byte[] stringBytes = new byte[size * 2];
        int offset = 0;
        int i = 0;
        curSpecNameToPos.clear();

        for (Iterator<String> it = specNames.keySet().iterator(); it.hasNext(); ) {
            stringOffsets[i] = offset;
            //name 是 配置的 fixedResName
            String name = it.next();
            for (String specName : specNames.get(name)) {
                // N res entry item point to one string constant
                // 记录 specName 和 i的关系
                curSpecNameToPos.put(specName, i);
//                System.out.printf("curSpecNameToPos put name= %s key= %s,value= %s\n",name,specName,i);
            }

            //写入 混淆过的 资源项名称字符串池 信息
            if (isUTF8) {
                stringBytes[offset++] = (byte) name.length();
                stringBytes[offset++] = (byte) name.length();
                totalSize += 2;
                byte[] tempByte = name.getBytes(Charset.forName("UTF-8"));
                if (name.length() != tempByte.length) {
                    throw new AndrolibException(String.format(
                            "writeSpecNameStringBlock %s UTF-8 length is different name %d, tempByte %d\n",
                            name,
                            name.length(),
                            tempByte.length
                    ));
                }
                System.arraycopy(tempByte, 0, stringBytes, offset, tempByte.length);
                offset += name.length();
                stringBytes[offset++] = NULL;
                totalSize += name.length() + 1;
            } else {
                writeShort(stringBytes, offset, (short) name.length());
                offset += 2;
                totalSize += 2;
                byte[] tempByte = name.getBytes(Charset.forName("UTF-16LE"));
                if ((name.length() * 2) != tempByte.length) {
                    throw new AndrolibException(String.format(
                            "writeSpecNameStringBlock %s UTF-16LE length is different name %d, tempByte %d\n",
                            name,
                            name.length(),
                            tempByte.length
                    ));
                }
                System.arraycopy(tempByte, 0, stringBytes, offset, tempByte.length);
                offset += tempByte.length;
                stringBytes[offset++] = NULL;
                stringBytes[offset++] = NULL;
                totalSize += tempByte.length + 2;
            }
            i++;
        }
        //要保证string size 是4的倍数,要补零
        size = totalSize - stringsOffset;
        if ((size % 4) != 0) {
            int add = 4 - (size % 4);
            for (i = 0; i < add; i++) {
                stringBytes[offset++] = NULL;
                totalSize++;
            }
        }

        out.writeInt(totalSize);
        out.writeInt(stringCount);
        out.writeInt(styleOffsetCount);
        out.writeInt(flags);
        out.writeInt(stringsOffset);
        out.writeInt(stylesOffset);
        out.writeIntArray(stringOffsets);
        out.write(stringBytes, 0, offset);
        return (chunkSize - totalSize);
    }

    public static int writeTableNameStringBlock(
            ExtDataInput reader, ExtDataOutput out, Map<Integer, String> tableProguardMap)
            throws IOException, AndrolibException {
        int type = reader.readInt();
        int chunkSize = reader.readInt();
        int stringCount = reader.readInt();
        int styleOffsetCount = reader.readInt();
        int flags = reader.readInt();
        int stringsOffset = reader.readInt();
        int stylesOffset = reader.readInt();

        StringBlock block = new StringBlock();
        block.m_isUTF8 = (flags & UTF8_FLAG) != 0;
        if (block.m_isUTF8) {
            System.out.printf("resources.arsc Character Encoding: utf-8\n");
        } else {
            System.out.printf("resources.arsc Character Encoding: utf-16\n");
        }

        //读取 字符串偏移数组
        block.m_stringOffsets = reader.readIntArray(stringCount);
        block.m_stringOwns = new int[stringCount];
        for (int i = 0; i < stringCount; i++) {
            block.m_stringOwns[i] = -1;
        }
        if (styleOffsetCount != 0) {
            //读取 style 偏移数组
            block.m_styleOffsets = reader.readIntArray(styleOffsetCount);
        }
        {
            int size = ((stylesOffset == 0) ? chunkSize : stylesOffset) - stringsOffset;
            if ((size % 4) != 0) {
                throw new IOException("String data size is not multiple of 4 (" + size + ").");
            }
            block.m_strings = new byte[size];
            //将字符串池 读取到  block.m_strings 中
            reader.readFully(block.m_strings);
        }
        if (stylesOffset != 0) {
            int size = (chunkSize - stylesOffset);
            if ((size % 4) != 0) {
                throw new IOException("Style data size is not multiple of 4 (" + size + ").");
            }
            //将style池 读取到block.m_styles 中
            block.m_styles = reader.readIntArray(size / 4);
        }

        int totalSize = 0;
        //同时写入字符串池的 type 和 头大小
        out.writeCheckInt(type, CHUNK_STRINGPOOL_TYPE);
        totalSize += 4;

        totalSize += 6 * 4 + 4 * stringCount + 4 * styleOffsetCount;
        //字符串起始位置
        stringsOffset = totalSize;

        byte[] strings = new byte[block.m_strings.length];
        int[] stringOffsets = new int[stringCount];
        //将字符串偏移数组 copy 给 stringOffsets
        System.arraycopy(block.m_stringOffsets, 0, stringOffsets, 0, stringOffsets.length);

        //开始写入混淆后的字符串
        int offset = 0;
        int i;
        for (i = 0; i < stringCount; i++) {
            stringOffsets[i] = offset;
            //如果找不到即没混淆这一项,直接拷贝
            if (tableProguardMap.get(i) == null) {
                //需要区分是否是最后一项  计算需要copy的长度
                int copyLen = (i == (stringCount - 1)) ? (block.m_strings.length - block.m_stringOffsets[i])
                        : (block.m_stringOffsets[i + 1] - block.m_stringOffsets[i]);
                System.arraycopy(block.m_strings, block.m_stringOffsets[i], strings, offset, copyLen);
                offset += copyLen;
                totalSize += copyLen;
            } else {
                String name = tableProguardMap.get(i);
                if (block.m_isUTF8) {
                    //utf8 前两个字节分别代表 utf-16 和 utf-8 下的字符串长度
                    strings[offset++] = (byte) name.length();
                    strings[offset++] = (byte) name.length();

                    totalSize += 2;
                    byte[] tempByte = name.getBytes(Charset.forName("UTF-8"));
                    if (name.length() != tempByte.length) {
                        throw new AndrolibException(String.format(
                                "writeTableNameStringBlock UTF-8 length is different  name %d, tempByte %d\n",
                                name.length(),
                                tempByte.length
                        ));
                    }
                    System.arraycopy(tempByte, 0, strings, offset, tempByte.length);
                    offset += name.length();
                    //utf-8 是以0结尾
                    strings[offset++] = NULL;
                    totalSize += name.length() + 1;
                } else {
                    writeShort(strings, offset, (short) name.length());
                    offset += 2;
                    totalSize += 2;
                    byte[] tempByte = name.getBytes(Charset.forName("UTF-16LE"));
                    if ((name.length() * 2) != tempByte.length) {
                        throw new AndrolibException(String.format(
                                "writeTableNameStringBlock UTF-16LE length is different  name %d, tempByte %d\n",
                                name.length(),
                                tempByte.length
                        ));
                    }
                    System.arraycopy(tempByte, 0, strings, offset, tempByte.length);
                    offset += tempByte.length;
                    strings[offset++] = NULL;
                    strings[offset++] = NULL;
                    totalSize += tempByte.length + 2;
                }
            }
        }
        //要保证string size 是4的倍数,要补零  -》 这里的 size 标识的是字符串池的大小，不过为什么要是4的倍数这个不太知道
        int size = totalSize - stringsOffset;
        if ((size % 4) != 0) {
            int add = 4 - (size % 4);
            for (i = 0; i < add; i++) {
                strings[offset++] = NULL;
                totalSize++;
            }
        }
        //因为是int的,如果之前的不为0 -》 这里为style起始位置赋值
        if (stylesOffset != 0) {
            stylesOffset = totalSize;
            totalSize += block.m_styles.length * 4;
        }

        out.writeInt(totalSize);
        out.writeInt(stringCount);
        out.writeInt(styleOffsetCount);
        out.writeInt(flags);
        out.writeInt(stringsOffset);
        out.writeInt(stylesOffset);
        out.writeIntArray(stringOffsets);
        if (stylesOffset != 0) {
            out.writeIntArray(block.m_styleOffsets);
        }
        out.write(strings, 0, offset);
        if (stylesOffset != 0) {
            out.writeIntArray(block.m_styles);
        }

        //输出减少的字节数
        return (chunkSize - totalSize);
    }

    /**
     * Reads whole (including chunk type) string block from stream. Stream must
     * be at the chunk type.
     *
     * @param reader ExtDataInput reader
     * @param out    ExtDataOutput out
     * @throws IOException ioexception
     */
    public static void writeAll(ExtDataInput reader, ExtDataOutput out) throws IOException {
        out.writeCheckChunkTypeInt(reader, CHUNK_STRINGPOOL_TYPE, CHUNK_NULL_TYPE);
        int chunkSize = reader.readInt();
        out.writeInt(chunkSize);
        //这里的 -8 是将type  ，头大小 和块大小 占的空间去掉
        out.writeBytes(reader, chunkSize - 8);
    }

    private static final int[] getUtf8(byte[] array, int offset) {
        int val = array[offset];
        int length;
        // We skip the utf16 length of the string
        if ((val & 0x80) != 0) {
            offset += 2;
        } else {
            offset += 1;
        }
        // And we read only the utf-8 encoded length of the string
        val = array[offset];
        offset += 1;
        if ((val & 0x80) != 0) {
            int low = (array[offset] & 0xFF);
            length = ((val & 0x7F) << 8) + low;
            offset += 1;
        } else {
            length = val;
        }
        return new int[]{offset, length};
    }

    private static final int[] getUtf16(byte[] array, int offset) {
        int val = ((array[offset + 1] & 0xFF) << 8 | array[offset] & 0xFF);

        if ((val & 0x8000) != 0) {
            int high = (array[offset + 3] & 0xFF) << 8;
            int low = (array[offset + 2] & 0xFF);
            int len_value = ((val & 0x7FFF) << 16) + (high + low);
            return new int[]{4, len_value * 2};
        }
        return new int[]{2, val * 2};
    }

    private static final int getShort(byte[] array, int offset) {
        return (array[offset + 1] & 0xff) << 8 | array[offset] & 0xff;
    }

    private static final void writeShort(byte[] array, int offset, short value) {
        array[offset] = (byte) (0xFF & value);
        array[offset + 1] = (byte) (0xFF & (value >> 8));
    }

    private static final int getShort(int[] array, int offset) {
        int value = array[offset / 4];
        if ((offset % 4) / 2 == 0) {
            return (value & 0xFFFF);
        } else {
            return (value >>> 16);
        }
    }

    /**
     * Returns number of strings in block.
     *
     * @return int number of strings in block.
     */
    public int getCount() {
        return m_stringOffsets != null ? m_stringOffsets.length : 0;
    }

    /**
     * Returns raw string (without any styling information) at specified index.
     *
     * @param index index
     * @return raw string
     */
    public String getString(int index) {
        if (index < 0 || m_stringOffsets == null || index >= m_stringOffsets.length) {
            return null;
        }
        int offset = m_stringOffsets[index];
        int length;

        if (m_isUTF8) {
            int[] val = getUtf8(m_strings, offset);
            offset = val[0];
            length = val[1];
        } else {
            int[] val = getUtf16(m_strings, offset);
            offset += val[0];
            length = val[1];
        }
        return decodeString(offset, length);
    }

    /**
     * Not yet implemented.
     * <p>
     * Returns string with style information (if any).
     *
     * @param index index
     * @return string with style information (if any).
     */
    public CharSequence get(int index) {
        return getString(index);
    }

    /**
     * Finds index of the string. Returns -1 if the string was not found.
     *
     * @param string input string
     * @return index of the string
     */
    public int find(String string) {
        if (string == null) {
            return -1;
        }
        for (int i = 0; i != m_stringOffsets.length; ++i) {
            int offset = m_stringOffsets[i];
            int length = getShort(m_strings, offset);
            if (length != string.length()) {
                continue;
            }
            int j = 0;
            for (; j != length; ++j) {
                offset += 2;
                if (string.charAt(j) != getShort(m_strings, offset)) {
                    break;
                }
            }
            if (j == length) {
                return i;
            }
        }
        return -1;
    }

    private String decodeString(int offset, int length) {
        try {
            return (m_isUTF8 ? UTF8_DECODER : UTF16LE_DECODER).decode(ByteBuffer.wrap(m_strings, offset, length)).toString();
        } catch (CharacterCodingException ex) {
            LOGGER.log(Level.WARNING, null, ex);
            return null;
        }
    }
}
