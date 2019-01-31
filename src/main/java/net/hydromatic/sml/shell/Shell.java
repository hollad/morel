package net.hydromatic.sml.shell;

import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;

import static org.jline.keymap.KeyMap.alt;

public class Shell {
  private static final ResourceBundle RESOURCE_BUNDLE =
      ResourceBundle.getBundle(Shell.class.getName(), Locale.ROOT);

  private boolean exit = false;
  private ShellSignalHandler signalHandler = null;
  private Application application;
  private Config appConfig;
  private PrintStream errorStream;
  private PrintStream outputStream;
  private LineReader lineReader;
  private boolean initComplete;

  public Shell() {
    setAppConfig(new Application());

    try {
      outputStream =
          new PrintStream(System.out, true, StandardCharsets.UTF_8.name());
      errorStream =
          new PrintStream(System.err, true, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      handleException(e);
    }

    // attempt to dynamically load signal handler
    try {
      Class handlerClass = Class.forName("sqlline.SunSignalHandler");
      signalHandler =
          (ShellSignalHandler) handlerClass.getConstructor().newInstance();
    } catch (Throwable t) {
      handleException(t);
    }
  }

  void handleException(Throwable e) {
    while (e instanceof InvocationTargetException) {
      e = ((InvocationTargetException) e).getTargetException();
    }
    e.printStackTrace(getErrorStream());
  }

  void setAppConfig(Application application) {
    this.application = application;
    this.appConfig = new Config(application);
  }

  public ShellOpts getOpts() {
    return appConfig.opts;
  }

  public void setOutputStream(PrintStream outputStream) {
    try {
      this.outputStream =
          new PrintStream(outputStream, true, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      handleException(e);
    }
  }

  PrintStream getOutputStream() {
    return outputStream;
  }

  public void setErrorStream(PrintStream errorStream) {
    try {
      this.errorStream = new PrintStream(
          errorStream, true, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      handleException(e);
    }
  }

  PrintStream getErrorStream() {
    return errorStream;
  }

  LineReader getLineReader() {
    return lineReader;
  }

  void setLineReader(LineReader reader) {
    this.lineReader = reader;
  }

  /**
   * Runs Shell, accepting input from the given input stream,
   * dispatching it to the appropriate
   * {@link net.hydromatic.sml.Shell.CommandHandler} until the global variable <code>exit</code> is
   * true.
   *
   * <p>Before you invoke this method, you can redirect output by
   * calling {@link #setOutputStream(PrintStream)}
   * and/or {@link #setErrorStream(PrintStream)}.
   *
   * @param args Command-line arguments
   * @param inputStream Input stream
   * @param saveHistory Whether to save the commands issued to Shell's history
   *                    file
   *
   * @return exit status
   *
   * @throws IOException if Shell cannot obtain
   *         history file or start console reader
   */
  public Status begin(String[] args, InputStream inputStream,
      boolean saveHistory) throws IOException {
    History fileHistory = new DefaultHistory();
    LineReader reader;
    boolean runningScript = getOpts().getRun() != null;
    if (runningScript) {
      try {
        FileInputStream scriptStream =
            new FileInputStream(getOpts().getRun());
        reader = getConsoleReader(scriptStream, fileHistory);
      } catch (Throwable t) {
        handleException(t);
        commands.quit(null, new DispatchCallback());
        return Status.OTHER;
      }
    } else {
      reader = getConsoleReader(inputStream, fileHistory);
    }

    final DispatchCallback callback = new DispatchCallback();
    Status status = initArgs(args, callback);
    switch (status) {
    case ARGS:
      usage();
      // fall through
    case OTHER:
      return status;
    default:
      break;
    }

    try {
      info(getApplicationTitle());
    } catch (Exception e) {
      handleException(e);
    }

    // basic setup done. From this point on, honor opts value for showing
    // exception
    initComplete = true;
    final Terminal terminal = lineReader.getTerminal();
    while (!exit) {
      try {
        // Execute one instruction; terminate on executing a script if
        // there is an error.
        signalHandler.setCallback(callback);
        dispatch(
            reader.readLine(
                Prompt.getPrompt(this).toAnsi(terminal),
                Prompt.getRightPrompt(this).toAnsi(terminal),
                (Character) null,
                null),
            callback);
        if (saveHistory) {
          fileHistory.save();
        }
        if (!callback.isSuccess() && runningScript) {
          commands.quit(null, callback);
          status = Status.OTHER;
        }
      } catch (EndOfFileException eof) {
        // CTRL-D
        commands.quit(null, callback);
      } catch (UserInterruptException ioe) {
        // CTRL-C
        try {
          callback.forceKillSqlQuery();
          callback.setToCancel();
          output(loc("command-canceled"));
        } catch (SQLException sqle) {
          handleException(sqle);
        }
      } catch (Throwable t) {
        handleException(t);
        callback.setToFailure();
      }
    }
    // ### NOTE jvs 10-Aug-2004:  Clean up any outstanding
    // connections automatically.
    // nothing is done with the callback beyond
    commands.closeall(null, new DispatchCallback());
    if (callback.isFailure()) {
      status = Status.OTHER;
    }
    return status;
  }

  /** Parses arguments.
   *
   * @param args Command-line arguments
   * @param callback Status callback
   * @return Whether arguments parsed successfully
   */
  Status initArgs(String[] args, DispatchCallback callback) {
    List<String> commands = new LinkedList<>();
    List<String> files = new LinkedList<>();
    String driver = null;
    String user = null;
    String pass = null;
    String url = null;
    String nickname = null;
    String logFile = null;
    String commandHandler = null;
    String appConfig = null;

    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("--help") || args[i].equals("-h")) {
        return Status.ARGS;
      }

      // -- arguments are treated as properties
      if (args[i].startsWith("--")) {
        String[] parts = split(args[i].substring(2), "=");
        debug(loc("setting-prop", Arrays.asList(parts)));
        if (parts.length > 0) {
          boolean ret;

          if (parts.length >= 2) {
            ret = getOpts().set(parts[0], parts[1], true);
          } else {
            ret = getOpts().set(parts[0], "true", true);
          }

          if (!ret) {
            return Status.ARGS;
          }
        }

        continue;
      }

      if (args[i].charAt(0) == '-') {
        if (i == args.length - 1) {
          return Status.ARGS;
        }
        if (args[i].equals("-d")) {
          driver = args[++i];
        } else if (args[i].equals("-ch")) {
          commandHandler = args[++i];
        } else if (args[i].equals("-n")) {
          user = args[++i];
        } else if (args[i].equals("-p")) {
          pass = args[++i];
        } else if (args[i].equals("-u")) {
          url = args[++i];
        } else if (args[i].equals("-e")) {
          commands.add(args[++i]);
        } else if (args[i].equals("-f")) {
          getOpts().setRun(args[++i]);
        } else if (args[i].equals("-log")) {
          logFile = args[++i];
        } else if (args[i].equals("-nn")) {
          nickname = args[++i];
        } else if (args[i].equals("-ac")) {
          appConfig = args[++i];
        } else {
          return Status.ARGS;
        }
      } else {
        files.add(args[i]);
      }
    }

    if (appConfig != null) {
      dispatch(COMMAND_PREFIX + "appconfig " + appConfig,
          new net.hydromatic.sml.Shell.DispatchCallback());
    }

    if (url != null || user != null || pass != null || driver != null) {
      String com =
          COMMAND_PREFIX + "connect "
              + (url == null ? "\"\"" : url) + " "
              + (user == null || user.length() == 0 ? "''" : user) + " "
              + (pass == null || pass.length() == 0 ? "''" : pass) + " "
              + (driver == null ? "" : driver);
      debug("issuing: " + com);
      dispatch(com, new net.hydromatic.sml.Shell.DispatchCallback());
    }

    if (nickname != null) {
      dispatch(COMMAND_PREFIX + "nickname " + nickname, new net.hydromatic.sml.Shell.DispatchCallback());
    }

    if (logFile != null) {
      dispatch(COMMAND_PREFIX + "record " + logFile, new net.hydromatic.sml.Shell.DispatchCallback());
    }

    if (commandHandler != null) {
      StringBuilder sb = new StringBuilder();
      for (String chElem : commandHandler.split(",")) {
        sb.append(chElem).append(" ");
      }
      dispatch(COMMAND_PREFIX + "commandhandler " + sb.toString(),
          new net.hydromatic.sml.Shell.DispatchCallback());
    }

    // now load properties files
    for (String file : files) {
      dispatch(COMMAND_PREFIX + "properties " + file, new net.hydromatic.sml.Shell.DispatchCallback());
    }

    if (commands.size() > 0) {
      // for single command execute, disable color
      getOpts().set(net.hydromatic.sml.Shell.BuiltInProperty.COLOR, false);
      getOpts().set(net.hydromatic.sml.Shell.BuiltInProperty.HEADER_INTERVAL, -1);

      for (String command : commands) {
        debug(loc("executing-command", command));
        dispatch(command, new net.hydromatic.sml.Shell.DispatchCallback());
      }

      exit = true; // execute and exit
    }

    Status status = Status.OK;

    // if a script file was specified, run the file and quit
    if (getOpts().getRun() != null) {
      dispatch(COMMAND_PREFIX + "run \"" + getOpts().getRun() + "\"", callback);
      if (callback.isFailure()) {
        status = Status.OTHER;
      }
      dispatch(COMMAND_PREFIX + "quit", new net.hydromatic.sml.Shell.DispatchCallback());
    }

    return status;
  }

  String getApplicationTitle() {
    try {
      return application.getInfoMessage();
    } catch (Exception e) {
      handleException(e);
      return Application.DEFAULT_APP_INFO_MESSAGE;
    }
  }

  String getVersion() {
    try {
      return application.getVersion();
    } catch (Exception e) {
      handleException(e);
      return Application.DEFAULT_APP_INFO_MESSAGE;
    }
  }

  public LineReader getConsoleReader(InputStream inputStream,
      History fileHistory) throws IOException {
    if (getLineReader() != null) {
      return getLineReader();
    }
    TerminalBuilder terminalBuilder = TerminalBuilder.builder();
    final Terminal terminal;
    if (inputStream != null) {
      terminalBuilder =
          terminalBuilder.streams(inputStream, System.out);
      terminal = terminalBuilder.build();
    } else {
      terminalBuilder = terminalBuilder.system(true);
      terminal = terminalBuilder.build();
    }

    final LineReaderBuilder lineReaderBuilder = LineReaderBuilder.builder()
        .terminal(terminal)
        .parser(new ShellParser(this))
        .variable(LineReader.HISTORY_FILE, getOpts().getHistoryFile())
        .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true);
    final LineReader lineReader = inputStream == null
        ? lineReaderBuilder
        .appName("sqlline")
        .completer(new ShellCompleter(this))
        .highlighter(new ShellHighlighter(this))
        .build()
        : lineReaderBuilder.build();

    addWidget(lineReader,
        this::nextColorSchemeWidget, "CHANGE_COLOR_SCHEME", alt('h'));
    fileHistory.attach(lineReader);
    setLineReader(lineReader);
    return lineReader;
  }

