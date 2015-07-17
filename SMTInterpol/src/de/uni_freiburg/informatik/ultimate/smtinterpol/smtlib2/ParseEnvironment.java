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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;

import de.uni_freiburg.informatik.ultimate.logic.IRAConstantFormatter;
import de.uni_freiburg.informatik.ultimate.logic.PrintTerm;
import de.uni_freiburg.informatik.ultimate.logic.SMTLIBException;
import de.uni_freiburg.informatik.ultimate.logic.Script;
import de.uni_freiburg.informatik.ultimate.logic.Term;
import de.uni_freiburg.informatik.ultimate.smtinterpol.Config;
import de.uni_freiburg.informatik.ultimate.smtinterpol.option.FrontEndOptions;
import de.uni_freiburg.informatik.ultimate.smtinterpol.option.OptionMap;
import de.uni_freiburg.informatik.ultimate.smtinterpol.util.MySymbolFactory;

public class ParseEnvironment {
	final Script      mScript;
	private File mCwd = null;
	// What to do when script exits
	private ExitHook mExitHook;
	// Initialize this lazily.
	private Deque<Long> mTiming;
	private boolean mContinueOnError = !Config.COMPETITION;

	private final FrontEndOptions mOptions;

	public ParseEnvironment(Script script, OptionMap options) {
		this(script, null, options);
	}
	
	public ParseEnvironment(Script script, ExitHook exit,
			OptionMap options) {
		mScript = script;
		mExitHook = exit;
		mOptions = options.getFrontEndOptions();
		if (!mOptions.isFrontEndActive())
			throw new IllegalArgumentException("Front End not active!");
	}
	
	public Script getScript() {
		return mScript;
	}

	static boolean convertSexp(StringBuilder sb, Object o, int level) {
        if (o instanceof Object[]) {
        	if (Config.RESULTS_ONE_PER_LINE && level > 0) {
        		sb.append(System.getProperty("line.separator"));
        		for (int i = 0; i < level; ++i)
        			sb.append(' ');
        	}
            sb.append('(');
            Object[] array = (Object[])o;
            boolean subarray = false;
            String sep = "";
            for (Object el : array) {
                sb.append(sep);
                subarray |= convertSexp(sb, el, level + Config.INDENTATION);
                sep = " ";
            }
            if (subarray && Config.RESULTS_ONE_PER_LINE) {
        		sb.append(System.getProperty("line.separator"));
        		for (int i = 0; i < level; ++i)
        			sb.append(' ');
        	}
            sb.append(')');
            return true;
        } else
            sb.append(o);
        return false;
	}
	
	public void parseScript(String filename) throws SMTLIBException {
		File oldcwd = mCwd;
		Reader reader;
		try {
			if (filename.equals("<stdin>")) {
				reader = new InputStreamReader(System.in);
			} else {
				File script = new File(filename);
				if (!script.isAbsolute())
					script = new File(mCwd, filename);
				mCwd = script.getParentFile();
				try {
					reader = new FileReader(script);
				} catch (FileNotFoundException ex) {
					throw new SMTLIBException("File not found: " + filename);
				}
			}
			parseStream(reader, filename);
		} finally {
			mCwd = oldcwd;
		}
	}
	
	public void parseStream(Reader reader, String streamname)
	    throws SMTLIBException {
		MySymbolFactory symfactory = new MySymbolFactory();
		Lexer lexer = new Lexer(reader);
		lexer.setSymbolFactory(symfactory);
		Parser parser = new Parser(lexer, symfactory);
		parser.setFileName(streamname);
		parser.setParseEnvironment(this);
		try {
			parser.parse();
		} catch (Exception ex) {
			System.err.println("Unexpected Exception: " + ex);
			throw new SMTLIBException(ex);
		}
	}
	
	public void include(String filename) throws SMTLIBException {
		ExitHook oldexit = mExitHook;
		mExitHook = new ExitHook() {
			@Override
			public void exitHook() {
				/* ignore exit */
			}
		};
		File oldcwd = mCwd;
		parseScript(filename);
		mCwd = oldcwd;
		mExitHook = oldexit;
	}
	
	public void printSuccess() {
		if (mOptions.isPrintSuccess()) {
			PrintWriter out = mOptions.getOutChannel();
			out.println("success");
			out.flush();
		}
	}
	
