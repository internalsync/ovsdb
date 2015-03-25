/*
 * Copyright (c) 2015 Brocade Communications Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.ovsdb.southbound.ovsdb.transact;

import static org.opendaylight.ovsdb.lib.operations.Operations.op;

import java.util.StringTokenizer;

import org.opendaylight.controller.md.sal.common.api.data.AsyncDataChangeEvent;
import org.opendaylight.ovsdb.lib.notation.Mutator;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.operations.TransactionBuilder;
import org.opendaylight.ovsdb.lib.schema.typed.TyperUtils;
import org.opendaylight.ovsdb.schema.openvswitch.Bridge;
import org.opendaylight.ovsdb.schema.openvswitch.Interface;
import org.opendaylight.ovsdb.schema.openvswitch.OpenVSwitch;
import org.opendaylight.ovsdb.schema.openvswitch.Port;
import org.opendaylight.ovsdb.southbound.SouthboundMapper;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.rev150105.OvsdbTerminationPointAugmentation;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.rev150105.ovsdb.port._interface.attributes.Options;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

public class TerminationPointCreateCommand implements TransactCommand {
    private AsyncDataChangeEvent<InstanceIdentifier<?>, OvsdbTerminationPointAugmentation> changes;
    private static final Logger LOG = LoggerFactory.getLogger(TerminationPointCreateCommand.class);

    public TerminationPointCreateCommand(AsyncDataChangeEvent<InstanceIdentifier<?>, OvsdbTerminationPointAugmentation> changes){
        this.changes = changes;
    }

    @Override
    public void execute(TransactionBuilder transaction) {
        for(OvsdbTerminationPointAugmentation terminationPoint: changes.getCreatedData().values()){
            LOG.debug("Received request to create termination point {} at managed node {}",
                    terminationPoint.getName(),
                    terminationPoint.getAttachedTo().getValue().firstIdentifierOf(Node.class));


            // Configure interface
            String interfaceUuid = "Interface_" + terminationPoint.getName();
            Interface ovsInterface = TyperUtils.getTypedRowWrapper(transaction.getDatabaseSchema(), Interface.class);
            ovsInterface.setName(terminationPoint.getName());
            ovsInterface.setType(SouthboundMapper.createOvsdbInterfaceType(terminationPoint.getInterfaceType()));
            Integer ofPort = terminationPoint.getOfport();
            if(ofPort != null) {
                ovsInterface.setOpenFlowPort(Sets.newHashSet(ofPort));
            }
            Integer ofPortRequest = terminationPoint.getOfportRequest();
            if(ofPortRequest != null) {
                ovsInterface.setOpenFlowPortRequest(Sets.newHashSet(ofPortRequest));
            }

            //Configure optional input
            if (terminationPoint.getOptions() != null) {
                for(Options option : terminationPoint.getOptions()){
                    StringTokenizer tokens = new StringTokenizer(option.getOption(),"=");
                    if(tokens.countTokens() == 2){
                        ovsInterface.setOptions(ImmutableMap.of(tokens.nextToken(),tokens.nextToken()));
                    }else{
                        LOG.warn("Option input {} is not valid a valid option.",option.getOption());
                    }
                }
            }
            transaction.add(op.insert(ovsInterface).withId(interfaceUuid));

            // Configure port with the above interface details
            String portUuid = "Port_" + terminationPoint.getName();
            Port port = TyperUtils.getTypedRowWrapper(transaction.getDatabaseSchema(), Port.class);
            port.setName(terminationPoint.getName());
            port.setInterfaces(Sets.newHashSet(new UUID(interfaceUuid)));
            transaction.add(op.insert(port).withId(portUuid));

            //Configure bridge with the above port details
            Bridge bridge = TyperUtils.getTypedRowWrapper(transaction.getDatabaseSchema(), Bridge.class);
            bridge.setName(terminationPoint.getBridgeName());
            bridge.setPorts(Sets.newHashSet(new UUID(portUuid)));

            transaction.add(op.mutate(bridge)
                    .addMutation(bridge.getPortsColumn().getSchema(), Mutator.INSERT,bridge.getPortsColumn().getData())
                    .where(bridge.getNameColumn().getSchema().opEqual(bridge.getNameColumn().getData())).build());
        }

    }

}
