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
package net.hydromatic.sml.compile;

import com.google.common.collect.ImmutableMap;

import net.hydromatic.sml.ast.Ast;
import net.hydromatic.sml.ast.AstBuilder;
import net.hydromatic.sml.ast.AstNode;
import net.hydromatic.sml.ast.Pos;
import net.hydromatic.sml.eval.Code;
import net.hydromatic.sml.eval.Codes;
import net.hydromatic.sml.eval.Environment;
import net.hydromatic.sml.eval.Environments;
import net.hydromatic.sml.eval.Unit;
import net.hydromatic.sml.type.PrimitiveType;
import net.hydromatic.sml.type.Type;
import net.hydromatic.sml.util.RobinsonUnifier;
import net.hydromatic.sml.util.Unifier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compiles an expression to code that can be evaluated. */
public class Compiler {
  final AstBuilder ast = AstBuilder.INSTANCE;
  final TypeSystem typeSystem = new TypeSystem();

  public CompiledStatement compileStatement(Environment env,
      AstNode statement) {
    final Ast.VarDecl decl;
    if (statement instanceof Ast.Exp) {
      decl = ast.varDecl(Pos.ZERO,
          ImmutableMap.of(ast.namedPat(Pos.ZERO, "it"), (Ast.Exp) statement));
    } else {
      decl = (Ast.VarDecl) statement;
    }
    final Map<String, TypeAndCode> varCodes = new LinkedHashMap<>();
    for (Map.Entry<Ast.Pat, Ast.Exp> e : decl.patExps.entrySet()) {
      final Ast.Pat pat = e.getKey();
      final String name = ((Ast.NamedPat) pat).name;
      final Ast.Exp exp = e.getValue();
      final Type type = deduceType(env, exp);
      final Code code = compile(env, exp);
      varCodes.put(name, new TypeAndCode(type, code));
    }
    final Type type = deduceType(env, decl);

    return new CompiledStatement() {
      public Type getType() {
        return type;
      }

      public Environment eval(Environment env, List<String> output) {
        Environment resultEnvironment = env;
        for (Map.Entry<String, TypeAndCode> entry : varCodes.entrySet()) {
          final String name = entry.getKey();
          final Code code = entry.getValue().code;
          final Type type = entry.getValue().type;
          final Object value = code.eval(env);
          resultEnvironment =
              Environments.add(resultEnvironment, name, type, value);
          output.add("val " + name + " = " + value + " : int");
        }
        return resultEnvironment;
      }
    };
  }

  private Type deduceType(Environment env, AstNode node) {
    final TypeResolver typeResolver = new TypeResolver(typeSystem);
    TypeEnv typeEnv = EmptyTypeEnv.INSTANCE
        .bind("true", typeResolver.atom(typeSystem.primitiveType("bool")))
        .bind("false", typeResolver.atom(typeSystem.primitiveType("bool")));
    final Unifier.Term typeTerm =
        typeResolver.deduceType(typeEnv, node);
    if (typeTerm instanceof Unifier.Atom) {
      return typeResolver.typeSystem.map.get(typeTerm.toString());
    }
    final List<Unifier.TermTerm> termPairs = new ArrayList<>();
    typeResolver.terms.forEach(tv ->
        termPairs.add(new Unifier.TermTerm(tv.term, tv.variable)));
    final Unifier.Substitution substitution =
        typeResolver.unifier.unify(termPairs);
    throw new AssertionError(); // TODO:
  }

  /** Resolves the type of an expression. */
  static class TypeResolver {
    final RobinsonUnifier unifier = new RobinsonUnifier();
    final List<TermVariable> terms = new ArrayList<>();
    private final TypeSystem typeSystem;

    TypeResolver(TypeSystem typeSystem) {
      this.typeSystem = typeSystem;
    }