	public void printError(String message) {
		PrintWriter out = mOptions.getOutChannel();
		out.print("(error \"");
		out.print(message);
		out.println("\")");
		out.flush();
		if (!mContinueOnError)
			System.exit(1);
	}
	
	public void printValues(Map<Term, Term> values) {
		PrintTerm pt = new PrintTerm();
		PrintWriter out = mOptions.getOutChannel();
		out.print('(');
		String sep = "";
		String itemSep = Config.RESULTS_ONE_PER_LINE 
				? System.getProperty("line.separator") + " " : " "; 
		for (Map.Entry<Term, Term> me : values.entrySet()) {
			out.print(sep);
			out.print('(');
			pt.append(out, me.getKey());
			out.print(' ');
			pt.append(out, me.getValue().getTheory().getLogic().isIRA()
					? new IRAConstantFormatter().transform(me.getValue())
							: me.getValue());
			out.print(')');
			sep = itemSep;
		}
		out.println(')');
		out.flush();
	}
	
	public void printResponse(Object response) {
		PrintWriter out = mOptions.getOutChannel();
		if (!mOptions.isPrintTermsCSE()) {
			if (response instanceof Term) {
				new PrintTerm().append(out, (Term) response);
				out.println();
				out.flush();
				return;
			} else if (response instanceof Term[]) {
				printTermResponse((Term[])response);
				out.flush();
				return;
			}
		}
		if (response instanceof Object[]) {
			StringBuilder sb = new StringBuilder();
			convertSexp(sb, response, 0);
			out.println(sb.toString());
		} else if (response instanceof Iterable) {
			Iterable<?> it = (Iterable<?>) response;
			out.print("(");
			for (Object o : it)
				printResponse(o);
			out.println(")");
		} else
			out.println(response);
		out.flush();
	}
	
	public void printInfoResponse(String info, Object response) {
		PrintWriter out = mOptions.getOutChannel();
		StringBuilder sb = new StringBuilder();
		sb.append('(').append(info).append(' ');
		convertSexp(sb, response, 0);
		out.println(sb.append(')').toString());
		out.flush();
	}
	
	/**
	 * Direct printing of a term array response.  This function is introduced to
	 * satisfy the requirement of the SMTLIB standard for the get-assertions
	 * command.  We have to print the terms "as they are asserted", i.e.,
	 * without introducing let terms via cse.
	 * @param response The response to print.
	 */
	public void printTermResponse(Term[] response) {
		StringBuilder sb = new StringBuilder();
		PrintTerm pt = new PrintTerm();
		sb.append('(');
        String sep = "";
        String itemSep = Config.RESULTS_ONE_PER_LINE 
        		? System.getProperty("line.separator") + " " : " ";
        for (Term t : response) {
            sb.append(sep);
            pt.append(sb, t);
            sep = itemSep;
        }
        sb.append(')');
		mOptions.getOutChannel().println(sb.toString());
		mOptions.getOutChannel().flush();
	}

	public void exit() {
		if (mExitHook == null) {
			mScript.exit();
			Runtime.getRuntime().exit(0);
		} else
			mExitHook.exitHook();
	}
	
	public void setInfo(String info, Object value) {
		if (info.equals(":error-behavior")) {
			if ("immediate-exit".equals(value))
				mContinueOnError = false;
			else if ("continued-execution".equals(value))
				mContinueOnError = true;
		}
		mScript.setInfo(info, value);
	}
	
	public Object getInfo(String info) {
		if (info.equals(":error-behavior"))
			return mContinueOnError ? "continued-execution" : "immediate-exit";
		return mScript.getInfo(info);
	}

	public void startTiming() {
		if (mTiming == null)
			mTiming = new ArrayDeque<Long>();
		mOptions.getOutChannel().print('(');
		mTiming.push(System.nanoTime());
	}
	
	public void endTiming() {
		long old = mTiming.pop();
		long duration = System.nanoTime() - old;
		double secs = duration / 1000000000.0; // NOCHECKSTYLE
		mOptions.getOutChannel().printf((Locale) null, " :time %.3f)", secs);
		mOptions.getOutChannel().println();
		mOptions.getOutChannel().flush();
	}
	
	public boolean isContinueOnError() {
		return mContinueOnError;
	}
}
