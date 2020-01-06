package com.tencent.mm.androlib;

import com.tencent.mm.androlib.res.data.ResPackage;
import com.tencent.mm.androlib.res.decoder.ARSCDecoder;
import com.tencent.mm.androlib.res.decoder.RawARSCDecoder;
import com.tencent.mm.androlib.res.util.ExtFile;
import com.tencent.mm.directory.DirectoryException;
import com.tencent.mm.resourceproguard.Configuration;
import com.tencent.mm.util.FileOperation;
import com.tencent.mm.util.TypedValue;
import com.tencent.mm.util.Utils;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.regex.Pattern;

/**
 * @author shwenzhang
 */
public class ApkDecoder {

  final HashSet<Path> mRawResourceFiles = new HashSet<>();
  private final Configuration config;
  private final ExtFile apkFile;
  private File mOutDir;
  private File mOutTempARSCFile;
  private File mOutARSCFile;
  private File mOutResFile;
  private File mRawResFile;
  private File mOutTempDir;
  private File mResMappingFile;
  private File mMergeDuplicatedResMappingFile;
  private HashMap<String, Integer> mCompressData;

  public ApkDecoder(Configuration config, File apkFile) {
    this.config = config;
    this.apkFile = new ExtFile(apkFile);
  }

  /**
   * 将 temp下的res文件 copy 到 混淆后的res/r文件中
   * @throws IOException
   */
  private void copyOtherResFiles() throws IOException {
    if (mRawResourceFiles.isEmpty()) {
      return;
    }

    //获取 原资源文件对象（temp下的res） 的Path
    Path resPath = mRawResFile.toPath();
    //获取混淆后的res/r文件Path
    Path destPath = mOutResFile.toPath();

    for (Path path : mRawResourceFiles) {
      Path relativePath = resPath.relativize(path);
      Path dest = destPath.resolve(relativePath);

      System.out.printf("copy res file not in resources.arsc file:%s\n", relativePath.toString());
      FileOperation.copyFileUsingStream(path.toFile(), dest.toFile());
    }
  }

  public void removeCopiedResFile(Path key) {
    mRawResourceFiles.remove(key);
  }

  public Configuration getConfig() {
    return config;
  }

  public boolean hasResources() throws AndrolibException {
    try {
      return apkFile.getDirectory().containsFile("resources.arsc");//apkFile="E:/111work/code//code_me/demo/release/app-release.apk"
    } catch (DirectoryException ex) {
      throw new AndrolibException(ex);
    }
  }

  private void ensureFilePath() throws IOException {
    //清空输出目录
    Utils.cleanDir(mOutDir);//mOutDir = "E:/111work/code/code_me/demo/app/build/outputs/apk/release/AndResGuard_app-release"

    String unZipDest = new File(mOutDir, TypedValue.UNZIP_FILE_PATH).getAbsolutePath();//mOutDir = "E:/111work/code/code_me/demo/app/build/outputs/apk/release/AndResGuard_app-release/temp"
    System.out.printf("unziping apk to %s\n", unZipDest);
    //解压apk到temp文件夹 并将apk中的所有文件明和压缩方式map到mCompressData
    mCompressData = FileOperation.unZipAPk(apkFile.getAbsoluteFile().getAbsolutePath(), unZipDest);
    //根据config来修改文件压缩配置
    dealWithCompressConfig();
    //将res混淆成r(创建存储资源文件的文件夹r或者res)
    //如果mKeepRoot为true，会keep住所有资源的原始路径，只混淆资源的名字（如：res/anim/a.xml）和 arsc name列
    if (!config.mKeepRoot) {
      mOutResFile = new File(mOutDir.getAbsolutePath() + File.separator + TypedValue.RES_FILE_PATH);
    } else {
      mOutResFile = new File(mOutDir.getAbsolutePath() + File.separator + "res");
    }

    //创建原资源文件对象（temp下的res）
    mRawResFile = new File(mOutDir.getAbsoluteFile().getAbsolutePath()
                           + File.separator
                           + TypedValue.UNZIP_FILE_PATH
                           + File.separator
                           + "res");
    //创建temp文件对象（temp）
    mOutTempDir = new File(mOutDir.getAbsoluteFile().getAbsolutePath() + File.separator + TypedValue.UNZIP_FILE_PATH);

    //这里纪录原始res目录的文件
    //遍历 mRawResFile 将 子文件放入 mRawResourceFiles 中
    Files.walkFileTree(mRawResFile.toPath(), new ResourceFilesVisitor());

    if (!mRawResFile.exists() || !mRawResFile.isDirectory()) {
      throw new IOException("can not found res dir in the apk or it is not a dir");
    }

    //创建 resources_temp 文件对象（outDir 下）
    mOutTempARSCFile = new File(mOutDir.getAbsoluteFile().getAbsolutePath() + File.separator + "resources_temp.arsc");

    //创建 resources 文件对象（outDir 下）
    mOutARSCFile = new File(mOutDir.getAbsoluteFile().getAbsolutePath() + File.separator + "resources.arsc");

    //获取出入apk的名字
    String basename = apkFile.getName().substring(0, apkFile.getName().indexOf(".apk"));

    //创建resource_mapping_ 文件对象（outDir 下）
    mResMappingFile = new File(mOutDir.getAbsoluteFile().getAbsolutePath()
                               + File.separator
                               + TypedValue.RES_MAPPING_FILE
                               + basename
                               + TypedValue.TXT_FILE);

    //创建 merge_duplicated_res_mapping_文件对象（outDir 下）（合并重复文件的mapping）
    mMergeDuplicatedResMappingFile = new File(mOutDir.getAbsoluteFile().getAbsolutePath()
                             + File.separator
                             + TypedValue.MERGE_DUPLICATED_RES_MAPPING_FILE
                             + basename
                             + TypedValue.TXT_FILE);
  }

