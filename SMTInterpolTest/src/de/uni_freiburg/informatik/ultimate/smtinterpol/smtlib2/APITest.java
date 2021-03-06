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

import java.math.BigInteger;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import de.uni_freiburg.informatik.ultimate.logic.Logics;
import de.uni_freiburg.informatik.ultimate.logic.ReasonUnknown;
import de.uni_freiburg.informatik.ultimate.logic.SMTLIBException;
import de.uni_freiburg.informatik.ultimate.logic.Sort;
import de.uni_freiburg.informatik.ultimate.logic.Script.LBool;
import de.uni_freiburg.informatik.ultimate.smtinterpol.Config;
import de.uni_freiburg.informatik.ultimate.smtinterpol.util.TestCaseWithLogger;

/**
 * A basic test case for the API of SMTInterpol.
 * @author Juergen Christ
 */
@RunWith(JUnit4.class)
public class APITest extends TestCaseWithLogger {
	
	private static class TerminationCounter implements TerminationRequest {

		private boolean mStop = false;
		
		@Override
		public boolean isTerminationRequested() {
			return mStop;
		}
		
		public void setStop(boolean stop) {
			mStop = stop;
		}
		
	}
	public APITest() {
		super(Level.TRACE);
	}
	/**
	 * Test what is possible without a logic...
	 */
	@Test
	public void testNoLogic() {
		SMTInterpol solver = new SMTInterpol(Logger.getRootLogger());
		solver.setOption(":interactive-mode", true);
		try {
			solver.sort("Bool");
			Assert.fail("Could retrieve sort Bool without logic");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver.assertTerm(solver.term("true"));
			Assert.fail("Could assert true without logic");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver.checkSat();
			Assert.fail("Could check satisfiability without logic");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver.push(1);
			Assert.fail("Could push without logic");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver.pop(1);
			Assert.fail("Could pop without logic");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver.assertTerm(solver.term("true"));
			Assert.fail("Could assert true without logic");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver.getAssertions();
			Assert.fail("Could get assertions without logic");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
	}

	@Test
	public void testFaultyLogic() {
		SMTInterpol solver = new SMTInterpol(Logger.getRootLogger());
		try {
			solver.setLogic("TestLogicThatDoesNotExist");
			Assert.fail("Could set strange logic!");
		} catch (UnsupportedOperationException expected) {
			System.err.println(expected.getMessage());
		}
	}
	
	@Test
	public void testPushPop() {
		SMTInterpol solver = new SMTInterpol(Logger.getRootLogger());
		solver.setLogic(Logics.QF_LIA);
		try {
			solver.push(3);// NOCHECKSTYLE
		} catch (SMTLIBException eUnexpected) {
			Assert.fail(eUnexpected.getMessage());
		}
		try {
			solver.pop(2);// NOCHECKSTYLE
		} catch (SMTLIBException eUnexpected) {
			Assert.fail(eUnexpected.getMessage());
		}
		try {
			solver.pop(2);// NOCHECKSTYLE
			Assert.fail("Could pop 4 levels after pushing 3");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
	}

	@Test
	public void testSetOptionLate() {
		SMTInterpol solver = new SMTInterpol(Logger.getRootLogger());
		solver.setLogic(Logics.QF_LIA);
		try {
			solver.setOption(":interactive-mode", true);
			Assert.fail("Could activate interactive mode after setting logic");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver.setOption(":produce-proofs", true);
			Assert.fail("Could activate proof production after setting logic");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver.setOption(":produce-unsat-cores", true);
			Assert.fail("Could activate unsat core production after setting logic");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver.setOption(":produce-assignments", true);
			Assert.fail("Could activate assignment production after setting logic");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver.setOption(":interpolant-check-mode", true);
			Assert.fail("Could activate interpolant check mode after setting logic");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver.setOption(":unsat-core-check-mode", true);
			Assert.fail("Could activate unsat core check mode after setting logic");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
	}
	
	@Test
	public void testSetLogicTwice() {
		SMTInterpol solver = new SMTInterpol(Logger.getRootLogger());
		solver.setLogic(Logics.QF_LIA);
		try {
			solver.setLogic(Logics.QF_LIA);
			Assert.fail("Could set logic a second time");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver.declareFun("x", new Sort[0], solver.sort("Int"));
			solver.assertTerm(solver.term("=", 
					solver.term("x"), solver.numeral(BigInteger.ZERO)));
			solver.checkSat();
		} catch (SMTLIBException eUnexpected) {
			Assert.fail(eUnexpected.getMessage());
		}
	}
	
	@Test
	public void testTermAssertion() {
		SMTInterpol solver1 = new SMTInterpol(Logger.getRootLogger());
		SMTInterpol solver2 = new SMTInterpol(Logger.getRootLogger());
		solver1.setLogic(Logics.QF_LIA);
		solver2.setLogic(Logics.QF_LIA);
		solver1.declareFun("x", new Sort[0], solver1.sort("Int"));
		solver2.declareFun("x", new Sort[0], solver2.sort("Int"));
		try {
			solver1.assertTerm(solver2.term("=",
					solver2.term("x"), solver2.numeral(BigInteger.ZERO)));
			Assert.fail("Could assert term created by different solver");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver1.assertTerm(solver1.term("x"));
			Assert.fail("Could assert non-Boolean term");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		if (Config.STRONG_USAGE_CHECKS) {
			try {
				solver1.assertTerm(solver1.term("=", solver1.term("x"),
						solver1.variable("y", solver1.sort("Int"))));
				Assert.fail("Could assert open term");
			} catch (SMTLIBException expected) {
				System.err.println(expected.getMessage());
			}
		} else {
			System.out.println("Skipping closed formula test since strong usage checks are disabled");// NOCHECKSTYLE
		}
	}
	
	@Test
	public void testUndeclared() {
		SMTInterpol solver = new SMTInterpol(Logger.getRootLogger());
		solver.setLogic(Logics.QF_LIA);
		try {
			solver.term("x");
			Assert.fail("Could create undeclared term");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
		try {
			solver.variable("x", solver.sort("NOTEXISTENT"));
			Assert.fail("Could create variable with unknown sort");
		} catch (SMTLIBException expected) {
			System.err.println(expected.getMessage());
		}
	}
	
	@Test
	public void cancellationRequest() {
		TerminationCounter tc = new TerminationCounter();
		SMTInterpol solver = new SMTInterpol(Logger.getRootLogger(), tc);
		solver.setLogic(Logics.CORE);
		Sort bool = solver.sort("Bool");
		solver.declareFun("P", new Sort[0], bool);
		solver.declareFun("Q", new Sort[0], bool);
		solver.declareFun("R", new Sort[0], bool);
		solver.push(1);
		solver.assertTerm(solver.term(
				"or", solver.term("P"), solver.term("Q")));
		solver.assertTerm(solver.term(
				"or", solver.term("P"), solver.term("R")));
		solver.assertTerm(solver.term(
				"or", solver.term("not", solver.term("P")),
				      solver.term("not", solver.term("Q"))));
		solver.assertTerm(solver.term(
				"or", solver.term("not", solver.term("P")),
				      solver.term("not", solver.term("R"))));
		solver.assertTerm(solver.term(
				"or", solver.term("not", solver.term("P")),
				      solver.term("Q"), solver.term("R")));
		LBool isSat = solver.checkSat();
		Assert.assertSame(LBool.SAT, isSat);
		solver.pop(1);
		tc.setStop(true);
		solver.push(1);
		solver.assertTerm(solver.term(
				"or", solver.term("P"), solver.term("Q")));
		solver.assertTerm(solver.term(
				"or", solver.term("P"), solver.term("R")));
		solver.assertTerm(solver.term(
				"or", solver.term("not", solver.term("P")),
				      solver.term("not", solver.term("Q"))));
		solver.assertTerm(solver.term(
				"or", solver.term("not", solver.term("P")),
				      solver.term("not", solver.term("R"))));
		solver.assertTerm(solver.term(
				"or", solver.term("not", solver.term("P")),
				      solver.term("Q"), solver.term("R")));
		solver.assertTerm(solver.term(
				"or", solver.term("P"), solver.term("not", solver.term("Q")),
						solver.term("not", solver.term("R"))));
		isSat = solver.checkSat();
		Assert.assertSame(LBool.UNKNOWN, isSat);
		ReasonUnknown ru = (ReasonUnknown) solver.getInfo(":reason-unknown");
		Assert.assertSame(ReasonUnknown.CANCELLED, ru);
		// Check monotonicity of checker
		tc.setStop(false);
		isSat = solver.checkSat();
		Assert.assertSame(LBool.UNKNOWN, isSat);
		ru = (ReasonUnknown) solver.getInfo(":reason-unknown");
		Assert.assertSame(ReasonUnknown.CANCELLED, ru);
		solver.pop(1);
		isSat = solver.checkSat();
		Assert.assertSame(LBool.SAT, isSat);
	}
}
