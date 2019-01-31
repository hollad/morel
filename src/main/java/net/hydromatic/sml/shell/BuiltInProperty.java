package net.hydromatic.sml.shell;

import java.io.File;
import java.util.Collections;
import java.util.Set;

/**
 * Built-in properties of Smlj Shell.
 *
 * <p>Every property must implement the {@link ShellProperty} interface;
 * it is convenient to put properties in this {@code enum} but not mandatory.
 */
public enum BuiltInProperty implements ShellProperty {

  COLOR_SCHEME("colorScheme", Type.STRING, DEFAULT, true, false,
      new Application().getName2HighlightStyle().keySet()),
  COLOR("color", Type.BOOLEAN, false),
  HISTORY_FILE("historyFile", Type.STRING,
      new File(ShellOpts.saveDir(), "history").getAbsolutePath()),
  PROMPT("prompt", Type.STRING, "ml> "),
  RIGHT_PROMPT("rightPrompt", Type.STRING, ""),
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
  public static ShellProperty valueOf(String propertyName,
      boolean ignoreCase) {
    for (ShellProperty property : values()) {
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

// End BuiltInProperty.java
