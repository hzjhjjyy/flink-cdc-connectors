/*
 * Copyright 2023 Ververica Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.connectors.oracle.source;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.runtime.highavailability.nonha.embedded.HaLeadershipControl;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.planner.factories.TestValuesTableFactory;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

import org.apache.commons.lang3.StringUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.apache.flink.util.Preconditions.checkState;
import static org.junit.Assert.assertNotNull;

/** IT tests for {@link OracleSourceBuilder.OracleIncrementalSource}. */
public class OracleSourceITCase extends OracleSourceTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(OracleSourceITCase.class);

    @Rule public final Timeout timeoutPerTest = Timeout.seconds(300);

    @Test
    public void testReadSingleTableWithSingleParallelism() throws Exception {
        String[] captureCustomerTables = new String[] {"CUSTOMERS"};
        testOracleParallelSource(
                1,
                FailoverType.NONE,
                FailoverPhase.NEVER,
                ProjectionType.FULL,
                captureCustomerTables,
                getSourceDDL(captureCustomerTables),
                initSnapshotForSingleTable(),
                initRedoLogForSingleTable());
    }

    @Test
    public void testReadSingleTableWithMultipleParallelism() throws Exception {
        String[] captureCustomerTables = new String[] {"CUSTOMERS"};
        testOracleParallelSource(
                4,
                FailoverType.NONE,
                FailoverPhase.NEVER,
                ProjectionType.FULL,
                captureCustomerTables,
                getSourceDDL(captureCustomerTables),
                initSnapshotForSingleTable(),
                initRedoLogForSingleTable());
    }

    @Test
    public void testReadSingleTableWithSingleParallelismAndProjection() throws Exception {
        String[] captureCustomerTables = new String[] {"CUSTOMERS"};
        testOracleParallelSource(
                1,
                FailoverType.NONE,
                FailoverPhase.NEVER,
                ProjectionType.PARTIAL,
                captureCustomerTables,
                getSourceDDLWithProjection(captureCustomerTables),
                initSnapshotForSingleTableWithProjection(),
                initRedoLogForSingleTableWithProjection());
    }

    @Test
    public void testReadSingleTableWithMultipleParallelismAndProjection() throws Exception {
        String[] captureCustomerTables = new String[] {"CUSTOMERS"};
        testOracleParallelSource(
                4,
                FailoverType.NONE,
                FailoverPhase.NEVER,
                ProjectionType.PARTIAL,
                captureCustomerTables,
                getSourceDDLWithProjection(captureCustomerTables),
                initSnapshotForSingleTableWithProjection(),
                initRedoLogForSingleTableWithProjection());
    }

    // Failover tests
    @Test
    public void testTaskManagerFailoverInSnapshotPhase() throws Exception {
        testOracleParallelSource(
                FailoverType.TM,
                FailoverPhase.SNAPSHOT,
                ProjectionType.FULL,
                new String[] {"CUSTOMERS"});
    }

    @Test
    public void testTaskManagerFailoverInRedoLogPhase() throws Exception {
        testOracleParallelSource(
                FailoverType.TM,
                FailoverPhase.REDO_LOG,
                ProjectionType.FULL,
                new String[] {"CUSTOMERS"});
    }

    @Test
    public void testJobManagerFailoverInSnapshotPhase() throws Exception {
        testOracleParallelSource(
                FailoverType.JM,
                FailoverPhase.SNAPSHOT,
                ProjectionType.FULL,
                new String[] {"CUSTOMERS"});
    }

    @Test
    public void testJobManagerFailoverInRedoLogPhase() throws Exception {
        testOracleParallelSource(
                FailoverType.JM,
                FailoverPhase.REDO_LOG,
                ProjectionType.FULL,
                new String[] {"CUSTOMERS"});
    }

    @Test
    public void testTaskManagerFailoverSingleParallelism() throws Exception {
        String[] captureCustomerTables = new String[] {"CUSTOMERS"};
        testOracleParallelSource(
                1,
                FailoverType.TM,
                FailoverPhase.SNAPSHOT,
                ProjectionType.FULL,
                captureCustomerTables,
                getSourceDDL(captureCustomerTables),
                initSnapshotForSingleTable(),
                initRedoLogForSingleTable());
    }

    @Test
    public void testJobManagerFailoverSingleParallelism() throws Exception {
        String[] captureCustomerTables = new String[] {"CUSTOMERS"};
        testOracleParallelSource(
                1,
                FailoverType.JM,
                FailoverPhase.SNAPSHOT,
                ProjectionType.FULL,
                captureCustomerTables,
                getSourceDDL(captureCustomerTables),
                initSnapshotForSingleTable(),
                initRedoLogForSingleTable());
    }

    private void testOracleParallelSource(
            FailoverType failoverType,
            FailoverPhase failoverPhase,
            ProjectionType projectionType,
            String[] captureCustomerTables)
            throws Exception {
        testOracleParallelSource(
                DEFAULT_PARALLELISM,
                failoverType,
                failoverPhase,
                projectionType,
                captureCustomerTables,
                getSourceDDL(captureCustomerTables),
                initSnapshotForSingleTable(),
                initRedoLogForSingleTable());
    }

    private void testOracleParallelSource(
            int parallelism,
            FailoverType failoverType,
            FailoverPhase failoverPhase,
            ProjectionType projectionType,
            String[] captureCustomerTables,
            String sourceDDL,
            String[] snapshotForSingleTable,
            String[] redoLogForSingleTable)
            throws Exception {
        createAndInitialize("customer.sql");
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        StreamTableEnvironment tEnv = StreamTableEnvironment.create(env);

        env.setParallelism(parallelism);
        env.enableCheckpointing(200L);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(1, 0));

        // first step: check the snapshot data
        tEnv.executeSql(sourceDDL);
        TableResult tableResult = tEnv.executeSql("select * from products");
        CloseableIterator<Row> iterator = tableResult.collect();
        JobID jobId = tableResult.getJobClient().get().getJobID();
        List<String> expectedSnapshotData = new ArrayList<>();
        for (int i = 0; i < captureCustomerTables.length; i++) {
            expectedSnapshotData.addAll(Arrays.asList(snapshotForSingleTable));
        }

        // trigger failover after some snapshot splits read finished
        if (failoverPhase == FailoverPhase.SNAPSHOT && iterator.hasNext()) {
            triggerFailover(
                    failoverType, jobId, miniClusterResource.getMiniCluster(), () -> sleepMs(100));
        }

        LOG.info("snapshot data start");
        assertEqualsInAnyOrder(
                expectedSnapshotData, fetchRows(iterator, expectedSnapshotData.size()));

        // second step: check the redo log data
        for (String tableId : captureCustomerTables) {
            makeFirstPartRedoLogEvents(ORACLE_SCHEMA + '.' + tableId, projectionType);
        }
        if (failoverPhase == FailoverPhase.REDO_LOG) {
            triggerFailover(
                    failoverType, jobId, miniClusterResource.getMiniCluster(), () -> sleepMs(200));
        }
        for (String tableId : captureCustomerTables) {
            makeSecondPartRedoLogEvents(ORACLE_SCHEMA + '.' + tableId, projectionType);
        }

        List<String> expectedRedoLogData = new ArrayList<>();
        for (int i = 0; i < captureCustomerTables.length; i++) {
            expectedRedoLogData.addAll(Arrays.asList(redoLogForSingleTable));
        }
        assertEqualsInAnyOrder(
                expectedRedoLogData, fetchRows(iterator, expectedRedoLogData.size()));
        tableResult.getJobClient().get().cancel().get();
    }

    private String getSourceDDL(String[] captureCustomerTables) {
        return format(
                "CREATE TABLE products ("
                        + " ID INT NOT NULL,"
                        + " NAME STRING,"
                        + " ADDRESS STRING,"
                        + " PHONE_NUMBER STRING,"
                        + " primary key (ID) not enforced"
                        + ") WITH ("
                        + " 'connector' = 'oracle-cdc',"
                        + " 'hostname' = '%s',"
                        + " 'port' = '%s',"
                        + " 'username' = '%s',"
                        + " 'password' = '%s',"
                        + " 'database-name' = '%s',"
                        + " 'schema-name' = '%s',"
                        + " 'table-name' = '%s',"
                        + " 'scan.incremental.snapshot.enabled' = 'false',"
                        + " 'debezium.log.mining.strategy' = 'online_catalog',"
                        + " 'debezium.log.mining.continuous.mine' = 'true',"
                        + " 'debezium.database.history.store.only.captured.tables.ddl' = 'true'"
                        + ")",
                ORACLE_CONTAINER.getHost(),
                ORACLE_CONTAINER.getOraclePort(),
                ORACLE_CONTAINER.getUsername(),
                ORACLE_CONTAINER.getPassword(),
                ORACLE_DATABASE,
                ORACLE_SCHEMA,
                getTableNameRegex(captureCustomerTables) // (customer|customer_1)
                );
    }

    private String[] initSnapshotForSingleTable() {
        return new String[] {
            "+I[101, user_1, Shanghai, 123567891234]",
            "+I[102, user_2, Shanghai, 123567891234]",
            "+I[103, user_3, Shanghai, 123567891234]",
            "+I[109, user_4, Shanghai, 123567891234]",
            "+I[110, user_5, Shanghai, 123567891234]",
            "+I[111, user_6, Shanghai, 123567891234]",
            "+I[118, user_7, Shanghai, 123567891234]",
            "+I[121, user_8, Shanghai, 123567891234]",
            "+I[123, user_9, Shanghai, 123567891234]",
            "+I[1009, user_10, Shanghai, 123567891234]",
            "+I[1010, user_11, Shanghai, 123567891234]",
            "+I[1011, user_12, Shanghai, 123567891234]",
            "+I[1012, user_13, Shanghai, 123567891234]",
            "+I[1013, user_14, Shanghai, 123567891234]",
            "+I[1014, user_15, Shanghai, 123567891234]",
            "+I[1015, user_16, Shanghai, 123567891234]",
            "+I[1016, user_17, Shanghai, 123567891234]",
            "+I[1017, user_18, Shanghai, 123567891234]",
            "+I[1018, user_19, Shanghai, 123567891234]",
            "+I[1019, user_20, Shanghai, 123567891234]",
            "+I[2000, user_21, Shanghai, 123567891234]"
        };
    }

    private String[] initRedoLogForSingleTable() {
        return new String[] {
            "-U[103, user_3, Shanghai, 123567891234]",
            "+U[103, user_3, Hangzhou, 123567891234]",
            "-D[102, user_2, Shanghai, 123567891234]",
            "+I[102, user_2, Shanghai, 123567891234]",
            "-U[103, user_3, Hangzhou, 123567891234]",
            "+U[103, user_3, Shanghai, 123567891234]",
            "-U[1010, user_11, Shanghai, 123567891234]",
            "+U[1010, user_11, Hangzhou, 123567891234]",
            "+I[2001, user_22, Shanghai, 123567891234]",
            "+I[2002, user_23, Shanghai, 123567891234]",
            "+I[2003, user_24, Shanghai, 123567891234]"
        };
    }

    private String getSourceDDLWithProjection(String[] captureCustomerTables) {
        return format(
                "CREATE TABLE products ("
                        + " ID INT NOT NULL,"
                        + " NAME STRING,"
                        + " primary key (ID) not enforced"
                        + ") WITH ("
                        + " 'connector' = 'oracle-cdc',"
                        + " 'hostname' = '%s',"
                        + " 'port' = '%s',"
                        + " 'username' = '%s',"
                        + " 'password' = '%s',"
                        + " 'database-name' = '%s',"
                        + " 'schema-name' = '%s',"
                        + " 'table-name' = '%s',"
                        + " 'scan.incremental.snapshot.enabled' = 'false',"
                        + " 'debezium.log.mining.strategy' = 'online_catalog',"
                        + " 'debezium.log.mining.continuous.mine' = 'true',"
                        + " 'debezium.database.history.store.only.captured.tables.ddl' = 'true'"
                        + ")",
                ORACLE_CONTAINER.getHost(),
                ORACLE_CONTAINER.getOraclePort(),
                ORACLE_CONTAINER.getUsername(),
                ORACLE_CONTAINER.getPassword(),
                ORACLE_DATABASE,
                ORACLE_SCHEMA,
                getTableNameRegex(captureCustomerTables) // (customer|customer_1)
                );
    }

    private String[] initSnapshotForSingleTableWithProjection() {
        return new String[] {
            "+I[101, user_1]",
            "+I[102, user_2]",
            "+I[103, user_3]",
            "+I[109, user_4]",
            "+I[110, user_5]",
            "+I[111, user_6]",
            "+I[118, user_7]",
            "+I[121, user_8]",
            "+I[123, user_9]",
            "+I[1009, user_10]",
            "+I[1010, user_11]",
            "+I[1011, user_12]",
            "+I[1012, user_13]",
            "+I[1013, user_14]",
            "+I[1014, user_15]",
            "+I[1015, user_16]",
            "+I[1016, user_17]",
            "+I[1017, user_18]",
            "+I[1018, user_19]",
            "+I[1019, user_20]",
            "+I[2000, user_21]"
        };
    }

    private String[] initRedoLogForSingleTableWithProjection() {
        return new String[] {
            "-U[103, user_3]",
            "+U[103, user_233]",
            "-D[102, user_2]",
            "+I[102, user_2]",
            "-U[103, user_233]",
            "+U[103, user_3]",
            "-U[1010, user_11]",
            "+U[1010, user_1111]",
            "+I[2001, user_22]",
            "+I[2002, user_23]",
            "+I[2003, user_24]"
        };
    }

    private void firstPartRedoLogEvents(String tableId) throws Exception {
        executeSql("UPDATE " + tableId + " SET address = 'Hangzhou' where id = 103");
        executeSql("DELETE FROM " + tableId + " where id = 102");
        executeSql("INSERT INTO " + tableId + " VALUES(102, 'user_2','Shanghai','123567891234')");
        executeSql("UPDATE " + tableId + " SET address = 'Shanghai' where id = 103");
    }

    private void secondPartRedoLogEvents(String tableId) throws Exception {
        executeSql("UPDATE " + tableId + " SET address = 'Hangzhou' where id = 1010");
        executeSql("INSERT INTO " + tableId + " VALUES(2001, 'user_22','Shanghai','123567891234')");
        executeSql("INSERT INTO " + tableId + " VALUES(2002, 'user_23','Shanghai','123567891234')");
        executeSql("INSERT INTO " + tableId + " VALUES(2003, 'user_24','Shanghai','123567891234')");
    }

    private void firstPartRedoLogEventsWithProjection(String tableId) throws Exception {
        executeSql("UPDATE " + tableId + " SET name = 'user_233' where id = 103");
        executeSql("DELETE FROM " + tableId + " where id = 102");
        executeSql("INSERT INTO " + tableId + " VALUES(102, 'user_2','Shanghai','123567891234')");
        executeSql("UPDATE " + tableId + " SET name = 'user_3' where id = 103");
    }

    private void secondPartRedoLogEventsWithProjection(String tableId) throws Exception {
        executeSql("UPDATE " + tableId + " SET name = 'user_1111' where id = 1010");
        executeSql("INSERT INTO " + tableId + " VALUES(2001, 'user_22','Shanghai','123567891234')");
        executeSql("INSERT INTO " + tableId + " VALUES(2002, 'user_23','Shanghai','123567891234')");
        executeSql("INSERT INTO " + tableId + " VALUES(2003, 'user_24','Shanghai','123567891234')");
    }

    private void makeFirstPartRedoLogEvents(String tableId, ProjectionType type) throws Exception {
        switch (type) {
            case FULL:
                firstPartRedoLogEvents(tableId);
                break;
            case PARTIAL:
                firstPartRedoLogEventsWithProjection(tableId);
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + type);
        }
    }

    private void makeSecondPartRedoLogEvents(String tableId, ProjectionType type) throws Exception {
        switch (type) {
            case FULL:
                secondPartRedoLogEvents(tableId);
                break;
            case PARTIAL:
                secondPartRedoLogEventsWithProjection(tableId);
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + type);
        }
    }

    private void sleepMs(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    private static List<String> fetchRows(Iterator<Row> iter, int size) {
        List<String> rows = new ArrayList<>(size);
        while (size > 0 && iter.hasNext()) {
            Row row = iter.next();
            rows.add(row.toString());
            size--;
        }
        return rows;
    }

    private String getTableNameRegex(String[] captureCustomerTables) {
        checkState(captureCustomerTables.length > 0);
        if (captureCustomerTables.length == 1) {
            return captureCustomerTables[0];
        } else {
            // pattern that matches multiple tables
            return format("(%s)", StringUtils.join(captureCustomerTables, "|"));
        }
    }

    private void createAndInitialize(String sqlFile) throws Exception {
        final String ddlFile = String.format("ddl/%s", sqlFile);
        final URL ddlTestFile = OracleSourceITCase.class.getClassLoader().getResource(ddlFile);
        assertNotNull("Cannot locate " + ddlFile, ddlTestFile);
        try (Connection connection = getConnection();
                Statement statement = connection.createStatement()) {

            try {
                // DROP TABLE IF EXITS
                statement.execute("DROP TABLE DEBEZIUM.CUSTOMERS");
                statement.execute("DROP TABLE DEBEZIUM.CUSTOMERS_1");
            } catch (Exception e) {
                LOG.error("DEBEZIUM.CUSTOMERS DEBEZIUM.CUSTOMERS_1 NOT EXITS", e);
            }

            final List<String> statements =
                    Arrays.stream(
                                    Files.readAllLines(Paths.get(ddlTestFile.toURI())).stream()
                                            .map(String::trim)
                                            .filter(x -> !x.startsWith("--") && !x.isEmpty())
                                            .map(
                                                    x -> {
                                                        final Matcher m =
                                                                COMMENT_PATTERN.matcher(x);
                                                        return m.matches() ? m.group(1) : x;
                                                    })
                                            .collect(Collectors.joining("\n"))
                                            .split(";"))
                            .collect(Collectors.toList());

            for (String stmt : statements) {
                statement.execute(stmt);
            }
        }
    }

    private void executeSql(String sql) throws Exception {
        try (Connection connection = getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    // ------------------------------------------------------------------------
    //  test utilities
    // ------------------------------------------------------------------------

    /** The type of failover. */
    private enum FailoverType {
        TM,
        JM,
        NONE
    }

    /** The phase of failover. */
    private enum FailoverPhase {
        SNAPSHOT,
        REDO_LOG,
        NEVER
    }

    /** The type of projection. */
    private enum ProjectionType {
        FULL,
        PARTIAL
    }

    private static void triggerFailover(
            FailoverType type, JobID jobId, MiniCluster miniCluster, Runnable afterFailAction)
            throws Exception {
        switch (type) {
            case TM:
                restartTaskManager(miniCluster, afterFailAction);
                break;
            case JM:
                triggerJobManagerFailover(jobId, miniCluster, afterFailAction);
                break;
            case NONE:
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + type);
        }
    }

    private static void triggerJobManagerFailover(
            JobID jobId, MiniCluster miniCluster, Runnable afterFailAction) throws Exception {
        final HaLeadershipControl haLeadershipControl = miniCluster.getHaLeadershipControl().get();
        haLeadershipControl.revokeJobMasterLeadership(jobId).get();
        afterFailAction.run();
        haLeadershipControl.grantJobMasterLeadership(jobId).get();
    }

    private static void restartTaskManager(MiniCluster miniCluster, Runnable afterFailAction)
            throws Exception {
        miniCluster.terminateTaskManager(0).get();
        afterFailAction.run();
        miniCluster.startTaskManager();
    }

    private static void waitForSinkSize(String sinkName, int expectedSize)
            throws InterruptedException {
        while (sinkSize(sinkName) < expectedSize) {
            Thread.sleep(100);
        }
    }

    private static int sinkSize(String sinkName) {
        synchronized (TestValuesTableFactory.class) {
            try {
                return TestValuesTableFactory.getRawResults(sinkName).size();
            } catch (IllegalArgumentException e) {
                // job is not started yet
                return 0;
            }
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                ORACLE_CONTAINER.getJdbcUrl(), ORACLE_SYSTEM_USER, ORACLE_SYSTEM_PASSWORD);
    }
}
