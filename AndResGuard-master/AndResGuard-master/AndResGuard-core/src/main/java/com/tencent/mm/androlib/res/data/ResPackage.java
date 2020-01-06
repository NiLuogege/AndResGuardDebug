/**
 * Copyright 2014 Ryszard Wiśniewski <brut.alll@gmail.com>
 * Copyright 2016 sim sun <sunsj1231@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.mm.androlib.res.data;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class ResPackage {
  private final String mName;

  //记录 资源id 和 混淆后的文件名称
  private final Map<Integer, String> mSpecNamesReplace;
  //记录 arsc name列的名称和 混淆后文件名 对应关系
  private final Map<String, Set<String>> mSpecNamesBlock;
  private boolean mCanProguard = false;

  public ResPackage(int id, String name) {
    this.mName = name;
    mSpecNamesReplace = new LinkedHashMap<>();
    mSpecNamesBlock = new LinkedHashMap<>();
  }

  public boolean isCanResguard() {
    return mCanProguard;
  }

  public void setCanResguard(boolean set) {
    mCanProguard = set;
  }

  public boolean hasSpecRepplace(String resID) {
    return mSpecNamesReplace.containsKey(resID);
  }

  public String getSpecRepplace(int resID) {
    return mSpecNamesReplace.get(resID);
  }

  public void putSpecNamesReplace(int resID, String value) {
    mSpecNamesReplace.put(resID, value);
  }

  public void putSpecNamesblock(String specName, String value) {
    Set<String> values = mSpecNamesBlock.get(specName);
    if (values == null) {
      values = new HashSet<>();
      mSpecNamesBlock.put(specName, values);
    }
    values.add(value);
  }

  public Map<String, Set<String>> getSpecNamesBlock() {
    return mSpecNamesBlock;
  }

  public String getName() {
    return mName;
  }

  @Override
  public String toString() {
    return mName;
  }
}
