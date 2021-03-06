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

package org.apache.hadoop.hbase.regionserver.kvaggregator;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.regionserver.ScanQueryMatcher.MatchCode;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * <p>
 * Dummy test aggregator which takes the String Value of the KV which is
 * expected to be in lowercase and transforms it to uppercase
 * </p>
 */
public class LowerToUpperAggregator implements KeyValueAggregator {

  @Override
  public void reset() {
  }

  @Override
  public KeyValue process(KeyValue kv) {
    byte[] newValue;
    String currentValue = Bytes.toString(kv.getValue());
    /**
     * transform it to uppercase.
     */
    String newValueString = currentValue.toUpperCase();
    newValue = Bytes.toBytes(newValueString);
    KeyValue newKv = new KeyValue(kv.getRow(), kv.getFamily(),
        kv.getQualifier(), kv.getTimestamp(), newValue);
    return newKv;
  }

  @Override
  public MatchCode nextAction(MatchCode origCode) {
    return origCode;
  }

  @Override
  public KeyValue finalizeKeyValues() {
    return null;
  }
}
