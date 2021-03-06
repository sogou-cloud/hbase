/**
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.master;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.RemoteExceptionHandler;
import org.apache.hadoop.hbase.TableNotDisabledException;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.ipc.HRegionInterface;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Sleeper;
import org.apache.hadoop.ipc.RemoteException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Uses Callable pattern so that operations against meta regions do not need
 * to duplicate retry logic.
 */
abstract class RetryableMetaOperation<T> implements Callable<T> {
  protected final Log LOG = LogFactory.getLog(this.getClass());
  protected final Sleeper sleeper;
  protected final MetaRegion m;
  protected final HMaster master;

  protected HRegionInterface server;

  protected RetryableMetaOperation(MetaRegion m, HMaster master) {
    this.m = m;
    this.master = master;
    this.sleeper = new Sleeper(this.master.getThreadWakeFrequency(),
      master.getStopper());
  }

  protected T doWithRetries()
  throws IOException, RuntimeException {
    List<IOException> exceptions = new ArrayList<IOException>();
    for (int tries = 0; tries < this.master.getNumRetries(); tries++) {
      if (this.master.isClosed()) {
        return null;
      }
      try {
        this.server =
          this.master.getServerConnection().getHRegionConnection(m.getServer());
        return this.call();
      } catch (IOException e) {
        if (e instanceof TableNotFoundException ||
            e instanceof TableNotDisabledException ||
            e instanceof InvalidColumnNameException) {
          throw e;
        }
        if (e instanceof RemoteException) {
          e = RemoteExceptionHandler.decodeRemoteException((RemoteException) e);
        }
        if (tries == this.master.getNumRetries() - 1) {
          if (LOG.isDebugEnabled()) {
            StringBuilder message = new StringBuilder(
                "Trying to contact region server for regionName '" +
                Bytes.toString(m.getRegionName()) + "', but failed after " +
                (tries + 1) + " attempts.\n");
            int i = 1;
            for (IOException e2 : exceptions) {
              message.append("Exception " + i + ":\n" + e2);
            }
            LOG.debug(message);
          }
          this.master.checkFileSystem(false);
          throw e;
        }
        if (LOG.isDebugEnabled()) {
          exceptions.add(e);
        }
      } catch (Exception e) {
        LOG.debug("Exception in RetryableMetaOperation: ", e);
        throw new RuntimeException(e);
      }
      this.sleeper.sleep();
    }
    return null;
  }
}