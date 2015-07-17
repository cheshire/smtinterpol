/*
 * Copyright (C) 2009-2012 University of Freiburg
 *
 * This file is part of SMTInterpol.
 *
 * SMTInterpol is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SMTInterpol is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with SMTInterpol.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.uni_freiburg.informatik.ultimate.smtinterpol.smtlib2;

import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import de.uni_freiburg.informatik.ultimate.logic.AnnotatedTerm;
import de.uni_freiburg.informatik.ultimate.logic.Annotation;
import de.uni_freiburg.informatik.ultimate.logic.ApplicationTerm;
import de.uni_freiburg.informatik.ultimate.logic.Assignments;
import de.uni_freiburg.informatik.ultimate.logic.CheckClosedTerm;
import de.uni_freiburg.informatik.ultimate.logic.ConstantTerm;
import de.uni_freiburg.informatik.ultimate.logic.FormulaUnLet;
import de.uni_freiburg.informatik.ultimate.logic.FunctionSymbol;
import de.uni_freiburg.informatik.ultimate.logic.FunctionSymbolFactory;
import de.uni_freiburg.informatik.ultimate.logic.Logics;
import de.uni_freiburg.informatik.ultimate.logic.Model;
import de.uni_freiburg.informatik.ultimate.logic.NoopScript;
import de.uni_freiburg.informatik.ultimate.logic.PrintTerm;
import de.uni_freiburg.informatik.ultimate.logic.QuotedObject;
import de.uni_freiburg.informatik.ultimate.logic.Rational;
import de.uni_freiburg.informatik.ultimate.logic.ReasonUnknown;
import de.uni_freiburg.informatik.ultimate.logic.SMTLIBException;
import de.uni_freiburg.informatik.ultimate.logic.Sort;
import de.uni_freiburg.informatik.ultimate.logic.Term;
import de.uni_freiburg.informatik.ultimate.logic.Theory;
import de.uni_freiburg.informatik.ultimate.logic.simplification.SimplifyDDA;
import de.uni_freiburg.informatik.ultimate.smtinterpol.Config;
import de.uni_freiburg.informatik.ultimate.smtinterpol.DefaultLogger;
import de.uni_freiburg.informatik.ultimate.smtinterpol.LogProxy;
import de.uni_freiburg.informatik.ultimate.smtinterpol.Main;
import de.uni_freiburg.informatik.ultimate.smtinterpol.convert.Clausifier;
import de.uni_freiburg.informatik.ultimate.smtinterpol.dpll.Clause;
import de.uni_freiburg.informatik.ultimate.smtinterpol.dpll.DPLLEngine;
import de.uni_freiburg.informatik.ultimate.smtinterpol.dpll.Literal;
import de.uni_freiburg.informatik.ultimate.smtinterpol.interpolate.Interpolator;
import de.uni_freiburg.informatik.ultimate.smtinterpol.interpolate.SymbolChecker;
import de.uni_freiburg.informatik.ultimate.smtinterpol.interpolate.SymbolCollector;
import de.uni_freiburg.informatik.ultimate.smtinterpol.option.OptionMap;
import de.uni_freiburg.informatik.ultimate.smtinterpol.option.OptionMap.CopyMode;
import de.uni_freiburg.informatik.ultimate.smtinterpol.option.SolverOptions;
import de.uni_freiburg.informatik.ultimate.smtinterpol.proof.ProofChecker;
import de.uni_freiburg.informatik.ultimate.smtinterpol.proof.ProofTermGenerator;
import de.uni_freiburg.informatik.ultimate.smtinterpol.proof.PropProofChecker;
import de.uni_freiburg.informatik.ultimate.smtinterpol.proof.UnsatCoreCollector;
import de.uni_freiburg.informatik.ultimate.util.ScopedArrayList;

/**
 * Implementation of the 
 * {@link de.uni_freiburg.informatik.ultimate.logic.Script} interface to
 * interact with SMTInterpol.
 * 
 * Users should however stick to the
 * {@link de.uni_freiburg.informatik.ultimate.logic.Script} interface which
 * provides most of the methods provided in this class.
 * @author Juergen Christ
 */
public class SMTInterpol extends NoopScript {
	
	private static class NoUserCancellation implements TerminationRequest {
		public boolean isTerminationRequested() {
			return false; // NOPMD
		}
	}
	
	public static enum CheckType {
		FULL {
			boolean check(DPLLEngine engine) {
				engine.provideCompleteness(DPLLEngine.COMPLETE);
				return engine.solve();
			}
		},
		PROPAGATION {
			boolean check(DPLLEngine engine) {
				engine.provideCompleteness(DPLLEngine.INCOMPLETE_CHECK);
				return engine.propagate();
			}
		},
		QUICK {
			boolean check(DPLLEngine engine) {
				engine.provideCompleteness(DPLLEngine.INCOMPLETE_CHECK);
				return engine.quickCheck();
			}
		};
		abstract boolean check(DPLLEngine engine);
	}
	
	private static class SMTInterpolSetup extends Theory.SolverSetup {
		
		private static class RewriteProofFactory extends FunctionSymbolFactory {
			Sort mProofSort;
			public RewriteProofFactory(String name, Sort proofSort) {
				super(name);
				mProofSort = proofSort;
			}

			@Override
			public int getFlags(
					BigInteger[] indices, Sort[] paramSorts, Sort resultSort) {
				return paramSorts.length == 1 ?  FunctionSymbol.INTERNAL
						: FunctionSymbol.LEFTASSOC | FunctionSymbol.INTERNAL;
			}

			@Override
			public Sort getResultSort(BigInteger[] indices, Sort[] paramSorts,
					Sort resultSort) {
				if (indices != null
					|| paramSorts.length == 0 || paramSorts.length > 2	
					|| resultSort != null
					|| paramSorts[0] != mProofSort)
					return null;

				if (paramSorts.length == 2 && paramSorts[0] != paramSorts[1])
					return null;
				
				return paramSorts[0];
			}
		}
		
		private final int mProofMode;
		
		public SMTInterpolSetup(int proofMode) {
			mProofMode = proofMode;
		}

