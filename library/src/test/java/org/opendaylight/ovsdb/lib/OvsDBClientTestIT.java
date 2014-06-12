/*
 * Copyright (C) 2014 EBay Software Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Authors : Ashwin Raveendran
 */
package org.opendaylight.ovsdb.lib;

import static org.opendaylight.ovsdb.lib.operations.Operations.op;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opendaylight.ovsdb.lib.message.MonitorRequest;
import org.opendaylight.ovsdb.lib.message.MonitorRequestBuilder;
import org.opendaylight.ovsdb.lib.message.MonitorSelect;
import org.opendaylight.ovsdb.lib.message.OvsdbRPC;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.message.UpdateNotification;
import org.opendaylight.ovsdb.lib.notation.Column;
import org.opendaylight.ovsdb.lib.notation.Mutator;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.operations.OperationResult;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;
import org.opendaylight.ovsdb.lib.schema.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;


public class OvsDBClientTestIT extends OvsdbTestBase {
    Logger logger = LoggerFactory.getLogger(OvsDBClientTestIT.class);

    OvsDBClientImpl ovs;
    DatabaseSchema dbSchema = null;
    static String testBridgeName = "br-test";
    static UUID testBridgeUuid = null;
    @Test
    public void testTransact() throws IOException, InterruptedException, ExecutionException {
        Assert.assertNotNull(dbSchema);
        TableSchema<GenericTableSchema> bridge = dbSchema.table("Bridge", GenericTableSchema.class);
        ColumnSchema<GenericTableSchema, String> name = bridge.column("name", String.class);

        createBridgeTransaction();
        abortTransaction();
        assertTransaction();
    }

    @Test
    public void testMonitorRequest() throws ExecutionException, InterruptedException, IOException {
        Assert.assertNotNull(dbSchema);
        // Create Test Bridge before testing the Monitor operation
        createBridgeTransaction();

        GenericTableSchema bridge = dbSchema.table("Bridge", GenericTableSchema.class);

        List<MonitorRequest<GenericTableSchema>> monitorRequests = Lists.newArrayList();
        ColumnSchema<GenericTableSchema, Set<Integer>> flood_vlans = bridge.multiValuedColumn("flood_vlans", Integer.class);

        monitorRequests.add(
                MonitorRequestBuilder.builder(bridge)
                        .addColumn(bridge.column("name"))
                        .addColumn(bridge.column("fail_mode", String.class))
                        .addColumn(flood_vlans)
                        .with(new MonitorSelect(true, true, true, true))
                        .build());

        final List<Object> results = Lists.newArrayList();

        MonitorHandle monitor = ovs.monitor(dbSchema, monitorRequests, new MonitorCallBack() {
            @Override
            public void update(TableUpdates result) {
                results.add(result);
                System.out.println("result = " + result);
            }

            @Override
            public void exception(Throwable t) {
                results.add(t);
                System.out.println("t = " + t);
            }
        });

        for (int i = 0; i < 3 ; i++) { //wait 3 seconds to get a result
            System.out.println("waiting on monitor response for Bridge Table...");
            if (!results.isEmpty()) break;
            Thread.sleep(1000);
        }

        Assert.assertTrue(!results.isEmpty());
        Object result = results.get(0);
        Assert.assertTrue(result instanceof TableUpdates);
        TableUpdates updates = (TableUpdates) result;
        TableUpdate<GenericTableSchema> update = updates.getUpdate(bridge);
        Row<GenericTableSchema> aNew = update.getNew();
        for (Column<GenericTableSchema, ?> column: aNew.getColumns()) {
            if (column.getSchema().equals(flood_vlans)) {
                Set<Integer> data = column.getData(flood_vlans);
                Assert.assertTrue(!data.isEmpty());
            }
        }
    }

