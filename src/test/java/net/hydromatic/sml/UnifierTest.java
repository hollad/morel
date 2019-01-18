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
package net.hydromatic.sml;

import net.hydromatic.sml.util.Unifier;

import org.hamcrest.Matcher;
import org.junit.Test;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;

/** Test for {@link net.hydromatic.sml.util.Unifier}. */
public class UnifierTest {
  private final Unifier unifier = new Unifier();

  private Unifier.Sequence a(Unifier.Term... terms) {
    return unifier.apply("a", terms);
  }

  private Unifier.Sequence b(Unifier.Term... terms) {
    return unifier.apply("b", terms);
  }

  private Unifier.Sequence c(Unifier.Term... terms) {
    return unifier.apply("c", terms);
  }

  private Unifier.Sequence d(Unifier.Term... terms) {
    return unifier.apply("d", terms);
  }

  private Unifier.Sequence f(Unifier.Term... terms) {
    return unifier.apply("f", terms);
  }

  private Unifier.Sequence g(Unifier.Term... terms) {
    return unifier.apply("g", terms);
  }

  private Unifier.Sequence h(Unifier.Term... terms) {
    return unifier.apply("h", terms);
  }

  private Unifier.Sequence p(Unifier.Term... terms) {
    return unifier.apply("p", terms);
  }

  private Unifier.Sequence bill(Unifier.Term... terms) {
    return unifier.apply("bill", terms);
  }

  private Unifier.Sequence bob(Unifier.Term... terms) {
    return unifier.apply("bob", terms);
  }

  private Unifier.Sequence john(Unifier.Term... terms) {
    return unifier.apply("john", terms);
  }

  private Unifier.Sequence tom(Unifier.Term... terms) {
    return unifier.apply("tom", terms);
  }

  private Unifier.Sequence father(Unifier.Term... terms) {
    return unifier.apply("father", terms);
  }

  private Unifier.Sequence mother(Unifier.Term... terms) {
    return unifier.apply("mother", terms);
  }

  private Unifier.Sequence parents(Unifier.Term... terms) {
    return unifier.apply("parents", terms);
  }

  private Unifier.Sequence parent(Unifier.Term... terms) {
    return unifier.apply("parent", terms);
  }

  private Unifier.Sequence grandParent(Unifier.Term... terms) {
    return unifier.apply("grandParent", terms);
  }

  private Unifier.Sequence connected(Unifier.Term... terms) {
    return unifier.apply("connected", terms);
  }

  private Unifier.Sequence part(Unifier.Term... terms) {
    return unifier.apply("part", terms);
  }

  // Turn off checkstyle, because non-static fields are conventionally
  // lower-case.
  // CHECKSTYLE: IGNORE 4
  private final Unifier.Variable X = unifier.variable("X");
  private final Unifier.Variable Y = unifier.variable("Y");
  private final Unifier.Variable W = unifier.variable("W");
  private final Unifier.Variable Z = unifier.variable("Z");

  private void assertThatUnify(Unifier.Term e1, Unifier.Term e2,
      Matcher<String> matcher) {
    final Unifier.Substitution unify = unifier.unify(e1, e2);
    assertThat(unify, notNullValue());
    assertThat(unify.toString(), matcher);
  }

  private void assertThatCannotUnify(Unifier.Term e1, Unifier.Term e2) {
    assertThat(unifier.unify(e1, e2), nullValue());
  }

  @Test public void test1() {
    final Unifier.Term e1 = p(f(a()), g(b()), Y);
    final Unifier.Term e2 = p(Z, g(d()), c());
    assertThat(e1.toString(), is("p(f(a), g(b), Y)"));
    assertThat(unifier.substitution(f(a(), Y), Z).toString(),
        is("[f(a, Y)/Z]"));
    assertThatCannotUnify(e1, e2);
  }

  @Test public void test2() {
    final Unifier.Term e1 = p(f(a()), g(b()), Y);
    final Unifier.Term e2 = p(Z, g(W), c());
    assertThatUnify(e1, e2, is("[b/W, c/Y, f(a)/Z]"));
  }

