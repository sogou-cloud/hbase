/**
 * Copyright The Apache Software Foundation.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.hadoop.hbase.regionserver.compactionhook;

import org.apache.hadoop.hbase.regionserver.RestrictedKeyValue;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * <p>
 * Dummy compaction hook which transforms a lower-case value in KV to
 * upper-case, implements CompactionHook interface.
 * </p>
 *
 */
public class LowerToUpperCompactionHook implements CompactionHook {

  @Override
  public RestrictedKeyValue transform (RestrictedKeyValue kv) {
    byte[] newValue;
    String currentValue = Bytes.toString(kv.getValue());
    /**
     * transform it to uppercase.
     */
    String newValueString = currentValue.toUpperCase();
    newValue = Bytes.toBytes(newValueString);
    kv.modifyValue(newValue);
    return kv;
  }
}
