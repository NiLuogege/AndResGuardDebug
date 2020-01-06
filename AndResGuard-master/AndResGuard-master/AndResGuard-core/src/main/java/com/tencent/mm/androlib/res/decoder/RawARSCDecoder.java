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

import com.mindprod.ledatastream.LEDataInputStream;
import com.tencent.mm.androlib.AndrolibException;
import com.tencent.mm.androlib.res.data.ResPackage;
import com.tencent.mm.androlib.res.data.ResType;
import com.tencent.mm.util.ExtDataInput;
import com.tencent.mm.util.Utils;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import org.apache.commons.io.input.CountingInputStream;

/**
 * 其实应该是原来有，并且在白名单里面的才去掉！现在没有判断是否在白名单中
 *
 * @author shwenzhang
 */
public class RawARSCDecoder {
    private final static short ENTRY_FLAG_COMPLEX = 0x0001;
    private final static short ENTRY_FLAG_PUBLIC = 0x0002;
    private final static short ENTRY_FLAG_WEAK = 0x0004;

    private static final Logger LOGGER = Logger.getLogger(ARSCDecoder.class.getName());
    private static final int KNOWN_CONFIG_BYTES = 64;

    // 用于存放 typeID 和 具体内容 的map
    private static HashMap<Integer, Set<String>> mExistTypeNames;

    private final CountingInputStream mCountIn;

    private ExtDataInput mIn;
    private Header mHeader;
    private StringBlock mTypeNames;
    private StringBlock mSpecNames;
    private ResPackage mPkg;
    private ResType mType;
    private int mTypeIdOffset = 0;
    private int mCurTypeID = -1;
    private ResPackage[] mPkgs;
    private int mResId;

    private RawARSCDecoder(InputStream arscStream) throws AndrolibException, IOException {
        arscStream = mCountIn = new CountingInputStream(arscStream);
        mIn = new ExtDataInput(new LEDataInputStream(arscStream));
        mExistTypeNames = new HashMap<>();
    }

    public static ResPackage[] decode(InputStream arscStream) throws AndrolibException {
        Utils.logRawARSC("method RawARSCDecoder--》decode");
        try {
            RawARSCDecoder decoder = new RawARSCDecoder(arscStream);
            Utils.logRawARSC("parse to get the exist names in the resouces.arsc first");
            return decoder.readTable();
        } catch (IOException ex) {
            throw new AndrolibException("Could not decode arsc file", ex);
        }
    }

    public static Set<String> getExistTypeSpecNameStrings(int type) {
        Utils.logRawARSC("getExistTypeSpecNameStrings type(typeId)=%s", type);
        return mExistTypeNames.get(type);
    }

    private ResPackage[] readTable() throws IOException, AndrolibException {
        //解析头部信息 RES_TABLE_TYPE,头大小,文件大小,块儿的起始结束位置 ，并对是否是文件头（ResTableHeader）做check
        // 例：type= 2 headerSize= 12 chunkSize= 272644 headerStart= 0 endPosition= 272644
        nextChunkCheckType(Header.TYPE_TABLE);
        //解析 package数
        int packageCount = mIn.readInt();
        //解析ResStringPool
        StringBlock.read(mIn);
        ResPackage[] packages = new ResPackage[packageCount];
        nextChunk();
        for (int i = 0; i < packageCount; i++) {
            packages[i] = readTablePackage();
        }
        return packages;
    }