		@Override
		public void setLogic(Theory theory, Logics logic) {
			int leftassoc = FunctionSymbol.LEFTASSOC;
			// Damn Java compiler...
			Sort proof = null;
			Sort[] proof2 = null;
			Sort bool = theory.getSort("Bool");
			Sort[] bool1 = {bool};
			if (mProofMode > 0) {
				// Partial proofs.
				// Declare all symbols needed for proof production
				declareInternalSort(theory, "@Proof", 0, 0);
				proof = theory.getSort("@Proof");
				proof2 = new Sort[] { proof, proof };
				declareInternalFunction(
						theory, "@res", proof2, proof, leftassoc);
				declareInternalFunction(theory, "@tautology", bool1, proof, 0);
				declareInternalFunction(theory, "@lemma", bool1, proof, 0);
				declareInternalFunction(theory, "@asserted", bool1, proof, 0);
			}
			if (mProofMode > 1) {
				// Full proofs.
				declareInternalFunction(theory, "@intern", bool1, proof, 0);
				declareInternalFunction(
						theory, "@split", new Sort[] {proof, bool}, proof, 0);
				defineFunction(theory, new RewriteProofFactory("@eq", proof));
				declareInternalFunction(theory, "@rewrite", bool1, proof, 0);
				declareInternalFunction(
						theory, "@clause", new Sort[] {proof, bool}, proof, 0);
			}
			defineFunction(theory, new FunctionSymbolFactory("@undefined") {
				
				@Override
				public int getFlags(
						BigInteger[] indices, Sort[] paramSorts, Sort resultSort) {
					return FunctionSymbol.INTERNAL | FunctionSymbol.RETURNOVERLOAD;
				}
				@Override
				public Sort getResultSort(BigInteger[] indices, Sort[] paramSorts,
						Sort resultSort) {
					if (indices != null || paramSorts.length != 0)
						return null;
					return resultSort;
				}
			});
			if (logic.isArray())
				declareArraySymbols(theory);
			if (logic.hasIntegers())
				declareIntSymbols(theory);
			if (logic.hasReals())
				declareRealSymbols(theory);
		}
		
		private final void declareIntSymbols(Theory theory) {
			Sort intSort = theory.getSort("Int");
			Sort[] sort1 = {intSort};
			declareInternalFunction(theory, "@mod0", sort1, intSort, 0);
			declareInternalFunction(theory, "@div0", sort1, intSort, 0);
		}
		
		private final void declareRealSymbols(Theory theory) {
			Sort realSort = theory.getSort("Real");
			Sort[] sort1 = {realSort};
			declareInternalFunction(theory, "@/0", sort1, realSort, 0);
		}
		
		private final void declareArraySymbols(Theory theory) {
			// Currently only diff
			Sort[] vars = theory.createSortVariables("Index", "Elem");
			Sort array = theory.getSort("Array", vars);
			declareInternalPolymorphicFunction(
					theory, "@diff", vars, new Sort[]{array, array}, vars[0], 0);
		}
	}
	
	private final OptionMap mOptions;
	private final SolverOptions mSolverOptions;
	
	private static class TimeoutTask extends TimerTask {
		private final DPLLEngine mEngine;
		public TimeoutTask(DPLLEngine engine) {
			mEngine = engine;
		}
		@Override
		public void run() {
			synchronized (mEngine) {
				mEngine.setCompleteness(DPLLEngine.INCOMPLETE_TIMEOUT);
				mEngine.stop();
			}
		}
	}
	
	private DPLLEngine mEngine;
	private Clausifier mClausifier;
	private ScopedArrayList<Term> mAssertions;
	private final TerminationRequest mCancel;
	
	private LogProxy mLogger;
	
	de.uni_freiburg.informatik.ultimate.smtinterpol.model.Model mModel = null;
	
	private final static Object NAME = new QuotedObject("SMTInterpol");
	private final static Object AUTHORS = new QuotedObject(
					"Jochen Hoenicke, Juergen Christ, and Alexander Nutz");
	private final static Object INTERPOLATION_METHOD = new QuotedObject("tree");
	// I assume an initial check s.t. first (get-info :status) returns sat
	private LBool mStatus = LBool.SAT;
	
	// The status set in the benchmark
	private String mStatusSet = null;
	private ReasonUnknown mReasonUnknown = null;
	
	// The assertion stack was modified after the last check-sat, i.e., the
	// m_status field is not valid and we have to deactivate
	// get-{value,model,interpolants,proof}.
	private boolean mAssertionStackModified = true;
	// The assertion stack level at which the first division-by-0 was
	// encountered.  If it is -1, it means "never"
	private int mBy0Seen = -1;
	
	// Timeout handling
	private final static class TimerHolder {
		public static Timer timer = new Timer("SMTInterpol timeout thread", true); // NOCHECKSTYLE
	}
	/**
	 * Delta debugger friendly version.  Exits with following codes:
	 * model-check-mode fails: 1
	 * interpolant-check-mode fails: 2
	 * exception during check-sat: 3
	 * command that needed sat after last check got unsat: 4
	 * command that needed unsat after last check got sat: 5
	 */
	private final boolean mDDFriendly =
	        !Config.COMPETITION 
			    && System.getProperty("smtinterpol.ddfriendly") != null;
	
	/**
	 * Default constructor using a default logger and no user termination
	 * request.  If this constructor is used, SMTInterpol assumes ownership of
	 * the logger.
	 */
	public SMTInterpol() {
		this(new DefaultLogger(), new NoUserCancellation());
	}
	
	/**
	 * Construct SMTInterpol with a user-owned logger but without user
	 * termination request.
	 * @param logger The logger owned by the caller.
	 */
	public SMTInterpol(LogProxy logger) {
		this(logger, new NoUserCancellation());
	}
	
	/**
	 * Construct SMTInterpol with a logger but without user termination request.
	 * The logger is assumed to be configured by the user.
	 * @param logger    The logger owned by the caller.
	 * @param ignored   This parameter is ignored!
	 * @deprecated Use a constructor version without the boolean parameter!
	 */
	public SMTInterpol(LogProxy logger, boolean ignored) {
		this(logger, new NoUserCancellation());
	}
	
	/**
	 * Default constructor using a default logger and a given user termination
	 * request.
	 * @param cancel User termination request to poll during checks.
	 */
	public SMTInterpol(TerminationRequest cancel) {
		this(new DefaultLogger(), cancel);
	}
	
