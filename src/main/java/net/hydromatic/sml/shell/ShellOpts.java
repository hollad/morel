package net.hydromatic.sml.shell;

import org.jline.reader.Completer;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static net.hydromatic.sml.shell.BuiltInProperty.COLOR;
import static net.hydromatic.sml.shell.BuiltInProperty.COLOR_SCHEME;
import static net.hydromatic.sml.shell.BuiltInProperty.HISTORY_FILE;
import static net.hydromatic.sml.shell.ShellProperty.DEFAULT;

class ShellOpts implements Completer {
  private final Shell shell;
  private final Map<ShellProperty, Object> propertiesMap = new HashMap<>();
  private String runFile;

  public ShellOpts(Shell shell) {
    this.shell = shell;
  }

  public static File saveDir() {
    File f =
        new File(
            System.getProperty("user.home"),
            ((System.getProperty("os.name")
                .toLowerCase(Locale.ROOT).contains("windows"))
                ? "" : ".") + "sqlline")
            .getAbsoluteFile();
    try {
      f.mkdirs();
    } catch (Exception e) {
      // ignore
    }

    return f;
  }

  public String get(ShellProperty key) {
    return String.valueOf(propertiesMap.getOrDefault(key, key.defaultValue()));
  }

  public boolean getBoolean(ShellProperty key) {
    if (key.type() == ShellProperty.Type.BOOLEAN) {
      return (boolean) propertiesMap.getOrDefault(key, key.defaultValue());
    } else {
      throw new IllegalArgumentException(
          shell.loc("wrong-prop-type", key.propertyName(), key.type()));
    }
  }

  public void set(ShellProperty key, Object value) {
    Object valueToSet = value;
    String strValue;
    switch (key.type()) {
    case STRING:
      strValue = value instanceof String
          ? (String) value : String.valueOf(value);
      valueToSet = DEFAULT.equalsIgnoreCase(strValue)
          ? key.defaultValue() : value;
      break;
    case INTEGER:
      try {
        valueToSet = value instanceof Integer || value.getClass() == int.class
            ? value : Integer.parseInt(String.valueOf(value));
      } catch (Exception e) {
        shell.error(
            shell.loc("not-a-number",
                key.propertyName().toLowerCase(Locale.ROOT),
                value));
        if (getVerbose()) {
          shell.handleException(e);
        }
        return;
      }
      break;
    case BOOLEAN:
      if (value instanceof Boolean || value.getClass() == boolean.class) {
        valueToSet = value;
      } else {
        strValue = String.valueOf(value);
        valueToSet = "true".equalsIgnoreCase(strValue)
            || "1".equalsIgnoreCase(strValue)
            || "on".equalsIgnoreCase(strValue)
            || "yes".equalsIgnoreCase(strValue);
      }
      break;
    }
    propertiesMap.put(key, valueToSet);
  }

  public String getColorScheme() {
    return get(COLOR_SCHEME);
  }

  public boolean getColor() {
    return getBoolean(COLOR);
  }

  public boolean set(String key, String value, boolean quiet) {
    if ("run".equals(key)) {
      setRun(value);
      return true;
    }
    final ShellProperty property = BuiltInProperty.valueOf(key, true);
    if (property == null) {
      if (!quiet) {
        // need to use System.err here because when bad command args
        // are passed this is called before init is done, meaning
        // that sqlline's error() output chokes because it depends
        // on properties like text coloring that can get set in
        // arbitrary order.
        System.err.println(shell.loc("unknown-prop", key));
      }
      return false;
    }
    if (property.isReadOnly()) {
      if (!quiet) {
        shell.error(shell.loc("property-readonly", key));
      }
      return false;
    } else {
      ShellProperty.Writer propertyWriter = propertiesConfig.get(property);
      if (propertyWriter != null) {
        propertyWriter.write(value);
      } else {
        set(property, value);
      }
      return true;
    }
  }

  public void load() {
  }

  public void setRun(String runFile) {
    this.runFile = runFile;
  }

  public String getRun() {
    return this.runFile;
  }

  public Object getHistoryFile() {
    return get(HISTORY_FILE);
  }

  public boolean getSilent() {
    return false;
  }

  public boolean getVerbose() {
    return false;
  }
}
