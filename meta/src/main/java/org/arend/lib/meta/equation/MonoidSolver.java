package org.arend.lib.meta.equation;

import org.arend.ext.concrete.ConcreteFactory;
import org.arend.ext.concrete.expr.ConcreteAppExpression;
import org.arend.ext.concrete.expr.ConcreteArgument;
import org.arend.ext.concrete.expr.ConcreteExpression;
import org.arend.ext.concrete.expr.ConcreteReferenceExpression;
import org.arend.ext.core.context.CoreBinding;
import org.arend.ext.core.definition.CoreClassDefinition;
import org.arend.ext.core.definition.CoreClassField;
import org.arend.ext.core.definition.CoreConstructor;
import org.arend.ext.core.expr.*;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.ext.error.ErrorReporter;
import org.arend.ext.reference.ArendRef;
import org.arend.ext.typechecking.ExpressionTypechecker;
import org.arend.ext.typechecking.TypedExpression;
import org.arend.lib.context.ContextHelper;
import org.arend.lib.error.AlgebraSolverError;
import org.arend.lib.meta.equation.binop_matcher.FunctionMatcher;
import org.arend.lib.util.*;
import org.arend.lib.util.algorithms.ComMonoidWP;
import org.arend.lib.util.algorithms.groebner.Buchberger;
import org.arend.lib.util.algorithms.idealmem.GroebnerIM;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;

public class MonoidSolver extends BaseEqualitySolver {
  private final FunctionMatcher mulMatcher;
  private final FunctionMatcher ideMatcher;
  private CompiledTerm lastCompiled;
  private TypedExpression lastTerm;
  private Map<CoreBinding, List<RuleExt>> contextRules;
  private boolean isCommutative;
  private boolean isSemilattice;
  private final CoreClassField ide;

  public MonoidSolver(EquationMeta meta, ExpressionTypechecker typechecker, ConcreteFactory factory, ConcreteReferenceExpression refExpr, CoreFunCallExpression equality, TypedExpression instance, CoreClassCallExpression classCall, CoreClassDefinition forcedClass, boolean useHypotheses) {
    super(meta, typechecker, factory, refExpr, instance, useHypotheses);
    this.equality = equality;

    isSemilattice = classCall.getDefinition().isSubClassOf(meta.MSemilattice) && (forcedClass == null || forcedClass.isSubClassOf(meta.MSemilattice));
    boolean isMultiplicative = !isSemilattice && classCall.getDefinition().isSubClassOf(meta.Monoid) && (forcedClass == null || forcedClass.isSubClassOf(meta.Monoid));
    isCommutative = isSemilattice || isMultiplicative && classCall.getDefinition().isSubClassOf(meta.CMonoid) && (forcedClass == null || forcedClass.isSubClassOf(meta.CMonoid)) || !isMultiplicative && classCall.getDefinition().isSubClassOf(meta.AbMonoid) && (forcedClass == null || forcedClass.isSubClassOf(meta.AbMonoid));
    ide = isSemilattice ? meta.top : isMultiplicative ? meta.ide : meta.zro;
    CoreClassField mul = isSemilattice ? meta.meet : isMultiplicative ? meta.mul : meta.plus;
    mulMatcher = FunctionMatcher.makeFieldMatcher(classCall, instance, mul, typechecker, factory, refExpr, meta.ext, 2);
    ideMatcher = FunctionMatcher.makeFieldMatcher(classCall, instance, ide, typechecker, factory, refExpr, meta.ext, 0);
  }

  public MonoidSolver(EquationMeta meta, ExpressionTypechecker typechecker, ConcreteFactory factory, ConcreteReferenceExpression refExpr, CoreFunCallExpression equality, TypedExpression instance, CoreClassCallExpression classCall, CoreClassDefinition forcedClass) {
    this(meta, typechecker, factory, refExpr, equality, instance, classCall, forcedClass, true);
  }

