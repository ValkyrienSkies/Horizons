package org.valkyrienskies.horizons.potato_battery.api.network;

public interface CircuitStampContext {
  int GROUND = -1;

  double getTimeStep();

  double getPreviousVoltage(int port);

  double getPreviousSourceCurrent(int sourceIndex);

  void stampConductance(int portA, int portB, double conductance);

  default void stampResistance(int portA, int portB, double resistance) {
    if (resistance <= 0.0) {
      stampConductance(portA, portB, 1.0e12);
      return;
    }

    stampConductance(portA, portB, 1.0 / resistance);
  }

  void stampCurrentSource(int fromPort, int toPort, double current);

  void stampVoltageSource(int sourceIndex, int positivePort, int negativePort, double voltage);

  void stampVCCS(int outPositive, int outNegative, int controlPositive, int controlNegative, double transconductance);

  void stampVCVS(int sourceIndex, int outPositive, int outNegative, int controlPositive, int controlNegative, double gain);
}
