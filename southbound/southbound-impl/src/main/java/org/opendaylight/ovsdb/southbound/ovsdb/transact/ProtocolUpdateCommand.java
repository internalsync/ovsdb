/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.ovsdb.southbound.ovsdb.transact;

import static org.opendaylight.ovsdb.lib.operations.Operations.op;
import static org.opendaylight.ovsdb.southbound.SouthboundUtil.schemaMismatchLog;

import com.google.common.base.Optional;
import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import org.opendaylight.controller.md.sal.binding.api.DataTreeModification;
import org.opendaylight.controller.md.sal.common.api.data.AsyncDataChangeEvent;
import org.opendaylight.ovsdb.lib.error.SchemaVersionMismatchException;
import org.opendaylight.ovsdb.lib.notation.Mutator;
import org.opendaylight.ovsdb.lib.operations.TransactionBuilder;
import org.opendaylight.ovsdb.lib.schema.typed.TyperUtils;
import org.opendaylight.ovsdb.schema.openvswitch.Bridge;
import org.opendaylight.ovsdb.southbound.SouthboundConstants;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.rev150105.OvsdbBridgeAugmentation;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.rev150105.ovsdb.bridge.attributes.ProtocolEntry;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProtocolUpdateCommand implements TransactCommand {

    private static final Logger LOG = LoggerFactory.getLogger(ProtocolUpdateCommand.class);

    @Override
    public void execute(TransactionBuilder transaction, BridgeOperationalState state,
                        AsyncDataChangeEvent<InstanceIdentifier<?>, DataObject> events) {
        execute(transaction, state, TransactUtils.extractCreatedOrUpdated(events, ProtocolEntry.class),
                TransactUtils.extractCreatedOrUpdated(events, OvsdbBridgeAugmentation.class));
    }

    @Override
    public void execute(TransactionBuilder transaction, BridgeOperationalState state,
                        Collection<DataTreeModification<Node>> modifications) {
        execute(transaction, state, TransactUtils.extractCreatedOrUpdated(modifications, ProtocolEntry.class),
                TransactUtils.extractCreatedOrUpdated(modifications, OvsdbBridgeAugmentation.class));
    }

    private void execute(TransactionBuilder transaction, BridgeOperationalState state,
                         Map<InstanceIdentifier<ProtocolEntry>, ProtocolEntry> protocols,
                         Map<InstanceIdentifier<OvsdbBridgeAugmentation>, OvsdbBridgeAugmentation> bridges) {
        for (Entry<InstanceIdentifier<ProtocolEntry>, ProtocolEntry> entry: protocols.entrySet()) {
            Optional<ProtocolEntry> operationalProtocolEntryOptional =
                    state.getProtocolEntry(entry.getKey());
            if (!operationalProtocolEntryOptional.isPresent()) {
                InstanceIdentifier<OvsdbBridgeAugmentation> bridgeIid =
                        entry.getKey().firstIdentifierOf(OvsdbBridgeAugmentation.class);
                Optional<OvsdbBridgeAugmentation> bridgeOptional =
                        state.getOvsdbBridgeAugmentation(bridgeIid);
                OvsdbBridgeAugmentation ovsdbBridge;
                if (bridgeOptional.isPresent()) {
                    ovsdbBridge = bridgeOptional.get();
                } else {
                    ovsdbBridge = bridges.get(bridgeIid);
                }
                if (ovsdbBridge != null
                        && ovsdbBridge.getBridgeName() != null
                        && entry.getValue() != null
                        && entry.getValue().getProtocol() != null) {
                    String protocolString = SouthboundConstants.OVSDB_PROTOCOL_MAP.get(entry.getValue().getProtocol());
                    if (protocolString != null) {
                        Bridge bridge = TyperUtils.getTypedRowWrapper(transaction.getDatabaseSchema(), Bridge.class);
                        bridge.setName(ovsdbBridge.getBridgeName().getValue());
                        try {
                            bridge.setProtocols(Sets.newHashSet(protocolString));
                            transaction.add(op.mutate(bridge).addMutation(bridge.getProtocolsColumn().getSchema(),
                                        Mutator.INSERT,bridge.getProtocolsColumn().getData())
                                .where(bridge.getNameColumn().getSchema().opEqual(bridge.getNameColumn().getData()))
                                .build());
                            LOG.info("Updated ProtocolEntry : {} for OVSDB Bridge : {} ",
                                    protocolString, bridge.getName());
                        } catch (SchemaVersionMismatchException e) {
                            schemaMismatchLog("protocols", "Bridge", e);
                        }
                    }
                }
            }
        }
    }

}
