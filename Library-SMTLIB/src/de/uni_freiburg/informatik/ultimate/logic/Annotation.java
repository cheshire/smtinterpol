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
package de.uni_freiburg.informatik.ultimate.logic;

import java.util.Arrays;

/**
 * Representation of an annotation in a formula.  Note that the keys ":named",
 * and ":pattern" have special meaning as described in the SMTLIB version 2
 * standard.
 * @author Jochen Hoenicke
 */
public class Annotation {
	String mKey;
	/** 
	 * The value of the annotation.
	 * In concrete syntax an annotation is an sexpr, i.e., there are three
	 * cases:
	 * <ul>
	 * <li>symbol:  this is represented by a String object.</li>
	 * <li>constant:  this is represented by a ConstantTerm object.</li>
	 * <li>(sexpr*):  this is represented by an Object[] array.</li>
	 * </ul>
	 * 
	 * However some annotations are preparsed.  E.g. the :pattern annotation
	 * is represented as an array of Terms.
	 */
	Object mValue;
	
	public Annotation(String key, Object value) {
		if (key == null)
			throw new SMTLIBException("Empty annotations not allowed!");
		mKey = key;
		mValue = value;
	}

	public String getKey() {
		return mKey;
	}

	public Object getValue() {
		return mValue;
	}
	
	public boolean equals(Object obj) {
		if (obj instanceof Annotation) {
			Annotation annot = (Annotation) obj;
			return mKey.equals(annot.mKey)
				&& (mValue == null ? annot.mValue == null
					: mValue instanceof Object[] 
					&& annot.mValue instanceof Object[]
						? Arrays.deepEquals((Object[]) mValue, 
										  (Object[]) annot.mValue)
						: mValue.equals(annot.mValue));
		}
		return false;
	}
	
	public int hashCode() {
		return mKey.hashCode() * 31 
			+ (mValue == null ? 0
			 : mValue instanceof Object[]
					 ? Arrays.deepHashCode((Object[]) mValue)
							 : mValue.hashCode());
	}
}
