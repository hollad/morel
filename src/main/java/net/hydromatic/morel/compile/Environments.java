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
package net.hydromatic.morel.compile;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Helpers for {@link Environment}. */
public abstract class Environments {

  /** An environment with only "true" and "false". */
  private static final Environment BASIC_ENVIRONMENT =
      EmptyEnvironment.INSTANCE
          .bind("true", PrimitiveType.BOOL, true)
          .bind("false", PrimitiveType.BOOL, false);

  private Environments() {}

  /** Creates an empty environment. */
  public static Environment empty() {
    return BASIC_ENVIRONMENT;
  }

  /** Creates an environment containing built-ins and the given foreign
   * values. */
  public static Environment env(TypeSystem typeSystem,
      Map<String, ForeignValue> valueMap) {
    return env(EmptyEnvironment.INSTANCE, typeSystem, valueMap);
  }

  /** Creates a compilation environment, including built-ins and foreign
   * values. */
  private static Environment env(Environment environment, TypeSystem typeSystem,
      Map<String, ForeignValue> valueMap) {
    final List<Binding> bindings = new ArrayList<>();
    for (Map.Entry<BuiltIn, Object> entry : Codes.BUILT_IN_VALUES.entrySet()) {
      BuiltIn key = entry.getKey();
      final Type type = key.typeFunction.apply(typeSystem);
      bindings.add(Binding.of(key.fullName, type, entry.getValue()));
      if (key.alias != null) {
        bindings.add(Binding.of(key.alias, type, entry.getValue()));
      }
    }
    bindings(typeSystem, valueMap, bindings);
    return bind(environment, bindings);
  }

  private static void bindings(TypeSystem typeSystem,
      Map<String, ForeignValue> map, List<Binding> bindings) {
    map.forEach((name, value) ->
        bindings.add(Binding.of(name, value.type(typeSystem), value.value())));
  }

  /** Creates an environment that is a given environment plus bindings. */
  static Environment bind(Environment env, Iterable<Binding> bindings) {
    if (Iterables.size(bindings) < 5) {
      for (Binding binding : bindings) {
        env = env.bind(binding.name, binding.type, binding.value);
      }
      return env;
    } else {
      final ImmutableMap.Builder<String, Binding> b = ImmutableMap.builder();
      bindings.forEach(binding -> b.put(binding.name, binding));
      return new MapEnvironment(env, b.build());
    }
  }

  /** Environment that inherits from a parent environment and adds one
   * binding. */
  static class SubEnvironment extends Environment {
    private final Environment parent;
    private final Binding binding;

    SubEnvironment(Environment parent, Binding binding) {
      this.parent = Objects.requireNonNull(parent);
      this.binding = Objects.requireNonNull(binding);
    }

    public Binding getOpt(String name) {
      if (name.equals(binding.name)) {
        return binding;
      }
      return parent.getOpt(name);
    }

    void visit(Consumer<Binding> consumer) {
      consumer.accept(binding);
      parent.visit(consumer);
    }
  }

  /** Empty environment. */
  private static class EmptyEnvironment extends Environment {
    static final EmptyEnvironment INSTANCE = new EmptyEnvironment();

    void visit(Consumer<Binding> consumer) {
    }

    public Binding getOpt(String name) {
      return null;
    }
  }

  /** Environment that keeps bindings in a map. */
  private static class MapEnvironment extends Environment {
    private final Environment parent;
    private final Map<String, Binding> map;

    MapEnvironment(Environment parent, ImmutableMap<String, Binding> map) {
      this.parent = Objects.requireNonNull(parent);
      this.map = Objects.requireNonNull(map);
    }

    void visit(Consumer<Binding> consumer) {
      map.values().forEach(consumer);
      parent.visit(consumer);
    }

    public Binding getOpt(String name) {
      final Binding binding = map.get(name);
      return binding != null ? binding : parent.getOpt(name);
    }
  }
}

// End Environments.java
