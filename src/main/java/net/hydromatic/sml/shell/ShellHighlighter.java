package net.hydromatic.sml.shell;

import org.jline.reader.impl.DefaultHighlighter;

/**
 * Highlighter class to implement logic of sql
 * and command syntax highlighting in sqlline.
 */
public class ShellHighlighter extends DefaultHighlighter {
  private final Shell shell;

  public ShellHighlighter(Shell shell) {
    this.shell = shell;
  }

}

// End ShellHighlighter.java

