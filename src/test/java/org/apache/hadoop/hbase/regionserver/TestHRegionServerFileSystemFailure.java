package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.JVMClusterUtil.RegionServerThread;

import static org.junit.Assert.*;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.apache.hadoop.hbase.util.HasThread;

public class TestHRegionServerFileSystemFailure {
  private static final Log LOG = LogFactory
      .getLog(TestHRegionServerFileSystemFailure.class);
  private static HBaseTestingUtility TEST_UTIL;
  private static byte[][] FAMILIES = { Bytes.toBytes("f1"),
      Bytes.toBytes("f2"), Bytes.toBytes("f3"), Bytes.toBytes("f4") };
  private static final int nLoaders = 10;
  private static Configuration conf;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    conf = new HBaseConfiguration().create();
    conf.setBoolean("ipc.client.ping", true);
    conf.setInt("ipc.ping.interval", 5000);
    TEST_UTIL = new HBaseTestingUtility(conf);
    TEST_UTIL.startMiniCluster(3);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  private static class TableLoader extends HasThread {
    private final HTable table;

    public TableLoader(HTable table) {
      this.table = table;
    }

    @Override
    public void run() {
      while (true) {
        try {
          for (int i = 0; i < FAMILIES.length; i++) {
            byte[] columnFamily = FAMILIES[i];
            TEST_UTIL.loadTable(table, columnFamily);
          }
        } catch (IOException e) {
          LOG.warn(e);
          break;
        }
      }
    }
  }

  @Test
  public void testHRegionServerFileSystemFailure() throws Exception {
    // Build some data.
    byte[] tableName = Bytes.toBytes("testCloseHRegion");
    HTable table = TEST_UTIL.createTable(tableName, FAMILIES);

    for (int i = 0; i < nLoaders; i++) {
      new TableLoader(table).start();
    }

    // Wait for loaders to build up some data.
    Thread.sleep(1000);


    // Bring down HDFS.
    TEST_UTIL.getDFSCluster().shutdownNameNode();

    // Verify checkFileSystem returns false.
    List <RegionServerThread> servers = TEST_UTIL.getHBaseCluster().getLiveRegionServerThreads();
    for(RegionServerThread serverThread : servers) {
      HRegionServer server = serverThread.getRegionServer();
      if (serverThread.isAlive() && !server.isStopRequested()) {
        server.checkFileSystem();
        assertFalse(server.fsOk);
        break;
      }
    }

    // Bring namenode, hbasemaster back up so we cleanup properly.
    TEST_UTIL.getDFSCluster().restartNameNode();
    HMaster master = TEST_UTIL.getMiniHBaseCluster().getMaster();
    if (master.isClosed()) {
      // Master died, we need to re-start it.
      TEST_UTIL.getMiniHBaseCluster().startNewMaster();
    }
  }
}