  private void addWidget(
      LineReader lineReader, Widget widget, String name, CharSequence keySeq) {
    lineReader.getWidgets().put(name, widget);
    lineReader.getKeyMaps().get(LineReader.EMACS).bind(widget, keySeq);
    lineReader.getKeyMaps().get(LineReader.VIINS).bind(widget, keySeq);
  }

  boolean nextColorSchemeWidget() {
    String current = getOpts().getColorScheme();
    Set<String> colorSchemes = application.getName2HighlightStyle().keySet();
    if (BuiltInProperty.DEFAULT.equalsIgnoreCase(current)) {
      if (!colorSchemes.isEmpty()) {
        getOpts().setColorScheme(colorSchemes.iterator().next());
      } else {
        getOpts().setColorScheme(BuiltInProperty.DEFAULT);
      }
      return true;
    }

    Iterator<String> colorSchemeIterator = colorSchemes.iterator();
    while (colorSchemeIterator.hasNext()) {
      String nextColorScheme = colorSchemeIterator.next();
      if (Objects.equals(nextColorScheme, current)) {
        if (colorSchemeIterator.hasNext()) {
          getOpts().setColorScheme(colorSchemeIterator.next());
        } else {
          getOpts().setColorScheme(BuiltInProperty.DEFAULT);
        }
        return true;
      }
    }
    getOpts().setColorScheme(BuiltInProperty.DEFAULT);
    return true;
  }