	/**
	 * Construct SMTInterpol with a logger and a user termination
	 * request.  This is the main constructor of SMTInterpol.
	 * @param logger The logger owned by the caller.
	 * @param cancel User termination request to poll during checks.
	 */
	public SMTInterpol(LogProxy logger, TerminationRequest cancel) {
		this(cancel, new OptionMap(logger));
	}
	
	/**
	 * Construct SMTInterpol with an option map.  SMTInterpol will use the
	 * logger used to initialize the option map.
	 * @param options The option map used to handle all options.
	 */
	public SMTInterpol(OptionMap options) {
		this(new NoUserCancellation(), options);
	}
	
	/**
	 * Construct SMTInterpol with a user termination request and a user created
	 * option map.  This constructor is mainly used by the front
	 * ends to set an option map including front end options.
	 * @param cancel  User termination request to poll during checks.
	 * @param options Option map to handle options.
	 */
	public SMTInterpol(TerminationRequest cancel,
			OptionMap options) {
		if (cancel == null)
			cancel = new NoUserCancellation();
		mLogger = options.getLogProxy();
		mOptions = options;
		mSolverOptions = options.getSolverOptions();
		mCancel = cancel;
		reset();
	}
	
	/**
	 * Construct SMTInterpol with a user-owned logger but without user
	 * termination request.  Note that the logger is assumed to be correctly set
	 * up.
	 * @param logger    The logger owned by the caller.
	 * @param ignored   This option is ignored!
	 * @param cancel    User termination request to poll during checks.
	 * @deprecated Use a constructor version without the boolean parameter.
	 */
	public SMTInterpol(
			LogProxy logger, boolean ownLogger, TerminationRequest cancel) {
		this(logger, cancel);
	}
	/**
	 * Copy the current context and modify some pre-theory options.  The copy
	 * shares the push/pop stack on the symbols but not on the assertions.
	 * Users should be careful not to mess up the push/pop stack, i.e., not to
	 * push on one context and pop on another one.
	 * 
	 * Note that this cloning does not clone the assertion stack and should not
	 * be used in multi-threaded contexts since users cannot guarantee correct
	 * push/pop-stack treatment.
	 * @param other   The context to clone.
	 * @param options The options to set before setting the logic.
	 * @param mode    What to do when copying existing options.
	 */
	public SMTInterpol(SMTInterpol other, Map<String, Object> options,
			OptionMap.CopyMode mode) {
		super(other.getTheory());
		mLogger = other.mLogger;
		mOptions = other.mOptions.copy(mode);
		mSolverOptions = mOptions.getSolverOptions();
		if (options != null)
			for (Map.Entry<String, Object> me : options.entrySet())
				setOption(me.getKey(), me.getValue());
		mCancel = other.mCancel;
		setupClausifier(getTheory().getLogic());
	}
	
	// Called in ctor => make it final
	/**
	 * Unset the logic and clear the assertion stack.  This does not reset
	 * online modifyable options.
	 */
	public final void reset() {
		super.reset();
		mEngine = null;
		mModel = null;
		mAssertionStackModified = true;
        if (mAssertions != null)
        	mAssertions.clear();
        mOptions.reset();
	}
	
	@Override
	public void push(int n) throws SMTLIBException {
		super.push(n);
		modifyAssertionStack();
		while (n-- > 0) {
			if (mAssertions != null)
				mAssertions.beginScope();
			mClausifier.push();
		}
	}
	
	@Override
	public void pop(int n) throws SMTLIBException {
		try {
			super.pop(n);
		} catch (SMTLIBException eBug) {
			if (mDDFriendly)
				System.exit(123);
			throw eBug;
		}
		modifyAssertionStack();
		int i = n;
		while (i-- > 0) {
			if (mAssertions != null)
				mAssertions.endScope();
		}
		mClausifier.pop(n);
		if (mStackLevel < mBy0Seen)
			// We've popped all division-by-0s.
			mBy0Seen = -1;
	}
	
	@Override
	public LBool checkSat() throws SMTLIBException {
		if (mEngine == null)
			throw new SMTLIBException("No logic set!");
		mModel = null;
		mAssertionStackModified = false;
		long timeout = mSolverOptions.getTimeout();
		TimeoutTask timer = null;
		if (timeout > 0) {
			timer = new TimeoutTask(mEngine);
			TimerHolder.timer.schedule(timer,
					mOptions.getSolverOptions().getTimeout());
		}
		
		LBool result = LBool.UNKNOWN;
		mReasonUnknown = ReasonUnknown.INCOMPLETE;
		mEngine.setRandomSeed(mSolverOptions.getRandomSeed());
		try {
			if (mSolverOptions.getCheckType().check(mEngine)) {
				if (mEngine.hasModel()) {
					result = LBool.SAT;
					if (mSolverOptions.isModelCheckModeActive()) {
						mModel = new de.uni_freiburg.informatik.ultimate.
								smtinterpol.model.Model(
								mClausifier, getTheory(),
								mSolverOptions.isModelsPartial());
						if (!mModel.checkTypeValues(mLogger) && mDDFriendly)
							System.exit(1);
						for (Term asserted : mAssertions) {
							Term checkedResult = mModel.evaluate(asserted);
							if (checkedResult != getTheory().mTrue) {
								if (mDDFriendly)
									System.exit(1);
								mLogger.fatal("Model does not satisfy " 
										+ asserted.toStringDirect());
//								for (Term t : getSatisfiedLiterals())
//									if (m_Model.evaluate(t) != getTheory().TRUE)
//										m_Logger.fatal("Unsat lit: " + t.toStringDirect());
							}
						}
					}
				} else {
					result = LBool.UNKNOWN;
					switch(mEngine.getCompleteness()) {
					case DPLLEngine.COMPLETE:
						if (mSolverOptions.getCheckType() == CheckType.FULL)
							throw new InternalError("Complete but no model?");
						mReasonUnknown = ReasonUnknown.INCOMPLETE;
						break;
					case DPLLEngine.INCOMPLETE_MEMOUT:
						mReasonUnknown = ReasonUnknown.MEMOUT;
						break;
					case DPLLEngine.INCOMPLETE_TIMEOUT:
						mReasonUnknown = ReasonUnknown.TIMEOUT;
						break;
					case DPLLEngine.INCOMPLETE_QUANTIFIER:
					case DPLLEngine.INCOMPLETE_THEORY:
						mReasonUnknown = ReasonUnknown.INCOMPLETE;
						break;
					case DPLLEngine.INCOMPLETE_UNKNOWN:
						mReasonUnknown = ReasonUnknown.CRASHED;
						break;
					case DPLLEngine.INCOMPLETE_CHECK:
						mReasonUnknown = ReasonUnknown.INCOMPLETE;
						break;
					case DPLLEngine.INCOMPLETE_CANCELLED:
						mReasonUnknown = ReasonUnknown.CANCELLED;
						break;
					default:
						throw new InternalError("Unknown incompleteness reason");
					}
					mLogger.info("Got %s as reason to return unknown",
									mEngine.getCompletenessReason());
				}
			} else {
				result = LBool.UNSAT;
				if (mSolverOptions.isProofCheckModeActive()) {
					ProofChecker proofchecker = 
							new ProofChecker(this, getLogger());
					if (!proofchecker.check(getProof())) { 
						if (mDDFriendly)
							System.exit(2);
						mLogger.fatal("Proof-checker did not verify");
					}
				}
			}
		} catch (OutOfMemoryError eoom) {
			// BUGFIX: Don't do this since log4j will produce another OOM.
//			m_Logger.fatal("OOM during check ",oom);
			mLogger.outOfMemory("Out of memory during checkSat");
			mReasonUnknown = ReasonUnknown.MEMOUT;
		} catch (Throwable ex) {
			if (mDDFriendly)
				System.exit(3);// NOCHECKSTYLE
			mLogger.fatal("Error during check ",ex);
			mReasonUnknown = ReasonUnknown.CRASHED;
		}
		mStatus = result;
		if (Config.CHECK_STATUS_SET && isStatusSet() 
				&& mReasonUnknown != ReasonUnknown.MEMOUT
					&& !mStatus.toString().equals(mStatusSet)) {
			mLogger.warn("Status differs: User said %s but we got %s",
					mStatusSet, mStatus);
			if (mDDFriendly)
				System.exit(13);
		}
		mStatusSet = null;
		if (timer != null)
			timer.cancel();
		return result;
	}
	