    /*
     * Ideally we should be using selectOpenVSwitchTableUuid() instead of this method.
     * But Row.java needs some Jackson Jitsu to obtain the appropriate Row Json mapped to Java object
     * for Select operation.
     * Hence using the Monitor approach to obtain the uuid of the open_vSwitch table entry.
     * Replace this method with selectOpenVSwitchTableUuid() once it is functional,
     */
    private UUID getOpenVSwitchTableUuid() throws ExecutionException, InterruptedException {
        Assert.assertNotNull(dbSchema);
        GenericTableSchema ovsTable = dbSchema.table("Open_vSwitch", GenericTableSchema.class);
        List<MonitorRequest<GenericTableSchema>> monitorRequests = Lists.newArrayList();
        ColumnSchema<GenericTableSchema, UUID> _uuid = ovsTable.column("_uuid", UUID.class);

        monitorRequests.add(
                MonitorRequestBuilder.builder(ovsTable)
                        .addColumn(_uuid)
                        .with(new MonitorSelect(true, true, true, true))
                        .build());

        final List<Object> results = Lists.newArrayList();

        MonitorHandle monitor = ovs.monitor(dbSchema, monitorRequests, new MonitorCallBack() {
            @Override
            public void update(TableUpdates result) {
                results.add(result);
            }

            @Override
            public void exception(Throwable t) {
                results.add(t);
                System.out.println("t = " + t);
            }
        });

        for (int i = 0; i < 3 ; i++) { //wait 5 seconds to get a result
            System.out.println("waiting on monitor response for open_vSwtich Table...");
            if (!results.isEmpty()) break;
            Thread.sleep(1000);
        }

        Assert.assertTrue(!results.isEmpty());
        Object result = results.get(0); // open_vSwitch table has just 1 row.
        Assert.assertTrue(result instanceof TableUpdates);
        TableUpdates updates = (TableUpdates) result;
        TableUpdate<GenericTableSchema> update = updates.getUpdate(ovsTable);
        return update.getUuid();
    }

    /*
     * TODO : selectOpenVSwitchTableUuid method isn't working as expected due to the Jackson
     * parsing challenges on the Row object returned by the Select operation.
     */
    private UUID selectOpenVSwitchTableUuid() throws ExecutionException, InterruptedException {
        Assert.assertNotNull(dbSchema);
        GenericTableSchema ovsTable = dbSchema.table("Open_vSwitch", GenericTableSchema.class);

        List<MonitorRequest<GenericTableSchema>> monitorRequests = Lists.newArrayList();
        ColumnSchema<GenericTableSchema, UUID> _uuid = ovsTable.column("_uuid", UUID.class);

        List<OperationResult> results = ovs.transactBuilder()
               .add(op.select(ovsTable)
                      .column(_uuid))
                      .execute()
                      .get();

        Assert.assertTrue(!results.isEmpty());
        OperationResult result = results.get(0);
        List<Row<GenericTableSchema>> rows = result.getRows();
        Row<GenericTableSchema> ovsTableRow = rows.get(0);
        return (UUID)ovsTableRow.getColumn(_uuid).getData();
    }

    private void createBridgeTransaction() throws IOException, InterruptedException, ExecutionException {
        Assert.assertNotNull(dbSchema);
        TableSchema<GenericTableSchema> bridge = dbSchema.table("Bridge", GenericTableSchema.class);
        GenericTableSchema ovsTable = dbSchema.table("Open_vSwitch", GenericTableSchema.class);

        ColumnSchema<GenericTableSchema, String> name = bridge.column("name", String.class);
        ColumnSchema<GenericTableSchema, String> fail_mode = bridge.column("fail_mode", String.class);
        ColumnSchema<GenericTableSchema, Set<Integer>> flood_vlans = bridge.multiValuedColumn("flood_vlans", Integer.class);
        ColumnSchema<GenericTableSchema, Set<UUID>> bridges = ovsTable.multiValuedColumn("bridges", UUID.class);
        ColumnSchema<GenericTableSchema, UUID> _uuid = ovsTable.column("_uuid", UUID.class);

        String namedUuid = "br_test";
        int nOperations = 7;
        int insertOperationIndex = 0;
        UUID parentTable = getOpenVSwitchTableUuid();
        ListenableFuture<List<OperationResult>> results = ovs.transactBuilder()
                 /*
                  * Make sure that the position of insert operation matches the insertOperationIndex.
                  * This will be used later when the Results are processed.
                  */
                .add(op.insert(bridge)
                        .withId(namedUuid)
                        .value(name, testBridgeName)
                        .value(flood_vlans, Sets.newHashSet(100, 101, 4001)))
                .add(op.comment("Inserting Bridge br-int"))
                .add(op.update(bridge)
                        .set(fail_mode, "secure")
                        .where(name.opEqual(testBridgeName))
                        .build())
                .add(op.select(bridge)
                        .column(name)
                        .column(_uuid)
                        .where(name.opEqual(testBridgeName))
                        .build())
                .add(op.mutate(bridge)
                        .addMutation(flood_vlans, Mutator.INSERT, Sets.newHashSet(200,400))
                        .where(name.opEqual(testBridgeName))
                        .build())
                .add(op.mutate(ovsTable)
                        .addMutation(bridges, Mutator.INSERT, Sets.newHashSet(new UUID(namedUuid)))
                        .where(_uuid.opEqual(parentTable))
                        .build())
                .add(op.commit(true))
                .execute();

        List<OperationResult> operationResults = results.get();
        Assert.assertFalse(operationResults.isEmpty());
        // Check if Results matches the number of operations in transaction
        Assert.assertEquals(nOperations, operationResults.size());
        System.out.println("Insert & Update operation results = " + operationResults);
        testBridgeUuid = operationResults.get(insertOperationIndex).getUuid();
    }

