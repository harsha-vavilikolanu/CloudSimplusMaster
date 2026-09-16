
package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.vms.Vm;

public class RCWBroker22 extends DatacenterBrokerSimple {

    public RCWBroker22(final CloudSimPlus simulation) {
        super(simulation);
    }

    /**
     * Runtime Computational Workload (RCW)
     *
     * RCW =
     * (Actual Remaining Workload + Incoming Cloudlet Length)
     * --------------------------------------------------------
     *                         VM MIPS
     */
    @Override
    protected Vm defaultVmMapper(final Cloudlet cloudlet) {

        /*
         * If Cloudlet is already bound to a VM,
         * use that VM.
         */
        if (cloudlet.isBoundToVm()) {
            return cloudlet.getVm();
        }

        /*
         * No VM available.
         */
        if (getVmExecList().isEmpty()) {
            return Vm.NULL;
        }

        final long incomingCloudletLength =
                cloudlet.getLength();

        Vm bestVm = Vm.NULL;
        double bestRcw = Double.MAX_VALUE;

        /*
         * Check every VM.
         */
        for (final Vm vm : getVmExecList()) {

            long actualRemainingWorkload = 0;

            /*
             * =====================================================
             * EXECUTING CLOUDLETS
             * =====================================================
             *
             * Use only the actual remaining MI.
             */
            for (final CloudletExecution execution :
                    vm.getCloudletScheduler()
                      .getCloudletExecList()) {

                actualRemainingWorkload +=
                        execution.getRemainingCloudletLength();
            }

            /*
             * =====================================================
             * WAITING CLOUDLETS
             * =====================================================
             *
             * Waiting Cloudlets have not started,
             * therefore their complete length is remaining.
             */
            for (final CloudletExecution execution :
                    vm.getCloudletScheduler()
                      .getCloudletWaitingList()) {

                actualRemainingWorkload +=
                        execution.getCloudlet().getLength();
            }

            /*
             * =====================================================
             * PREDICTED REMAINING WORKLOAD
             * =====================================================
             */
            long predictedRemainingWorkload =
                    actualRemainingWorkload
                    + incomingCloudletLength;

            /*
             * =====================================================
             * RCW CALCULATION
             * =====================================================
             */
            double rcw =
                    (double) predictedRemainingWorkload
                    / vm.getMips();

            System.out.printf(
                    "Cloudlet %d | VM %d"
                    + " | Actual Remaining = %d MI"
                    + " | Incoming = %d MI"
                    + " | Predicted Remaining = %d MI"
                    + " | RCW = %.4f%n",

                    cloudlet.getId(),
                    vm.getId(),
                    actualRemainingWorkload,
                    incomingCloudletLength,
                    predictedRemainingWorkload,
                    rcw
            );

            /*
             * Select VM with minimum RCW.
             */
            if (rcw < bestRcw) {
                bestRcw = rcw;
                bestVm = vm;
            }
        }

        if (bestVm == Vm.NULL) {
            return Vm.NULL;
        }

        System.out.printf(
                ">>> Cloudlet %d SELECTED VM %d"
                + " | Minimum RCW = %.4f%n",

                cloudlet.getId(),
                bestVm.getId(),
                bestRcw
        );

        System.out.println(
                "----------------------------------------"
        );

        return bestVm;
    }
}