    /**
     * 解析 Package块
     *
     * @return
     * @throws IOException
     * @throws AndrolibException
     */
    private ResPackage readTablePackage() throws IOException, AndrolibException {
        checkChunkType(Header.TYPE_PACKAGE);
        //获取 package id
        int id = mIn.readInt();
        //获取 包名
        String name = mIn.readNullEndedString(128, true);
        /*资源类型 string pool 偏移 （typeNameStrings） */
        mIn.skipInt();
        /*lastPublicType (typeNameCount) */
        mIn.skipInt();
        /*资源关键字 string pool 偏移 （specNameStrings） */
        mIn.skipInt();
        /*lastPublicKey(specNameCount)  */
        mIn.skipInt();

        //如果PackageHeader的大小 等于 splitHeaderSize 这就说明PackageHeader中包含 mTypeIdOffset
        // TypeIdOffset was added platform_frameworks_base/@f90f2f8dc36e7243b85e0b6a7fd5a590893c827e
        // which is only in split/new applications.
        int splitHeaderSize = (2 + 2 + 4 + 4 + (2 * 128) + (4 * 5)); // short, short, int, int, char[128], int * 4
        if (mHeader.headerSize == splitHeaderSize) {
            mTypeIdOffset = mIn.readInt();
            Utils.logRawARSC("mTypeIdOffset= %s", mTypeIdOffset);
        }

        //解析 资源类型字符串池
        mTypeNames = StringBlock.read(mIn);
        //解析 资源项名称字符串池
        mSpecNames = StringBlock.read(mIn);

        //通过package id 获取 resid 不过mResId 的组成应该是  (((packId) << 24) | (((resTypeId) & 0xFF) << 16) | (entryid & 0xFFFF))
        //具体参考 ParseResourceUtils 中的 getResId 方法。这里的 mResId 只是一个半成品，后面还会进过运算的到 最终的mResId
        mResId = id << 24;
        mPkg = new ResPackage(id, name);
        nextChunk();

        //resources.arsc中会通过 TYPE_LIBRARY 中会记录自己的依赖的库
        while (mHeader.type == Header.TYPE_LIBRARY) {
            Utils.logRawARSC("TYPE_LIBRARY 时调用 readTableTypeSpec");
            readLibraryType();
        }

        //解析 TYPE_SPEC_TYPE
        while (mHeader.type == Header.TYPE_SPEC_TYPE) {
            Utils.logRawARSC("TYPE_SPEC_TYPE 时调用 readTableTypeSpec");
            readTableTypeSpec();
        }

        return mPkg;
    }

    private void readLibraryType() throws AndrolibException, IOException {
        checkChunkType(Header.TYPE_LIBRARY);
        int libraryCount = mIn.readInt();

        int packageId;
        String packageName;

        for (int i = 0; i < libraryCount; i++) {
            packageId = mIn.readInt();
            packageName = mIn.readNullEndedString(128, true);
            Utils.logRawARSC("Decoding Shared Library (%s), pkgId: %d", packageName, packageId);
        }

        nextChunk();
        while (mHeader.type == Header.TYPE_TYPE) {
            readTableTypeSpec();
        }
    }

    private void readTableTypeSpec() throws AndrolibException, IOException {
        readSingleTableTypeSpec();

        nextChunk();
        while (mHeader.type == Header.TYPE_SPEC_TYPE) {
            Utils.logRawARSC("eTypeSpec");
            readSingleTableTypeSpec();
            nextChunk();
        }
        while (mHeader.type == Header.TYPE_TYPE) {
            readConfig();
            nextChunk();
        }
    }

    private void readSingleTableTypeSpec() throws AndrolibException, IOException {
        checkChunkType(Header.TYPE_SPEC_TYPE);
        int id = mIn.readUnsignedByte();
        mIn.skipBytes(3);
        int entryCount = mIn.readInt();

        /* flags */
        mIn.skipBytes(entryCount * 4);

        mCurTypeID = id;
        mResId = (0xff000000 & mResId) | id << 16;
        mType = new ResType(mTypeNames.getString(id - 1), mPkg);

        Utils.logRawARSC("readSingleTableTypeSpec mCurTypeID= %s ,mResId= %s , typeName= %s ",
                mCurTypeID, mResId, mType.getName());
    }