    private void assertTransaction() throws InterruptedException, ExecutionException {
        Assert.assertNotNull(dbSchema);
        TableSchema<GenericTableSchema> bridge = dbSchema.table("Bridge", GenericTableSchema.class);
        ColumnSchema<GenericTableSchema, String> name = bridge.column("name", String.class);

        /*
         * Adding a separate Assert operation in a transaction. Lets not mix this with other
         * valid transactions as above.
         */
        ListenableFuture<List<OperationResult>> results = ovs.transactBuilder()
                .add(op.delete(bridge)
                        .where(name.opEqual(testBridgeName))
                        .build())
                .add(op.assertion("Assert12345")) // Failing intentionally
                .execute();

        List<OperationResult> operationResults = results.get();
        Assert.assertFalse(operationResults.isEmpty());
        /* Testing for an Assertion Error */
        Assert.assertFalse(operationResults.get(1).getError() == null);
        System.out.println("Assert operation results = " + operationResults);
    }

    private void abortTransaction() throws InterruptedException, ExecutionException {
        Assert.assertNotNull(dbSchema);
        TableSchema<GenericTableSchema> bridge = dbSchema.table("Bridge", GenericTableSchema.class);
        ColumnSchema<GenericTableSchema, String> name = bridge.column("name", String.class);

        /*
         * Adding a separate Abort operation in a transaction. Lets not mix this with other
         * valid transactions as above.
         */
        ListenableFuture<List<OperationResult>> results = ovs.transactBuilder()
                .add(op.delete(bridge)
                        .where(name.opEqual(testBridgeName))
                        .build())
                .add(op.abort())
                .execute();

        List<OperationResult> operationResults = results.get();
        Assert.assertFalse(operationResults.isEmpty());
        /* Testing for Abort Error */
        Assert.assertFalse(operationResults.get(1).getError() == null);
        System.out.println("Abort operation results = " + operationResults);
    }

    public void testGetDBs() throws ExecutionException, InterruptedException {
        ListenableFuture<List<String>> databases = ovs.getDatabases();
        List<String> dbNames = databases.get();
        Assert.assertNotNull(dbNames);
        boolean hasOpenVswitchSchema = false;
        for(String dbName : dbNames) {
           if (dbName.equals(OPEN_VSWITCH_SCHEMA)) {
                hasOpenVswitchSchema = true;
                break;
           }
        }
        Assert.assertTrue(OPEN_VSWITCH_SCHEMA+" schema is not supported by the switch", hasOpenVswitchSchema);
    }

    @Before
    public  void setUp() throws IOException, ExecutionException, InterruptedException {
        if (ovs != null) {
            return;
        }
        OvsdbRPC rpc = getTestConnection();
        if (rpc == null) {
            System.out.println("Unable to Establish Test Connection");
        }
        ExecutorService executorService = Executors.newFixedThreadPool(3);
        ovs = new OvsDBClientImpl(rpc, executorService);
        testGetDBs();
        dbSchema = ovs.getSchema(OPEN_VSWITCH_SCHEMA, true).get();
    }

    @After
    public void tearDown() throws InterruptedException, ExecutionException {
        TableSchema<GenericTableSchema> bridge = dbSchema.table("Bridge", GenericTableSchema.class);
        ColumnSchema<GenericTableSchema, String> name = bridge.column("name", String.class);
        GenericTableSchema ovsTable = dbSchema.table("Open_vSwitch", GenericTableSchema.class);
        ColumnSchema<GenericTableSchema, Set<UUID>> bridges = ovsTable.multiValuedColumn("bridges", UUID.class);
        ColumnSchema<GenericTableSchema, UUID> _uuid = ovsTable.column("_uuid", UUID.class);
        UUID parentTable = getOpenVSwitchTableUuid();

        ListenableFuture<List<OperationResult>> results = ovs.transactBuilder()
                .add(op.delete(bridge)
                        .where(name.opEqual(testBridgeName))
                        .build())
                .add(op.mutate(ovsTable)
                        .addMutation(bridges, Mutator.DELETE, Sets.newHashSet(testBridgeUuid))
                        .where(_uuid.opEqual(parentTable))
                        .build())
                .add(op.commit(true))
                .execute();

        List<OperationResult> operationResults = results.get();
        System.out.println("Delete operation results = " + operationResults);
    }


    @Override
    public void update(Object node, UpdateNotification upadateNotification) {
        // TODO Auto-generated method stub

    }

    @Override
    public void locked(Object node, List<String> ids) {
        // TODO Auto-generated method stub

    }
    @Override
    public void stolen(Object node, List<String> ids) {
        // TODO Auto-generated method stub

    }
}
