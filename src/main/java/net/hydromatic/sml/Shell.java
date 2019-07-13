/*
 * Licensed to Julian Hyde under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Julian Hyde licenses this file to you under the Apache
 * License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package net.hydromatic.sml;

import com.google.common.collect.ImmutableList;

import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.compile.CompileException;
import net.hydromatic.sml.compile.Compiler;
import net.hydromatic.sml.compile.Environment;
import net.hydromatic.sml.compile.Environments;
import net.hydromatic.sml.parse.ParseException;
import net.hydromatic.sml.parse.SmlParserImpl;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Command shell for ML, powered by JLine3. */
public class Shell {
  private final List<String> argList;
  private final boolean echo;
  private final Terminal terminal;
  private final boolean banner;
  private boolean help;

  /** Command-line entry point.
   *
   * @param args Command-line arguments */
  public static void main(String[] args) {
    try {
      final Shell main = new Shell(args, System.in, System.out);
      main.run();
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  /** Creates a Shell. */
  public Shell(String[] args, InputStream in, PrintStream out)
      throws IOException {
    this.argList = ImmutableList.copyOf(args);
    this.echo = argList.contains("--echo");
    this.help = argList.contains("--help");
    this.banner = !argList.contains("--banner=false");
    final boolean dumb = argList.contains("--terminal=dumb");
    final boolean system = !argList.contains("--system=false");

    final TerminalBuilder builder = TerminalBuilder.builder();
    builder.streams(in, out);
    builder.system(system);
    builder.dumb(dumb);
    if (dumb) {
      builder.type("dumb");
    }
    terminal = builder.build();
  }

  void usage() {
    String[] usageLines = {
        "Usage: java " + Shell.class.getName(),
    };
    printAll(Arrays.asList(usageLines));
  }

  void help() {
    String[] helpLines = {
        "List of available commands:",
        "    help   Print this help",
        "    quit   Quit shell",
    };
    printAll(Arrays.asList(helpLines));
  }

  private void printAll(List<String> lines) {
    for (String line : lines) {
      terminal.writer().println(line);
    }
  }

  /** Generates a banner to be shown on startup. */
  private String banner() {
    return "smlj version 0.1-SNAPSHOT"
        + " (java version \"" + System.getProperty("java.version")
        + "\", JRE " + System.getProperty("java.vendor.version")
        + " (build " + System.getProperty("java.vm.version")
        + "), " + terminal.getName()
        + ", " + terminal.getType() + ")";
  }

  public void run() {
    if (help) {
      usage();
      return;
    }

    final DefaultParser parser = new DefaultParser();
    parser.setEofOnUnclosedQuote(true);
    parser.setEofOnUnclosedBracket(DefaultParser.Bracket.CURLY,
        DefaultParser.Bracket.ROUND, DefaultParser.Bracket.SQUARE);

    final String equalsPrompt = new AttributedStringBuilder()
        .style(AttributedStyle.DEFAULT.bold()).append("=")
        .style(AttributedStyle.DEFAULT).append(" ")
        .toAnsi(terminal);
    final String minusPrompt = new AttributedStringBuilder()
        .style(AttributedStyle.DEFAULT.bold()).append("-")
        .style(AttributedStyle.DEFAULT).append(" ")
        .toAnsi(terminal);

    if (banner) {
      terminal.writer().println(banner());
    }
    LineReader reader = LineReaderBuilder.builder()
        .terminal(terminal)
        .parser(parser)
        .variable(LineReader.SECONDARY_PROMPT_PATTERN, minusPrompt)
        .build();

    Environment env = Environments.empty();
    final StringBuilder buf = new StringBuilder();
    final List<String> lines = new ArrayList<>();
    while (true) {
      String line = null;
      try {
        final String prompt = buf.length() == 0 ? equalsPrompt : minusPrompt;
        final String rightPrompt = null;
        line = reader.readLine(prompt, rightPrompt, (MaskingCallback) null,
            null);
      } catch (UserInterruptException e) {
        // Ignore
      } catch (EndOfFileException e) {
        return;
      }
      if (line == null) {
        continue;
      }

      line = line.trim();

      if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
        break;
      }
      ParsedLine pl = reader.getParser().parse(line, 0);
      String[] argv = pl.words().subList(1, pl.words().size())
          .toArray(new String[0]);
      try {
        if ("help".equals(pl.word()) || "?".equals(pl.word())) {
          help();
        }
        buf.append(pl.line());
        if (pl.line().endsWith(";")) {
          final String code = buf.toString();
          buf.setLength(0);
          final SmlParserImpl smlParser =
              new SmlParserImpl(new StringReader(code));
          final AstNode statement;
          try {
            statement = smlParser.statementSemicolon();
            final Compiler.CompiledStatement compiled =
                Compiler.prepareStatement(env, statement);
            env = compiled.eval(env, lines);
            printAll(lines);
            terminal.writer().flush();
            lines.clear();
          } catch (ParseException | CompileException e) {
            terminal.writer().println(e.getMessage());
          }
          if (echo) {
            terminal.writer().println(code);
          }
        }
      } catch (IllegalArgumentException e) {
        terminal.writer().println(e.getMessage());
      }
    }
  }
}

// End Shell.java