    private void readConfig() throws IOException, AndrolibException {
        checkChunkType(Header.TYPE_TYPE);
        int typeId = mIn.readUnsignedByte() - mTypeIdOffset;

        int typeFlags = mIn.readByte();
        /* reserved */
        mIn.skipBytes(2);

        int entryCount = mIn.readInt();
        int entriesStart = mIn.readInt();

        Utils.logRawARSC("readConfig typeId= %s,entryCount= %s", typeId, entryCount);

        readConfigFlags();
        int[] entryOffsets = mIn.readIntArray(entryCount);
        for (int i = 0; i < entryOffsets.length; i++) {
            if (entryOffsets[i] != -1) {
                mResId = (mResId & 0xffff0000) | i;
                readEntry();
            }
        }
    }

    /**
     * 需要防止由于某些非常恶心的白名单，导致出现重复id
     *
     * @throws IOException
     * @throws AndrolibException
     */
    private void readEntry() throws IOException, AndrolibException {
        /* size */
        mIn.skipBytes(2);
        short flags = mIn.readShort();
        int specNamesId = mIn.readInt();
        putTypeSpecNameStrings(mCurTypeID, mSpecNames.getString(specNamesId));
        boolean readDirect = false;
        if ((flags & ENTRY_FLAG_COMPLEX) == 0) {
            readDirect = true;
            readValue(readDirect, specNamesId);
        } else {
            readDirect = false;
            readComplexEntry(readDirect, specNamesId);
        }
    }

    private void readComplexEntry(boolean flags, int specNamesId) throws IOException, AndrolibException {
        int parent = mIn.readInt();
        int count = mIn.readInt();
        for (int i = 0; i < count; i++) {
            mIn.readInt();
            readValue(flags, specNamesId);
        }
    }

    private void readValue(boolean flags, int specNamesId) throws IOException, AndrolibException {
        /* size */
        mIn.skipCheckShort((short) 8);
        /* zero */
        mIn.skipCheckByte((byte) 0);
        byte type = mIn.readByte();
        int data = mIn.readInt();
    }

    private void readConfigFlags() throws IOException, AndrolibException {
        int read = 28;
        int size = mIn.readInt();
        if (size < 28) {
            throw new AndrolibException("Config size < 28");
        }

        boolean isInvalid = false;
        short mcc = mIn.readShort();
        short mnc = mIn.readShort();
        char[] language = new char[]{(char) mIn.readByte(), (char) mIn.readByte()};
        char[] country = new char[]{(char) mIn.readByte(), (char) mIn.readByte()};
        byte orientation = mIn.readByte();
        byte touchscreen = mIn.readByte();
        int density = mIn.readUnsignedShort();
        byte keyboard = mIn.readByte();
        byte navigation = mIn.readByte();
        byte inputFlags = mIn.readByte();
        /* inputPad0 */
        mIn.skipBytes(1);

        short screenWidth = mIn.readShort();
        short screenHeight = mIn.readShort();

        short sdkVersion = mIn.readShort();
        /* minorVersion, now must always be 0 */
        mIn.skipBytes(2);

        byte screenLayout = 0;
        byte uiMode = 0;
        short smallestScreenWidthDp = 0;

        if (size >= 32) {
            screenLayout = mIn.readByte();
            uiMode = mIn.readByte();
            smallestScreenWidthDp = mIn.readShort();
            read = 32;
        }

        short screenWidthDp = 0;
        short screenHeightDp = 0;
        if (size >= 36) {
            screenWidthDp = mIn.readShort();
            screenHeightDp = mIn.readShort();
            read = 36;
        }

        char[] localeScript = null;
        char[] localeVariant = null;
        if (size >= 48) {
            localeScript = readScriptOrVariantChar(4).toCharArray();
            localeVariant = readScriptOrVariantChar(8).toCharArray();
            read = 48;
        }

        byte screenLayout2 = 0;
        if (size >= 52) {
            screenLayout2 = mIn.readByte();
            mIn.skipBytes(3); // reserved padding
            read = 52;
        }

        if (size >= 56) {
            mIn.skipBytes(4);
            read = 56;
        }

        if (size >= 64) {
            mIn.skipBytes(8);
            read = 64;
        }

        int exceedingSize = size - KNOWN_CONFIG_BYTES;
        if (exceedingSize > 0) {
            byte[] buf = new byte[exceedingSize];
            mIn.readFully(buf);
            BigInteger exceedingBI = new BigInteger(1, buf);

            if (exceedingBI.equals(BigInteger.ZERO)) {
                LOGGER.fine(String.format("Config flags size > %d, but exceeding bytes are all zero, so it should be ok.",
                        KNOWN_CONFIG_BYTES
                ));
            } else {
                LOGGER.warning(String.format("Config flags size > %d. Exceeding bytes: 0x%X.",
                        KNOWN_CONFIG_BYTES,
                        exceedingBI
                ));
            }
        } else {
            int remainingSize = size - read;
            if (remainingSize > 0) {
                mIn.skipBytes(remainingSize);
            }
        }
    }

