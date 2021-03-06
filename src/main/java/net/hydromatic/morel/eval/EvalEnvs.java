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
package net.hydromatic.morel.eval;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.util.Pair;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Helpers for {@link EvalEnv}. */
public class EvalEnvs {
  /** Creates an evaluation environment with the given (name, value) map. */
  public static EvalEnv copyOf(Map<String, Object> valueMap) {
    return new MapEvalEnv(valueMap);
  }

  private EvalEnvs() {}

  /** Evaluation environment that inherits from a parent environment and adds
   * one binding. */
  static class SubEvalEnv implements EvalEnv {
    private final EvalEnv parentEnv;
    private final String name;
    protected Object value;

    SubEvalEnv(EvalEnv parentEnv, String name, Object value) {
      this.parentEnv = parentEnv;
      this.name = name;
      this.value = value;
    }

    @Override public String toString() {
      return valueMap().toString();
    }

    public void visit(BiConsumer<String, Object> consumer) {
      consumer.accept(name, value);
      parentEnv.visit(consumer);
    }

    public Object getOpt(String name) {
      for (SubEvalEnv e = this;;) {
        if (name.equals(e.name)) {
          return e.value;
        }
        if (e.parentEnv instanceof SubEvalEnv) {
          e = (SubEvalEnv) e.parentEnv;
        } else {
          return e.parentEnv.getOpt(name);
        }
      }
    }
  }

  /** Similar to {@link SubEvalEnv} but mutable. */
  static class MutableSubEvalEnv extends SubEvalEnv implements MutableEvalEnv {
    MutableSubEvalEnv(EvalEnv parentEnv, String name) {
      super(parentEnv, name, null);
    }

    public void set(Object value) {
      this.value = value;
    }
  }

  /** Similar to {@link MutableEvalEnv} but binds several names. */
  static class MutableArraySubEvalEnv implements MutableEvalEnv {
    private final EvalEnv parentEnv;
    private final ImmutableList<String> names;
    protected Object[] values;

    MutableArraySubEvalEnv(EvalEnv parentEnv, List<String> names) {
      this.parentEnv = parentEnv;
      this.names = ImmutableList.copyOf(names);
    }

    @Override public String toString() {
      return valueMap().toString();
    }

    public void set(Object value) {
      values = (Object[]) value;
      assert values.length == names.size();
    }

    public void visit(BiConsumer<String, Object> consumer) {
      for (int i = 0; i < names.size(); i++) {
        consumer.accept(names.get(i), values[i]);
      }
      parentEnv.visit(consumer);
    }

    public Object getOpt(String name) {
      final int i = names.indexOf(name);
      if (i >= 0) {
        return values[i];
      }
      return parentEnv.getOpt(name);
    }
  }

  /** Evaluation environment that binds several slots based on a pattern. */
  static class MutablePatSubEvalEnv extends MutableArraySubEvalEnv {
    private final Ast.Pat pat;
    private int slot;

    MutablePatSubEvalEnv(EvalEnv parentEnv, Ast.Pat pat, List<String> names) {
      super(parentEnv, names);
      this.pat = pat;
      this.values = new Object[names.size()];
      assert !(pat instanceof Ast.IdPat);
    }

    @Override public void set(Object value) {
      if (!setOpt(value)) {
        // If this error happens, perhaps your code should be calling "setOpt"
        // and handling a false result appropriately.
        throw new AssertionError("bind failed");
      }
    }

    @Override public boolean setOpt(Object value) {
      slot = 0;
      return bindRecurse(pat, value);
    }

    boolean bindRecurse(Ast.Pat pat, Object argValue) {
      final List<Object> listValue;
      final Ast.LiteralPat literalPat;
      switch (pat.op) {
      case ID_PAT:
        this.values[slot++] = argValue;
        return true;

      case WILDCARD_PAT:
        return true;

      case BOOL_LITERAL_PAT:
      case CHAR_LITERAL_PAT:
      case STRING_LITERAL_PAT:
        literalPat = (Ast.LiteralPat) pat;
        return literalPat.value.equals(argValue);

      case INT_LITERAL_PAT:
        literalPat = (Ast.LiteralPat) pat;
        return ((BigDecimal) literalPat.value).intValue() == (Integer) argValue;

      case REAL_LITERAL_PAT:
        literalPat = (Ast.LiteralPat) pat;
        return ((BigDecimal) literalPat.value).doubleValue() == (Double) argValue;

      case TUPLE_PAT:
        final Ast.TuplePat tuplePat = (Ast.TuplePat) pat;
        listValue = (List) argValue;
        for (Pair<Ast.Pat, Object> pair : Pair.zip(tuplePat.args, listValue)) {
          if (!bindRecurse(pair.left, pair.right)) {
            return false;
          }
        }
        return true;

      case RECORD_PAT:
        final Ast.RecordPat recordPat = (Ast.RecordPat) pat;
        listValue = (List) argValue;
        for (Pair<Ast.Pat, Object> pair
            : Pair.zip(recordPat.args.values(), listValue)) {
          if (!bindRecurse(pair.left, pair.right)) {
            return false;
          }
        }
        return true;

      case LIST_PAT:
        final Ast.ListPat listPat = (Ast.ListPat) pat;
        listValue = (List) argValue;
        if (listValue.size() != listPat.args.size()) {
          return false;
        }
        for (Pair<Ast.Pat, Object> pair : Pair.zip(listPat.args, listValue)) {
          if (!bindRecurse(pair.left, pair.right)) {
            return false;
          }
        }
        return true;

      case CONS_PAT:
        final Ast.InfixPat infixPat = (Ast.InfixPat) pat;
        @SuppressWarnings("unchecked") final List<Object> consValue =
            (List) argValue;
        if (consValue.isEmpty()) {
          return false;
        }
        final Object head = consValue.get(0);
        final List<Object> tail = consValue.subList(1, consValue.size());
        return bindRecurse(infixPat.p0, head)
            && bindRecurse(infixPat.p1, tail);

      case CON0_PAT:
        final Ast.Con0Pat con0Pat = (Ast.Con0Pat) pat;
        final List con0Value = (List) argValue;
        return con0Value.get(0).equals(con0Pat.tyCon.name);

      case CON_PAT:
        final Ast.ConPat conPat = (Ast.ConPat) pat;
        final List conValue = (List) argValue;
        return conValue.get(0).equals(conPat.tyCon.name)
            && bindRecurse(conPat.pat, conValue.get(1));

      default:
        throw new AssertionError("cannot compile " + pat.op + ": " + pat);
      }
    }
  }

  /** Evaluation environment that reads from a map. */
  static class MapEvalEnv implements EvalEnv {
    final Map<String, Object> valueMap;

    MapEvalEnv(Map<String, Object> valueMap) {
      this.valueMap = ImmutableMap.copyOf(valueMap);
    }

    @Override public String toString() {
      return valueMap().toString();
    }

    public Object getOpt(String name) {
      return valueMap.get(name);
    }

    public void visit(BiConsumer<String, Object> consumer) {
      valueMap.forEach(consumer);
    }
  }
}

// End EvalEnvs.java
