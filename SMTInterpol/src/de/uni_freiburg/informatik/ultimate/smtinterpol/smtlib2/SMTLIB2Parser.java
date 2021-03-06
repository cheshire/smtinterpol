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

import de.uni_freiburg.informatik.ultimate.logic.SMTLIBException;
import de.uni_freiburg.informatik.ultimate.logic.Script;
import de.uni_freiburg.informatik.ultimate.smtinterpol.IParser;

public class SMTLIB2Parser implements IParser {

	@Override
	public int run(Script solver, String filename) {
		if (filename == null)
			filename = "<stdin>";
        ParseEnvironment parseEnv = new ParseEnvironment(solver);
        try {
        	// Have to carry this option through
        	parseEnv.setOption(":regular-output-channel",
        			solver.getOption(":regular-output-channel"));
        	parseEnv.setOption(":print-success",
        			solver.getOption(":print-success"));
        	parseEnv.setOption(":print-terms-cse",
        			solver.getOption(":print-terms-cse"));
        	parseEnv.parseScript(filename);
        } catch (SMTLIBException se) {
        	parseEnv.printError(se.getMessage());
        }
		return 0;
	}

}
