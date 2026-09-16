package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.vms.Vm;

public class RuntimeAdaptiveBroker extends DatacenterBrokerSimple {

    public RuntimeAdaptiveBroker(CloudSimPlus simulation) {
        super(simulation);
    }

    @Override
    protected Vm defaultVmMapper(final Cloudlet cloudlet) {

        System.out.println("Cloudlet " + cloudlet.getId());

        for (Vm vm : getVmExecList()) {

            System.out.println(
                    "VM " + vm.getId() +
                            "  MIPS = " + vm.getMips()
            );
        }

        return super.defaultVmMapper(cloudlet);
    }
}