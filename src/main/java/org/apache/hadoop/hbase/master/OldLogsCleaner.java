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

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Chore;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.RemoteExceptionHandler;
import org.apache.hadoop.hbase.Stoppable;
import org.apache.hadoop.hbase.regionserver.wal.HLog;

/**
 * This Chore, everytime it runs, will clear the logs in the old logs folder
 * that are older than hbase.master.logcleaner.ttl and, in order to limit the
 * number of deletes it sends, will only delete maximum 20 in a single run.
 */
public class OldLogsCleaner extends Chore {

  static final Log LOG = LogFactory.getLog(OldLogsCleaner.class.getName());

  // Max number we can delete on every chore, this is to make sure we don't
  // issue thousands of delete commands around the same time
  private final int maxDeletedLogs;
  private final FileSystem fs;
  private final Path oldLogDir;
  private final LogCleanerDelegate logCleaner;
  private final Configuration conf;

  /**
   *
   * @param p the period of time to sleep between each run
   * @param s the stopper
   * @param conf configuration to use
   * @param fs handle to the FS
   * @param oldLogDir the path to the archived logs
   */
  public OldLogsCleaner(final int p, final Stoppable s,
                        Configuration conf, FileSystem fs,
                        Path oldLogDir) {
    super("OldLogsCleaner", p, s);
    // Use the log cleaner provided by replication if enabled, unless something
    // was already provided
    if (conf.getBoolean(HConstants.REPLICATION_ENABLE_KEY, false) &&
        conf.get("hbase.master.logcleanerplugin.impl") == null) {
      conf.set("hbase.master.logcleanerplugin.impl",
          "org.apache.hadoop.hbase.replication.master.ReplicationLogCleaner");
    }
    this.maxDeletedLogs =
        conf.getInt("hbase.master.logcleaner.maxdeletedlogs", 20);
    this.fs = fs;
    this.oldLogDir = oldLogDir;
    this.conf = conf;
    this.logCleaner = getLogCleaner();
  }

  private LogCleanerDelegate getLogCleaner() {
    try {
      Class c = Class.forName(conf.get("hbase.master.logcleanerplugin.impl",
        TimeToLiveLogCleaner.class.getCanonicalName()));
      LogCleanerDelegate cleaner = (LogCleanerDelegate) c.newInstance();
      cleaner.setConf(conf);
      return cleaner;
    } catch (Exception e) {
      LOG.warn("Passed log cleaner implementation throws errors, " +
          "defaulting to TimeToLiveLogCleaner", e);
      return new TimeToLiveLogCleaner();
    }
  }

  /**
   * Delete log files directories recursively.
   * @param files The list of files/directories to traverse.
   * @param deleteCountLeft Max number of files to delete
   * @param maxDepth Max Directory depth to recurse
   * @return Number of files left to delete (deleteCountLeft - number deleted)
   * @throws IOException
   */
  private int cleanFiles(FileStatus[] files, int deleteCountLeft,
                         int maxDepth) throws IOException {
    if (files == null || files.length == 0) return deleteCountLeft;
    if (maxDepth <= 0) {
      LOG.warn("Old Logs directory structure is too deep: " + files[0].getPath());
      return deleteCountLeft;
    }
    for (FileStatus file : files) {
      if (deleteCountLeft <= 0) return 0; // we don't have anymore to delete
      if (file.isDir()) {
        deleteCountLeft = cleanFiles(this.fs.listStatus(file.getPath()),
                                     deleteCountLeft, maxDepth - 1);
        continue;
      }
      Path filePath = file.getPath();
      if (HLog.validateHLogFilename(filePath.getName())) {
        if (logCleaner.isLogDeletable(filePath) ) {
          this.fs.delete(filePath, true);
          deleteCountLeft--;
        }
      } else {
        LOG.warn("Found a wrongly formatted file: "
            + file.getPath().getName());
        this.fs.delete(filePath, true);
        deleteCountLeft--;
      }
    }
    return deleteCountLeft;
  }

  @Override
  protected void chore() {
    try {
      cleanFiles(this.fs.listStatus(this.oldLogDir), maxDeletedLogs, 2);
    } catch (IOException e) {
      e = RemoteExceptionHandler.checkIOException(e);
      LOG.warn("Error while cleaning the logs", e);
    }
  }
}
