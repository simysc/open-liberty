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
package io.openliberty.cdi40.internal.fat.startupEvents.ear.ejb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.openliberty.cdi40.internal.fat.startupEvents.sharedLib.AbstractObserver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;

/**
 * The expected order of events is...
 *
 * observeInit
 * observeEjbPostConstruct
 * c_observeStartup1
 * a_observeStartup2
 * b_observeStartup3
 * test
 * observeEjbPreDestroy
 * observeShutdown
 * observeDestroy
 */
@ApplicationScoped
public class EjbApplicationScopedBean extends AbstractObserver {

    private volatile boolean postConstructObserved = false;
    private volatile boolean preDestroyObserved = false;

    @Override
    public void observeInit(@Observes @Initialized(ApplicationScoped.class) Object event) {
        super.observeInit(event);
        //@Initialized(ApplicationScoped.class) should be seen before EJB PostConstruct
        assertFalse(logMsg("@Initialized(ApplicationScoped.class) was observed after EJB PostConstruct"), postConstructObserved);
    }

    public void observeEjbPostConstruct() {
        trace("observeEjbPostConstruct");
        this.postConstructObserved = true;

        //EJB PostConstruct should be seen after @Initialized(ApplicationScoped.class)
        assertTrue(logMsg("EJB PostConstruct observed before @Initialized(ApplicationScoped.class)"), initObserved);
        //EJB PostConstruct should be seen before any request
        assertFalse(logMsg("EJB PostConstruct was observed after test"), testRequested);
    }

    @Override
    public void test() {
        super.test();

        //test should be seen before EJB PreDestroy
        assertFalse(logMsg("EJB PreDestroy was observed after Startup"), preDestroyObserved);
    }

    public void observeEjbPreDestroy() {
        trace("observeEjbPreDestroy");
        this.preDestroyObserved = true;

        //EJB PreDestroy should be seen after any test request
        assertTrue(logMsg("EJB PreDestroy was observed before test"), testRequested);
        //EJB PreDestroy should be seen before Shutdown
        assertFalse(logMsg("EJB PreDestroy was observed after Shutdown"), shutdownObserved);
    }

}
