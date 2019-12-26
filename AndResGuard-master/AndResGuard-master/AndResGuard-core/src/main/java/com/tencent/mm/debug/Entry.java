package com.tencent.mm.debug;

import com.tencent.mm.resourceproguard.InputParam;
import com.tencent.mm.resourceproguard.Main;

import java.io.File;
import java.util.ArrayList;

/**
 * Created by niluogege on 2019/11/14.
 */
public class Entry {
    public static void main(String[] args) {

        ArrayList<String> whiteListFullName = new ArrayList<>();
        ArrayList<String> compressFilePattern = new ArrayList<>();
        compressFilePattern.add("*.png");
        compressFilePattern.add("*.jpg");
        compressFilePattern.add("*.gif");

        InputParam.Builder builder = new InputParam.Builder()
                .setWhiteList(whiteListFullName)
                .setUse7zip(true)
                .setMetaName("META-INF")
                .setFixedResName("bbb")
                .setKeepRoot(false)
                .setMergeDuplicatedRes(true)
                .setCompressFilePattern(compressFilePattern)
                .setZipAlign("D:/soft/AndroidSDK/build-tools/28.0.3/zipalign")
                .setSevenZipPath("D:/softCacheData/.gradle/caches/modules-2/files-2.1/com.tencent.mm/SevenZip/1.2.17/4786999cf29d8e3b0c39a80359b5127bda36132a/SevenZip-1.2.17-windows-x86_64.exe")
                .setOutBuilder("E:/111work/code/code_me/demo/app/build/outputs/apk/release/AndResGuard_app-release")
                .setApkPath("E:/111work/code//code_me/demo/release/app-release.apk")
                .setUseSign(true)
                .setDigestAlg("SHA-1")
                .setMinSDKVersion(19);

        builder.setFinalApkBackupPath("E:/111work/code//code_me/demo/release/resGuard/app-release.apk");

        builder.setSignFile(new File("E:/111work/code/code_me/demo/keystore/release.keystore"))
                .setKeypass("testres")
                .setStorealias("testres")
                .setStorepass("testres");
        builder.setSignatureType(InputParam.SignatureType.SchemaV1);


        //配置InputParam 并传入 Main的 gradleRun 方法
        InputParam inputParam = builder.create();
        Main.gradleRun(inputParam);
    }
}
