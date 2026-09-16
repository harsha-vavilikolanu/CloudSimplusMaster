package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.core.CloudSimPlus;

public class WorkloadAwareBroker extends DatacenterBrokerSimple {

    public WorkloadAwareBroker(final CloudSimPlus simulation) {
        super(simulation);
    }

}