  /**
   * 根据config来修改压缩的值
   */
  private void dealWithCompressConfig() {
    //如果有需要压缩的文件，将文件设置为压缩格式
    if (config.mUseCompress) {
      HashSet<Pattern> patterns = config.mCompressPatterns;
      if (!patterns.isEmpty()) {
        for (Entry<String, Integer> entry : mCompressData.entrySet()) {
          String name = entry.getKey();
          for (Iterator<Pattern> it = patterns.iterator(); it.hasNext(); ) {
            Pattern p = it.next();
            if (p.matcher(name).matches()) {
              mCompressData.put(name, TypedValue.ZIP_DEFLATED);
            }
          }
        }
      }
    }
  }

  public HashMap<String, Integer> getCompressData() {
    return mCompressData;
  }

  public File getOutDir() {
    return mOutDir;
  }

  public void setOutDir(File outDir) throws AndrolibException {
    mOutDir = outDir;
  }

  public File getOutResFile() {
    return mOutResFile;
  }

  public File getRawResFile() {
    return mRawResFile;
  }

  public File getOutTempARSCFile() {
    return mOutTempARSCFile;
  }

  public File getOutARSCFile() {
    return mOutARSCFile;
  }

  public File getOutTempDir() {
    return mOutTempDir;
  }

  public File getResMappingFile() {
    return mResMappingFile;
  }

  public File getMergeDuplicatedResMappingFile() {
    return mMergeDuplicatedResMappingFile;
  }

  public void decode() throws AndrolibException, IOException, DirectoryException {

    //apk中是否包含resources.arsc 文件
    if (hasResources()) {

      //创建后续需要使用的文件及文件夹，以及修改压缩配置
      ensureFilePath();

      System.out.printf("decoding resources.arsc\n");

      //解析arsc文件 将 typeID 和 具体内容 存放在  mExistTypeNames 这个map中
      RawARSCDecoder.decode(apkFile.getDirectory().getFileInput("resources.arsc"));

      //解析arsc文件 并输出 ResPackage 将混淆后的名字 放入mCompressData中
      ResPackage[] pkgs = ARSCDecoder.decode(apkFile.getDirectory().getFileInput("resources.arsc"), this);

      //把没有纪录在resources.arsc的资源文件也拷进dest目录
      copyOtherResFiles();

      //将混淆写入 到 resources_temp （outDir 下） 中
      ARSCDecoder.write(apkFile.getDirectory().getFileInput("resources.arsc"), this, pkgs);
    }
  }

  class ResourceFilesVisitor extends SimpleFileVisitor<Path> {
    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      mRawResourceFiles.add(file);
      return FileVisitResult.CONTINUE;
    }
  }
}