	private final boolean isStatusSet() {
		return mStatusSet != null && !mStatusSet.equals("unknown");
	}

	@Override
	public void setLogic(String logic)
	    throws UnsupportedOperationException, SMTLIBException {
		try {
			setLogic(Logics.valueOf(logic));
		} catch (IllegalArgumentException ex) {
			/* Logic is not in enumeration */
			throw new 
			UnsupportedOperationException("Logic " + logic + " not supported");
		}
	}
	
	@Override
	public void setLogic(Logics logic)
		throws UnsupportedOperationException, SMTLIBException {
		mSolverSetup = new SMTInterpolSetup(getProofMode());
		super.setLogic(logic);
		setupClausifier(logic);
	}

	/**
	 * Setup the clausifier and the engine according to the logic,
	 * the current proof production mode, and some other options.
	 * @param logic the SMT-LIB logic to use.
	 * @throws UnsupportedOperationException if the logic is not supported
	 * by SMTInterpol.
	 */
	private void setupClausifier(Logics logic) {
		try {
			int proofMode = getProofMode();
			mEngine = new DPLLEngine(getTheory(), mLogger, mCancel);
			mClausifier = new Clausifier(mEngine, proofMode);
			// This has to be before set-logic since we need to capture
			// initialization of CClosure.
			mEngine.setProofGeneration(proofMode > 0);
			mClausifier.setLogic(logic);
			boolean produceAssignment = getBooleanOption(":produce-assignments");
			mClausifier.setAssignmentProduction(produceAssignment);
			mEngine.setProduceAssignments(produceAssignment);
			mEngine.setRandomSeed(mSolverOptions.getRandomSeed());
			if (getBooleanOption(":interactive-mode"))
				mAssertions = new ScopedArrayList<Term>();
			mOptions.setOnline();
		} catch (UnsupportedOperationException eLogicUnsupported) {
			super.reset();
			mEngine = null;
			mClausifier = null;
			throw eLogicUnsupported;
		}
	}

	@Override
	public LBool assertTerm(Term term) throws SMTLIBException {
		if (mEngine == null)
			throw new SMTLIBException("No logic set!");
		super.assertTerm(term);
		if (!term.getSort().equals(getTheory().getBooleanSort())) {
			if (term.getSort().getTheory() == getTheory())
				throw new SMTLIBException("Asserted terms must have sort Bool");
			else
				throw new SMTLIBException("Asserted terms created with incompatible theory");
		}
		if (Config.STRONG_USAGE_CHECKS && !new CheckClosedTerm().isClosed(term))
			throw new SMTLIBException("Asserted terms must be closed");
		if (mAssertions != null)
			mAssertions.add(term);
		if (mEngine.inconsistent()) {
			mLogger.info("Asserting into inconsistent context");
			return LBool.UNSAT;
		}
		try {
			modifyAssertionStack();
			mClausifier.addFormula(term);
			/* We always have to reset the flag, but only need to set the stack
			 * level if it is not already set. 
			 */
			if (mClausifier.resetBy0Seen() && mBy0Seen == -1)
				mBy0Seen = mStackLevel;
			if (!mEngine.quickCheck()) {
				mLogger.info("Assertion made context inconsistent");
				return LBool.UNSAT;
			}
		} catch (UnsupportedOperationException ex) {
			throw new SMTLIBException(ex.getMessage());
		} catch (RuntimeException exc) {
			if (mDDFriendly)
				System.exit(7);// NOCHECKSTYLE
			throw exc;
		} catch (AssertionError exc) {
			if (mDDFriendly)
				System.exit(7);// NOCHECKSTYLE
			throw exc;
		}
		return LBool.UNKNOWN;
	}

	@Override
	public Term[] getAssertions() throws SMTLIBException {
		if (mEngine == null)
			throw new SMTLIBException("No logic set!");
		if (mAssertions != null)
			return mAssertions.toArray(new Term[mAssertions.size()]);
		throw new SMTLIBException(
				"Set option :interactive-mode to true to get assertions!");
	}

