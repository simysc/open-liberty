/*******************************************************************************
 * Copyright (c) 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package io.openliberty.cdi.internal.core.interceptors.app;

import java.util.ArrayList;
import java.util.List;

import javax.enterprise.context.RequestScoped;

/**
 * Allows various test methods to record that they're executing
 * <p>
 * Request scoped so that a new instance is created for each test
 */
@RequestScoped
public class ExecutionRecorder {

    private List<String> records = new ArrayList<>();

    public void record(String execution) {
        records.add(execution);
    }

    public List<String> getExecutions() {
        return new ArrayList<>(records);
    }

}
