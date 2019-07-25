#!/bin/gawk
#
# Post-processes a cast generated by asciinema;
# can remove sections between start/end times,
# and can even up timings of keystrokes.
#
function foo(offset, delta, lower) {
  if (debug) printf "offset=[%f] delta=[%f] lower=[%f]\n", offset, delta, lower
  if (delta > lower) {
    return offset + delta - lower # min(delta - lower, lower)
  }
  return offset
}

function cut(t, t0, t1) {
  if (t >= t1 && tPrev < t1) {
    cutDelta += (t1 - t0)
  }
}

function changeTime(t, arg) {
  t = t + 0
  delta = t - tPrev

  cutDeltaPrev = cutDelta
  if (FILENAME ~ "/smlj-0.1.0.cast") {
    cut(t, 80.6, 87.2)
  }

  offset += (cutDelta - cutDeltaPrev)
  offset += nextOffset
  nextOffset = 0

  # quantize the gaps between keystrokes
  if (debug) printf "delta=[%f] offset=[%f] cutDelta=[%f]\n", delta, offset, cutDelta
  if (cutDelta == cutDeltaPrev && 0) {
    if (arg == spaceArg) {
      if (prevArg == dotArg) {
        offset = foo(offset, delta, 0.4)
      } else {
        offset = foo(offset, delta, 0.01)
      }
    } else if (arg == dotArg) {
      offset = foo(offset, delta, 0.1)
    } else if (arg == newlineArg) {
      offset = foo(offset, delta, 0.4)
    } else if (arg == eqArg) {
      nextOffset = 0.6
    } else if (delta >= 0.03 && (delta < 2 || length(arg) == 3)) {
      offset = foo(offset, delta, 0.04)
    }
  }
  prevArg = arg
  tPrev = t
}

BEGIN {
    spaceArg = sprintf("%c %c", 34, 34)
    newlineArg = sprintf("%c\\r\\n%c", 34, 34)
    eqArg = sprintf("%c\\u001b[?1h\\u001b=\\u001b[?2004h\\u001b[1m=\\u001b[0m %c", 34, 34)
    offset = 0
    debug = 0
}
{
  s = $0;
  if (match(s, /^\[([0-9.]+), "([^"]*)", (".*")\]$/, matches)) {
    t = matches[1]
    verb = matches[2]
    arg = matches[3]
    changeTime(t, arg)
    if (FILENAME ~ "/smlj-0.1.0.cast") {
      if (index(arg, "SLF4J:") > 0) {
        next
      }
    }
    printf "[%.2f, \"%s\", %s]\n", t - offset, verb, arg;
  } else {
    print
  }
}

# End post.awk