	@Override
	public Assignments getAssignment() throws SMTLIBException {
		if (mEngine == null)
			throw new SMTLIBException("No logic set!");
		if (!mEngine.isProduceAssignments())
			throw new SMTLIBException(
				"Set option :produce-assignments to true to generate assignments!");
		checkAssertionStackModified();
		return mEngine.getAssignments();
	}

	@Override
	public Object getInfo(String info) throws UnsupportedOperationException {
		if (":status".equals(info))
			return mStatus;
		if (":name".equals(info))
			return NAME;
		if (":version".equals(info))
			return new QuotedObject(Main.getVersion());
		if (":authors".equals(info))
			return AUTHORS;
		if (":all-statistics".equals(info)) {
			return mEngine == null ? new Object[0] : mEngine.getStatistics();
		}
		if (":status-set".equals(info))
			return mStatusSet;
		if (":options".equals(info)) {
			return mOptions.getInfo();
		}
		if (":reason-unknown".equals(info)) {
			if (mStatus != LBool.UNKNOWN)
				throw new SMTLIBException("Status not unknown");
			return mReasonUnknown;
		}
		if (":assertion-stack-levels".equals(info))
			return mStackLevel;
		// Info from our SMTLIB interpolation proposal
		if (":interpolation-method".equals(info))
			return INTERPOLATION_METHOD;
		return mOptions.getInfo(info);
	}

	@Override
	public Object getOption(String opt) throws UnsupportedOperationException {
		return mOptions.get(opt);
	}

	/**
	 * Get the proofMode according to the options that are set.
	 * @returns 2 for full proofs, 1 for propositional only proofs, 0
	 * for no proofs.
	 */
	private int getProofMode() {
		if (mSolverOptions.isProofCheckModeActive()
				|| mSolverOptions.isProduceProofs()) {
			return 2;
		} else if (mSolverOptions.isProduceInterpolants()
				|| getBooleanOption(":produce-unsat-cores")) { 
			return 1;
		} else {
			return 0;
		}
	}
	
