/******************************************************************************* 
 * Copyright (c) 2010 Red Hat, Inc. 
 * Distributed under license by Red Hat, Inc. All rights reserved. 
 * This program is made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.eclipse.org/legal/epl-v10.html 
 * 
 * Contributors: 
 * Red Hat, Inc. - initial API and implementation 
 ******************************************************************************/ 
package org.jboss.tools.common.el.core.ca;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jdt.core.IJavaElement;
import org.jboss.tools.common.text.TextProposal;

/**
 * Class to store proposals generated by ELResolver implementers
 *  
 * @author Victor Rubezhny
 */
public class ELTextProposal extends TextProposal {
	private Set<IJavaElement> allElements;

	/**
	 * Adds a Java Element for the proposal
	 * 
	 * @param element
	 */
	public void addJavaElement(IJavaElement element) {
		if (this.allElements == null) {
			this.allElements = new HashSet<IJavaElement>();
		}
		this.allElements.add(element);
	}

	/**
	 * returns all the Java elements for the proposal
	 * @return
	 */
	public IJavaElement[] getAllJavaElements() {
		if (this.allElements == null || this.allElements.size() == 0) {
			return new IJavaElement[0];
		}
		return (IJavaElement[])this.allElements.toArray(new IJavaElement[this.allElements.size()]);
	}
}
