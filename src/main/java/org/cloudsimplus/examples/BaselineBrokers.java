package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.vms.Vm;

import java.util.Comparator;
import java.util.List;

public class BaselineBrokers {

    public static class FcfsBroker
            extends DatacenterBrokerSimple {

        public FcfsBroker(
                CloudSimPlus simulation,
                String name) {

            super(simulation, name);

            setVmMapper(
                    this::fcfsMap);
        }

        private Vm fcfsMap(
                Cloudlet cloudlet) {

            List<Vm> vms =
                    getVmExecList();

            if (vms.isEmpty()) {
                return Vm.NULL;
            }

            return vms.stream()
                    .min(
                            Comparator.comparingDouble(
                                    Vm::getCpuPercentUtilization))
                    .orElse(vms.get(0));
        }
    }

    public static class RoundRobinBroker
            extends DatacenterBrokerSimple {

        private int currentIndex = 0;

        public RoundRobinBroker(
                CloudSimPlus simulation,
                String name) {

            super(simulation, name);

            setVmMapper(
                    this::roundRobinMap);
        }

        private Vm roundRobinMap(
                Cloudlet cloudlet) {

            List<Vm> vms =
                    getVmExecList();

            if (vms.isEmpty()) {
                return Vm.NULL;
            }

            Vm vm =
                    vms.get(
                            currentIndex
                                    % vms.size());

            currentIndex++;

            return vm;
        }
    }

    public static class MaxMinBroker
            extends DatacenterBrokerSimple {

        public MaxMinBroker(
                CloudSimPlus simulation,
                String name) {

            super(simulation, name);

            setVmMapper(
                    this::maxMinMap);
        }

        private Vm maxMinMap(
                Cloudlet cloudlet) {

            List<Vm> vms =
                    getVmExecList();

            if (vms.isEmpty()) {
                return Vm.NULL;
            }

            return vms.stream()
                    .max(
                            Comparator.comparingDouble(
                                    Vm::getMips))
                    .orElse(vms.get(0));
        }
    }
}