    private Unifier.Term deduceType(TypeEnv env, AstNode node) {
      final Unifier.Variable v;
      final Ast.Exp exp;
      switch (node.op) {
      case INT_LITERAL:
        return atom(PrimitiveType.INT);
      case REAL_LITERAL:
        return atom(PrimitiveType.REAL);
      case STRING_LITERAL:
        return atom(PrimitiveType.STRING);
      case BOOL_LITERAL:
        return atom(PrimitiveType.BOOL);
      case ANDALSO:
      case ORELSE:
        return infix(env, (Ast.InfixCall) node, PrimitiveType.BOOL);
      case VAL_DECL:
        final Ast.VarDecl varDecl = (Ast.VarDecl) node;
        exp = varDecl.patExps.entrySet().iterator().next().getValue();
        return deduceType(env, exp);
      case ID:
        final Ast.Id id = (Ast.Id) node;
        return env.get(id.name);
      case IF:
        // TODO: check that condition has type boolean
        // TODO: check that ifTrue has same type as ifFalse
        final Ast.If if_ = (Ast.If) node;
        final Unifier.Term trueTerm = deduceType(env, if_.ifTrue);
        final Unifier.Term falseTerm = deduceType(env, if_.ifFalse);
        v = unifier.variable();
        equiv(trueTerm, v);
        equiv(falseTerm, v);
        return v;
      case FN:
        final Ast.Fn fn = (Ast.Fn) node;
        return deduceType(env, fn.match);
      case MATCH:
        final Ast.Match match = (Ast.Match) node;
        final String parameter = ((Ast.NamedPat) match.pat).name;
        final Unifier.Variable parameterType = unifier.variable();
        final TypeEnv env2 = env.bind(parameter, parameterType);
        final Unifier.Term expType = deduceType(env2, match.e);
        return unifier.apply("fn", parameterType, expType);
      case PLUS:
      case MINUS:
      case TIMES:
      case DIVIDE:
        return infix(env, (Ast.InfixCall) node, PrimitiveType.INT);
      default:
        throw new AssertionError("cannot deduce type for " + node.op);
      }
    }

    /** Registers an infix operator whose type is the same as its arguments. */
    private Unifier.Term infix(TypeEnv env, Ast.InfixCall call, Type type) {
      final Unifier.Atom atom = atom(type);
      Unifier.Variable v = unifier.variable();
      equiv(atom, v);
      call.forEachArg(arg -> equiv(deduceType(env, arg), v));
      return atom;
    }

    private void equiv(Unifier.Term term, Unifier.Variable atom) {
      terms.add(new TermVariable(term, atom));
    }

    private Unifier.Atom atom(Type type) {
      return unifier.atom(type.description());
    }
  }

  public Code compile(Environment env, Ast.Exp expression) {
    final Ast.Literal literal;
    final Ast.InfixCall call;
    final Code code0;
    final Code code1;
    switch (expression.op) {
    case INT_LITERAL:
      literal = (Ast.Literal) expression;
      return Codes.constant(((BigDecimal) literal.value).intValue());
    case REAL_LITERAL:
      literal = (Ast.Literal) expression;
      return Codes.constant(((BigDecimal) literal.value).floatValue());
    case BOOL_LITERAL:
      literal = (Ast.Literal) expression;
      final Boolean boolValue = (Boolean) literal.value;
      return Codes.constant(boolValue);
    case STRING_LITERAL:
      literal = (Ast.Literal) expression;
      final String stringValue = (String) literal.value;
      return Codes.constant(stringValue);
    case ID:
      final Ast.Id id = (Ast.Id) expression;
      return Codes.get(id.name);
    case IF:
      final Ast.If if_ = (Ast.If) expression;
      final Code conditionCode = compile(env, if_.condition);
      final Code trueCode = compile(env, if_.ifTrue);
      final Code falseCode = compile(env, if_.ifFalse);
      return Codes.ifThenElse(conditionCode, trueCode, falseCode);
    case LET:
      final Ast.LetExp let = (Ast.LetExp) expression;
      final List<Codes.NameTypeCode> varCodes = new ArrayList<>();
      for (Map.Entry<Ast.Pat, Ast.Exp> e : let.decl.patExps.entrySet()) {
        final Ast.Pat pat = e.getKey();
        final String name;
        switch (pat.op) {
        case NAMED_PAT:
          name = ((Ast.NamedPat) pat).name;
          break;
        default:
          throw new AssertionError("TODO:");
        }
        final Ast.Exp exp = e.getValue();
        final Type type = deduceType(env, exp);
        final Code initCode = compile(env, exp);
        varCodes.add(new Codes.NameTypeCode(name, type, initCode));
      }
      final Code resultCode = compile(env, let.e);
      return Codes.let(varCodes, resultCode);
    case FN:
      final Ast.Fn fn = (Ast.Fn) expression;
      final String paramName = ((Ast.NamedPat) fn.match.pat).name;
      final FnType fnType = (FnType) deduceType(env, fn);
      final Environment env2 = Environments.add(env, paramName,
          fnType.paramType, Unit.INSTANCE);
      code0 = compile(env2, fn.match.e);
      return Codes.fn(paramName, fnType.paramType, code0);
    case APPLY:
      final Ast.Apply apply = (Ast.Apply) expression;
      final Code fnCode = compile(env, apply.fn);
      final Code argCode = compile(env, apply.arg);
      return Codes.apply(fnCode, argCode);
    case ANDALSO:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
      return Codes.andAlso(code0, code1);
    case ORELSE:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
      return Codes.orElse(code0, code1);
    case PLUS:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
      return Codes.plus(code0, code1);
    case MINUS:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
      return Codes.minus(code0, code1);
    case TIMES:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
      return Codes.times(code0, code1);
    case DIVIDE:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
      return Codes.divide(code0, code1);
    case CARET:
      call = (Ast.InfixCall) expression;
      code0 = compile(env, call.a0);
      code1 = compile(env, call.a1);
      return Codes.power(code0, code1);
    default:
      throw new AssertionError("op not handled: " + expression.op);
    }
  }

