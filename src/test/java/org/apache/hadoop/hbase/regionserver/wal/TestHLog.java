/**
 * Copyright 2007 The Apache Software Foundation
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
package org.apache.hadoop.hbase.regionserver.wal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.regionserver.wal.HLog.Reader;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.FSConstants.SafeModeAction;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.LeaseManager;
import org.apache.hadoop.io.SequenceFile;
import org.apache.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.apache.hadoop.hbase.util.HasThread;

/** JUnit test case for HLog */
public class TestHLog  {
  private static final Log LOG = LogFactory.getLog(TestHLog.class);
  {
    ((Log4JLogger)DataNode.LOG).getLogger().setLevel(Level.ALL);
    ((Log4JLogger)LeaseManager.LOG).getLogger().setLevel(Level.ALL);
    ((Log4JLogger)FSNamesystem.LOG).getLogger().setLevel(Level.ALL);
    ((Log4JLogger)DFSClient.LOG).getLogger().setLevel(Level.ALL);
    ((Log4JLogger)HLog.LOG).getLogger().setLevel(Level.ALL);
  }

  private static Configuration conf;
  private static FileSystem fs;
  private static Path dir;
  private static MiniDFSCluster cluster;
  private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static Path hbaseDir;
  private static Path oldLogDir;

  @Before
  public void setUp() throws Exception {

    FileStatus[] entries = fs.listStatus(new Path("/"));
    for (FileStatus dir : entries) {
      // Go directly to the NN to delete, since fs.delete will try to use
      // trash and directories like /user cannot be moved to trash.
      assertTrue(cluster.getNameNode().delete(
            dir.getPath().toUri().getPath(), true));
    }

  }

  @After
  public void tearDown() throws Exception {
  }
  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    // Make block sizes small.
    TEST_UTIL.getConfiguration().setInt("dfs.blocksize", 1024 * 1024);
    TEST_UTIL.getConfiguration().setInt(
        "hbase.regionserver.flushlogentries", 1);
    // needed for testAppendClose()
    TEST_UTIL.getConfiguration().setBoolean("dfs.support.append", true);
    // quicker heartbeat interval for faster DN death notification
    TEST_UTIL.getConfiguration().setInt("heartbeat.recheck.interval", 5000);
    TEST_UTIL.getConfiguration().setInt("dfs.heartbeat.interval", 1);
    TEST_UTIL.getConfiguration().setInt("dfs.socket.timeout", 5000);
    // faster failover with cluster.shutdown();fs.close() idiom
    TEST_UTIL.getConfiguration()
        .setInt("ipc.client.connect.max.retries", 1);
    TEST_UTIL.getConfiguration().setInt(
        "dfs.client.block.recovery.retries", 1);
    TEST_UTIL.getConfiguration().setBoolean(HConstants.HLOG_FORMAT_BACKWARD_COMPATIBILITY, false);
    TEST_UTIL.startMiniCluster(3);

    conf = TEST_UTIL.getConfiguration();
    cluster = TEST_UTIL.getDFSCluster();
    fs = cluster.getFileSystem();