  void usage() {
    output(loc("cmd-usage"));
  }

  /**
   * Print the specified message to the console
   *
   * @param msg     the message to print
   * @param newline if false, do not append a newline
   */
  public void output(String msg, boolean newline) {
    output(getColorBuffer().append(msg), newline);
  }

  /**
   * Print the specified message to the console
   *
   * @param msg the message to print
   */
  public void output(String msg) {
    output(msg, true);
  }

  public void output(String msg, boolean newline, PrintStream out) {
    output(getColorBuffer().append(msg), newline, out);
  }

  public void output(ColorBuffer msg, boolean newline) {
    output(msg, newline, getOutputStream());
  }

  public void output(ColorBuffer msg, boolean newline, PrintStream out) {
    if (newline) {
      out.println(msg.getColor());
    } else {
      out.print(msg.getColor());
    }
  }

  public void info(String msg) {
    if (!getOpts().getSilent()) {
      output(msg, true, getErrorStream());
    }
  }

  public void info(ColorBuffer msg) {
    if (!getOpts().getSilent()) {
      output(msg, true, getErrorStream());
    }
  }

  /**
   * Issue the specified error message
   *
   * @param msg the message to issue
   * @return false always
   */
  public boolean error(String msg) {
    output(getColorBuffer().red(msg), true, errorStream);
    return false;
  }

