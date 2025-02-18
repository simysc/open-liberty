/*******************************************************************************
 * Copyright (c) 2012, 2021 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package io.openliberty.el.internal.cdi;

import javax.el.ELContext;
import javax.el.ELException;
import javax.el.ExpressionFactory;
import javax.el.MethodExpression;
import javax.el.ValueExpression;

public interface ELFactoryWrapperForCDI {

    public void setExpressionFactory(ExpressionFactory arg0);

    public Object coerceToType(Object arg0, Class<?> arg1) throws ELException;

    public MethodExpression createMethodExpression(ELContext arg0, String arg1, Class<?> arg2, Class<?>[] arg3) throws ELException, NullPointerException;

    public ValueExpression createValueExpression(Object arg0, Class<?> arg1);

    public ValueExpression createValueExpression(ELContext arg0, String arg1, Class<?> arg2) throws NullPointerException, ELException;
}
