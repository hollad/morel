package net.hydromatic.sml.shell;

import java.util.Map;

public class Application {
  public static final String DEFAULT_APP_INFO_MESSAGE = "smlj";

  public Map<String, HighlightStyle> getName2HighlightStyle() {
    return BuiltInHighlightStyle.BY_NAME;
  }

  public String getVersion() {
    return "0.1";
  }

  public ShellOpts getOpts(Shell shell) {
    return new ShellOpts(shell);
  }

  public String getInfoMessage() {
    return "smlj";
  }
}
