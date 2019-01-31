package net.hydromatic.sml.shell;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;

/**
 * Customization for the prompt shown at the start of each line.
 */
class Prompt {
  private Prompt() {
  }

  static AttributedString getRightPrompt(Shell shell) {
    final String value = shell.getOpts().get(BuiltInProperty.RIGHT_PROMPT);
    final String currentPrompt = String.valueOf((Object) null).equals(value)
        ? (String) BuiltInProperty.RIGHT_PROMPT.defaultValue() : value;
    return getPrompt(shell, currentPrompt);
  }

  static AttributedString getPrompt(Shell shell) {
    final String defaultPrompt =
        String.valueOf(BuiltInProperty.PROMPT.defaultValue());
    final String currentPrompt = shell.getOpts().get(BuiltInProperty.PROMPT);
    return getPrompt(shell, currentPrompt);
  }

  private static AttributedString getPrompt(Shell shell, String prompt) {
    AttributedStringBuilder promptStringBuilder = new AttributedStringBuilder();
    promptStringBuilder.append(prompt);
    return promptStringBuilder.toAttributedString();
  }

}

// End Prompt.java