	@Override
	public Term getProof()
	    throws SMTLIBException, UnsupportedOperationException {
		if (mEngine == null)
			throw new SMTLIBException("No logic set!");
		int proofMode = getProofMode();
		if (proofMode == 0)
			throw new SMTLIBException("Option :produce-proofs not set to true");
		if (proofMode == 1)
			mLogger.warn("Using partial proofs (cut at CNF-level).  "
				+ "Set option :produce-proofs to true to get complete proofs."
			);
		checkAssertionStackModified();
		Clause unsat = retrieveProof();
		if (Config.CHECK_PROP_PROOF) {
			PropProofChecker ppc = new PropProofChecker();
			boolean correct = ppc.check(unsat);
			assert correct;
		}
		try {
			ProofTermGenerator generator = new ProofTermGenerator(getTheory());
			Term res = generator.convert(retrieveProof());
			if (mBy0Seen != -1)
				res = new Div0Remover().transform(res);
			return res;
		} catch (Exception exc) {	
			throw new SMTLIBException(exc.getMessage() == null 
					? exc.toString() : exc.getMessage());
		}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public Term[] getInterpolants(Term[] partition, int[] startOfSubtree) {
		if (mEngine == null)
			throw new SMTLIBException("No logic set!");
		if (getTheory().getLogic().isArray())
			throw new UnsupportedOperationException(
					"Array interpolation not implemented yet");
		if (!mSolverOptions.isProduceProofs()
				&& !mSolverOptions.isProduceInterpolants())
			throw new SMTLIBException(
					"Interpolant production not enabled.  Set either :produce-interpolants or :produce-proofs to true");
		checkAssertionStackModified();
		if (partition.length != startOfSubtree.length)
			throw new SMTLIBException(
			    "Partition table and subtree array need to have equal length");
		if (Config.STRONG_USAGE_CHECKS) {
			for (int i = 0; i < partition.length; i++) {
				if (startOfSubtree[i] < 0)
					throw new SMTLIBException(
					    "subtree array must not contain negative element");
				int j = i;
				while (startOfSubtree[i] < j)
					j = startOfSubtree[j - 1];
				if (startOfSubtree[i] != j)
					throw new SMTLIBException("malformed subtree array.");
			}
			if (startOfSubtree[partition.length - 1] != 0)
				throw new SMTLIBException("malformed subtree array.");
		}
		Set<String>[] parts = new Set[partition.length];
		String errormsg = 
			"arguments must be named terms or conjunctions of named terms";
		for (int i = 0; i < partition.length; i++) {
			if (!(partition[i] instanceof ApplicationTerm)) {
				throw new SMTLIBException(errormsg);
			}
			FunctionSymbol fsym = ((ApplicationTerm) partition[i]).getFunction();
			Term[] terms;
			if (fsym.isIntern()) {
				if (!fsym.getName().equals("and"))
					throw new SMTLIBException(errormsg);
				terms = ((ApplicationTerm) partition[i]).getParameters();
			} else
				terms = new Term[] { partition[i] };
			parts[i] = new HashSet<String>();
			for (int j = 0; j < terms.length; j++) {
				if (!(terms[j] instanceof ApplicationTerm)) {
					throw new SMTLIBException(errormsg);
				}
				ApplicationTerm appTerm = (ApplicationTerm) terms[j];
				if (appTerm.getParameters().length != 0)
					throw new SMTLIBException(errormsg);
				if (appTerm.getFunction().isIntern())
					throw new SMTLIBException(errormsg);
				parts[i].add(appTerm.getFunction().getName().intern());
			}
		}
		SMTInterpol tmpBench = null;
		SymbolCollector collector = null;
		Set<FunctionSymbol> globals = null;
		if (mSolverOptions.isInterpolantCheckModeActive()) {
			HashSet<String> usedParts = new HashSet<String>();
			for (Set<String> part : parts)
				usedParts.addAll(part);
			tmpBench = new SMTInterpol(this,
					Collections.singletonMap(":interactive-mode",
							(Object)Boolean.TRUE), CopyMode.CURRENT_VALUE);
			int old = tmpBench.mLogger.getLoglevel();
			try {
				tmpBench.mLogger.setLoglevel(LogProxy.LOGLEVEL_ERROR);
				// Clone the current context except for the parts used in the
				// interpolation problem
				collector = new SymbolCollector();
				collector.startCollectTheory();
			termloop: 
			    for (Term asserted : mAssertions) {
					if (asserted instanceof AnnotatedTerm) {
						AnnotatedTerm annot = (AnnotatedTerm) asserted;
						for (Annotation an : annot.getAnnotations()) {
							if (":named".equals(an.getKey()) 
									&& usedParts.contains(an.getValue()))
								continue termloop;
						}
					}
					tmpBench.assertTerm(asserted);
					collector.addGlobalSymbols(asserted);
				}
				globals = collector.getTheorySymbols();
			} finally {
				tmpBench.mLogger.setLoglevel(old);
			}
			// free space
			usedParts = null;
		}
		Interpolator interpolator =
			new Interpolator(mLogger, tmpBench, getTheory(), parts, startOfSubtree);
		Clause refutation = retrieveProof();
		Term[] ipls = interpolator.getInterpolants(refutation);
		
		if (mBy0Seen != -1) {
			Div0Remover rem = new Div0Remover();
			for (int i = 0; i < ipls.length; ++i)
				ipls[i] = rem.transform(ipls[i]);
		}
		
		if (mSolverOptions.isInterpolantCheckModeActive()) {
			boolean error = false;
			int old = tmpBench.mLogger.getLoglevel();
			try {
				tmpBench.mLogger.setLoglevel(LogProxy.LOGLEVEL_ERROR);
				// Compute Symbol occurrence
				Map<FunctionSymbol, Integer>[] occs =
					new Map[partition.length];
				for (int i = 0; i < partition.length; ++i)
					occs[i] = collector.collect(partition[i]);
				// Recompute the symbol occurrence:
				// occs[i] should be the symbols occurring in the subtree of
				// partition[i]
				for (int i = 0; i < startOfSubtree.length; ++i) {
					// Find children
					int child = i - 1;
					while (child >= startOfSubtree[i]) {
						// join occurrence maps
						for (Map.Entry<FunctionSymbol, Integer> me
						        : occs[child].entrySet()) {
							Integer ival = occs[i].get(me.getKey());
							ival = ival == null ? me.getValue()
								: ival + me.getValue();
							occs[i].put(me.getKey(), ival);
						}
						child = startOfSubtree[child] - 1;
					}
				}
				SymbolChecker checker = new SymbolChecker(globals);
				for (int i = 0; i < startOfSubtree.length; ++i) {
					tmpBench.push(1);
					// Find and assert children
					int child = i - 1;
					while (child >= startOfSubtree[i]) {
						tmpBench.assertTerm(ipls[child]);
						child = startOfSubtree[child] - 1;
					}
					// Assert node
					tmpBench.assertTerm(partition[i]);
					// Assert negated interpolant
					try {
						if (i != ipls.length)
							tmpBench.assertTerm(tmpBench.term("not", ipls[i]));
					} catch (SMTLIBException exc) {
						mLogger.error("Could not assert interpolant", exc);
						error = true;
					}
					LBool res = tmpBench.checkSat();
					if (res == LBool.SAT) {
						if (mDDFriendly)
							System.exit(2);
						mLogger.error("Interpolant %d not inductive: "
								+ " (Check returned %s)", i, res);
						error = true;
					} else if (res == LBool.UNKNOWN) {
						ReasonUnknown ru = tmpBench.mReasonUnknown;
						mLogger.warn("Unable to check validity of interpolant: "
								+ ru);
						// I don't set the error flag here since I am not sure
						// whether this is a real error or not.  Maybe we should
						// base this on ru?
					}
					tmpBench.pop(1);
					// Check symbol condition
					if (i != ipls.length 
						&& checker.check(ipls[i], occs[i], occs[ipls.length])) {
						mLogger.error("Symbol error in Interpolant %d.  "
								+ "Subtree only symbols: %s.  "
								+ "Non-subtree only symbols: %s.", i,
								checker.getLeftErrors(),
								checker.getRightErrors());
						error = true;
					}
				}
			} finally {
				tmpBench.mLogger.setLoglevel(old);
				// Not needed for now, but maybe later...
				tmpBench.exit();
			}
			if (error)
				throw new SMTLIBException(
				        "generated interpolants did not pass sanity check");
		}
		if (mSolverOptions.isSimplifyInterpolants()) {
			SimplifyDDA simplifier = new SimplifyDDA(new SMTInterpol(this, 
					Collections.singletonMap(
							":check-type",
							(Object) mSolverOptions.getSimplifierCheckType()),
							CopyMode.CURRENT_VALUE),
							getBooleanOption(":simplify-repeatedly"));
			for (int i = 0; i < ipls.length; ++i)
				ipls[i] = simplifier.getSimplifiedTerm(ipls[i]);
		}
		return ipls;
	}
	
	@Override
	public Term[] getUnsatCore()
	    throws SMTLIBException, UnsupportedOperationException {
		if (mEngine == null)
			throw new SMTLIBException("No logic set!");
		if (!getBooleanOption(":produce-unsat-cores"))
			throw new SMTLIBException(
					"Set option :produce-unsat-cores to true before using get-unsat-cores");
		checkAssertionStackModified();
		Clause unsat = mEngine.getProof();
		if (unsat == null)
			throw new SMTLIBException("Logical context not inconsistent!");
		Term[] core = new UnsatCoreCollector(this).getUnsatCore(unsat);
		if (getBooleanOption(":unsat-core-check-mode")) {
			HashSet<String> usedParts = new HashSet<String>();
			for (Term t : core)
				usedParts.add(((ApplicationTerm)t).getFunction().getName());
			SMTInterpol tmpBench = new SMTInterpol(this, null, CopyMode.CURRENT_VALUE);
			int old = tmpBench.mLogger.getLoglevel();
			try {
				tmpBench.mLogger.setLoglevel(LogProxy.LOGLEVEL_ERROR);
				// Clone the current context except for the parts used in
				// the unsat core
			termloop:
			    for (Term asserted : mAssertions) {
					if (asserted instanceof AnnotatedTerm) {
						AnnotatedTerm annot = (AnnotatedTerm) asserted;
						for (Annotation an : annot.getAnnotations()) {
							if (":named".equals(an.getKey()) 
									&& usedParts.contains(an.getValue()))
								continue termloop;
						}
					}
					tmpBench.assertTerm(asserted);
				}
				for (Term t : core)
					tmpBench.assertTerm(t);
				LBool isUnsat = tmpBench.checkSat();
				if (isUnsat != LBool.UNSAT) {
					mLogger.error(
							"Unsat core could not be proven unsat (Result is %s)",
							isUnsat);
				}
			} finally {
				tmpBench.mLogger.setLoglevel(old);
				// Not needed for now, but maybe later...
				tmpBench.exit();
			}
		}
		return core;
	}

	@Override
	public Map<Term, Term> getValue(Term[] terms)
	    throws SMTLIBException, UnsupportedOperationException {
		if (mEngine == null)
			throw new SMTLIBException("No logic set!");
		buildModel();
		return mModel.evaluate(terms);
	}
	
	@Override
	public Model getModel() throws SMTLIBException,
			UnsupportedOperationException {
		if (mEngine == null)
			throw new SMTLIBException("No logic set!");
		buildModel();
		return mModel;
	}

	@Override
	public void setInfo(String info, Object value) {
		if (info.equals(":status")
			&& value instanceof String) {
			if (value.equals("sat")) {
				mStatus = LBool.SAT;
				mStatusSet = "sat";
			} else if (value.equals("unsat")) {
				mStatus = LBool.UNSAT;
				mStatusSet = "unsat";
			} else if (value.equals("unknown")) {
				mStatus = LBool.UNKNOWN;
				mStatusSet = "unknown";
			}
		}
	}
	
	@Override
	public void setOption(String opt, Object value)
	    throws UnsupportedOperationException, SMTLIBException {
		mOptions.set(opt, value);
	}
	
	public Term simplify(Term term) throws SMTLIBException {
		CheckType old = mSolverOptions.getCheckType();
		int oldNumScopes = mStackLevel;
		try {
			mSolverOptions.setCheckType(mSolverOptions.getSimplifierCheckType());
			return new SimplifyDDA(this, getBooleanOption(":simplify-repeatedly")).
					getSimplifiedTerm(term);
		} finally {
			mSolverOptions.setCheckType(old);
			assert (mStackLevel == oldNumScopes);
		}
	}

	/**
	 * Perform a restart and switch the decisions of all undecided literals.
	 * This method should efficiently lead the solver to explore another path
	 * in the search tree. 
	 */
	public void flipDecisions() {
		mEngine.flipDecisions();
	}
	
	/**
	 * Flip the truth value decision for a name literal.
	 * @param name The name used in the annotation for this literal.
	 * @throws SMTLIBException If name not known.
	 */
	public void flipNamedLiteral(String name) throws SMTLIBException {
		mEngine.flipNamedLiteral(name);
	}

	/**
	 * Access to the internal CNF transformer.  Should not be used by users.
	 * @return Internal CNF transformer.
	 */
	public Clausifier getClausifier() {
		return mClausifier;
	}

	/**
	 * Access to the internal DPLL engine.  Should not be used by users.
	 * @return Internal DPLL engine.
	 */
	public DPLLEngine getEngine() {
		return mEngine;
	}	

	/**
	 * Access to the logger used by SMTInterpol.
	 * @return The logger used by SMTInterpol.
	 */
	public LogProxy getLogger() {
		return mLogger;
	}

	protected void setEngine(DPLLEngine engine) {
		mEngine = engine;
	}

	protected void setClausifier(Clausifier clausifier) {
		mClausifier = clausifier;
	}
	
	private void checkAssertionStackModified() throws SMTLIBException {
		if (mAssertionStackModified)
			throw new SMTLIBException(
					"Assertion stack has been modified since last check-sat!");
	}
	
	private void modifyAssertionStack() {
		mAssertionStackModified = true;
		mModel = null;
	}
	
	private void buildModel() throws SMTLIBException {
		checkAssertionStackModified();
		if (mEngine.inconsistent()) {
			if (mDDFriendly)
				System.exit(4); // NOCHECKSTYLE
			throw new SMTLIBException("Context is inconsistent");
		}
		if (mStatus != LBool.SAT) {
			// Once we have incomplete solvers we might check mReasonUnknown...
			if (mDDFriendly)
				System.exit(9);
			throw new SMTLIBException(
					"Cannot construct model since solving did not complete");
		}
		if (mModel == null) {
			mModel = new
				de.uni_freiburg.informatik.ultimate.smtinterpol.model.Model(
					mClausifier, getTheory(), mSolverOptions.isModelsPartial());
		}
	}
	
	/**
	 * Retrieve the proof in its internal format.  Users should use
	 * {@link #getProof()} to retrieve the proof as a proof term.
	 * @return Internal proof.
	 * @throws SMTLIBException If no proof is present or the proof is found to
	 *                         to be incorrect.
	 */
	@SuppressWarnings("unused")
	public Clause retrieveProof() throws SMTLIBException {
		Clause unsat = mEngine.getProof();
		if (unsat == null) {
			if (mDDFriendly)
				System.exit(5); // NOCHECKSTYLE
			throw new SMTLIBException("Logical context not inconsistent!");
		}
		Clause proof = mSolverOptions.getProofTransformation().transform(unsat);
		if (Config.CHECK_PROP_PROOF
			&& (proof.getSize() != 0 || !new PropProofChecker().check(proof)))
				throw new SMTLIBException("Proof incorrect");
		return proof;
	}
	
	/**
	 * Get all literals currently set to true.  Note that this function might
	 * also be called if SMTInterpol is currently in an unsat state.  Then, it
	 * will simply return an empty array.
	 * @return All literals currently set to true.
	 * @throws SMTLIBException If the assertion stack is modified since the last
	 *                         clean state.
	 */
	public Term[] getSatisfiedLiterals() throws SMTLIBException {
		checkAssertionStackModified();
		return mEngine.getSatisfiedLiterals();
	}
	
	/**
	 * A helper function to be called from the debugger...
	 */
	@SuppressWarnings("unused")
	private boolean dumpInterpolationBug(
			int[] startOfSubtree, Term[] partition, Term[] ipls, int num) {
		try {
			FileWriter fw = new FileWriter("iplBug.txt");
			FormulaUnLet unlet = new FormulaUnLet();
			PrintTerm outputter = new PrintTerm();
			// Find and assert children
			int child = num - 1;
			while (child >= startOfSubtree[num]) {
				outputter.append(fw, unlet.unlet(ipls[child]));
				child = startOfSubtree[child] - 1;
				fw.append("\nand\n");
			}
			// Assert node
			outputter.append(fw, ((ApplicationTerm) partition[num]).
					getFunction().getDefinition());
			fw.append('\n');
			// Assert negated interpolant
			if (num != ipls.length) {
				fw.append("==>\n");
				outputter.append(fw, unlet.unlet(ipls[num]));
				fw.append('\n');
			}
			fw.flush();
			fw.close();
			return true;
		} catch (IOException eioe) {
			eioe.printStackTrace();
			return false;
		}
	}
	
	@Override
	public Iterable<Term[]> checkAllsat(final Term[] input) {
		final Literal[] lits = new Literal[input.length];
		for (int i = 0; i < input.length; ++i) {
			if (input[i].getSort() != getTheory().getBooleanSort())
				throw new SMTLIBException("AllSAT over non-Boolean");
			lits[i] = mClausifier.getCreateLiteral(input[i]);
		}
		return new Iterable<Term[]>() {
			
			@Override
			public Iterator<Term[]> iterator() {
				return mEngine.new AllSatIterator(lits, input);
			}
		};
	}
	
	@Override
	public Term[] findImpliedEquality(Term[] x, Term[] y)
		throws SMTLIBException, UnsupportedOperationException {
		if (x.length != y.length)
			throw new SMTLIBException("Different number of x's and y's");
		if (x.length < 2)
			throw new SMTLIBException("Need at least two elements to find equality");
		for (int i = 0; i < x.length; ++i)
			if (!x[i].getSort().isNumericSort() 
					|| !y[i].getSort().isNumericSort())
				throw new SMTLIBException("Only numeric types supported");
		LBool isSat = checkSat();
		if (isSat == LBool.UNSAT)
			throw new SMTLIBException("Context is inconsistent!");
		// TODO: If we get unknown, we can nevertheless try.  But quick-check
		//       on numerals won't work since it produces a really dull model
		//       since it does not allow pivoting
//		if (isSat == LBool.UNKNOWN)
//			// We cannot even prove satisfiability of the context.  No chance to
//			// prove inductivity of an equality!
//			return new Term[0];
		Term[] terms = new Term[x.length + y.length];
		System.arraycopy(x, 0, terms, 0, x.length);
		System.arraycopy(y, 0, terms, x.length, y.length);
		Map<Term, Term> vals = getValue(terms);
		Rational x0 = (Rational) ((ConstantTerm) vals.get(x[0])).getValue();
		Rational y0 = (Rational) ((ConstantTerm) vals.get(y[0])).getValue();
		Rational x1 = null, y1 = null;
		for (int i = 1; i < x.length; ++i) {
			x1 = (Rational) ((ConstantTerm) vals.get(x[i])).getValue();
			y1 = (Rational) ((ConstantTerm) vals.get(y[i])).getValue();
			if (x1.equals(x0)) {
				if (!y1.equals(y0))
					// There is no implied equality!
					return new Term[0];
			} else
				break;
		}
		Rational xdiff = x0.sub(x1);
		if (xdiff.equals(Rational.ZERO))
			// There is no implied equality
			return new Term[0];
		Rational a = y0.subdiv(y1, xdiff);
		Rational b = Rational.ONE;
		Rational c = y0.mul(x1).subdiv(x0.mul(y1), xdiff);
		Sort s = x[0].getSort();
		// Check for integers
		if (x[0].getSort().getName().equals("Int")
				&& y[0].getSort().getName().equals("Int")) {
			if (!a.isIntegral()) {
				BigInteger denom = a.denominator();
				a = a.mul(denom);
				b = b.mul(denom);
				c = c.mul(denom);
			}
			if (!c.isIntegral()) {
				BigInteger denom = c.denominator();
				a = a.mul(denom);
				b = b.mul(denom);
				c = c.mul(denom);
			}
		} else if (s.getName().equals("Int"))
			s = sort("Real");
		Term at = a.toTerm(s), bt = b.toTerm(s), ct = c.toTerm(s);
		// Check implication
		if (mSolverOptions.getCheckType() == CheckType.FULL) {
		    // This version only works with full checks.  If we forbid case
		    // splits, we cannot refute the disjunction created by this method.
    		Term[] disj = new Term[x.length];
    		for (int i = 0; i < x.length; ++i)
    			disj[i] = term("not", term("=", term("*", at, x[i]),
    						term("+", term("*", bt, y[i]), ct)));
    		try {
    			push(1);
    			assertTerm(term("or", disj));
    			LBool isImplied = checkSat();
    			if (isImplied != LBool.UNSAT)
    				return new Term[] {};
    		} finally {
    			pop(1);
    		}
		} else { 
        	// This method works for all modes
        	for (int i = 0; i < x.length; ++i) {
        		Term neq = term("not", term("=", term("*", at, x[i]),
        				term("+", term("*", bt, y[i]), ct)));
        		try {
        			push(1);
        			assertTerm(neq);
        			LBool isImplied = checkSat();
        			if (isImplied != LBool.UNSAT)
        				return new Term[] {};
        		} finally {
        			pop(1);
        		}
        	}
		}
		return new Term[] {at, bt, ct};
	}

	@Override
	public void declareFun(String fun, Sort[] paramSorts, Sort resultSort)
		throws SMTLIBException {
		Sort realSort = resultSort.getRealSort();
		if (realSort.isArraySort()
				&& realSort.getArguments()[0] == getTheory().getBooleanSort())
			throw new UnsupportedOperationException(
					"SMTInterpol does not support Arrays with Boolean indices");
		super.declareFun(fun, paramSorts, resultSort);
	}
	
	private final boolean getBooleanOption(String option) {
		return ((Boolean) mOptions.get(option)).booleanValue();
	}

//	@Override
//	public void exit() {
//		if (mTimer != null) {
//			mTimer.cancel();
//			mTimer = null;
//		}
//		super.exit();
//	}

	public OptionMap getOptionMap() {
		return mOptions;
	}

}
