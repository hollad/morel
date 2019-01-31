package net.hydromatic.sml.shell;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * Completer for SQLLine. It dispatches to sub-completers based on the
 * current arguments.
 */
class ShellCompleter implements Completer {
  private Shell shell;

  ShellCompleter(Shell shell) {
    this.shell = shell;
  }

  @Override public void complete(LineReader reader, ParsedLine line,
      List<Candidate> candidates) {
    final String bufferStr = reader.getBuffer().substring(0).trim();
    // TODO:
  }
}

// End SqlLineCompleter.java