  @Test public void test3() {
    // Note: Hesham Alassaf's test says that these cannot be unified; I think
    // because X is free, and so it assumes that Xs are distinct.
    final Unifier.Term e1 = p(f(f(b())), X);
    final Unifier.Term e2 = p(f(Y), X);
    assertThatUnify(e1, e2, is("[X/X, f(b)/Y]"));
  }

  @Test public void test4() {
    final Unifier.Term e1 = p(f(f(b())), c());
    final Unifier.Term e2 = p(f(Y), X);
    assertThatUnify(e1, e2, is("[c/X, f(b)/Y]"));
  }

  @Test public void test5() {
    final Unifier.Term e1 = p(a(), X);
    final Unifier.Term e2 = p(b(), Y);
    assertThatCannotUnify(e1, e2);
  }

  @Test public void test6() {
    final Unifier.Term e1 = p(X, a());
    final Unifier.Term e2 = p(b(), Y);
    assertThatUnify(e1, e2, is("[b/X, a/Y]"));
  }

  @Test public void test7() {
    final Unifier.Term e1 = f(a(), X);
    final Unifier.Term e2 = f(a(), b());
    assertThatUnify(e1, e2, is("[b/X]"));
  }

  @Test public void test8() {
    final Unifier.Term e1 = f(X);
    final Unifier.Term e2 = f(Y);
    assertThatUnify(e1, e2, is("[Y/X]"));
  }

  @Test public void test9() {
    final Unifier.Term e1 = f(g(X), X);
    final Unifier.Term e2 = f(Y);
    assertThatCannotUnify(e1, e2);
  }

  @Test public void test10() {
    final Unifier.Term e1 = f(g(X));
    final Unifier.Term e2 = f(Y);
    assertThatUnify(e1, e2, is("[g(X)/Y]"));
  }

  @Test public void test11() {
    final Unifier.Term e1 = f(g(X), X);
    final Unifier.Term e2 = f(Y, a());
    assertThatUnify(e1, e2, is("[a/X, g(X)/Y]"));
  }

  @Test public void test12() {
    final Unifier.Term e1 = father(X, Y);
    final Unifier.Term e2 = father(bob(), tom());
    assertThatUnify(e1, e2, is("[bob/X, tom/Y]"));
  }

  @Test public void test13() {
    final Unifier.Term e1 = parents(X, father(X), mother(bill()));
    final Unifier.Term e2 = parents(bill(), father(bill()), Y);
    assertThatUnify(e1, e2, is("[bill/X, mother(bill)/Y]"));
  }

  @Test public void test14() {
    final Unifier.Term e1 = grandParent(X, parent(parent(X)));
    final Unifier.Term e2 = grandParent(john(), parent(Y));
    assertThatUnify(e1, e2, is("[john/X, parent(john)/Y]"));
  }

  @Test public void test15() {
    final Unifier.Term e1 = p(f(a(), g(X)));
    final Unifier.Term e2 = p(Y, Y);
    assertThatCannotUnify(e1, e2);
  }

  @Test public void test16() {
    final Unifier.Term e1 = p(a(), X, h(g(Z)));
    final Unifier.Term e2 = p(Z, h(Y), h(Y));
    assertThatUnify(e1, e2, is("[h(Y)/X, g(a)/Y, a/Z]"));
  }

  @Test public void test17() {
    final Unifier.Term e1 = p(X, X);
    final Unifier.Term e2 = p(Y, f(Y));
    if (unifier.occurs()) {
      assertThatCannotUnify(e1, e2);
    } else {
      assertThatUnify(e1, e2, is("[Y/X, f(Y)/Y]"));
    }
  }

  @Test public void test18() {
    final Unifier.Term e1 = part(W, X);
    final Unifier.Term e2 = connected(f(W, X), W);
    assertThatCannotUnify(e1, e2);
  }

  @Test public void test19() {
    final Unifier.Term e1 = p(f(X), a(), Y);
    final Unifier.Term e2 = p(f(bill()), Z, g(b()));
    assertThatUnify(e1, e2, is("[bill/X, g(b)/Y, a/Z]"));
  }
}

// End UnifierTest.java
