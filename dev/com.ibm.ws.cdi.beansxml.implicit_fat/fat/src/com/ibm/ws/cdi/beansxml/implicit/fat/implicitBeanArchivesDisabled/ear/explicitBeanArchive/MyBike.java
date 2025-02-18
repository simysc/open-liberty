/*******************************************************************************
 * Copyright (c) 2015, 2021 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.cdi.beansxml.implicit.fat.implicitBeanArchivesDisabled.ear.explicitBeanArchive;

import javax.enterprise.context.ApplicationScoped;

/**
 *
 */
@ApplicationScoped
public class MyBike {

    public static final String BIKE = "Bike";

    public String getMyBike() {
        return BIKE;
    }
}
