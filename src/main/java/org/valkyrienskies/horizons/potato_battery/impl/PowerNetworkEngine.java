package org.valkyrienskies.horizons.potato_battery.impl;

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import java.util.Collection;
import java.util.function.Supplier;
import java.util.concurrent.locks.LockSupport;

/** Holds multiple PowerNetworks and manages their threading  */
public final class PowerNetworkEngine implements AutoCloseable {
  private final Supplier<? extends Collection<? extends IPowerNetwork<?>>> networks;
  private final Thread thread;
  private volatile boolean running = true;
  private final double timeStep;

  public PowerNetworkEngine(Supplier<? extends Collection<? extends IPowerNetwork<?>>> networks, int tps) {
    if (tps <= 0) throw new IllegalArgumentException("TPS must be positive");
    this.networks = networks;
    this.timeStep = 1.0 / tps;
    thread = new Thread(this::run, "Potato Battery Thread");
    thread.setDaemon(true);
    thread.start();
  }

  private void run() {
    long interval = (long) (timeStep * 1e9);
    try {
      while (running) {
        long start = System.nanoTime();
        for (IPowerNetwork<?> network : networks.get()) network.energyTick(timeStep);
        LockSupport.parkNanos(Math.max(1, interval - (System.nanoTime() - start)));
      }
    } catch (RuntimeException e) {
      running = false;
      com.mojang.logging.LogUtils.getLogger().error("Potato Battery simulation stopped after an error", e);
    }
  }

  @Override public void close() {
    running = false;
    thread.interrupt();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