  public static List<Integer> removeDuplicates(List<Integer> list) {
    List<Integer> result = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      if (i == list.size() - 1 || !list.get(i).equals(list.get(i + 1))) {
        result.add(list.get(i));
      }
    }
    return result;
  }

  @Override
  public ConcreteExpression solve(@Nullable ConcreteExpression hint, @NotNull TypedExpression leftExpr, @NotNull TypedExpression rightExpr, @NotNull ErrorReporter errorReporter) {
    CompiledTerm term1 = lastTerm == leftExpr ? lastCompiled : compileTerm(leftExpr.getExpression());
    CompiledTerm term2 = compileTerm(rightExpr.getExpression());
    lastTerm = rightExpr;
    lastCompiled = term2;

    boolean commutative = false;
    if (isCommutative && !term1.nf.equals(term2.nf)) {
      commutative = true;
      term1.nf = CountingSort.sort(term1.nf);
      term2.nf = CountingSort.sort(term2.nf);
    }
    isCommutative = commutative;

    boolean semilattice = false;
    if (isSemilattice && commutative && !term1.nf.equals(term2.nf)) {
      semilattice = true;
      term1.nf = removeDuplicates(term1.nf);
      term2.nf = removeDuplicates(term2.nf);
    }
    isSemilattice = semilattice;

    ConcreteExpression lastArgument;
    if (!term1.nf.equals(term2.nf)) {
      if (!useHypotheses) return null;

      List<RuleExt> rules = new ArrayList<>();
      if (contextRules == null) {
        contextRules = new HashMap<>();
      }
      ContextHelper helper = new ContextHelper(hint);
      for (CoreBinding binding : helper.getContextBindings(typechecker)) {
        rules.addAll(contextRules.computeIfAbsent(binding, k -> {
          List<RuleExt> ctxList = new ArrayList<>();
          typeToRule(null, binding, false, ctxList);
          return ctxList;
        }));
      }
      for (CoreBinding binding : helper.getAdditionalBindings(typechecker)) {
        typeToRule(null, binding, true, rules);
      }

      if (isCommutative) {
        ComMonoidWPSolver solver = new ComMonoidWPSolver();
        var equalities = new ArrayList<Equality>();
        for (RuleExt rule : rules) {
          if (rule.direction == Direction.FORWARD || rule.direction == Direction.UNKNOWN) {
            equalities.add(new Equality(rule.binding != null ? factory.ref(rule.binding) : factory.core(rule.expression), rule.lhsTerm, rule.rhsTerm, rule.lhs, rule.rhs));
          } else {
            equalities.add(new Equality(rule.binding != null ? factory.ref(rule.binding) : factory.core(rule.expression), rule.rhsTerm, rule.lhsTerm, rule.rhs, rule.lhs));
          }
        }
        return solver.solve(term1, term2, equalities);
      }

      List<Step> trace1 = new ArrayList<>();
      List<Step> trace2 = new ArrayList<>();
      List<Integer> newNf1 = applyRules(term1.nf, rules, trace1);
      List<Integer> newNf2 = applyRules(term2.nf, rules, trace2);
      if (!newNf1.equals(newNf2)) {
        errorReporter.report(new AlgebraSolverError(term1.nf, term2.nf, values.getValues(), rules, trace1, trace2, hint != null ? hint : refExpr));
        return null;
      }

      while (!trace1.isEmpty() && !trace2.isEmpty()) {
        if (trace1.get(trace1.size() - 1).equals(trace2.get(trace2.size() - 1))) {
          trace1.remove(trace1.size() - 1);
          trace2.remove(trace2.size() - 1);
        } else {
          break;
        }
      }

      for (Step step : trace1) {
        step.rule.count++;
      }
      for (Step step : trace2) {
        step.rule.count++;
      }
      for (RuleExt rule : rules) {
        if (rule.count > 0) {
          if (rule.rnfTerm == null) {
            rule.rnfTerm = computeNFTerm(rule.rhs);
          }
          if (rule.cExpr != null) {
            continue;
          }

          ConcreteExpression cExpr = rule.binding != null ? factory.ref(rule.binding) : factory.core(null, rule.expression);
          if (rule.direction == Direction.BACKWARD) {
            cExpr = factory.app(factory.ref(meta.ext.inv.getRef()), true, singletonList(cExpr));
          }
          if (!isNF(rule.lhsTerm) || !isNF(rule.rhsTerm)) {
            cExpr = factory.appBuilder(factory.ref(meta.termsEqConv.getRef()))
              .app(factory.ref(dataRef), false)
              .app(rule.lhsTerm)
              .app(rule.rhsTerm)
              .app(cExpr)
              .build();
          }
          if (rule.count > 1 && !(cExpr instanceof ConcreteReferenceExpression) || rule.binding == null) {
            ArendRef letClause = factory.local("rule" + letClauses.size());
            letClauses.add(factory.letClause(letClause, Collections.emptyList(), null, cExpr));
            rule.cExpr = factory.ref(letClause);
          } else {
            rule.cExpr = cExpr;
          }
        }
      }

      ConcreteExpression expr1 = trace1.isEmpty() ? null : traceToExpr(term1.nf, trace1, dataRef, factory);
      ConcreteExpression expr2 = trace2.isEmpty() ? null : factory.app(factory.ref(meta.ext.inv.getRef()), true, singletonList(traceToExpr(term2.nf, trace2, dataRef, factory)));
      if (expr1 == null && expr2 == null) {
        lastArgument = factory.ref(meta.ext.prelude.getIdp().getRef());
      } else if (expr2 == null) {
        lastArgument = expr1;
      } else if (expr1 == null) {
        lastArgument = expr2;
      } else {
        lastArgument = factory.appBuilder(factory.ref(meta.ext.concat.getRef())).app(expr1).app(expr2).build();
      }
    } else {
      lastArgument = factory.ref(meta.ext.prelude.getIdp().getRef());
    }

    return factory.appBuilder(factory.ref((semilattice ? meta.semilatticeTermsEq : commutative ? meta.commTermsEq : meta.termsEq).getRef()))
      .app(factory.ref(dataRef), false)
      .app(term1.concrete)
      .app(term2.concrete)
      .app(lastArgument)
      .build();
  }

  private static class Equality {
    public final ConcreteExpression binding;
    public List<Integer> lhsNF;
    public List<Integer> rhsNF;
    public ConcreteExpression lhsTerm;
    public ConcreteExpression rhsTerm;

    private Equality(ConcreteExpression binding, ConcreteExpression lhsTerm, ConcreteExpression rhsTerm, List<Integer> lhsNF, List<Integer> rhsNF) {
      this.binding = binding;
      this.lhsTerm = lhsTerm;
      this.rhsTerm = rhsTerm;
      this.lhsNF = lhsNF;
      this.rhsNF = rhsNF;
    }
  }

  private class ComMonoidWPSolver {

    public ConcreteExpression solve(CompiledTerm term1, CompiledTerm term2, List<Equality> axioms) {
      int alphabetSize = Collections.max(term1.nf) + 1;
      alphabetSize = Integer.max(alphabetSize, Collections.max(term2.nf) + 1);
      for (Equality axiom : axioms) {
        if (!axiom.lhsNF.isEmpty()) {
          alphabetSize = Integer.max(alphabetSize, Collections.max(axiom.lhsNF) + 1);
        }
        if (!axiom.rhsNF.isEmpty()) {
          alphabetSize = Integer.max(alphabetSize, Collections.max(axiom.rhsNF) + 1);
        }
      }

      var word1Pow = ComMonoidWP.elemsSeqToPowersSeq(term1.nf, alphabetSize);
      var word2Pow = ComMonoidWP.elemsSeqToPowersSeq(term2.nf, alphabetSize);
      List<Pair<List<Integer>, List<Integer>>> axiomsPow = new ArrayList<>();

      for (Equality axiom : axioms) {
        axiomsPow.add(new Pair<>(ComMonoidWP.elemsSeqToPowersSeq(axiom.lhsNF, alphabetSize), ComMonoidWP.elemsSeqToPowersSeq(axiom.rhsNF, alphabetSize)));
      }

      var wpAlgorithm = new ComMonoidWP(new GroebnerIM(new Buchberger()));
      var axiomsToApply = wpAlgorithm.solve(word1Pow, word2Pow, axiomsPow);

      List<Integer> curWord = new ArrayList<>(term1.nf);

      if (axiomsToApply == null) return null;

      ConcreteExpression proofTerm = null;

      for (Pair<Integer, Boolean> axiom : axiomsToApply) {
        var equalityToApply = axioms.get(axiom.proj1);
        var isDirect = axiom.proj2;
        var powsToRemove = isDirect ? axiomsPow.get(axiom.proj1).proj1 : axiomsPow.get(axiom.proj1).proj2;
        var rhsNF = isDirect ? equalityToApply.rhsNF : equalityToApply.lhsNF;
        var lhsTerm = isDirect ? equalityToApply.lhsTerm : equalityToApply.rhsTerm;
        var rhsTerm = isDirect ? equalityToApply.rhsTerm : equalityToApply.lhsTerm;
        ConcreteExpression rhsTermNF = computeNFTerm(rhsNF);
        ConcreteExpression nfProofTerm = equalityToApply.binding; // factory.ref(equalityToApply.binding);

        if (!isDirect) {
          nfProofTerm = factory.app(factory.ref(meta.ext.inv.getRef()), true, singletonList(nfProofTerm));
        }

        //if (!isNF(equalityToApply.lhsTerm) || !isNF(equalityToApply.rhsTerm)) {
          nfProofTerm = factory.appBuilder(factory.ref(meta.commTermsEqConv.getRef()))
                  .app(factory.ref(dataRef), false)
                  .app(lhsTerm)
                  .app(rhsTerm)
                  .app(nfProofTerm)
                  .build();
        //}

        var indexesToReplace = ComMonoidWP.findIndexesToRemove(curWord, powsToRemove);
        var subwordToReplace = new ArrayList<Integer>();
        var newWord = new ArrayList<>(curWord);

        for (Integer integer : indexesToReplace) {
          subwordToReplace.add(newWord.get(integer));
          newWord.remove(integer.intValue());
        }

        int prefix = 0;
        for (int i = 0; i < indexesToReplace.size(); ++i) {
          int ind = indexesToReplace.get(i);
          indexesToReplace.set(i, ind + i - prefix);
          prefix = ind + i + 1;
        }

        for (int i = rhsNF.size() - 1; i >= 0; --i) {
          newWord.add(0, rhsNF.get(i));
        }

        if (subwordToReplace.size() > 1) {
          ConcreteExpression sortProofLeft = factory.appBuilder(factory.ref(meta.sortDef.getRef())).app(computeNFTerm(subwordToReplace)).build();
          nfProofTerm = factory.app(factory.ref(meta.ext.concat.getRef()), true, Arrays.asList(sortProofLeft, nfProofTerm));
        }
        ConcreteExpression sortProofRight = factory.appBuilder(factory.ref(meta.sortDef.getRef())).app(rhsTermNF).build();
        sortProofRight = factory.app(factory.ref(meta.ext.inv.getRef()), true, singletonList(sortProofRight));
        nfProofTerm = factory.app(factory.ref(meta.ext.concat.getRef()), true, Arrays.asList(nfProofTerm, sortProofRight));

        ConcreteExpression stepProofTerm = factory.appBuilder(factory.ref(meta.commReplaceDef.getRef()))
                .app(factory.ref(dataRef), false)
                .app(computeNFTerm(curWord))
                .app(computeNFTerm(indexesToReplace))
                .app(rhsTermNF)
                .app(nfProofTerm)
                .build();
        if (proofTerm == null) {
          proofTerm = stepProofTerm;
        } else {
          proofTerm = factory.app(factory.ref(meta.ext.concat.getRef()), true, Arrays.asList(proofTerm, stepProofTerm));
        }

        curWord = newWord;
      }

      if (proofTerm == null) {
        proofTerm = factory.ref(meta.ext.prelude.getIdp().getRef());
      } else {
        ConcreteExpression sortProof = factory.appBuilder(factory.ref(meta.sortDef.getRef())).app(computeNFTerm(curWord)).build();
        proofTerm = factory.app(factory.ref(meta.ext.concat.getRef()), true, Arrays.asList(proofTerm, sortProof));
      }

      return factory.appBuilder(factory.ref(meta.commTermsEq.getRef()))
              .app(factory.ref(dataRef), false)
              .app(term1.concrete)
              .app(term2.concrete)
              .app(proofTerm)
              .build();
    }
  }


  private boolean typeToRule(TypedExpression typed, CoreBinding binding, boolean alwaysForward, List<RuleExt> rules) {
    if (binding == null && typed == null) {
      return false;
    }
    CoreExpression type = binding != null ? binding.getTypeExpr() : typed.getType();
    CoreFunCallExpression eq = Utils.toEquality(type, null, null);
    if (eq == null) {
      CoreExpression typeNorm = type.normalize(NormalizationMode.WHNF).getUnderlyingExpression();
      if (!(typeNorm instanceof CoreClassCallExpression)) {
        return false;
      }
      CoreClassCallExpression classCall = (CoreClassCallExpression) typeNorm;
      boolean isLDiv = classCall.getDefinition().isSubClassOf(meta.ldiv);
      boolean isRDiv = classCall.getDefinition().isSubClassOf(meta.rdiv);
      if (!isLDiv && !isRDiv) {
        return false;
      }
      List<ConcreteExpression> args = singletonList(binding != null ? factory.ref(binding) : factory.core(null, typed));
      return (!isLDiv || typeToRule(typechecker.typecheck(factory.app(factory.ref(meta.ldiv.getPersonalFields().get(0).getRef()), false, args), null), null, true, rules)) &&
        (!isRDiv || typeToRule(typechecker.typecheck(factory.app(factory.ref(meta.rdiv.getPersonalFields().get(0).getRef()), false, args), null), null, true, rules));
    }

    List<Integer> lhs = new ArrayList<>();
    List<Integer> rhs = new ArrayList<>();
    ConcreteExpression lhsTerm = computeTerm(eq.getDefCallArguments().get(1), lhs);
    ConcreteExpression rhsTerm = computeTerm(eq.getDefCallArguments().get(2), rhs);
    if (binding == null) {
      rules.add(new RuleExt(typed, null, Direction.FORWARD, lhs, rhs, lhsTerm, rhsTerm));
    } else {
      Direction direction = alwaysForward || lhs.size() > rhs.size() ? Direction.FORWARD : Direction.UNKNOWN;
      RuleExt rule = new RuleExt(typed, binding, direction, lhs, rhs, lhsTerm, rhsTerm);
      if (!alwaysForward && lhs.size() < rhs.size()) {
        rule.setBackward();
      }
      rules.add(rule);
    }
    return true;
  }

  private static final int MAX_STEPS = 100;

  private List<Integer> applyRules(List<Integer> term, List<RuleExt> rules, List<Step> trace) {
    boolean hasBadRules = false;
    for (RuleExt rule : rules) {
      if (rule.isIncreasing()) {
        hasBadRules = true;
        break;
      }
    }

    int i = 0;
    boolean found;
    do {
      found = false;
      for (RuleExt rule : rules) {
        int pos = Collections.indexOfSubList(term, rule.lhs);
        if (rule.direction == Direction.UNKNOWN) {
          if (pos < 0) {
            pos = Collections.indexOfSubList(term, rule.rhs);
            if (pos >= 0) {
              rule.setBackward();
            }
          } else {
            rule.direction = Direction.FORWARD;
          }
          if (rule.isIncreasing()) {
            hasBadRules = true;
          }
        }
        if (pos >= 0) {
          Step step = new Step(rule, pos);
          trace.add(step);
          term = step.apply(term);
          found = true;
          break;
        }
      }
      i++;
    } while (found && (!hasBadRules || i < MAX_STEPS));

    return term;
  }

  private ConcreteExpression traceToExpr(List<Integer> nf, List<Step> trace, ArendRef dataRef, ConcreteFactory factory) {
    ConcreteExpression result = null;
    for (Step step : trace) {
      ConcreteExpression expr = factory.appBuilder(factory.ref(meta.replaceDef.getRef()))
        .app(factory.ref(dataRef), false)
        .app(computeNFTerm(nf))
        .app(factory.number(step.position))
        .app(factory.number(step.rule.lhs.size()))
        .app(step.rule.rnfTerm)
        .app(step.rule.cExpr)
        .build();
      if (result == null) {
        result = expr;
      } else {
        result = factory.app(factory.ref(meta.ext.concat.getRef()), true, Arrays.asList(result, expr));
      }
      nf = step.apply(nf);
    }
    return result;
  }

  private static boolean isNF(ConcreteExpression term) {
    return term instanceof ConcreteReferenceExpression || isNFRec(term);
  }

  private static boolean isNFRec(ConcreteExpression term) {
    if (!(term instanceof ConcreteAppExpression)) {
      return false;
    }
    List<? extends ConcreteArgument> args = ((ConcreteAppExpression) term).getArguments();
    return args.size() == 1 || args.size() == 2 && args.get(0).getExpression() instanceof ConcreteAppExpression && ((ConcreteAppExpression) args.get(0).getExpression()).getArguments().size() == 1 && isNF(args.get(1).getExpression());
  }

  private ConcreteExpression computeNFTerm(List<Integer> nf) {
    return formList(nf.stream().map(factory::number).collect(Collectors.toList()), factory, meta.ext.nil, meta.ext.cons);
  }

  // TODO: create a proper util method somewhere else instead of this
  public static ConcreteExpression formList(List<ConcreteExpression> nf, ConcreteFactory factory, CoreConstructor nil, CoreConstructor cons) {
    ConcreteExpression result = factory.ref(nil.getRef());
    for (int i = nf.size() - 1; i >= 0; i--) {
      result = factory.appBuilder(factory.ref(cons.getRef())).app(nf.get(i)).app(result).build();
    }
    return result;
  }

  public enum Direction { FORWARD, BACKWARD, UNKNOWN }

  public static class Step {
    private final RuleExt rule;
    private final int position;

    private Step(RuleExt rule, int position) {
      this.rule = rule;
      this.position = position;
    }

    public List<Integer> apply(List<Integer> term) {
      if (term.size() < position + rule.lhs.size()) {
        return null;
      }
      List<Integer> result = new ArrayList<>(term.size() - rule.lhs.size() + rule.rhs.size());
      for (int i = 0; i < position; i++) {
        result.add(term.get(i));
      }
      result.addAll(rule.rhs);
      for (int i = position + rule.lhs.size(); i < term.size(); i++) {
        result.add(term.get(i));
      }
      return result;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Step step = (Step) o;
      return position == step.position && rule.equals(step.rule);
    }

    @Override
    public int hashCode() {
      return Objects.hash(rule, position);
    }
  }

  public static class Rule {
    public final TypedExpression expression;
    public final CoreBinding binding;
    public Direction direction;
    public List<Integer> lhs;
    public List<Integer> rhs;

    private Rule(TypedExpression expression, CoreBinding binding, Direction direction, List<Integer> lhs, List<Integer> rhs) {
      this.expression = expression;
      this.binding = binding;
      this.direction = direction;
      this.lhs = lhs;
      this.rhs = rhs;
    }

    boolean isIncreasing() {
      return direction == Direction.UNKNOWN ? lhs.size() == rhs.size() : lhs.size() <= rhs.size();
    }
  }

  private static class RuleExt extends Rule {
    private ConcreteExpression lhsTerm;
    private ConcreteExpression rhsTerm;
    private int count;
    private ConcreteExpression cExpr;
    private ConcreteExpression rnfTerm;

    private RuleExt(TypedExpression expression, CoreBinding binding, Direction direction, List<Integer> lhs, List<Integer> rhs, ConcreteExpression lhsTerm, ConcreteExpression rhsTerm) {
      super(expression, binding, direction, lhs, rhs);
      this.lhsTerm = lhsTerm;
      this.rhsTerm = rhsTerm;
    }

    private void setBackward() {
      if (direction == Direction.BACKWARD) {
        return;
      }
      direction = Direction.BACKWARD;
      List<Integer> nf = rhs;
      rhs = lhs;
      lhs = nf;
      ConcreteExpression term = rhsTerm;
      rhsTerm = lhsTerm;
      lhsTerm = term;
    }
  }

  private static class CompiledTerm {
    private final ConcreteExpression concrete;
    private List<Integer> nf;

    private CompiledTerm(ConcreteExpression concrete, List<Integer> nf) {
      this.concrete = concrete;
      this.nf = nf;
    }
  }

  private CompiledTerm compileTerm(CoreExpression expression) {
    List<Integer> nf = new ArrayList<>();
    return new CompiledTerm(computeTerm(expression, nf), nf);
  }

  private ConcreteExpression computeTerm(CoreExpression expression, List<Integer> nf) {
    CoreExpression expr = expression.normalize(NormalizationMode.WHNF);

    if (ideMatcher.match(expr) != null) {
      return factory.ref(meta.ideMTerm.getRef());
    }

    List<CoreExpression> args = mulMatcher.match(expr);
    if (args != null) {
      List<ConcreteExpression> cArgs = new ArrayList<>(2);
      cArgs.add(computeTerm(args.get(0), nf));
      cArgs.add(computeTerm(args.get(1), nf));
      return factory.app(factory.ref(meta.mulMTerm.getRef()), true, cArgs);
    }

    int index = values.addValue(expr);
    nf.add(index);
    return factory.app(factory.ref(meta.varMTerm.getRef()), true, singletonList(factory.number(index)));
  }

  @Override
  protected ConcreteExpression getDefaultValue() {
    return factory.ref(ide.getRef());
  }

  @Override
  protected ConcreteExpression getDataClass(ConcreteExpression instanceArg, ConcreteExpression dataArg) {
    ConcreteExpression data = factory.ref((isSemilattice ? meta.LData : isCommutative ? meta.CData : meta.Data).getRef());
    return isSemilattice
      ? factory.classExt(data, Arrays.asList(factory.implementation(meta.SemilatticeDataCarrier.getRef(), instanceArg), factory.implementation(meta.DataFunction.getRef(), dataArg)))
      : factory.app(data, Arrays.asList(factory.arg(instanceArg, false), factory.arg(dataArg, true)));
  }
}
