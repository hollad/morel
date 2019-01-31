package net.hydromatic.sml.shell;

import org.jline.reader.impl.DefaultParser;

/**
 * Implements multi-line parsing.
 */
public class ShellParser extends DefaultParser {
  private final Shell shell;

  public ShellParser(final Shell shell) {
    this.shell = shell;
  }

}

// End ShellParser.java
