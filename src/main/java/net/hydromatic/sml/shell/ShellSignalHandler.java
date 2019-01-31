package net.hydromatic.sml.shell;

import net.hydromatic.sml.Shell;

/**
 * A signal handler interface. The interface is decoupled from the
 * implementation since signal handlers are not portable across JVMs, so we use
 * dynamic class-loading.
 */
public interface ShellSignalHandler {
  /**
   * Sets the dispatchCallback to be alerted of by signals.
   *
   * @param dispatchCallback statement affected
   */
  void setCallback(DispatchCallback dispatchCallback);
}
