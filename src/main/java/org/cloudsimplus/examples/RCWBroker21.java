package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.vms.Vm;

public class RCWBroker21 extends DatacenterBrokerSimple {

    public RCWBroker21(CloudSimPlus simulation) {
        super(simulation);
    }

    @Override
    protected Vm defaultVmMapper(Cloudlet cloudlet) {
        if (cloudlet.isBoundToVm()) return cloudlet.getVm();
        if (getVmExecList().isEmpty()) return Vm.NULL;

        Vm bestVm = Vm.NULL;
        double minRCW = Double.MAX_VALUE;
        double bestWorkloadAfter = 0;

        for (Vm vm : getVmExecList()) {
            double currentWorkload = 0.0;
            
            // 1. Sum remaining lengths of executing Cloudlets
            for (CloudletExecution cle : vm.getCloudletScheduler().getCloudletExecList()) {
                currentWorkload += (cle.getCloudlet().getLength() - cle.getCloudlet().getFinishedLengthSoFar());
            }
            
            // 2. Sum full lengths of waiting Cloudlets
            for (CloudletExecution clw : vm.getCloudletScheduler().getCloudletWaitingList()) {
                currentWorkload += clw.getCloudlet().getLength();
            }

            // 3. Proposed Formula: RCW_i = (R_i + L_j) / MIPS_i
            double workloadAfter = currentWorkload + cloudlet.getLength();
            double rcw = workloadAfter / vm.getMips();

            if (rcw < minRCW) {
                minRCW = rcw;
                bestVm = vm;
                bestWorkloadAfter = workloadAfter;
            }
        }

        System.out.printf(">>> Cloudlet %d MAPPED TO VM %d | Updated Workload = %.0f MI | RCW = %.4f%n",
                cloudlet.getId(), bestVm.getId(), bestWorkloadAfter, minRCW);

        return bestVm;
    }
}