    private String readScriptOrVariantChar(int length) throws AndrolibException, IOException {
        StringBuilder string = new StringBuilder(16);

        while (length-- != 0) {
            short ch = mIn.readByte();
            if (ch == 0) {
                break;
            }
            string.append((char) ch);
        }
        mIn.skipBytes(length);

        return string.toString();
    }

    /**
     * 解析头部数据 并赋值给 mHeader
     *
     * @return
     * @throws IOException
     */
    private Header nextChunk() throws IOException {
        return mHeader = Header.read(mIn, mCountIn);
    }

    private void checkChunkType(int expectedType) throws AndrolibException {
        if (mHeader.type != expectedType) {
            throw new AndrolibException(String.format("Invalid chunk type: expected=0x%08x, got=0x%08x",
                    expectedType,
                    mHeader.type
            ));
        }
    }

    private void nextChunkCheckType(int expectedType) throws IOException, AndrolibException {
        nextChunk();
        checkChunkType(expectedType);
    }

    private void putTypeSpecNameStrings(int type, String name) {
        Set<String> names = mExistTypeNames.get(type);
        if (names == null) {
            names = new HashSet<>();
        }
        names.add(name);
        mExistTypeNames.put(type, names);

        if (type == 10) {

            Utils.logRawARSC("mExistTypeNames put key= %s, value= %s ", type, names);
        }
//        Utils.logRawARSC("mExistTypeNames put key(typeId)= %s", type);
    }

    /**
     * 数据头模块封装
     */
    public static class Header {
        public final static short TYPE_NONE = -1;
        public final static short TYPE_TABLE = 0x0002;
        public final static short TYPE_PACKAGE = 0x0200;
        public final static short TYPE_TYPE = 0x0201;
        public final static short TYPE_SPEC_TYPE = 0x0202;
        public final static short TYPE_LIBRARY = 0x0203;
        public final short type;
        public final int headerSize;
        public final int chunkSize;
        public final int startPosition;
        public final int endPosition;

        public Header(short type, int headerSize, int chunkSize, int headerStart) {
            this.type = type;
            this.headerSize = headerSize;
            this.chunkSize = chunkSize;
            this.startPosition = headerStart;
            this.endPosition = headerStart + chunkSize;

//            Utils.logRawARSC("header type= %s headerSize= %s chunkSize= %s headerStart= %s endPosition= %s ", type, headerSize, chunkSize, startPosition, endPosition);
        }

        public static Header read(ExtDataInput in, CountingInputStream countIn) throws IOException {
            short type;
            //获取已经读过的字节数，默认值是0
            int start = countIn.getCount();
            try {
                type = in.readShort();
            } catch (EOFException ex) {
                return new Header(TYPE_NONE, 0, 0, countIn.getCount());
            }
            return new Header(type, in.readShort(), in.readInt(), start);
        }
    }

    public static class FlagsOffset {
        public final int offset;
        public final int count;

        public FlagsOffset(int offset, int count) {
            this.offset = offset;
            this.count = count;
        }
    }
}