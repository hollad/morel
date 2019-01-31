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

import net.hydromatic.sml.shell.ShellSignalHandler;

import org.jline.builtins.Completers;
import org.jline.reader.Completer;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.text.ChoiceFormat;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/** Standard ML REPL. */
public class Shell {
  private static final ResourceBundle RESOURCE_BUNDLE =
      ResourceBundle.getBundle(Shell.class.getName(), Locale.ROOT);

  private static final String SEPARATOR = System.getProperty("line.separator");
  private boolean exit = false;
  public static final String COMMAND_PREFIX = "!";
  private String lastProgress = null;
  private final Commands commands = new Commands(this);
  private PrintStream outputStream;
  private PrintStream errorStream;
  private LineReader lineReader;
  private List<String> batch = null;
  private final Reflector reflector;
  private Application application;
  private Config appConfig;

  // saveDir() is used in various opts that assume it's set. But that means
  // properties starting with "Shell" are read into props in unspecific
  // order using reflection to find setter methods. Avoid
  // confusion/NullPointer due about order of config by prefixing it.
  public static final String Shell_BASE_DIR = "x.Shell.basedir";

  private static boolean initComplete = false;

  private ShellSignalHandler signalHandler = null;
  private final Completer commandCompleter;

  static {
    String testClass = "org.jline.reader.LineReader";
    try {
      Class.forName(testClass);
    } catch (Throwable t) {
      String message =
          locStatic(RESOURCE_BUNDLE, System.err, "jline-missing", testClass);
      throw new ExceptionInInitializerError(message);
    }
  }

  static Manifest getManifest() throws IOException {
    URL base = Shell.class.getResource("/META-INF/MANIFEST.MF");
    URLConnection c = base.openConnection();
    if (c instanceof JarURLConnection) {
      return ((JarURLConnection) c).getManifest();
    }

    return null;
  }

