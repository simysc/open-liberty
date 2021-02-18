/*******************************************************************************
 * Copyright (c) 2021 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package test.jdbc.heritage;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;

import com.ibm.websphere.simplicity.ShrinkHelper;

import componenttest.annotation.Server;
import componenttest.annotation.TestServlet;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.topology.impl.LibertyServer;
import componenttest.topology.utils.FATServletClient;
import test.jdbc.heritage.app.JDBCHeritageTestServlet;

@RunWith(FATRunner.class)
public class HeritageJDBCTest extends FATServletClient {
    @Server("com.ibm.ws.jdbc.heritage")
    @TestServlet(servlet = JDBCHeritageTestServlet.class, path = "heritageApp/JDBCHeritageTestServlet")
    public static LibertyServer server;

    @BeforeClass
    public static void setUp() throws Exception {
        WebArchive heritageApp = ShrinkWrap.create(WebArchive.class, "heritageApp.war")
                        .addPackage("test.jdbc.heritage.app");
        ShrinkHelper.exportDropinAppToServer(server, heritageApp);

        server.addInstalledAppForValidation("heritageApp");

        server.startServer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.stopServer();
    }
}
