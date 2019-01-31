package net.hydromatic.sml.shell;

import org.jline.utils.AttributedStyle;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static net.hydromatic.sml.shell.AttributedStyles.BLACK;
import static net.hydromatic.sml.shell.AttributedStyles.BLUE;
import static net.hydromatic.sml.shell.AttributedStyles.BOLD_BLACK;
import static net.hydromatic.sml.shell.AttributedStyles.BOLD_BLUE;
import static net.hydromatic.sml.shell.AttributedStyles.BOLD_GREEN;
import static net.hydromatic.sml.shell.AttributedStyles.BOLD_MAGENTA;
import static net.hydromatic.sml.shell.AttributedStyles.BOLD_RED;
import static net.hydromatic.sml.shell.AttributedStyles.BOLD_WHITE;
import static net.hydromatic.sml.shell.AttributedStyles.BOLD_YELLOW;
import static net.hydromatic.sml.shell.AttributedStyles.BRIGHT;
import static net.hydromatic.sml.shell.AttributedStyles.CYAN;
import static net.hydromatic.sml.shell.AttributedStyles.GREEN;
import static net.hydromatic.sml.shell.AttributedStyles.ITALIC_BRIGHT;
import static net.hydromatic.sml.shell.AttributedStyles.ITALIC_CYAN;
import static net.hydromatic.sml.shell.AttributedStyles.ITALIC_GREEN;
import static net.hydromatic.sml.shell.AttributedStyles.MAGENTA;
import static net.hydromatic.sml.shell.AttributedStyles.RED;
import static net.hydromatic.sml.shell.AttributedStyles.WHITE;
import static net.hydromatic.sml.shell.AttributedStyles.YELLOW;

/**
 * Pre-defined highlight styles.
 *
 * <p>The {@link #CHESTER}, {@link #DRACULA}, {@link #SOLARIZED} and
 * {@link #VS2010} styles are inspired by
 * <a href="https://github.com/Gillisdc/sqldeveloper-syntax-highlighting">
 * Gillis's Colour schemes for Oracle SQL Developer</a> (not the same but more
 * or less similar).
 *
 * <p>Similarly, the {@link #OBSIDIAN} style is inspired by
 * <a href="https://github.com/ozmoroz/ozbsidian-sqldeveloper">
 * ozmoroz's OzBsidian colour scheme for Oracle SQL Developer</a>.
 *
 * @see HighlightStyle
 */
enum BuiltInHighlightStyle {
  DARK(BOLD_BLUE, BOLD_WHITE, GREEN, CYAN, ITALIC_BRIGHT, YELLOW, WHITE),
  LIGHT(BOLD_RED, BOLD_BLACK, GREEN, CYAN, ITALIC_BRIGHT, YELLOW, BLACK),
  CHESTER(BOLD_BLUE, BOLD_WHITE, RED, CYAN, ITALIC_GREEN, YELLOW, WHITE),
  DRACULA(BOLD_MAGENTA, BOLD_WHITE, GREEN, RED, ITALIC_CYAN, YELLOW, WHITE),
  SOLARIZED(BOLD_YELLOW, BOLD_BLUE, GREEN, RED, ITALIC_BRIGHT, CYAN, BLUE),
  VS2010(BOLD_BLUE, BOLD_WHITE, RED, MAGENTA, ITALIC_GREEN, BRIGHT, WHITE),
  OBSIDIAN(BOLD_GREEN, BOLD_WHITE, RED, MAGENTA, ITALIC_BRIGHT, YELLOW, WHITE);

  final HighlightStyle style;

  BuiltInHighlightStyle(AttributedStyle keyWordsStyle,
      AttributedStyle commandsStyle,
      AttributedStyle quotedStyle,
      AttributedStyle sqlIdentifierStyle,
      AttributedStyle commentedStyle,
      AttributedStyle numberStyle,
      AttributedStyle defaultStyle) {
    this.style = new HighlightStyle(keyWordsStyle, commandsStyle, quotedStyle,
        sqlIdentifierStyle, commentedStyle, numberStyle, defaultStyle);
  }

  static final Map<String, HighlightStyle> BY_NAME;

  static {
    final Map<String, HighlightStyle> map = new HashMap<>();
    for (BuiltInHighlightStyle value : values()) {
      map.put(value.name().toLowerCase(Locale.ROOT), value.style);
    }
    BY_NAME = Collections.unmodifiableMap(map);
  }
}

// End BuiltInHighlightStyle.java
