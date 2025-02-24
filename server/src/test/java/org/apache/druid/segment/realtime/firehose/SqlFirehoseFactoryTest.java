/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.realtime.firehose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.apache.druid.data.input.Firehose;
import org.apache.druid.data.input.Row;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.InputRowParser;
import org.apache.druid.data.input.impl.MapInputRowParser;
import org.apache.druid.data.input.impl.TimeAndDimsParseSpec;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.metadata.TestDerbyConnector;
import org.apache.druid.metadata.input.SqlTestUtils;
import org.apache.druid.segment.TestHelper;
import org.apache.druid.segment.transform.TransformSpec;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class SqlFirehoseFactoryTest
{
  private static final List<File> FIREHOSE_TMP_DIRS = new ArrayList<>();
  private static File TEST_DIR;
  private final String TABLE_NAME_1 = "FOOS_TABLE_1";
  private final String TABLE_NAME_2 = "FOOS_TABLE_2";

  private final List<String> SQLLIST1 = ImmutableList.of("SELECT timestamp,a,b FROM FOOS_TABLE_1");
  private final List<String> SQLLIST2 = ImmutableList.of(
      "SELECT timestamp,a,b FROM FOOS_TABLE_1",
      "SELECT timestamp,a,b FROM FOOS_TABLE_2"
  );

  @Rule
  public final TestDerbyConnector.DerbyConnectorRule derbyConnectorRule = new TestDerbyConnector.DerbyConnectorRule();
  private final ObjectMapper mapper = TestHelper.makeSmileMapper();

  private final InputRowParser parser = TransformSpec.NONE.decorate(
      new MapInputRowParser(
        new TimeAndDimsParseSpec(
            new TimestampSpec("timestamp", "auto", null),
            new DimensionsSpec(
                DimensionsSpec.getDefaultSchemas(Arrays.asList("timestamp", "a", "b")),
                new ArrayList<>(),
                new ArrayList<>()
            )
        )
      )
  );
  private TestDerbyConnector derbyConnector;

  @BeforeClass
  public static void setup() throws IOException
  {
    TEST_DIR = File.createTempFile(SqlFirehoseFactoryTest.class.getSimpleName(), "testDir");
    org.apache.commons.io.FileUtils.forceDelete(TEST_DIR);
    FileUtils.mkdirp(TEST_DIR);
  }

  @AfterClass
  public static void teardown() throws IOException
  {
    org.apache.commons.io.FileUtils.forceDelete(TEST_DIR);
    for (File dir : FIREHOSE_TMP_DIRS) {
      org.apache.commons.io.FileUtils.forceDelete(dir);
    }
  }

  private void assertResult(List<Row> rows, List<String> sqls)
  {
    Assert.assertEquals(10 * sqls.size(), rows.size());
    rows.sort(Comparator.comparing(Row::getTimestamp)
                        .thenComparingInt(r -> Integer.valueOf(r.getDimension("a").get(0)))
                        .thenComparingInt(r -> Integer.valueOf(r.getDimension("b").get(0))));
    int rowCount = 0;
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < sqls.size(); j++) {
        final Row row = rows.get(rowCount);
        String timestampSt = StringUtils.format("2011-01-12T00:0%s:00.000Z", i);
        Assert.assertEquals(timestampSt, row.getTimestamp().toString());
        Assert.assertEquals(i, Integer.valueOf(row.getDimension("a").get(0)).intValue());
        Assert.assertEquals(i, Integer.valueOf(row.getDimension("b").get(0)).intValue());
        rowCount++;
      }
    }
  }

  private void assertNumRemainingCacheFiles(File firehoseTmpDir, int expectedNumFiles)
  {
    final String[] files = firehoseTmpDir.list();
    Assert.assertNotNull(files);
    Assert.assertEquals(expectedNumFiles, files.length);
  }

  private File createFirehoseTmpDir(String dirSuffix) throws IOException
  {
    final File firehoseTempDir = File.createTempFile(
        SqlFirehoseFactoryTest.class.getSimpleName(),
        dirSuffix
    );
    org.apache.commons.io.FileUtils.forceDelete(firehoseTempDir);
    FileUtils.mkdirp(firehoseTempDir);
    FIREHOSE_TMP_DIRS.add(firehoseTempDir);
    return firehoseTempDir;
  }

  @Test
  public void testWithoutCacheAndFetch() throws Exception
  {
    derbyConnector = derbyConnectorRule.getConnector();
    SqlTestUtils testUtils = new SqlTestUtils(derbyConnector);
    testUtils.createAndUpdateTable(TABLE_NAME_1, 10);
    final SqlFirehoseFactory factory =
        new SqlFirehoseFactory(
            SQLLIST1,
            0L,
            0L,
            0L,
            0L,
            true,
            testUtils.getDerbyFirehoseConnector(),
            mapper
        );

    final List<Row> rows = new ArrayList<>();
    final File firehoseTmpDir = createFirehoseTmpDir("testWithoutCacheAndFetch");
    try (Firehose firehose = factory.connect(parser, firehoseTmpDir)) {
      while (firehose.hasMore()) {
        rows.add(firehose.nextRow());
      }
    }

    assertResult(rows, SQLLIST1);
    assertNumRemainingCacheFiles(firehoseTmpDir, 0);
    testUtils.dropTable(TABLE_NAME_1);
  }


  @Test
  public void testWithoutCache() throws IOException
  {
    derbyConnector = derbyConnectorRule.getConnector();
    SqlTestUtils testUtils = new SqlTestUtils(derbyConnector);
    testUtils.createAndUpdateTable(TABLE_NAME_1, 10);
    final SqlFirehoseFactory factory =
        new SqlFirehoseFactory(
            SQLLIST1,
            0L,
            null,
            null,
            null,
            true,
            testUtils.getDerbyFirehoseConnector(),
            mapper
        );


    final List<Row> rows = new ArrayList<>();
    final File firehoseTmpDir = createFirehoseTmpDir("testWithoutCache");
    try (Firehose firehose = factory.connect(parser, firehoseTmpDir)) {
      while (firehose.hasMore()) {
        rows.add(firehose.nextRow());
      }
    }

    assertResult(rows, SQLLIST1);
    assertNumRemainingCacheFiles(firehoseTmpDir, 0);
    testUtils.dropTable(TABLE_NAME_1);
  }


  @Test
  public void testWithCacheAndFetch() throws IOException
  {
    derbyConnector = derbyConnectorRule.getConnector();
    SqlTestUtils testUtils = new SqlTestUtils(derbyConnector);
    testUtils.createAndUpdateTable(TABLE_NAME_1, 10);
    testUtils.createAndUpdateTable(TABLE_NAME_2, 10);

    final SqlFirehoseFactory factory = new
        SqlFirehoseFactory(
        SQLLIST2,
        null,
        null,
        0L,
        null,
        true,
        testUtils.getDerbyFirehoseConnector(),
        mapper
    );

    final List<Row> rows = new ArrayList<>();
    final File firehoseTmpDir = createFirehoseTmpDir("testWithCacheAndFetch");
    try (Firehose firehose = factory.connect(parser, firehoseTmpDir)) {
      while (firehose.hasMore()) {
        rows.add(firehose.nextRow());
      }
    }

    assertResult(rows, SQLLIST2);
    assertNumRemainingCacheFiles(firehoseTmpDir, 2);
    testUtils.dropTable(TABLE_NAME_1);
    testUtils.dropTable(TABLE_NAME_2);

  }
}
