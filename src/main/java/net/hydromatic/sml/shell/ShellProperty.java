package net.hydromatic.sml.shell;

import java.util.Set;

/**
 * Definition of property that may be specified for SqlLine.
 *
 * @see BuiltInProperty
 */
public interface ShellProperty {
  String DEFAULT = "default";
  String[] BOOLEAN_VALUES = {
      Boolean.TRUE.toString(), Boolean.FALSE.toString()
  };

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

// End ShellProperty.java