  /** Statement that has been compiled and is ready to be run from the
   * REPL. If a declaration, it evaluates an expression and also
   * creates a new environment (with new variables bound) and
   * generates a line or two of output for the REPL. */
  public interface CompiledStatement {
    Environment eval(Environment environment, List<String> output);

    Type getType();
  }

  /** A (type, code) pair. */
  public static class TypeAndCode {
    public final Type type;
    public final Code code;

    private TypeAndCode(Type type, Code code) {
      this.type = type;
      this.code = code;
    }
  }

  /** A table that contains all types in use, indexed by their description (e.g.
   * "{@code int -> int}"). */
  private static class TypeSystem {
    final Map<String, Type> map = new HashMap<>();

    TypeSystem() {
      map.put("int", PrimitiveType.INT);
      map.put("bool", PrimitiveType.BOOL);
      map.put("string", PrimitiveType.STRING);
      map.put("real", PrimitiveType.REAL);
      map.put("unit", PrimitiveType.UNIT);
    }

    /** Creates a primitive type. */
    Type primitiveType(String name) {
      switch (name) {
      case "bool": return PrimitiveType.BOOL;
      case "CHAR": return PrimitiveType.CHAR;
      case "int": return PrimitiveType.INT;
      case "real": return PrimitiveType.REAL;
      case "string": return PrimitiveType.STRING;
      case "unit": return PrimitiveType.INT;
      default:
        throw new AssertionError("not a primitive type: " + name);
      }
    }

    /** Creates a function type. */
    Type fnType(Type paramType, Type resultType) {
      final String description =
          paramType.description() + " -> " + resultType.description();
      return map.computeIfAbsent(description,
          d -> new FnType(d, paramType, resultType));
    }
  }

  /** The type of a function value. */
  private static class FnType implements Type {
    public final String description;
    public final Type paramType;
    public final Type resultType;

    private FnType(String description, Type paramType, Type resultType) {
      this.description = description;
      this.paramType = paramType;
      this.resultType = resultType;
    }

    public String description() {
      return description;
    }
  }

  /** Pair consisting of a term and a variable. */
  private static class TermVariable {
    final Unifier.Term term;
    final Unifier.Variable variable;

    private TermVariable(Unifier.Term term, Unifier.Variable variable) {
      this.term = term;
      this.variable = variable;
    }

    @Override public String toString() {
      return term + " = " + variable;
    }
  }

  /** Type environment. */
  private interface TypeEnv {
    Unifier.Term get(String name);
    TypeEnv bind(String name, Unifier.Term typeTerm);
  }

  /** Empty type environment. */
  enum EmptyTypeEnv implements TypeEnv {
    INSTANCE;

    @Override public Unifier.Term get(String name) {
      throw new AssertionError("not found: " + name);
    }

    @Override public TypeEnv bind(String name, Unifier.Term typeTerm) {
      return new BindTypeEnv(name, typeTerm, this);
    }
  }

  /** A type environment that consists of a type environment plus one
   * binding. */
  private static class BindTypeEnv implements TypeEnv {
    private final String definedName;
    private final Unifier.Term typeTerm;
    private final TypeEnv parent;

    BindTypeEnv(String definedName, Unifier.Term typeTerm, TypeEnv parent) {
      this.definedName = Objects.requireNonNull(definedName);
      this.typeTerm = Objects.requireNonNull(typeTerm);
      this.parent = Objects.requireNonNull(parent);
    }

    @Override public Unifier.Term get(String name) {
      if (name.equals(definedName)) {
        return typeTerm;
      } else {
        return parent.get(name);
      }
    }

    @Override public TypeEnv bind(String name, Unifier.Term typeTerm) {
      return new BindTypeEnv(name, typeTerm, this);
    }
  }
}

// End Compiler.java