    hbaseDir = new Path(TEST_UTIL.getConfiguration().get("hbase.rootdir"));
    oldLogDir = new Path(hbaseDir, ".oldlogs");
    dir = new Path(hbaseDir, getName());
  }
  private static String getName() {
    // TODO Auto-generated method stub
    return "TestHLog";
  }

  /**
   * Just write multiple logs then split.  Before fix for HADOOP-2283, this
   * would fail.
   * @throws IOException
   */
  @Test
  public void testSplit() throws IOException {

    final byte [] tableName = Bytes.toBytes(getName());
    final byte [] rowName = tableName;
    Path logdir = new Path(dir, HConstants.HREGION_LOGDIR_NAME);
    HLog log = new HLog(fs, logdir, oldLogDir, conf, null);
    final int howmany = 3;
    HRegionInfo[] infos = new HRegionInfo[3];
    for(int i = 0; i < howmany; i++) {
      infos[i] = new HRegionInfo(new HTableDescriptor(tableName),
                Bytes.toBytes("" + i), Bytes.toBytes("" + (i+1)), false);
    }
    // Add edits for three regions.
    try {
      for (int ii = 0; ii < howmany; ii++) {
        for (int i = 0; i < howmany; i++) {

          for (int j = 0; j < howmany; j++) {
            WALEdit edit = new WALEdit();
            byte [] family = Bytes.toBytes("column");
            byte [] qualifier = Bytes.toBytes(Integer.toString(j));
            byte [] column = Bytes.toBytes("column:" + Integer.toString(j));
            edit.add(new KeyValue(rowName, family, qualifier,
                System.currentTimeMillis(), column));
            LOG.info("Region " + i + ": " + edit);
            log.append(infos[i], tableName, edit,
              System.currentTimeMillis());
          }
        }
        log.rollWriter();
      }
      log.close();
      Path splitsdir = new Path(dir, "splits");
      List<Path> splits =
        HLog.splitLog(splitsdir, logdir, oldLogDir, fs, conf);
      verifySplits(splits, howmany);
      log = null;
    } finally {
      if (log != null) {
        log.closeAndDelete();
      }
    }
  }

  /**
   * Test new HDFS-265 sync.
   * @throws Exception
   */
  @Test
  public void testSync() throws Exception {
    byte [] bytes = Bytes.toBytes(getName());
    // First verify that using streams all works.
    Path p = new Path(dir, getName() + ".fsdos");
    FSDataOutputStream out = fs.create(p);
    out.write(bytes);
    out.sync();
    FSDataInputStream in = fs.open(p);
    assertTrue(in.available() > 0);
    byte [] buffer = new byte [1024];
    int read = in.read(buffer);
    assertEquals(bytes.length, read);
    out.close();
    in.close();
    Path subdir = new Path(dir, "hlogdir");
    HLog wal = new HLog(fs, subdir, oldLogDir, conf, null);
    final int total = 20;

    HRegionInfo info = new HRegionInfo(new HTableDescriptor(bytes),
                null,null, false);

    for (int i = 0; i < total; i++) {
      WALEdit kvs = new WALEdit();
      kvs.add(new KeyValue(Bytes.toBytes(i), bytes, bytes));
      wal.append(info, bytes, kvs, System.currentTimeMillis());
    }
    // Now call sync and try reading.  Opening a Reader before you sync just
    // gives you EOFE.
    wal.sync();
    // Open a Reader.
    Path walPath = wal.computeFilename();
    HLog.Reader reader = HLog.getReader(fs, walPath, conf);
    int count = 0;
    HLog.Entry entry = new HLog.Entry();
    while ((entry = reader.next(entry)) != null) count++;
    assertEquals(total + 1, count); // one extra, for the initial Dummy entry.
    reader.close();
    // Add test that checks to see that an open of a Reader works on a file
    // that has had a sync done on it.
    for (int i = 0; i < total; i++) {
      WALEdit kvs = new WALEdit();
      kvs.add(new KeyValue(Bytes.toBytes(i), bytes, bytes));
      wal.append(info, bytes, kvs, System.currentTimeMillis());
    }
    reader = HLog.getReader(fs, walPath, conf);
    count = 0;
    while((entry = reader.next(entry)) != null) count++;
    assertTrue(count >= total);
    reader.close();
    // If I sync, should see double the edits.
    wal.sync();
    reader = HLog.getReader(fs, walPath, conf);
    count = 0;
    while((entry = reader.next(entry)) != null) count++;
    assertEquals(total * 2 + 1, count); // one extra, for the initial Dummy entry
    // Now do a test that ensures stuff works when we go over block boundary,
    // especially that we return good length on file.
    final byte [] value = new byte[1025 * 1024];  // Make a 1M value.
    for (int i = 0; i < total; i++) {
      WALEdit kvs = new WALEdit();
      kvs.add(new KeyValue(Bytes.toBytes(i), bytes, value));
      wal.append(info, bytes, kvs, System.currentTimeMillis());
    }
    // Now I should have written out lots of blocks.  Sync then read.
    wal.sync();
    reader = HLog.getReader(fs, walPath, conf);
    count = 0;
    while((entry = reader.next(entry)) != null) count++;
    assertEquals(total * 3 + 1, count); // one extra, for the initial Dummy entry
    reader.close();
    // Close it and ensure that closed, Reader gets right length also.
    wal.close();
    reader = HLog.getReader(fs, walPath, conf);
    count = 0;
    while((entry = reader.next(entry)) != null) count++;
    assertEquals(total * 3 + 1, count); // one extra, for the initial Dummy entry
    reader.close();
  }

  /**
   * Test the findMemstoresWithEditsEqualOrOlderThan method.
   * @throws IOException
   */
  @Test
  public void testFindMemstoresWithEditsEqualOrOlderThan() throws IOException {
    Map<byte [], Long> regionsToSeqids1 = new HashMap<byte [], Long>();
    Map<byte [], Long> regionsToSeqids2 = new HashMap<byte [], Long>();
    for (int i = 0; i < 10; i++) {
      Long l = Long.valueOf(i);
      regionsToSeqids1.put(l.toString().getBytes(), l);
    }
    byte [][] regions =
      HLog.findMemstoresWithEditsEqualOrOlderThan(1, regionsToSeqids1, regionsToSeqids2);
    assertEquals(2, regions.length);
    assertTrue(Bytes.equals(regions[0], "0".getBytes()) ||
        Bytes.equals(regions[0], "1".getBytes()));
    regions = HLog.findMemstoresWithEditsEqualOrOlderThan(3, regionsToSeqids2,
        regionsToSeqids1);
    int count = 4;
    assertEquals(count, regions.length);
    // Regions returned are not ordered.
    for (int i = 0; i < count; i++) {
      assertTrue(Bytes.equals(regions[i], "0".getBytes()) ||
        Bytes.equals(regions[i], "1".getBytes()) ||
        Bytes.equals(regions[i], "2".getBytes()) ||
        Bytes.equals(regions[i], "3".getBytes()));
    }
  }

  private void verifySplits(List<Path> splits, final int howmany)
  throws IOException {
    assertEquals(howmany, splits.size());
    for (int i = 0; i < splits.size(); i++) {
      LOG.info("Verifying=" + splits.get(i));
      HLog.Reader reader = HLog.getReader(fs, splits.get(i), conf);
      try {
        int count = 0;
        String previousRegion = null;
        long seqno = -1;
        HLog.Entry entry = new HLog.Entry();
        while((entry = reader.next(entry)) != null) {
          HLogKey key = entry.getKey();
          String region = Bytes.toString(key.getRegionName());
          // Assert that all edits are for same region.
          if (previousRegion != null) {
            assertEquals(previousRegion, region);
          }
          LOG.info("oldseqno=" + seqno + ", newseqno=" + key.getLogSeqNum());
          assertTrue(seqno < key.getLogSeqNum());
          seqno = key.getLogSeqNum();
          previousRegion = region;
          count++;
        }
        assertEquals(howmany * howmany, count);
      } finally {
        reader.close();
      }
    }
  }

  // For this test to pass, requires:
  // 1. HDFS-200 (append support)
  // 2. HDFS-988 (SafeMode should freeze file operations
  //              [FSNamesystem.nextGenerationStampForBlock])
  // 3. HDFS-142 (on restart, maintain pendingCreates)
  @Test
  public void testAppendClose() throws Exception {
    byte [] tableName = Bytes.toBytes(getName());
    HRegionInfo regioninfo = new HRegionInfo(new HTableDescriptor(tableName),
        HConstants.EMPTY_START_ROW, HConstants.EMPTY_END_ROW, false);
    Path subdir = new Path(dir, "hlogdir");
    Path archdir = new Path(dir, "hlogdir_archive");
    HLog wal = new HLog(fs, subdir, archdir, conf, null);
    final int total = 20;

    for (int i = 0; i < total; i++) {
      WALEdit kvs = new WALEdit();
      kvs.add(new KeyValue(Bytes.toBytes(i), tableName, tableName));
      wal.append(regioninfo, tableName, kvs, System.currentTimeMillis());
    }
    // Now call sync to send the data to HDFS datanodes
    wal.sync(true);
    int namenodePort = cluster.getNameNodePort();
    final Path walPath = wal.computeFilename();

    // Stop the cluster.  (ensure restart since we're sharing MiniDFSCluster)
    try {
      cluster.getNameNode().setSafeMode(SafeModeAction.SAFEMODE_ENTER);
      cluster.shutdown();
      try {
        // wal.writer.close() will throw an exception,
        // but still call this since it closes the LogSyncer thread first
        wal.close();
      } catch (Throwable e) {
        // We can get a RunTimeException here as well.
        LOG.info(e);
      }
      fs.close(); // closing FS last so DFSOutputStream can't call close
      LOG.info("STOPPED first instance of the cluster");
    } finally {
      // Restart the cluster
      while (cluster.isClusterUp()){
        LOG.error("Waiting for cluster to go down");
        Thread.sleep(1000);
      }
      LOG.info("Waiting a few seconds before re-starting HDFS");
      Thread.sleep(5000);
      cluster = TEST_UTIL.startMiniDFSClusterForTestHLog(namenodePort);
      cluster.waitActive();
      fs = cluster.getFileSystem();
      LOG.info("STARTED second instance.");
    }

    // set the lease period to be 1 second so that the
    // namenode triggers lease recovery upon append request
    Method setLeasePeriod = cluster.getClass()
      .getDeclaredMethod("setLeasePeriod", new Class[]{Long.TYPE, Long.TYPE});
    setLeasePeriod.setAccessible(true);
    setLeasePeriod.invoke(cluster,
                          new Object[]{new Long(1000), new Long(1000)});
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      LOG.info(e);
    }

    // Now try recovering the log, like the HMaster would do
    final DistributedFileSystem recoveredFs = (DistributedFileSystem)fs;
    final Configuration rlConf = conf;

    class RecoverLogThread extends HasThread {
      public Exception exception = null;
      public void run() {
          try {
            FSUtils.recoverFileLease(recoveredFs, walPath, rlConf);
            assertTrue(recoveredFs.recoverLease(walPath));
          } catch (IOException e) {
            exception = e;
          } catch (NoSuchMethodException e) {
            exception = e;
          }
      }
    }

    RecoverLogThread t = new RecoverLogThread();
    t.start();
    // Timeout after 60 sec. Without correct patches, would be an infinite loop
    t.join(60 * 1000);
    if(t.isAlive()) {
      t.interrupt();
      throw new Exception("Timed out waiting for HLog.recoverLog()");
    }

    if (t.exception != null)
      throw t.exception;

    // Make sure you can read all the content
    SequenceFile.Reader reader
      = new SequenceFile.Reader(fs, walPath, conf);
    int count = 0;
    HLogKey key = HLog.newKey(conf);
    WALEdit val = new WALEdit();
    while (reader.next(key, val)) {
      if (Arrays.equals(key.getRegionName(), HLog.DUMMY)) {
        continue;
      }
      count++;
      assertTrue("Should be one KeyValue per WALEdit",
                 val.getKeyValues().size() == 1);
    }
    assertEquals(total, count);
    reader.close();
  }

  /**
   * Tests that we can write out an edit, close, and then read it back in again.
   * @throws IOException
   */
  @Test
  public void testEditAdd() throws IOException {
    final int COL_COUNT = 10;
    final byte [] tableName = Bytes.toBytes("tablename");
    final byte [] row = Bytes.toBytes("row");
    HLog.Reader reader = null;
    HLog log = new HLog(fs, dir, oldLogDir, conf, null);
    try {
      // Write columns named 1, 2, 3, etc. and then values of single byte
      // 1, 2, 3...
      long timestamp = System.currentTimeMillis();
      WALEdit cols = new WALEdit();
      for (int i = 0; i < COL_COUNT; i++) {
        cols.add(new KeyValue(row, Bytes.toBytes("column"),
            Bytes.toBytes(Integer.toString(i)),
          timestamp, new byte[] { (byte)(i + '0') }));
      }
      HRegionInfo info = new HRegionInfo(new HTableDescriptor(tableName),
        row,Bytes.toBytes(Bytes.toString(row) + "1"), false);
      final byte [] regionName = info.getRegionName();
      log.append(info, tableName, cols, System.currentTimeMillis());
      long logSeqId = log.startCacheFlush(info.getRegionName());
      log.completeCacheFlush(regionName, tableName, logSeqId, info.isMetaRegion());
      log.close();
      Path filename = log.computeFilename();
      log = null;
      // Now open a reader on the log and assert append worked.
      reader = HLog.getReader(fs, filename, conf);
      // Above we added all columns on a single row so we only read one
      // entry in the below... thats why we have '1'.
      for (int count = 0; count < 1; ) {
        HLog.Entry entry = reader.next(null);
        if (entry == null) break;
        HLogKey key = entry.getKey();
        WALEdit val = entry.getEdit();
        if (Arrays.equals(key.getRegionName(), HLog.DUMMY)) {
          continue;
        }
        assertTrue(Bytes.equals(regionName, key.getRegionName()));
        assertTrue(Bytes.equals(tableName, key.getTablename()));
        KeyValue kv = val.getKeyValues().get(0);
        assertTrue(Bytes.equals(row, kv.getRow()));
        assertEquals((byte)(count + '0'), kv.getValue()[0]);
        System.out.println(key + " " + val);
        count++;
      }
    } finally {
      if (log != null) {
        log.closeAndDelete();
      }
      if (reader != null) {
        reader.close();
      }
    }
  }

  /**
   * @throws IOException
   */
  @Test
  public void testAppend() throws IOException {
    final int COL_COUNT = 10;
    final byte [] tableName = Bytes.toBytes("tablename");
    final byte [] row = Bytes.toBytes("row");
    Reader reader = null;
    HLog log = new HLog(fs, dir, oldLogDir, conf, null);
    try {
      // Write columns named 1, 2, 3, etc. and then values of single byte
      // 1, 2, 3...
      long timestamp = System.currentTimeMillis();
      WALEdit cols = new WALEdit();
      for (int i = 0; i < COL_COUNT; i++) {
        cols.add(new KeyValue(row, Bytes.toBytes("column"),
          Bytes.toBytes(Integer.toString(i)),
          timestamp, new byte[] { (byte)(i + '0') }));
      }
      HRegionInfo hri = new HRegionInfo(new HTableDescriptor(tableName),
          HConstants.EMPTY_START_ROW, HConstants.EMPTY_END_ROW);
      log.append(hri, tableName, cols, System.currentTimeMillis());
      long logSeqId = log.startCacheFlush(hri.getRegionName());
      log.completeCacheFlush(hri.getRegionName(), tableName, logSeqId, false);
      log.close();
      Path filename = log.computeFilename();
      log = null;
      // Now open a reader on the log and assert append worked.
      reader = HLog.getReader(fs, filename, conf);
      reader.next(); // skip the dummy entry.
      HLog.Entry entry = reader.next();
      assertEquals(COL_COUNT, entry.getEdit().size());
      int idx = 0;
      for (KeyValue val : entry.getEdit().getKeyValues()) {
        assertTrue(Bytes.equals(hri.getRegionName(),
          entry.getKey().getRegionName()));
        assertTrue(Bytes.equals(tableName, entry.getKey().getTablename()));
        assertTrue(Bytes.equals(row, val.getRow()));
        assertEquals((byte)(idx + '0'), val.getValue()[0]);
        System.out.println(entry.getKey() + " " + val);
        idx++;
      }
    } finally {
      if (log != null) {
        log.closeAndDelete();
      }
      if (reader != null) {
        reader.close();
      }
    }
  }
  
  @Test
  public void testLogCleaning() throws Exception {
    LOG.info("testLogCleaning");
    final byte [] tableName = Bytes.toBytes("testLogCleaning");
    final byte [] tableName2 = Bytes.toBytes("testLogCleaning2");

    HLog log = new HLog(fs, dir, oldLogDir, conf, null);
    HRegionInfo hri = new HRegionInfo(new HTableDescriptor(tableName),
        HConstants.EMPTY_START_ROW, HConstants.EMPTY_END_ROW);
    HRegionInfo hri2 = new HRegionInfo(new HTableDescriptor(tableName2),
        HConstants.EMPTY_START_ROW, HConstants.EMPTY_END_ROW);

    // Add a single edit and make sure that rolling won't remove the file
    // Before HBASE-3198 it used to delete it
    addEdits(log, hri, tableName, 1);
    log.rollWriter();
    assertEquals(1, log.getNumLogFiles());

    // See if there's anything wrong with more than 1 edit
    addEdits(log, hri, tableName, 2);
    log.rollWriter();
    assertEquals(2, log.getNumLogFiles());

    // Now mix edits from 2 regions, still no flushing
    addEdits(log, hri, tableName, 1);
    addEdits(log, hri2, tableName2, 1);
    addEdits(log, hri, tableName, 1);
    addEdits(log, hri2, tableName2, 1);
    log.rollWriter();
    assertEquals(3, log.getNumLogFiles());

    // Flush the first region, we expect to see the first two files getting
    // archived
    addEdits(log, hri, tableName, 1);
    long seqId = log.startCacheFlush(hri.getRegionName());
    log.completeCacheFlush(hri.getRegionName(), tableName, seqId, false);
    log.rollWriter();
    assertEquals(2, log.getNumLogFiles());

    // Flush the second region, which removes all the remaining output files
    // since the oldest was completely flushed.
    addEdits(log, hri2, tableName2, 1);
    seqId = log.startCacheFlush(hri2.getRegionName());
    log.completeCacheFlush(hri2.getRegionName(), tableName2, seqId, false);
    log.rollWriter();
    assertEquals(0, log.getNumLogFiles());
  }

  private void addEdits(HLog log, HRegionInfo hri, byte [] tableName,
                        int times) throws IOException {
    final byte [] row = Bytes.toBytes("row");
    for (int i = 0; i < times; i++) {
      long timestamp = System.currentTimeMillis();
      WALEdit cols = new WALEdit();
      cols.add(new KeyValue(row, row, row, timestamp, row));
      log.append(hri, tableName, cols, timestamp);
    }
  }

  static class DumbLogEntriesVisitor implements LogEntryVisitor {

    int increments = 0;

    @Override
    public void visitLogEntryBeforeWrite(HRegionInfo info, HLogKey logKey,
                                         WALEdit logEdit) {
      increments++;
    }
  }
}