  static String getManifestAttribute(String name) {
    try {
      Manifest m = getManifest();
      if (m == null) {
        return "??";
      }

      Attributes attrs = m.getAttributes("Shell");
      if (attrs == null) {
        return "???";
      }

      String val = attrs.getValue(name);
      if (val == null || "".equals(val)) {
        return "????";
      }

      return val;
    } catch (Exception e) {
      e.printStackTrace();
      return "?????";
    }
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

  static String getApplicationContactInformation() {
    return getManifestAttribute("Implementation-Vendor");
  }

  String loc(String res, int param) {
    try {
      return new MessageFormat(
          new ChoiceFormat(RESOURCE_BUNDLE.getString(res)).format(param),
          Locale.ROOT).format(new Object[]{param});
    } catch (Exception e) {
      return res + ": " + param;
    }
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

  protected String locElapsedTime(long milliseconds) {
    return loc("time-ms", milliseconds / 1000d);
  }

  /**
   * Starts the program.
   *
   * @param args Arguments specified on the command-line
   * @throws IOException on error
   */
  public static void main(String[] args) throws IOException {
    start(args, null, true);
  }

  /**
   * Starts the program with redirected input.
   *
   * <p>For redirected output, use {@link #setOutputStream} and
   * {@link #setErrorStream}.
   *
   * <p>Exits with 0 on success, 1 on invalid arguments, and 2 on any
   * other error.
   *
   * @param args        same as main()
   * @param inputStream redirected input, or null to use standard input
   * @return Status code to be returned to the operating system
   * @throws IOException on error
   */
  public static Status mainWithInputRedirection(String[] args,
      InputStream inputStream) throws IOException {
    return start(args, inputStream, false);
  }

  public Shell() {
    setAppConfig(new Application() {});

    try {
      outputStream =
          new PrintStream(System.out, true, StandardCharsets.UTF_8.name());
      errorStream =
          new PrintStream(System.err, true, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      handleException(e);
    }

    reflector = new Reflector(this);
    getOpts().loadProperties(System.getProperties());
    commandCompleter = new ShellCommandCompleter(this);

    // attempt to dynamically load signal handler
    try {
      Class handlerClass = Class.forName("Shell.SunSignalHandler");
      signalHandler =
          (ShellSignalHandler) handlerClass.getConstructor().newInstance();
    } catch (Throwable t) {
      handleException(t);
    }
  }

  /**
   * Backwards compatibility method to allow
   * {@link #mainWithInputRedirection(String[], java.io.InputStream)} proxied
   * calls to keep method signature but add in new behavior of not saving
   * queries.
   *
   * @param args        args[] passed in directly from {@link #main(String[])}
   * @param inputStream Stream to read sql commands from (stdin or a file) or
   *                    null for an interactive shell
   * @param saveHistory Whether to save the commands issued to Shell's history
   *                    file
   *
   * @return Whether successful
   *
   * @throws IOException if Shell cannot obtain
   *         history file or start console reader
   */
  public static Status start(String[] args,
      InputStream inputStream,
      boolean saveHistory) throws IOException {
    Shell Shell = new Shell();
    Status status = Shell.begin(args, inputStream, saveHistory);

    if (!Boolean.getBoolean(ShellOpts.PROPERTY_NAME_EXIT)) {
      System.exit(status.ordinal());
    }

    return status;
  }


  /**
   * Entry point to creating a {@link ColorBuffer} with color
   * enabled or disabled depending on the value of {@link ShellOpts#getColor}.
   */
  ColorBuffer getColorBuffer() {
    return new ColorBuffer(getOpts().getColor());
  }

  /**
   * Entry point to creating a {@link ColorBuffer} with color enabled or
   * disabled depending on the value of {@link ShellOpts#getColor}.
   */
  ColorBuffer getColorBuffer(String msg) {
    return new ColorBuffer(msg, getOpts().getColor());
  }

  /**
   * Walk through all the known drivers and try to register them.
   */
  void registerKnownDrivers() {
    if (appConfig.allowedDrivers == null) {
      return;
    }
    for (String driverName : appConfig.allowedDrivers) {
      try {
        Class.forName(driverName);
      } catch (Throwable t) {
        // ignore
      }
    }
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
          new DispatchCallback());
    }

    if (url != null || user != null || pass != null || driver != null) {
      String com =
          COMMAND_PREFIX + "connect "
              + (url == null ? "\"\"" : url) + " "
              + (user == null || user.length() == 0 ? "''" : user) + " "
              + (pass == null || pass.length() == 0 ? "''" : pass) + " "
              + (driver == null ? "" : driver);
      debug("issuing: " + com);
      dispatch(com, new DispatchCallback());
    }

    if (nickname != null) {
      dispatch(COMMAND_PREFIX + "nickname " + nickname, new DispatchCallback());
    }

    if (logFile != null) {
      dispatch(COMMAND_PREFIX + "record " + logFile, new DispatchCallback());
    }

    if (commandHandler != null) {
      StringBuilder sb = new StringBuilder();
      for (String chElem : commandHandler.split(",")) {
        sb.append(chElem).append(" ");
      }
      dispatch(COMMAND_PREFIX + "commandhandler " + sb.toString(),
          new DispatchCallback());
    }

    // now load properties files
    for (String file : files) {
      dispatch(COMMAND_PREFIX + "properties " + file, new DispatchCallback());
    }

    if (commands.size() > 0) {
      // for single command execute, disable color
      getOpts().set(BuiltInProperty.COLOR, false);
      getOpts().set(BuiltInProperty.HEADER_INTERVAL, -1);

      for (String command : commands) {
        debug(loc("executing-command", command));
        dispatch(command, new DispatchCallback());
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
      dispatch(COMMAND_PREFIX + "quit", new DispatchCallback());
    }

    return status;
  }

  /**
   * Runs Shell, accepting input from the given input stream,
   * dispatching it to the appropriate
   * {@link CommandHandler} until the global variable <code>exit</code> is
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
    try {
      getOpts().load();
    } catch (Exception e) {
      handleException(e);
    }

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
      getOpts().set(BuiltInProperty.MAX_WIDTH, terminal.getWidth());
      getOpts().set(BuiltInProperty.MAX_HEIGHT, terminal.getHeight());
    }

    final LineReaderBuilder lineReaderBuilder = LineReaderBuilder.builder()
        .terminal(terminal)
        .parser(new ShellParser(this))
        .variable(LineReader.HISTORY_FILE, getOpts().getHistoryFile())
        .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true);
    final LineReader lineReader = inputStream == null
        ? lineReaderBuilder
        .appName("Shell")
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
   * Dispatch the specified line to the appropriate {@link CommandHandler}.
   *
   * @param line The command-line to dispatch
   */
  void dispatch(String line, DispatchCallback callback) {
    if (line == null) {
      // exit
      exit = true;
      return;
    }

    if (line.trim().length() == 0) {
      callback.setStatus(DispatchCallback.Status.SUCCESS);
      return;
    }

    if (isComment(line)) {
      callback.setStatus(DispatchCallback.Status.SUCCESS);
      return;
    }

    line = line.trim();

    if (isHelpRequest(line)) {
      line = COMMAND_PREFIX + "help";
    }

    final boolean echoToFile;
    if (line.startsWith(COMMAND_PREFIX)) {
      Map<String, CommandHandler> cmdMap = new TreeMap<>();
      String commandLine = line.substring(1);
      for (CommandHandler commandHandler : getCommandHandlers()) {
        String match = commandHandler.matches(commandLine);
        if (match != null) {
          cmdMap.put(match, commandHandler);
        }
      }

      final CommandHandler matchingHandler;
      switch (cmdMap.size()) {
      case 0:
        callback.setStatus(DispatchCallback.Status.FAILURE);
        error(loc("unknown-command", commandLine));
        return;
      case 1:
        matchingHandler = cmdMap.values().iterator().next();
        break;
      default:
        // look for the exact match
        matchingHandler = cmdMap.get(split(commandLine, 1)[0]);
        if (matchingHandler == null) {
          callback.setStatus(DispatchCallback.Status.FAILURE);
          error(loc("multiple-matches", cmdMap.keySet().toString()));
          return;
        }
        break;
      }

      echoToFile = matchingHandler.echoToFile();
      callback.setStatus(DispatchCallback.Status.RUNNING);
      matchingHandler.execute(commandLine, callback);
    } else {
      echoToFile = true;
      callback.setStatus(DispatchCallback.Status.RUNNING);
      commands.sql(line, callback);
    }

    // save it to the current script, if any
    if (scriptOutputFile != null && echoToFile) {
      scriptOutputFile.addLine(line);
    }

  }

  /**
   * Test whether a line is a help request other than !help.
   *
   * @param line the line to be tested
   * @return true if a help request
   */
  boolean isHelpRequest(String line) {
    return line.equals("?") || line.equalsIgnoreCase("help");
  }

  /**
   * Test whether a line is a comment.
   *
   * @param line the line to be tested
   * @return true if a comment
   */
  boolean isComment(String line, boolean trim) {
    final String trimmedLine = trim ? line.trim() : line;
    for (String comment: getDialect().getShellOneLineComments()) {
      if (trimmedLine.startsWith(comment)) {
        return true;
      }
    }
    return false;
  }

  boolean isComment(String line) {
    return isComment(line, true);
  }

  /**
   * Print the specified message to the console
   *
   * @param msg the message to print
   */
  public void output(String msg) {
    output(msg, true);
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

  public void output(ColorBuffer msg) {
    output(msg, true);
  }

  public void output(String msg, boolean newline, PrintStream out) {
    output(getColorBuffer(msg), newline, out);
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

    if (recordOutputFile == null) {
      return;
    }

    // only write to the record file if we are writing a line ...
    // otherwise we might get garbage from backspaces and such.
    if (newline) {
      recordOutputFile.addLine(msg.getMono()); // always just write mono
    }
  }

  /**
   * Print the specified message to the console
   *
   * @param msg     the message to print
   * @param newline if false, do not append a newline
   */
  public void output(String msg, boolean newline) {
    output(getColorBuffer(msg), newline);
  }

  void autocommitStatus(Connection c) throws SQLException {
    debug(loc("autocommit-status", c.getAutoCommit() + ""));
  }

  /**
   * Ensure that autocommit is on for the current connection
   *
   * @return true if autocommit is set
   */
  boolean assertAutoCommit() {
    if (!assertConnection()) {
      return false;
    }

    try {
      if (getDatabaseConnection().connection.getAutoCommit()) {
        return error(loc("autocommit-needs-off"));
      }
    } catch (Exception e) {
      return error(e);
    }

    return true;
  }

  /**
   * Assert that we have an active, living connection. Print an error message
   * if we do not.
   *
   * @return true if there is a current, active connection
   */
  boolean assertConnection() {
    try {
      if (getDatabaseConnection() == null
          || getDatabaseConnection().connection == null) {
        return error(loc("no-current-connection"));
      }

      if (getDatabaseConnection().connection.isClosed()) {
        return error(loc("connection-is-closed"));
      }
    } catch (SQLException sqle) {
      return error(loc("no-current-connection"));
    }

    return true;
  }

  /**
   * Print out any warnings that exist for the current connection.
   */
  void showWarnings() {
    if (getDatabaseConnection().connection == null) {
      return;
    }

    if (!getOpts().getShowWarnings()) {
      return;
    }

    try {
      showWarnings(getDatabaseConnection().connection.getWarnings());
    } catch (Exception e) {
      handleException(e);
    }
  }

  /**
   * Print the specified warning on the console, as well as any warnings that
   * are returned from {@link SQLWarning#getNextWarning}.
   *
   * @param warn the {@link SQLWarning} to print
   */
  void showWarnings(SQLWarning warn) {
    if (warn == null) {
      return;
    }

    if (seenWarnings.get(warn) == null) {
      // don't re-display warnings we have already seen
      seenWarnings.put(warn, new java.util.Date());
      handleSQLException(warn);
    }

    SQLWarning next = warn.getNextWarning();
    if (next != warn) {
      showWarnings(next);
    }
  }

  /**
   * Try to obtain the current size of the specified {@link ResultSet} by
   * jumping to the last row and getting the row number.
   *
   * @param rs the {@link ResultSet} to get the size for
   * @return the size, or -1 if it could not be obtained
   */
  int getSize(ResultSet rs) {
    try {
      if (rs.getType() == ResultSet.TYPE_FORWARD_ONLY) {
        return -1;
      }

      rs.last();
      int total = rs.getRow();
      rs.beforeFirst();
      return total;
    } catch (SQLException | AbstractMethodError sqle) {
      return -1;
    }
  }

  ResultSet getColumns(String table) throws SQLException {
    if (!assertConnection()) {
      return null;
    }

    return getDatabaseConnection().meta.getColumns(
        getDatabaseConnection().meta.getConnection().getCatalog(),
        null,
        table,
        "%");
  }

  ResultSet getTables() throws SQLException {
    if (!assertConnection()) {
      return null;
    }

    return getDatabaseConnection().meta.getTables(
        getDatabaseConnection().meta.getConnection().getCatalog(),
        null,
        "%",
        new String[] {"TABLE"});
  }

  Set<String> getColumnNames(DatabaseMetaDataWrapper meta) {
    Set<String> names = new HashSet<>();
    info(loc("building-tables"));

    try {
      ResultSet columns = getColumns("%");

      try {
        int total = getSize(columns);
        int index = 0;

        while (columns.next()) {
          // add the following strings:
          // 1. column name
          // 2. table name
          // 3. tablename.columnname

          progress(index++, total);
          final String tableName = columns.getString("TABLE_NAME");
          final String columnName = columns.getString("COLUMN_NAME");
          names.add(tableName);
          names.add(columnName);
          names.add(tableName + "." + columnName);
        }

        progress(index, index);
      } finally {
        columns.close();
      }

      info(loc("done"));

      return names;
    } catch (Throwable t) {
      handleException(t);
      return Collections.emptySet();
    }
  }

  /**
   * Splits the line into an array, tokenizing on space characters.
   *
   * @param line the line to break up
   * @return an array of individual words
   */
  String[] split(String line) {
    return split(line, 0);
  }

  /**
   * Splits the line into an array, tokenizing on space characters,
   * limiting the number of words to read.
   *
   * @param line the line to break up
   * @param limit the limit for number of tokens
   *        to be processed (0 means no limit)
   * @return an array of individual words
   */
  String[] split(String line, int limit) {
    return split(line, " ", limit);
  }

  /**
   * Splits the line into an array of possibly-compound identifiers, observing
   * the database's quoting syntax.
   *
   * <p>For example, on Oracle, which uses double-quote (&quot;) as quote
   * character,</p>
   *
   * <blockquote>!tables "My Schema"."My Table"</blockquote>
   *
   * <p>returns</p>
   *
   * <blockquote>{ {"!tables"}, {"My Schema", "My Table"} }</blockquote>
   *
   * @param line the line to break up
   * @return an array of compound words
   */
  public String[][] splitCompound(String line) {
    final Dialect dialect = getDialect();

    int state = SPACE;
    int idStart = -1;
    final char[] chars = line.toCharArray();
    int n = chars.length;

    // Trim off trailing semicolon and/or whitespace
    while (n > 0
        && (Character.isWhitespace(chars[n - 1])
        || chars[n - 1] == ';')) {
      --n;
    }

    final List<String[]> words = new ArrayList<>();
    final List<String> current = new ArrayList<>();
    for (int i = 0; i < n;) {
      char c = chars[i];
      switch (state) {
      case SPACE:
      case DOT_SPACE:
        ++i;
        if (Character.isWhitespace(c)) {
          // nothing
        } else if (c == '.') {
          state = DOT_SPACE;
        } else if (c == dialect.getOpenQuote()) {
          if (state == SPACE) {
            if (current.size() > 0) {
              words.add(
                  current.toArray(new String[current.size()]));
              current.clear();
            }
          }
          state = QUOTED;
          idStart = i;
        } else {
          if (state == SPACE) {
            if (current.size() > 0) {
              words.add(
                  current.toArray(new String[current.size()]));
              current.clear();
            }
          }
          state = UNQUOTED;
          idStart = i - 1;
        }
        break;
      case QUOTED:
        ++i;
        if (c == dialect.getCloseQuote()) {
          if (i < n
              && chars[i] == dialect.getCloseQuote()) {
            // Repeated quote character inside a quoted identifier.
            // Eliminate one of the repeats, and we remain inside a
            // quoted identifier.
            System.arraycopy(chars, i, chars, i - 1, n - i);
            --n;
          } else {
            state = SPACE;
            final String word =
                String.copyValueOf(chars, idStart, i - idStart - 1);
            current.add(word);
          }
        }
        break;
      case UNQUOTED:
        // We are in an unquoted identifier. Whitespace or dot ends
        // the identifier, anything else extends it.
        ++i;
        if (Character.isWhitespace(c) || c == '.') {
          String word = String.copyValueOf(chars, idStart, i - idStart - 1);
          if (word.equalsIgnoreCase("NULL")) {
            word = null;
          } else if (dialect.isUpper()) {
            word = word.toUpperCase(Locale.ROOT);
          }
          current.add(word);
          state = c == '.' ? DOT_SPACE : SPACE;
        }
        break;
      default:
        throw new AssertionError("unexpected state " + state);
      }
    }

    switch (state) {
    case SPACE:
    case DOT_SPACE:
      break;
    case QUOTED:
    case UNQUOTED:
      // In the middle of a quoted string. Be lenient, and complete the
      // word.
      String word = String.copyValueOf(chars, idStart, n - idStart);
      if (state == UNQUOTED) {
        if (word.equalsIgnoreCase("NULL")) {
          word = null;
        } else if (dialect.isUpper()) {
          word = word.toUpperCase(Locale.ROOT);
        }
      }
      current.add(word);
      break;
    default:
      throw new AssertionError("unexpected state " + state);
    }

    if (current.size() > 0) {
      words.add(current.toArray(new String[0]));
    }

    return words.toArray(new String[0][]);
  }

  Dialect getDialect() {
    final DatabaseConnection databaseConnection = getDatabaseConnection();
    return databaseConnection == null || databaseConnection.getDialect() == null
        ? DialectImpl.getDefault()
        : databaseConnection.getDialect();
  }

  /**
   * In a region of whitespace.
   */
  private static final int SPACE = 0;

  /**
   * In a region of whitespace that contains a dot.
   */
  private static final int DOT_SPACE = 1;

  /**
   * Inside a quoted identifier.
   */
  private static final int QUOTED = 2;

  /**
   * Inside an unquoted identifier.
   */
  private static final int UNQUOTED = 3;

  String dequote(String str) {
    if (str == null) {
      return null;
    }

    if ((str.length() == 1 && (str.charAt(0) == '\'' || str.charAt(0) == '\"'))
        || ((str.charAt(0) == '"' || str.charAt(0) == '\''
        || str.charAt(str.length() - 1) == '"'
        || str.charAt(str.length() - 1) == '\'')
        && str.charAt(0) != str.charAt(str.length() - 1))) {
      throw new IllegalArgumentException(
          "A quote should be closed for <" + str + ">");
    }
    char prevQuote = 0;
    int index = 0;
    while ((str.charAt(index) == str.charAt(str.length() - index - 1))
        && (str.charAt(index) == '"' || str.charAt(index) == '\'')) {
      // if start and end point to the same element
      if (index == str.length() - index - 1) {
        if (prevQuote == str.charAt(index)) {
          throw new IllegalArgumentException(
              "A non-paired quote may not occur between the same quotes");
        } else {
          break;
        }
        // else if start and end point to neighbour elements
      } else if (index == str.length() - index - 2) {
        index++;
        break;
      }
      prevQuote = str.charAt(index);
      index++;
    }

    return index == 0 ? str : str.substring(index, str.length() - index);
  }

  String[] split(String line, String delim) {
    return split(line, delim, 0);
  }

  public String[] split(String line, String delim, int limit) {
    if (delim.indexOf('\'') != -1 || delim.indexOf('"') != -1) {
      // quotes in delim are not supported yet
      throw new UnsupportedOperationException();
    }
    boolean inQuotes = false;
    int tokenStart = 0;
    int lastProcessedIndex = 0;

    List<String> tokens = new ArrayList<>();
    for (int i = 0; i < line.length(); i++) {
      if (limit > 0 && tokens.size() == limit) {
        break;
      }
      if (line.charAt(i) == '\'' || line.charAt(i) == '"') {
        if (inQuotes) {
          if (line.charAt(tokenStart) == line.charAt(i)) {
            inQuotes = false;
            tokens.add(line.substring(tokenStart, i + 1));
            lastProcessedIndex = i;
          }
        } else {
          tokenStart = i;
          inQuotes = true;
        }
      } else if (line.regionMatches(i, delim, 0, delim.length())) {
        if (inQuotes) {
          i += delim.length() - 1;
          continue;
        } else if (i > 0 && (
            !line.regionMatches(i - delim.length(), delim, 0, delim.length())
                && line.charAt(i - 1) != '\''
                && line.charAt(i - 1) != '"')) {
          tokens.add(line.substring(tokenStart, i));
          lastProcessedIndex = i;
          i += delim.length() - 1;

        }
      } else if (i > 0
          && line.regionMatches(i - delim.length(), delim, 0, delim.length())) {
        if (inQuotes) {
          continue;
        }
        tokenStart = i;
      }
    }
    if ((lastProcessedIndex != line.length() - 1
        && (limit == 0 || limit > tokens.size()))
        || (lastProcessedIndex == 0 && line.length() == 1)) {
      tokens.add(line.substring(tokenStart));
    }
    String[] ret = new String[tokens.size()];
    for (int i = 0; i < tokens.size(); i++) {
      ret[i] = dequote(tokens.get(i));
    }

    return ret;
  }

  static <K, V> Map<K, V> map(K key, V value, Object... obs) {
    final Map<K, V> m = new HashMap<>();
    m.put(key, value);
    for (int i = 0; i < obs.length - 1; i += 2) {
      //noinspection unchecked
      m.put((K) obs[i], (V) obs[i + 1]);
    }
    return m;
  }

  static boolean getMoreResults(Statement stmnt) {
    try {
      return stmnt.getMoreResults();
    } catch (Throwable t) {
      return false;
    }
  }

  static String xmlEncode(String str, String charsCouldBeNotEncoded) {
    if (str == null) {
      return str;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < str.length(); i++) {
      char ch = str.charAt(i);
      switch (ch) {
      case '"':
        // could be skipped for xml attribute in case of single quotes
        // could be skipped for element text
        if (charsCouldBeNotEncoded.indexOf(ch) == -1) {
          sb.append("&quot;");
        } else {
          sb.append(ch);
        }
        break;
      case '<':
        sb.append("&lt;");
        break;
      case '&':
        sb.append("&amp;");
        break;
      case '>':
        // could be skipped for xml attribute and there is no sequence ]]>
        // could be skipped for element text and there is no sequence ]]>
        if ((i > 1 && str.charAt(i - 1) == ']' && str.charAt(i - 2) == ']')
            || charsCouldBeNotEncoded.indexOf(ch) == -1) {
          sb.append("&gt;");
        } else {
          sb.append(ch);
        }
        break;
      case '\'':
        // could be skipped for xml attribute in case of double quotes
        // could be skipped for element text
        if (charsCouldBeNotEncoded.indexOf(ch) == -1) {
          sb.append("&apos;");
        } else {
          sb.append(ch);
        }
        break;
      default:
        sb.append(ch);
      }
    }
    return sb.toString();
  }

  /**
   * Split the line based on spaces, asserting that the number of words is
   * correct.
   *
   * @param line      the line to split
   * @param assertLen the number of words to assure
   * @param usage     the message to output if there are an incorrect number of
   *                  words.
   * @return the split lines, or null if the assertion failed.
   */
  String[] split(String line, int assertLen, String usage) {
    String[] ret = split(line);

    if (ret.length != assertLen) {
      error(usage);
      return null;
    }

    return ret;
  }

  /**
   * Wrap the specified string by breaking on space characters.
   *
   * @param toWrap the string to wrap
   * @param len    the maximum length of any line
   * @param start  the number of spaces to pad at the beginning of a line
   * @return the wrapped string
   */
  String wrap(String toWrap, int len, int start) {
    StringBuilder buff = new StringBuilder();
    StringBuilder line = new StringBuilder();

    char[] head = new char[start];
    Arrays.fill(head, ' ');

    for (StringTokenizer tok = new StringTokenizer(toWrap, " ");
         tok.hasMoreTokens();) {
      String next = tok.nextToken();
      final int x = line.length();
      line.append(line.length() == 0 ? "" : " ").append(next);
      if (line.length() > len) {
        // The line is now too long. Backtrack: remove the last word, start a
        // new line containing just that word.
        line.setLength(x);
        buff.append(line).append(SEPARATOR).append(head);
        line.setLength(0);
        line.append(next);
      }
    }

    buff.append(line);

    return buff.toString();
  }

  /**
   * Output a progress indicator to the console.
   *
   * @param cur the current progress
   * @param max the maximum progress, or -1 if unknown
   */
  void progress(int cur, int max) {
    StringBuilder out = new StringBuilder();

    if (lastProgress != null) {
      char[] back = new char[lastProgress.length()];
      Arrays.fill(back, '\b');
      out.append(back);
    }

    String progress =
        cur + "/" + (max == -1 ? "?" : "" + max) + " "
            + (max == -1 ? "(??%)"
            : "(" + cur * 100 / (max == 0 ? 1 : max) + "%)");

    if (cur >= max && max != -1) {
      progress += " " + loc("done") + SEPARATOR;
      lastProgress = null;
    } else {
      lastProgress = progress;
    }

    out.append(progress);

    getOutputStream().print(out.toString());
    getOutputStream().flush();
  }

  ///////////////////////////////
  // Exception handling routines
  ///////////////////////////////

  public void handleException(Throwable e) {
    while (e instanceof InvocationTargetException) {
      e = ((InvocationTargetException) e).getTargetException();
    }

    if (e instanceof SQLException) {
      handleSQLException((SQLException) e);
    } else if (e instanceof WrappedSqlException) {
      handleSQLException((SQLException) e.getCause());
    } else if (!initComplete && !getOpts().getVerbose()) {
      // all init errors must be verbose
      if (e.getMessage() == null) {
        error(e.getClass().getName());
      } else {
        error(e.getMessage());
      }
    } else {
      e.printStackTrace(getErrorStream());
    }
  }

  void handleSQLException(SQLException e) {
    // all init errors must be verbose
    final boolean showWarnings = !initComplete || getOpts().getShowWarnings();
    final boolean verbose = !initComplete || getOpts().getVerbose();
    final boolean showNested = !initComplete || getOpts().getShowNestedErrs();

    if (e instanceof SQLWarning && !showWarnings) {
      return;
    }

    String type = e instanceof SQLWarning ? loc("Warning") : loc("Error");

    error(
        loc(e instanceof SQLWarning ? "Warning" : "Error",
            e.getMessage() == null ? "" : e.getMessage().trim(),
            e.getSQLState() == null ? "" : e.getSQLState().trim(),
            e.getErrorCode()));

    if (verbose) {
      e.printStackTrace();
    }

    if (!showNested) {
      return;
    }

    for (SQLException nested = e.getNextException();
         nested != null && nested != e;
         nested = nested.getNextException()) {
      handleSQLException(nested);
    }
  }

  /** Looks for a driver with a particular URL. Returns the name of the class
   * if found, null if not found. */
  String scanForDriver(String url) {
    try {
      // already registered
      Driver driver;
      if ((driver = findRegisteredDriver(url)) != null) {
        return driver.getClass().getCanonicalName();
      }

      System.out.println("before");
      scanDrivers();

      if ((driver = findRegisteredDriver(url)) != null) {
        return driver.getClass().getCanonicalName();
      }

      System.out.println("return null");
      return null;
    } catch (Exception e) {
      e.printStackTrace();
      debug(e.toString());
      return null;
    }
  }

  private Driver findRegisteredDriver(String url) {
    for (Enumeration<Driver> drivers = DriverManager.getDrivers();
         drivers.hasMoreElements();) {
      Driver driver = drivers.nextElement();
      try {
        if (driver.acceptsURL(url)) {
          return driver;
        }
      } catch (Exception e) {
        // ignore
      }
    }

    return null;
  }

  Set<Driver> scanDrivers() {
    long start = System.currentTimeMillis();

    Set<Driver> scannedDrivers = new HashSet<>();
    // if appConfig.allowedDrivers.isEmpty() then do nothing
    if (appConfig.allowedDrivers == null
        || !appConfig.allowedDrivers.isEmpty()) {
      Set<String> driverClasses = appConfig.allowedDrivers == null
          ? Collections.emptySet() : new HashSet<>(appConfig.allowedDrivers);
      for (Driver driver : ServiceLoader.load(Driver.class)) {
        if (driverClasses.isEmpty()
            || driverClasses.contains(driver.getClass().getCanonicalName())) {
          scannedDrivers.add(driver);
        }
      }
    }
    long end = System.currentTimeMillis();
    info("scan complete in " + (end - start) + "ms");
    return scannedDrivers;
  }

  ///////////////////////////////////////
  // ResultSet output formatting classes
  ///////////////////////////////////////

  int print(ResultSet rs, DispatchCallback callback) throws SQLException {
    String format = getOpts().getOutputFormat();
    OutputFormat f = getOutputFormats().get(format);
    if ("csv".equals(format)) {
      final SeparatedValuesOutputFormat csvOutput =
          (SeparatedValuesOutputFormat) f;
      if ((csvOutput.separator == null && getOpts().getCsvDelimiter() != null)
          || (csvOutput.separator != null
          && !csvOutput.separator.equals(getOpts().getCsvDelimiter())
          || csvOutput.quoteCharacter
          != getOpts().getCsvQuoteCharacter())) {
        f = new SeparatedValuesOutputFormat(this,
            getOpts().getCsvDelimiter(), getOpts().getCsvQuoteCharacter());
        Map<String, OutputFormat> updFormats =
            new HashMap<>(getOutputFormats());
        updFormats.put("csv", f);
        updateOutputFormats(updFormats);
      }
    }

    if (f == null) {
      error(loc("unknown-format", format, getOutputFormats().keySet()));
      f = new TableOutputFormat(this);
    }

    Rows rows;
    if (getOpts().getIncremental()) {
      rows = new IncrementalRows(this, rs, callback);
    } else {
      rows = new BufferedRows(this, rs);
    }

    return f.print(rows);
  }

  Statement createStatement() throws SQLException {
    Statement stmnt = getDatabaseConnection().connection.createStatement();
    int timeout = getOpts().getTimeout();
    if (timeout > -1) {
      stmnt.setQueryTimeout(timeout);
    }
    int rowLimit = getOpts().getRowLimit();
    if (rowLimit != 0) {
      stmnt.setMaxRows(rowLimit);
    }

    return stmnt;
  }

  void runBatch(List<String> statements) {
    try {
      Statement stmnt = createStatement();
      try {
        for (String statement : statements) {
          stmnt.addBatch(statement);
        }

        int[] counts = stmnt.executeBatch();
        if (counts == null) {
          counts = new int[0];
        }

        output(
            getColorBuffer()
                .pad(getColorBuffer().bold("COUNT"), 8)
                .append(getColorBuffer().bold("STATEMENT")));

        for (int i = 0; i < counts.length; i++) {
          output(
              getColorBuffer().pad(counts[i] + "", 8)
                  .append(statements.get(i)));
        }
      } finally {
        try {
          stmnt.close();
        } catch (Exception e) {
          // ignore
        }
      }
    } catch (Exception e) {
      handleException(e);
    }
  }

  // for testing
  int runCommands(DispatchCallback callback, String... cmds) {
    return runCommands(Arrays.asList(cmds), callback);
  }

  public int runCommands(List<String> cmds, DispatchCallback callback) {
    int successCount = 0;

    try {
      int index = 1;
      int size = cmds.size();
      for (String cmd : cmds) {
        info(getColorBuffer().pad(index++ + "/" + size, 13).append(cmd));
        dispatch(cmd, callback);
        boolean success = callback.isSuccess();
        // if we do not force script execution, abort
        // when a failure occurs.
        if (!success && !getOpts().getForce()) {
          error(loc("abort-on-error", cmd));
          return successCount;
        }
        successCount += success ? 1 : 0;
      }
    } catch (Exception e) {
      handleException(e);
    }

    return successCount;
  }

  void setCompletions() {
    if (getDatabaseConnection() != null) {
      getDatabaseConnection().setCompletions(getOpts().getFastConnect());
    }
  }

  void outputProperty(String key, String value) {
    output(getColorBuffer()
        .green(getColorBuffer()
            .pad(key, 20)
            .getMono())
        .append(value));
  }
  public ShellOpts getOpts() {
    return appConfig.opts;
  }

  public void setOpts(ShellOpts opts) {
    appConfig = appConfig.withOpts(opts);
  }

  DatabaseConnections getDatabaseConnections() {
    return connections;
  }

  public boolean isExit() {
    return exit;
  }

  public void setExit(boolean exit) {
    this.exit = exit;
  }

  Set<Driver> getDrivers() {
    return drivers;
  }

  void setDrivers(Set<Driver> drivers) {
    this.drivers = drivers;
  }

  public static String getSeparator() {
    return SEPARATOR;
  }

  Commands getCommands() {
    return commands;
  }

  OutputFile getScriptOutputFile() {
    return scriptOutputFile;
  }

  void setScriptOutputFile(OutputFile script) {
    this.scriptOutputFile = script;
  }

  OutputFile getRecordOutputFile() {
    return recordOutputFile;
  }

  void setRecordOutputFile(OutputFile record) {
    this.recordOutputFile = record;
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

  List<String> getBatch() {
    return batch;
  }

  void setBatch(List<String> batch) {
    this.batch = batch;
  }

  public Reflector getReflector() {
    return reflector;
  }

  public Completer getCommandCompleter() {
    return commandCompleter;
  }

  void setAppConfig(Application application) {
    setDrivers(null);
    this.application = application;
    this.appConfig = new Config(application);
  }

  public HighlightStyle getHighlightStyle() {
    return appConfig.name2highlightStyle.get(getOpts().getColorScheme());
  }

  public Collection<CommandHandler> getCommandHandlers() {
    return appConfig.commandHandlers;
  }

  public void updateCommandHandlers(
      Collection<CommandHandler> commandHandlers) {
    appConfig = appConfig.withCommandHandlers(commandHandlers);
  }

  public Map<String, OutputFormat> getOutputFormats() {
    return appConfig.formats;
  }

  public void updateOutputFormats(Map<String, OutputFormat> formats) {
    appConfig = appConfig.withFormats(formats);
  }

  /** Exit status returned to the operating system. OK, ARGS, OTHER
   * correspond to 0, 1, 2. */
  public enum Status {
    OK, ARGS, OTHER
  }

  /** Cache of configuration settings that come from
   * {@link Application}. */
  private class Config {
    final Collection<String> allowedDrivers;
    final ShellOpts opts;
    final Collection<CommandHandler> commandHandlers;
    final Map<String, OutputFormat> formats;
    final Map<String, HighlightStyle> name2highlightStyle;
    Config(Application application) {
      this(application.allowedDrivers(),
          application.getOpts(Shell.this),
          application.getCommandHandlers(Shell.this),
          application.getOutputFormats(Shell.this),
          application.getName2HighlightStyle());
    }

    Config(Collection<String> knownDrivers,
        ShellOpts opts,
        Collection<CommandHandler> commandHandlers,
        Map<String, OutputFormat> formats,
        Map<String, HighlightStyle> name2HighlightStyle) {
      this.allowedDrivers = knownDrivers == null
          ? null : Collections.unmodifiableSet(new HashSet<>(knownDrivers));
      this.opts = opts;
      this.commandHandlers = Collections.unmodifiableList(
          new ArrayList<>(commandHandlers));
      this.formats = Collections.unmodifiableMap(formats);
      this.name2highlightStyle = name2HighlightStyle;
    }

    Config withCommandHandlers(Collection<CommandHandler> commandHandlers) {
      return new Config(this.allowedDrivers, this.opts,
          commandHandlers, this.formats, this.name2highlightStyle);
    }

    Config withFormats(Map<String, OutputFormat> formats) {
      return new Config(this.allowedDrivers, this.opts,
          this.commandHandlers, formats, this.name2highlightStyle);
    }

    Config withOpts(ShellOpts opts) {
      return new Config(this.allowedDrivers, opts,
          this.commandHandlers, this.formats, this.name2highlightStyle);
    }
  }

  interface Application {
    String DEFAULT_APP_INFO_MESSAGE = "smlj";

    default String getInfoMessage() {
      return "smlj";
    }

    default String getVersion() {
      return "0.1";
    }
  }

  static class ShellOpts implements Completer {
    public static final String PROPERTY_PREFIX = "sqlline.";
    public static final String PROPERTY_NAME_EXIT =
        PROPERTY_PREFIX + "system.exit";

    void loadProperties(Properties properties) {
      for (String key : Commands.asMap(properties).keySet()) {
        if (key.equals(PROPERTY_NAME_EXIT)) {
          // fix for sf.net bug 879422
          continue;
        }
        if (key.startsWith(PROPERTY_PREFIX)) {
          set(key.substring(PROPERTY_PREFIX.length()), properties.getProperty(key));
        }
      }
    }

    public String getColorScheme() {
      return get(COLOR_SCHEME);
    }

    public boolean getColor() {
      return getBoolean(COLOR);
    }


  }

  static class Commands {
    private static final String[] METHODS = {
        "allProceduresAreCallable",
        "allTablesAreSelectable",
        "dataDefinitionCausesTransactionCommit",
        "dataDefinitionIgnoredInTransactions",
        "doesMaxRowSizeIncludeBlobs",
        "getCatalogSeparator",
        "getCatalogTerm",
        "getDatabaseProductName",
        "getDatabaseProductVersion",
        "getDefaultTransactionIsolation",
        "getDriverMajorVersion",
        "getDriverMinorVersion",
        "getDriverName",
        "getDriverVersion",
        "getExtraNameCharacters",
        "getIdentifierQuoteString",
        "getMaxBinaryLiteralLength",
        "getMaxCatalogNameLength",
        "getMaxCharLiteralLength",
        "getMaxColumnNameLength",
        "getMaxColumnsInGroupBy",
        "getMaxColumnsInIndex",
        "getMaxColumnsInOrderBy",
        "getMaxColumnsInSelect",
        "getMaxColumnsInTable",
        "getMaxConnections",
        "getMaxCursorNameLength",
        "getMaxIndexLength",
        "getMaxProcedureNameLength",
        "getMaxRowSize",
        "getMaxSchemaNameLength",
        "getMaxStatementLength",
        "getMaxStatements",
        "getMaxTableNameLength",
        "getMaxTablesInSelect",
        "getMaxUserNameLength",
        "getNumericFunctions",
        "getProcedureTerm",
        "getSchemaTerm",
        "getSearchStringEscape",
        "getSQLKeywords",
        "getStringFunctions",
        "getSystemFunctions",
        "getTimeDateFunctions",
        "getURL",
        "getUserName",
        "isCatalogAtStart",
        "isReadOnly",
        "nullPlusNonNullIsNull",
        "nullsAreSortedAtEnd",
        "nullsAreSortedAtStart",
        "nullsAreSortedHigh",
        "nullsAreSortedLow",
        "storesLowerCaseIdentifiers",
        "storesLowerCaseQuotedIdentifiers",
        "storesMixedCaseIdentifiers",
        "storesMixedCaseQuotedIdentifiers",
        "storesUpperCaseIdentifiers",
        "storesUpperCaseQuotedIdentifiers",
        "supportsAlterTableWithAddColumn",
        "supportsAlterTableWithDropColumn",
        "supportsANSI92EntryLevelSQL",
        "supportsANSI92FullSQL",
        "supportsANSI92IntermediateSQL",
        "supportsBatchUpdates",
        "supportsCatalogsInDataManipulation",
        "supportsCatalogsInIndexDefinitions",
        "supportsCatalogsInPrivilegeDefinitions",
        "supportsCatalogsInProcedureCalls",
        "supportsCatalogsInTableDefinitions",
        "supportsColumnAliasing",
        "supportsConvert",
        "supportsCoreSQLGrammar",
        "supportsCorrelatedSubqueries",
        "supportsDataDefinitionAndDataManipulationTransactions",
        "supportsDataManipulationTransactionsOnly",
        "supportsDifferentTableCorrelationNames",
        "supportsExpressionsInOrderBy",
        "supportsExtendedSQLGrammar",
        "supportsFullOuterJoins",
        "supportsGroupBy",
        "supportsGroupByBeyondSelect",
        "supportsGroupByUnrelated",
        "supportsIntegrityEnhancementFacility",
        "supportsLikeEscapeClause",
        "supportsLimitedOuterJoins",
        "supportsMinimumSQLGrammar",
        "supportsMixedCaseIdentifiers",
        "supportsMixedCaseQuotedIdentifiers",
        "supportsMultipleResultSets",
        "supportsMultipleTransactions",
        "supportsNonNullableColumns",
        "supportsOpenCursorsAcrossCommit",
        "supportsOpenCursorsAcrossRollback",
        "supportsOpenStatementsAcrossCommit",
        "supportsOpenStatementsAcrossRollback",
        "supportsOrderByUnrelated",
        "supportsOuterJoins",
        "supportsPositionedDelete",
        "supportsPositionedUpdate",
        "supportsSchemasInDataManipulation",
        "supportsSchemasInIndexDefinitions",
        "supportsSchemasInPrivilegeDefinitions",
        "supportsSchemasInProcedureCalls",
        "supportsSchemasInTableDefinitions",
        "supportsSelectForUpdate",
        "supportsStoredProcedures",
        "supportsSubqueriesInComparisons",
        "supportsSubqueriesInExists",
        "supportsSubqueriesInIns",
        "supportsSubqueriesInQuantifieds",
        "supportsTableCorrelationNames",
        "supportsTransactions",
        "supportsUnion",
        "supportsUnionAll",
        "usesLocalFilePerTable",
        "usesLocalFiles",
    };

    private static final String CONNECT_PROPERTY = "#CONNECT_PROPERTY#.";
    private final Shell shell;

    Commands(Shell shell) {
      this.shell = shell;
    }

    public void history(String line, DispatchCallback callback) {
      try {
        String argsLine = line.substring("history".length());
        org.jline.builtins.Commands.history(
            shell.getLineReader(),
            shell.getOutputStream(),
            shell.getErrorStream(),
            argsLine.isEmpty()
                ? new String[]{"-d"}
                : shell.split(argsLine, " "));
      } catch (IOException e) {
        callback.setToFailure();
      }
      callback.setToSuccess();
    }

    static Map<String, String> asMap(Properties properties) {
      //noinspection unchecked
      return (Map) properties;
    }
  }

  /**
   * Callback.
   */
  static class DispatchCallback {
    private Status status;
    private Statement statement;

    public DispatchCallback() {
      this.status = Status.UNSET;
    }

    /**
     * Sets the sql statement the callback should keep track of so that it can
     * be canceled.
     *
     * @param statement the statement to track
     */
    public void trackSqlQuery(Statement statement) {
      this.statement = statement;
      status = Status.RUNNING;
    }

    public void setToSuccess() {
      status = Status.SUCCESS;
    }

    public boolean isSuccess() {
      return Status.SUCCESS == status;
    }

    public void setToFailure() {
      status = Status.FAILURE;
    }

    public boolean isFailure() {
      return Status.FAILURE == status;
    }

    public boolean isRunning() {
      return Status.RUNNING == status;
    }

    public void setToCancel() {
      status = Status.CANCELED;
    }

    public boolean isCanceled() {
      return Status.CANCELED == status;
    }

    /**
     * If a statement has been set by {@link #trackSqlQuery(java.sql.Statement)}
     * then calls {@link java.sql.Statement#cancel()} on it.
     * As with {@link java.sql.Statement#cancel()}
     * the effect of calling this is dependent on the underlying DBMS and
     * driver.
     *
     * @throws SQLException on database error
     */
    public void forceKillSqlQuery() throws SQLException {
      // regardless of whether it's necessary to actually call .cancel() set
      // the flag to indicate a cancel was requested so we can message the
      // interactive shell if we want. If there is something to cancel, cancel
      // it.
      setStatus(Status.CANCELED);
      if (null != statement) {
        statement.cancel();
      }
    }

    public Status getStatus() {
      return status;
    }

    public void setStatus(Status status) {
      this.status = status;
    }

    /** Status of a command. */
    enum Status {
      UNSET, RUNNING, SUCCESS, FAILURE, CANCELED
    }
  }

  static class Reflector {
    private final Shell shell;

    Reflector(Shell shell) {
      this.shell = shell;
    }

    public Object invoke(Object on, String method, Object... args)
        throws InvocationTargetException, IllegalAccessException,
        ClassNotFoundException {
      return invoke(on, method, Arrays.asList(args));
    }

    public Object invoke(Object on, String method, List args)
        throws InvocationTargetException, IllegalAccessException,
        ClassNotFoundException {
      return invoke(on, on == null ? null : on.getClass(), method, args);
    }

    public Object invoke(Object on, Class defClass, String methodName, List args)
        throws InvocationTargetException, IllegalAccessException,
        ClassNotFoundException {
      Class c = defClass != null ? defClass : on.getClass();
      List<Method> candidateMethods = new LinkedList<>();

      for (Method method : c.getMethods()) {
        if (method.getName().equalsIgnoreCase(methodName)) {
          candidateMethods.add(method);
        }
      }

      if (candidateMethods.size() == 0) {
        throw new IllegalArgumentException(
            shell.loc("no-method", methodName, c.getName()));
      }

      for (Method method : candidateMethods) {
        Class[] ptypes = method.getParameterTypes();
        if (ptypes.length != args.size()) {
          continue;
        }

        Object[] converted = convert(args, ptypes);
        if (converted == null) {
          continue;
        }

        if (!Modifier.isPublic(method.getModifiers())) {
          continue;
        }

        return method.invoke(on, converted);
      }

      return null;
    }

    public static Object[] convert(List objects, Class[] toTypes)
        throws ClassNotFoundException {
      Object[] converted = new Object[objects.size()];
      for (int i = 0; i < converted.length; i++) {
        converted[i] = convert(objects.get(i), toTypes[i]);
      }
      return converted;
    }

    public static Object convert(Object ob, Class toType)
        throws ClassNotFoundException {
      if (ob == null || ob.toString().equals("null")) {
        return null;
      }
      if (toType == String.class) {
        return ob.toString();
      } else if (toType == Byte.class || toType == byte.class) {
        return Byte.valueOf(ob.toString());
      } else if (toType == Character.class || toType == char.class) {
        return ob.toString().charAt(0);
      } else if (toType == Short.class || toType == short.class) {
        return Short.valueOf(ob.toString());
      } else if (toType == Integer.class || toType == int.class) {
        return Integer.valueOf(ob.toString());
      } else if (toType == Long.class || toType == long.class) {
        return Long.valueOf(ob.toString());
      } else if (toType == Double.class || toType == double.class) {
        return Double.valueOf(ob.toString());
      } else if (toType == Float.class || toType == float.class) {
        return Float.valueOf(ob.toString());
      } else if (toType == Boolean.class || toType == boolean.class) {
        return ob.toString().equals("true")
            || ob.toString().equals(true + "")
            || ob.toString().equals("1")
            || ob.toString().equals("on")
            || ob.toString().equals("yes");
      } else if (toType == Class.class) {
        return Class.forName(ob.toString());
      }

      return null;
    }
  }

  /**
   * Suggests completions for a command.
   */
  class ShellCommandCompleter extends AggregateCompleter {
    ShellCommandCompleter(Shell shell) {
      super(new LinkedList<>());
      List<Completer> completers = new LinkedList<>();

      for (CommandHandler commandHandler : shell.getCommandHandlers()) {
        for (String cmd : commandHandler.getNames()) {
          List<Completer> compl = new LinkedList<>();
          final List<Completer> parameterCompleters =
              commandHandler.getParameterCompleters();
          if (parameterCompleters.size() == 1
              && parameterCompleters.iterator().next()
              instanceof Completers.RegexCompleter) {
            completers.add(parameterCompleters.iterator().next());
          } else {
            compl.add(new StringsCompleter(SqlLine.COMMAND_PREFIX + cmd));
            compl.addAll(parameterCompleters);
            compl.add(new NullCompleter()); // last param no complete
            completers.add(new ArgumentCompleter(compl));
          }
        }
      }

      getCompleters().addAll(completers);
    }
  }

  /**
   * A generic command to be executed. Execution of the command should be
   * dispatched to the
   * {@link #execute(String, DispatchCallback)} method after
   * determining that the command is appropriate with the
   * {@link #matches(String)} method.
   */
  public interface CommandHandler {
    /**
     * @return the name of the command
     */
    String getName();

    /**
     * @return all the possible names of this command.
     */
    List<String> getNames();

    /**
     * @return the short help description for this command.
     */
    String getHelpText();

    /**
     * Checks to see if the specified string can be dispatched to this
     * command.
     *
     * @param line The command line to check
     * @return the command string that matches, or null if it no match
     */
    String matches(String line);

    /**
     * Executes the specified command.
     *
     * @param line The full command line to execute
     * @param dispatchCallback the callback to check or interrupt the action
     */
    void execute(String line, DispatchCallback dispatchCallback);

    /**
     * Returns the completers that can handle parameters.
     *
     * @return Completers that can handle parameters
     */
    List<Completer> getParameterCompleters();

    /**
     * Returns whether the command should be written to the output file of the
     * {@code !script} command.
     *
     * <p>Returns {@code true} by default, but the {@code !script} command hides
     * itself by returning {@code false}.
     *
     * @return true if command should be written to file
     */
    boolean echoToFile();
  }

  /**
   * A buffer that can output segments using ANSI color.
   */
  static final class ColorBuffer implements Comparable {
    /** Style attribute. */
    enum ColorAttr {
      BOLD("\033[1m"),
      NORMAL("\033[m"),
      REVERS("\033[7m"),
      LINED("\033[4m"),
      GREY("\033[1;30m"),
      RED("\033[1;31m"),
      GREEN("\033[1;32m"),
      BLUE("\033[1;34m"),
      CYAN("\033[1;36m"),
      YELLOW("\033[1;33m"),
      MAGENTA("\033[1;35m"),
      INVISIBLE("\033[8m");

      private final String style;

      ColorAttr(String style) {
        this.style = style;
      }

      @Override public String toString() {
        return style;
      }
    }

    private final List<Object> parts = new LinkedList<>();

    private final boolean useColor;

    ColorBuffer(boolean useColor) {
      this.useColor = useColor;
      append("");
    }

    ColorBuffer(String str, boolean useColor) {
      this.useColor = useColor;
      append(str);
    }

    /**
     * Pad the specified String with spaces to the indicated length
     *
     * @param str The String to pad
     * @param len The length we want the return String to be
     * @return the passed in String with spaces appended until the
     *         length matches the specified length.
     */
    ColorBuffer pad(ColorBuffer str, int len) {
      int n = str.getVisibleLength();
      while (n < len) {
        str.append(" ");
        n++;
      }

      return append(str);
    }

    ColorBuffer center(String str, int len) {
      return append(centerString(str, len));
    }

    static String centerString(String str, int len) {
      final int n = len - str.length();
      if (n <= 0) {
        return str;
      }
      final StringBuilder buf = new StringBuilder();
      final int left = n / 2;
      final int right = n - left;
      for (int i = 0; i < left; i++) {
        buf.append(' ');
      }
      buf.append(str);
      for (int i = 0; i < right; i++) {
        buf.append(' ');
      }
      return buf.toString();
    }

    ColorBuffer pad(String str, int len) {
      if (str == null) {
        str = "";
      }

      return pad(new ColorBuffer(str, false), len);
    }

    public String getColor() {
      return getBuffer(useColor);
    }

    public String getMono() {
      return getBuffer(false);
    }

    String getBuffer(boolean color) {
      StringBuilder buf = new StringBuilder();
      for (Object part : parts) {
        if (!color && part instanceof ColorAttr) {
          continue;
        }
        buf.append(part.toString());
      }
      return buf.toString();
    }

    /**
     * Truncate the ColorBuffer to the specified length and return
     * the new ColorBuffer. Any open color tags will be closed.
     */
    public ColorBuffer truncate(int len) {
      ColorBuffer cbuff = new ColorBuffer(useColor);
      ColorAttr lastAttr = null;
      for (Iterator<Object> i = parts.iterator();
           cbuff.getVisibleLength() < len && i.hasNext();) {
        Object next = i.next();
        if (next instanceof ColorAttr) {
          lastAttr = (ColorAttr) next;
          cbuff.append((ColorAttr) next);
          continue;
        }

        String val = next.toString();
        if (cbuff.getVisibleLength() + val.length() > len) {
          int partLen = len - cbuff.getVisibleLength();
          val = val.substring(0, partLen);
        }

        cbuff.append(val);
      }

      // close off the buffer with a normal tag
      if (lastAttr != null && lastAttr != ColorAttr.NORMAL) {
        cbuff.append(ColorAttr.NORMAL);
      }

      return cbuff;
    }

    public String toString() {
      return getColor();
    }

    public ColorBuffer append(String str) {
      parts.add(str);
      return this;
    }

    public ColorBuffer append(ColorBuffer buf) {
      parts.addAll(buf.parts);
      return this;
    }

    public ColorBuffer append(ColorAttr attr) {
      parts.add(attr);
      return this;
    }

    public int getVisibleLength() {
      return getMono().length();
    }

    public ColorBuffer append(ColorAttr attr, String val) {
      parts.add(attr);
      parts.add(val);
      parts.add(ColorAttr.NORMAL);
      return this;
    }

    public ColorBuffer bold(String str) {
      return append(ColorAttr.BOLD, str);
    }

    public ColorBuffer lined(String str) {
      return append(ColorAttr.LINED, str);
    }

    public ColorBuffer grey(String str) {
      return append(ColorAttr.GREY, str);
    }

    public ColorBuffer red(String str) {
      return append(ColorAttr.RED, str);
    }

    public ColorBuffer blue(String str) {
      return append(ColorAttr.BLUE, str);
    }

    public ColorBuffer green(String str) {
      return append(ColorAttr.GREEN, str);
    }

    public ColorBuffer cyan(String str) {
      return append(ColorAttr.CYAN, str);
    }

    public ColorBuffer yellow(String str) {
      return append(ColorAttr.YELLOW, str);
    }

    public ColorBuffer magenta(String str) {
      return append(ColorAttr.MAGENTA, str);
    }

    public int compareTo(Object other) {
      return getMono().compareTo(((ColorBuffer) other).getMono());
    }
  }

  /**
   * Built-in properties of SqlLine.
   *
   * <p>Every property must implement the {@link SqlLineProperty} interface;
   * it is convenient to put properties in this {@code enum} but not mandatory.
   */
  public enum BuiltInProperty implements SqlLineProperty {

    AUTO_COMMIT("autoCommit", Type.BOOLEAN, true),
    AUTO_SAVE("autoSave", Type.BOOLEAN, false),
    COLOR_SCHEME("colorScheme", Type.STRING, DEFAULT, true, false,
        new Application().getName2HighlightStyle().keySet()),
    COLOR("color", Type.BOOLEAN, false),
    CSV_DELIMITER("csvDelimiter", Type.STRING, ","),

    CSV_QUOTE_CHARACTER("csvQuoteCharacter", Type.CHAR, '\''),

    DATE_FORMAT("dateFormat", Type.STRING, DEFAULT),
    ESCAPE_OUTPUT("escapeOutput", Type.BOOLEAN, false),

    FAST_CONNECT("fastConnect", Type.BOOLEAN, true),
    FORCE("force", Type.BOOLEAN, false),
    HEADER_INTERVAL("headerInterval", Type.INTEGER, 100),
    HISTORY_FILE("historyFile", Type.STRING,
        new File(SqlLineOpts.saveDir(), "history").getAbsolutePath()),
    INCREMENTAL("incremental", Type.BOOLEAN, false),
    ISOLATION("isolation", Type.STRING, "TRANSACTION_REPEATABLE_READ",
        true, false, new HashSet<>(new Application().getIsolationLevels())),
    MAX_COLUMN_WIDTH("maxColumnWidth", Type.INTEGER, -1),
    // don't save maxheight, maxwidth: it is automatically set based on
    // the terminal configuration
    MAX_HEIGHT("maxHeight", Type.INTEGER, 80, false, false, null),
    MAX_WIDTH("maxWidth", Type.INTEGER, 80, false, false, null),

    MAX_HISTORY_ROWS("maxHistoryRows",
        Type.INTEGER, DefaultHistory.DEFAULT_HISTORY_SIZE),
    MAX_HISTORY_FILE_ROWS("maxHistoryFileRows",
        Type.INTEGER, DefaultHistory.DEFAULT_HISTORY_FILE_SIZE),

    MODE("mode", Type.STRING, LineReader.EMACS, true,
        false, new HashSet<>(Arrays.asList(LineReader.EMACS, "vi"))),
    NUMBER_FORMAT("numberFormat", Type.STRING, DEFAULT),
    NULL_VALUE("nullValue", Type.STRING, DEFAULT),
    SILENT("silent", Type.BOOLEAN, false),
    OUTPUT_FORMAT("outputFormat", Type.STRING, "table"),
    PROMPT("prompt", Type.STRING, "sqlline> "),
    RIGHT_PROMPT("rightPrompt", Type.STRING, ""),
    ROW_LIMIT("rowLimit", Type.INTEGER, 0),
    SHOW_ELAPSED_TIME("showElapsedTime", Type.BOOLEAN, true),
    SHOW_HEADER("showHeader", Type.BOOLEAN, true),
    SHOW_NESTED_ERRS("showNestedErrs", Type.BOOLEAN, false),
    SHOW_WARNINGS("showWarnings", Type.BOOLEAN, true),
    STRICT_JDBC("strictJdbc", Type.BOOLEAN, false),
    TIME_FORMAT("timeFormat", Type.STRING, DEFAULT),
    TIMEOUT("timeout", Type.INTEGER, -1),
    TIMESTAMP_FORMAT("timestampFormat", Type.STRING, DEFAULT),
    TRIM_SCRIPTS("trimScripts", Type.BOOLEAN, true),
    USE_LINE_CONTINUATION("useLineContinuation", Type.BOOLEAN, true),
    VERBOSE("verbose", Type.BOOLEAN, false),
    VERSION("version", Type.STRING, new Application().getVersion(), false, true,
        null);

    private final String propertyName;
    private final Type type;
    private final Object defaultValue;
    private final boolean couldBeStored;
    private final boolean isReadOnly;
    private final Set<String> availableValues;

    BuiltInProperty(String propertyName, Type type, Object defaultValue) {
      this(propertyName, type, defaultValue, true, false, null);
    }

    BuiltInProperty(
        String propertyName,
        Type type,
        Object defaultValue,
        boolean couldBeStored,
        boolean isReadOnly,
        Set<String> availableValues) {
      this.propertyName = propertyName;
      this.type = type;
      this.defaultValue = defaultValue;
      this.isReadOnly = isReadOnly;
      this.couldBeStored = couldBeStored;
      this.availableValues = availableValues == null
          ? Collections.emptySet() : Collections.unmodifiableSet(availableValues);
    }

    @Override public String propertyName() {
      return propertyName;
    }

    @Override public Object defaultValue() {
      return defaultValue;
    }

    @Override public boolean isReadOnly() {
      return isReadOnly;
    }

    @Override public boolean couldBeStored() {
      return couldBeStored;
    }

    @Override public Type type() {
      return type;
    }

    @Override public Set<String> getAvailableValues() {
      return availableValues;
    }

    /** Returns the built-in property with the given name, or null if not found.
     *
     * @param propertyName Property name
     * @param ignoreCase Whether to ignore case
     *
     * @return Property, or null if not found
     */
    public static SqlLineProperty valueOf(String propertyName,
        boolean ignoreCase) {
      for (SqlLineProperty property : values()) {
        if (compare(propertyName, property.propertyName(), ignoreCase)) {
          return property;
        }
      }
      return null;
    }

    private static boolean compare(String s0, String s1, boolean ignoreCase) {
      return ignoreCase ? s1.equalsIgnoreCase(s0) : s1.equals(s0);
    }
  }

  /**
   * Definition of property that may be specified for SqlLine.
   *
   * @see BuiltInProperty
   */
  public interface SqlLineProperty {
    String DEFAULT = "default";
    String[] BOOLEAN_VALUES = {
        Boolean.TRUE.toString(), Boolean.FALSE.toString()};

    String propertyName();

    Object defaultValue();

    boolean isReadOnly();

    boolean couldBeStored();

    Type type();

    Set<String> getAvailableValues();

    /** Property writer. */
    @FunctionalInterface
    interface Writer {
      void write(String value);
    }

    /** Data type of property. */
    enum Type {
      BOOLEAN,
      CHAR,
      INTEGER,
      STRING;
    }

  }

}
// End Main.java