  public boolean error(Throwable t) {
    handleException(t);
    return false;
  }

  public void debug(String msg) {
    if (getOpts().getVerbose()) {
      output(getColorBuffer().blue(msg), true, errorStream);
    }
  }

  /**
   * Entry point to creating a {@link ColorBuffer} with color
   * enabled or disabled depending on the value of {@link ShellOpts#getColor}.
   */
  ColorBuffer getColorBuffer() {
    return new ColorBuffer(getOpts().getColor());
  }

  String loc(String res, Object... params) {
    return locStatic(RESOURCE_BUNDLE, getErrorStream(), res, params);
  }

  static String locStatic(ResourceBundle resourceBundle, PrintStream err,
      String res, Object... params) {
    try {
      return new MessageFormat(resourceBundle.getString(res), Locale.ROOT)
          .format(params);
    } catch (Exception e) {
      e.printStackTrace(err);

      try {
        return res + ": " + Arrays.toString(params);
      } catch (Exception e2) {
        return res;
      }
    }
  }

  /** Exit status returned to the operating system. OK, ARGS, OTHER
   * correspond to 0, 1, 2. */
  public enum Status {
    OK, ARGS, OTHER
  }

  /** Cache of configuration settings that come from
   * {@link Application}. */
  private class Config {
    final ShellOpts opts;
    final Map<String, HighlightStyle> name2highlightStyle;
    Config(Application application) {
      this(application.getOpts(Shell.this),
          application.getName2HighlightStyle());
    }

    Config(ShellOpts opts,
        Map<String, HighlightStyle> name2HighlightStyle) {
      this.opts = opts;
      this.name2highlightStyle = name2HighlightStyle;
    }

    Config withOpts(ShellOpts opts) {
      return new Config(opts, this.name2highlightStyle);
    }
  }

}
