/*******************************************************************************
 * Copyright (c) 2021,2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package test.jakarta.concurrency.web;

import static jakarta.enterprise.concurrent.ContextServiceDefinition.ALL_REMAINING;
import static jakarta.enterprise.concurrent.ContextServiceDefinition.APPLICATION;
import static jakarta.enterprise.concurrent.ContextServiceDefinition.SECURITY;
import static jakarta.enterprise.concurrent.ContextServiceDefinition.TRANSACTION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TransferQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.annotation.Resource;
import jakarta.ejb.EJBException;
import jakarta.enterprise.concurrent.ContextService;
import jakarta.enterprise.concurrent.ContextServiceDefinition;
import jakarta.enterprise.concurrent.LastExecution;
import jakarta.enterprise.concurrent.ManageableThread;
import jakarta.enterprise.concurrent.ManagedExecutorDefinition;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorDefinition;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.enterprise.concurrent.ManagedTask;
import jakarta.enterprise.concurrent.ManagedThreadFactory;
import jakarta.enterprise.concurrent.ManagedThreadFactoryDefinition;
import jakarta.enterprise.concurrent.ZonedTrigger;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.junit.Test;

import componenttest.app.FATServlet;
import test.context.list.ListContext;
import test.context.location.ZipCode;
import test.context.timing.Timestamp;

@ContextServiceDefinition(name = "java:app/concurrent/appContextSvc",
                          propagated = APPLICATION,
                          cleared = { TRANSACTION, SECURITY },
                          unchanged = ALL_REMAINING)
@ContextServiceDefinition(name = "java:module/concurrent/ZLContextSvc",
                          propagated = { ZipCode.CONTEXT_NAME, ListContext.CONTEXT_NAME },
                          cleared = "Priority",
                          unchanged = { APPLICATION, TRANSACTION })
@ManagedExecutorDefinition(name = "java:module/concurrent/executor5",
                           context = "java:module/concurrent/ZLContextSvc",
                           hungTaskThreshold = 300000,
                           maxAsync = 1)
@ManagedScheduledExecutorDefinition(name = "java:comp/concurrent/executor6",
                                    context = "java:app/concurrent/appContextSvc",
                                    hungTaskThreshold = 360000,
                                    maxAsync = 2)
@ManagedThreadFactoryDefinition(name = "java:app/concurrent/lowPriorityThreads",
                                context = "java:app/concurrent/appContextSvc",
                                priority = 3)
@ManagedExecutorDefinition(name = "java:global/concurrent/executor7",
                           maxAsync = 3)

// TODO remove the following once we can enable them in application.xml
@ContextServiceDefinition(name = "java:app/concurrent/dd/ZPContextService",
                          cleared = ListContext.CONTEXT_NAME,
                          propagated = { ZipCode.CONTEXT_NAME, "Priority" },
                          unchanged = APPLICATION)
@ManagedExecutorDefinition(name = "java:app/concurrent/dd/ZPExecutor",
                           context = "java:app/concurrent/dd/ZPContextService",
                           hungTaskThreshold = 420000,
                           maxAsync = 2)
@ManagedScheduledExecutorDefinition(name = "java:global/concurrent/dd/ScheduledExecutor",
                                    context = "java:comp/DefaultContextService",
                                    hungTaskThreshold = 410000,
                                    maxAsync = 1)
@ManagedThreadFactoryDefinition(name = "java:app/concurrent/dd/ThreadFactory",
                                context = "java:app/concurrent/appContextSvc",
                                priority = 4)
@SuppressWarnings("serial")
@WebServlet("/*")
public class ConcurrencyTestServlet extends FATServlet {

    // Maximum number of nanoseconds to wait for a task to finish.
    private static final long TIMEOUT_NS = TimeUnit.MINUTES.toNanos(2);

    @Resource(lookup = "concurrent/context2")
    ContextService contextSvc2;

    @Resource(name = "java:module/env/concurrent/threadFactoryRef")
    ManagedThreadFactory defaultThreadFactory;

    @Resource(lookup = "concurrent/executor1")
    ManagedExecutorService executor1;

    @Resource(name = "java:comp/env/concurrent/executor3Ref", lookup = "concurrent/executor3")
    ManagedExecutorService executor3;

    @Resource(name = "java:global/env/concurrent/executor4Ref", lookup = "concurrent/executor4")
    ManagedScheduledExecutorService executor4;

    @Resource(lookup = "java:module/concurrent/executor5")
    ManagedExecutorService executor5;

    @Resource(lookup = "java:comp/concurrent/executor6")
    ManagedScheduledExecutorService executor6;

    @Resource(lookup = "java:comp/DefaultManagedThreadFactory")
    ForkJoinWorkerThreadFactory forkJoinThreadFactory;

    @Resource(lookup = "java:app/concurrent/lowPriorityThreads")
    ManagedThreadFactory lowPriorityThreads;

    private ExecutorService unmanagedThreads;

    @Override
    public void destroy() {
        unmanagedThreads.shutdownNow();
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        unmanagedThreads = Executors.newFixedThreadPool(5);

        // This EJB needs to first be used so that its ContextServiceDefinition,
        // which is relied upon by the other EJB, can be processed
        try {
            InitialContext.doLookup("java:global/ConcurrencyTestApp/ConcurrencyTestEJB/ContextServiceDefinerBean!test.jakarta.concurrency.ejb.ContextServiceDefinerBean");
        } catch (NamingException x) {
            throw new ServletException(x);
        }
    }

    /**
     * Covers the new ManagedExecutorService API methods: completedStage and failedStage,
     * ensuring propagation of context to dependent stages.
     */
    @Test
    public void testCompletedAndFailedStages() throws Exception {
        CompletionStage<Integer> stage1 = executor5.completedStage(1);
        CompletionStage<Integer> stage2 = executor5.failedStage(new IOException("Not a real error."));
        CompletionStage<Integer> stage3 = stage2.exceptionally(failure -> failure.getClass().getSimpleName().length());
        ListContext.newList();
        try {
            CompletionStage<Void> stage4 = stage3.thenAcceptBothAsync(stage1, (r3, r1) -> ListContext.add(r3 - r1));
            LinkedBlockingQueue<String> results = new LinkedBlockingQueue<String>();
            stage4.thenRunAsync(() -> results.add(ListContext.asString()));
            ListContext.newList();
            String result = results.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS);
            assertEquals("[10]", result); // "IOException".length() - 1
        } finally {
            ListContext.clear();
        }
    }

    /**
     * Look up an application-defined ContextService that is configured to propagate the
     * application component's name space. Verify that the ContextService provides access to
     * the application component's name space by attempting a lookup from a contextualized
     * completion stage action.
     */
    @Test
    public void testContextServiceDefinitionPropagatesApplicationContext() throws Exception {
        ContextService appContextSvc = InitialContext.doLookup("java:app/concurrent/appContextSvc");
        Callable<?> contextualLookup = appContextSvc.contextualCallable(() -> {
            try {
                return InitialContext.doLookup("java:app/concurrent/appContextSvc");
            } catch (NamingException x) {
                throw new CompletionException(x);
            }
        });
        Future<?> future = unmanagedThreads.submit(contextualLookup);
        Object result = future.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);
        assertNotNull(result);
        assertTrue(result.toString(), result instanceof ContextService);
    }

    /**
     * Look up an application-defined ContextService that is configured to
     * propagate some third-party context (ZipCode and List),
     * clear other third-party context (Priority and Timestamp),
     * and leave other types of context, such as Application, unchanged.
     * Verify that the ContextService behaves as configured.
     */
    @Test
    public void testContextServiceDefinitionPropagatesThirdPartyContext() throws Exception {
        ContextService contextSvc = InitialContext.doLookup("java:module/concurrent/ZLContextSvc");

        // Put some fake context onto the thread:
        Timestamp.set();
        ZipCode.set(55901);
        ListContext.newList();
        ListContext.add(10);
        ListContext.add(28);
        Thread.currentThread().setPriority(7);
        Long ts0 = Timestamp.get();
        Thread.sleep(100); // ensure we progress from the current timestamp

        try {
            // Contextualize a Supplier with the above context:
            Supplier<Object[]> contextualSupplier = contextSvc.contextualSupplier(() -> {
                // The Supplier records the context
                Object lookupResult;
                try {
                    lookupResult = InitialContext.doLookup("java:app/concurrent/appContextSvc");
                } catch (NamingException x) {
                    lookupResult = x;
                }
                ListContext.add(46); // verify this change is included
                return new Object[] {
                                      lookupResult,
                                      ZipCode.get(),
                                      ListContext.asString(),
                                      Thread.currentThread().getPriority(),
                                      Timestamp.get()
                };
            });

            // Alter some of the context on the current thread
            ZipCode.set(55906);
            ListContext.newList();
            ListContext.add(5);

            // Run with the captured context:
            Object[] results = contextualSupplier.get();

            // Application context was configured to be left unchanged, so the java:app name must be found:
            if (results[0] instanceof Throwable)
                throw new AssertionError(results[0]);
            assertTrue(results[0].toString(), results[0] instanceof ContextService);

            // Zip code context was configured to be propagated
            assertEquals(Integer.valueOf(55901), results[1]);

            // List context was configured to be propagated
            assertEquals("[10, 28, 46]", results[2]);

            // Priority context was configured to be cleared
            assertEquals(Integer.valueOf(5), results[3]);

            // Timestamp context was implicitly configured to be cleared
            assertNull(results[4]);

            // Verify that context is restored on the current thread:
            assertEquals(55906, ZipCode.get());
            assertEquals("[5]", ListContext.asString());
            assertEquals(7, Thread.currentThread().getPriority());
            assertEquals(ts0, Timestamp.get());

            // Run the supplier on another thread
            CompletableFuture<Object[]> future = CompletableFuture.supplyAsync(contextualSupplier);
            results = future.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);

            // Application context was configured to be left unchanged, so the java:app name must not be found:
            assertTrue(results[0].toString(), results[0] instanceof NamingException);

            // Zip code context was configured to be propagated
            assertEquals(Integer.valueOf(55901), results[1]);

            // List context was configured to be propagated
            assertEquals("[10, 28, 46, 46]", results[2]);

            // Priority context was configured to be cleared
            assertEquals(Integer.valueOf(5), results[3]);

            // Timestamp context was implicitly configured to be cleared
            assertNull(results[4]);
        } finally {
            // Remove fake context
            Timestamp.clear();
            ZipCode.clear();
            ListContext.clear();
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        }
    }

    /**
     * Verify that the ManagedExecutorService copy(stage) and copy(CompletableFuture) methods
     * create copies that propagate thread context to dependent stages that are created from the
     * copy.
     */
    @Test
    public void testCopy() throws Exception {
        CompletableFuture<Integer> stage1 = new CompletableFuture<Integer>();
        CompletionStage<Integer> stage2 = CompletableFuture.completedStage(6);

        CompletableFuture<Integer> stage1copy = executor5.copy(stage1);
        CompletionStage<Integer> stage2copy = executor6.copy(stage2);

        try {
            ZipCode.set(55901);
            CompletableFuture<Integer> stage3 = stage1copy.thenApplyAsync(i -> ZipCode.get() + i); // 55902

            ZipCode.set(55906);
            CompletionStage<Object> stage4 = stage2copy.thenApplyAsync(i -> {
                try {
                    return InitialContext.doLookup("java:comp/concurrent/executor" + i); // executor6
                } catch (NamingException x) {
                    throw new CompletionException(x);
                }
            });

            ZipCode.set(55904);
            CompletableFuture<Integer> stage5 = stage3.thenCombine(stage4, (z, s) -> {
                return ZipCode.get() - (s instanceof ManagedScheduledExecutorService ? z : 0); // 55904 - 55902 = 2
            });

            ZipCode.clear();
            assertTrue(stage1.complete(1));
            assertEquals(Integer.valueOf(2), stage5.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
        } finally {
            ZipCode.clear();
        }
    }

    /**
     * Use currentContextExecutor to capture a snapshot of the current thread context and apply
     * it elsewhere at later points in time.
     */
    @Test
    public void testCurrentContextExecutor() throws Exception {
        ContextService contextSvc = InitialContext.doLookup("java:module/concurrent/ZLContextSvc");

        try {
            Thread.currentThread().setPriority(7);

            ZipCode.set(55901);
            Executor nwRochesterExecutor = contextSvc.currentContextExecutor();

            ZipCode.set(55902);
            Executor swRochesterExecutor = contextSvc.currentContextExecutor();

            ZipCode.set(55906);
            Thread.currentThread().setPriority(6);
            Timestamp.set();
            Long timestamp1 = Timestamp.get();

            nwRochesterExecutor.execute(() -> {
                assertEquals(55901, ZipCode.get()); // propagated
                assertEquals(null, Timestamp.get()); // cleared
                assertEquals(Thread.NORM_PRIORITY, Thread.currentThread().getPriority()); // cleared
                try {
                    assertNotNull(InitialContext.doLookup("java:module/concurrent/ZLContextSvc")); // unchanged
                } catch (NamingException x) {
                    throw new AssertionError(x);
                }
            });

            assertEquals(55906, ZipCode.get());
            assertEquals(timestamp1, Timestamp.get());
            assertEquals(6, Thread.currentThread().getPriority());

            swRochesterExecutor.execute(() -> {
                assertEquals(55902, ZipCode.get()); // propagated
                assertEquals(null, Timestamp.get()); // cleared
                assertEquals(Thread.NORM_PRIORITY, Thread.currentThread().getPriority()); // cleared
                try {
                    assertNotNull(InitialContext.doLookup("java:module/concurrent/ZLContextSvc")); // unchanged
                } catch (NamingException x) {
                    throw new AssertionError(x);
                }
            });

            assertEquals(55906, ZipCode.get());
            assertEquals(timestamp1, Timestamp.get());
            assertEquals(6, Thread.currentThread().getPriority());
        } finally {
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
            Timestamp.clear();
            ZipCode.clear();
        }
    }

    /**
     * Use a ContextService from a ContextServiceDefinition that is defined in an EJB.
     */
    @Test
    public void testEJBAnnoContextServiceDefinition() throws Exception {
        Executor bean = InitialContext.doLookup("java:global/ConcurrencyTestApp/ConcurrencyTestEJB/ExecutorBean!java.util.concurrent.Executor");
        assertNotNull(bean);
        bean.execute(() -> {
            try {
                ContextService contextSvc = InitialContext.doLookup("java:module/concurrent/ZLContextSvc");

                // Put some fake context onto the thread:
                Timestamp.set();
                ZipCode.set(55906);
                ListContext.newList();
                ListContext.add(6);
                ListContext.add(12);
                Thread.currentThread().setPriority(4);
                Long ts0 = Timestamp.get();
                Thread.sleep(100); // ensure we progress from the current timestamp

                try {
                    // Contextualize a Callable with the above context:
                    Callable<Object[]> contextualCallable = contextSvc.contextualCallable(() -> {
                        // The Callable records the context
                        Object lookupResult;
                        try {
                            lookupResult = InitialContext.doLookup("java:comp/concurrent/executor8");
                        } catch (NamingException x) {
                            throw new CompletionException(x);
                        }
                        return new Object[] {
                                              lookupResult,
                                              ZipCode.get(),
                                              ListContext.asString(),
                                              Thread.currentThread().getPriority(),
                                              Timestamp.get()
                        };
                    });

                    // Alter some of the context on the current thread
                    ZipCode.set(55902);
                    ListContext.newList();
                    ListContext.add(2);
                    Thread.currentThread().setPriority(3);

                    // Run with the captured context:
                    Object[] results;
                    try {
                        results = contextualCallable.call();
                    } catch (RuntimeException x) {
                        throw x;
                    } catch (Exception x) {
                        throw new CompletionException(x);
                    }

                    // Application context was configured to be propagated
                    assertTrue(results[0].toString(), results[0] instanceof ManagedExecutorService);

                    // Zip code context was configured to be propagated
                    assertEquals(Integer.valueOf(55906), results[1]);

                    // List context was configured to be propagated
                    assertEquals("[6, 12]", results[2]);

                    // Priority context was configured to be left unchanged
                    assertEquals(Integer.valueOf(3), results[3]);

                    // Timestamp context was configured to be cleared
                    assertNull(results[4]);

                    // Verify that context is restored on the current thread:
                    assertEquals(55902, ZipCode.get());
                    assertEquals("[2]", ListContext.asString());
                    assertEquals(3, Thread.currentThread().getPriority());
                    assertEquals(ts0, Timestamp.get());

                    // Run the supplier on another thread
                    Future<Object[]> future = unmanagedThreads.submit(contextualCallable);
                    results = future.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);

                    // Application context was configured to be propagated
                    assertTrue(results[0].toString(), results[0] instanceof ManagedExecutorService);

                    // Zip code context was configured to be propagated
                    assertEquals(Integer.valueOf(55906), results[1]);

                    // List context was configured to be propagated
                    assertEquals("[6, 12]", results[2]);

                    // Priority context was configured to be left unchanged
                    assertEquals(Integer.valueOf(Thread.NORM_PRIORITY), results[3]);

                    // Timestamp context was configured to be cleared
                    assertNull(results[4]);
                } finally {
                    // Remove fake context
                    Timestamp.clear();
                    ZipCode.clear();
                    ListContext.clear();
                    Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
                }

            } catch (ExecutionException | InterruptedException | NamingException | TimeoutException x) {
                throw new EJBException(x);
            }
        });
    }

    /**
     * Use a ManagedExecutorService from a ManagedExecutorDefinition that is defined in an EJB.
     */
    @Test
    public void testEJBAnnoManagedExecutorDefinition() throws Exception {
        Exchanger<Long> exchanger = new Exchanger<Long>();
        Supplier<Object> runTestAsEJB = () -> {
            // Enforcement of maxAsync=1
            try {
                long threadId = Thread.currentThread().getId();
                Long otherThreadId = exchanger.exchange(threadId, 1, TimeUnit.SECONDS);
                fail("This thread (" + threadId + ") and other thread (" + otherThreadId +
                     ") are running at the same time in violation of maxAsync=1 of ManagedExecutorDefinition");
            } catch (InterruptedException x) {
                throw new CompletionException(x);
            } catch (TimeoutException x) {
                // expected
            }

            // Third-party ZipCode context must be cleared
            assertEquals(0, ZipCode.get());

            // Application context must be propagated
            try {
                return InitialContext.doLookup("java:comp/concurrent/executor8");
            } catch (NamingException x) {
                throw new CompletionException(x);
            }
        };

        Executor bean = InitialContext.doLookup("java:global/ConcurrencyTestApp/ConcurrencyTestEJB/ExecutorBean!java.util.concurrent.Executor");
        assertNotNull(bean);
        bean.execute(() -> {
            try {
                ZipCode.set(55901);

                ManagedExecutorService executor = InitialContext.doLookup("java:app/env/concurrent/executor8ref");

                CompletableFuture<?> future1 = executor.supplyAsync(runTestAsEJB);
                CompletableFuture<?> future2 = executor.supplyAsync(runTestAsEJB);

                Object result = CompletableFuture.anyOf(future1, future2).join();
                assertNotNull(result);
                assertTrue(result.toString(), result instanceof ManagedExecutorService);

                // Cancel whichever hasn't completed yet,
                future1.cancel(true);
                future2.cancel(true);
            } catch (NamingException x) {
                throw new EJBException(x);
            } finally {
                ZipCode.clear();
            }
        });
    }

    /**
     * Use a ManagedScheduledExecutorService from a ManagedScheduledExecutorDefinition that is defined in an EJB.
     */
    @Test
    public void testEJBAnnoManagedScheduledExecutorDefinition() throws Exception {
        Function<CyclicBarrier, Integer> runTestAsEJB = barrier -> {
            // Enforcement of maxAsync=2
            try {
                int index = barrier.await(TIMEOUT_NS, TimeUnit.NANOSECONDS);
                fail("3 async tasks were able to run at once, in violation of maxAsync=2. Arrival index: " + index);
            } catch (BrokenBarrierException x) {
                // expected
            } catch (InterruptedException | TimeoutException x) {
                throw new CompletionException(x);
            }

            // Third-party List context must be propagated
            assertEquals("[28, 45, 53]", ListContext.asString());

            // Application context must be propagated
            try {
                ManagedScheduledExecutorService result = InitialContext.doLookup("java:module/concurrent/executor9");
                assertNotNull(result);
            } catch (NamingException x) {
                throw new CompletionException(x);
            }

            // Third-party Timestamp context must be cleared
            assertEquals(null, Timestamp.get());

            return ZipCode.get();
        };

        Executor bean = InitialContext.doLookup("java:global/ConcurrencyTestApp/ConcurrencyTestEJB/ExecutorBean!java.util.concurrent.Executor");
        assertNotNull(bean);
        bean.execute(() -> {
            try {
                // Add some fake context
                ListContext.newList();
                ListContext.add(28);
                ListContext.add(45);
                ListContext.add(53);
                Timestamp.set();

                ManagedScheduledExecutorService executor = InitialContext.doLookup("java:module/concurrent/executor9");

                CompletableFuture<CyclicBarrier> stage1 = executor.newIncompleteFuture();

                ZipCode.set(55901);
                CompletableFuture<?> stage2a = stage1.thenApplyAsync(runTestAsEJB);

                ZipCode.set(55902);
                CompletableFuture<?> stage2b = stage1.thenApplyAsync(runTestAsEJB);

                ZipCode.set(55904);
                CompletableFuture<?> stage2c = stage1.thenApplyAsync(runTestAsEJB);

                ZipCode.set(55906);
                assertTrue(stage1.complete(new CyclicBarrier(3)));

                CyclicBarrier barrier = stage1.join();

                // 2 must run in parallel
                for (long start = System.nanoTime(); System.nanoTime() - start < TIMEOUT_NS && barrier.getNumberWaiting() < 2;)
                    TimeUnit.MILLISECONDS.sleep(200);

                assertEquals(2, barrier.getNumberWaiting());

                // ensure that all 3 do not run in parallel
                try {
                    CompletableFuture.allOf(stage2a, stage2b, stage2c).get(1, TimeUnit.SECONDS);
                    fail("3 async tasks must not run in parallel when maxAsync=2");
                } catch (TimeoutException x) {
                    // expected
                }

                // break the barrier
                barrier.reset();

                // wait for the third async task to start
                for (long start = System.nanoTime(); System.nanoTime() - start < TIMEOUT_NS && barrier.getNumberWaiting() < 1;)
                    TimeUnit.MILLISECONDS.sleep(200);

                barrier.reset();

                assertEquals(Integer.valueOf(55901), stage2a.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
                assertEquals(Integer.valueOf(55902), stage2b.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
                assertEquals(Integer.valueOf(55904), stage2c.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            } catch (ExecutionException | InterruptedException | NamingException | TimeoutException x) {
                throw new EJBException(x);
            } finally {
                ListContext.clear();
                Timestamp.clear();
                ZipCode.clear();
            }
        });
    }

    /**
     * Use a ManagedThreadFactory from a ManagedThreadFactoryDefinition that is defined in an EJB.
     */
    @Test
    public void testEJBAnnoManagedThreadFactoryDefinition() throws Exception {
        Executor bean = InitialContext.doLookup("java:global/ConcurrencyTestApp/ConcurrencyTestEJB/ExecutorBean!java.util.concurrent.Executor");
        assertNotNull(bean);
        bean.execute(() -> {
            try {
                ManagedThreadFactory threadFactory = InitialContext.doLookup("java:module/concurrent/tf");

                try {
                    ZipCode.set(55904);
                    Thread.currentThread().setPriority(4);

                    LinkedBlockingQueue<Object> results = new LinkedBlockingQueue<Object>();

                    threadFactory.newThread(() -> {
                        results.add(Thread.currentThread().getPriority());
                        results.add(ZipCode.get());
                        try {
                            results.add(InitialContext.doLookup("java:module/concurrent/tf"));
                        } catch (Throwable x) {
                            results.add(x);
                        }
                    }).start();

                    // Verify that priority from the ManagedThreadFactoryDefinition is used,
                    Object priority = results.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS);
                    assertEquals(Integer.valueOf(6), priority);

                    // Verify that custom thread context type ZipCode is cleared
                    Object zipCode = results.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS);
                    assertEquals(Integer.valueOf(0), zipCode);

                    // Verify that Application component context is propagated,
                    Object resultOfLookup = results.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS);
                    if (resultOfLookup instanceof Throwable)
                        throw new CompletionException((Throwable) resultOfLookup);
                    assertNotNull(resultOfLookup);
                    assertTrue(resultOfLookup.toString(), resultOfLookup instanceof ManagedThreadFactory);
                } finally {
                    Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
                    ZipCode.clear();
                }

            } catch (InterruptedException | NamingException x) {
                throw new EJBException(x);
            }
        });
    }

    /**
     * Use a ContextService that is defined by a context-service in an EJB deployment descriptor.
     */
    @Test
    public void testEJBDDContextServiceDefinition() throws Exception {
        // java:global name is accessible outside of the EJB module,
        ContextService contextSvc = InitialContext.doLookup("java:global/concurrent/dd/ejb/LPContextService");

        // Put some fake context onto the thread:
        Timestamp.set();
        ZipCode.set(55904);
        ListContext.newList();
        ListContext.add(4);
        ListContext.add(8);
        Thread.currentThread().setPriority(4);
        Long ts0 = Timestamp.get();

        try {
            // Contextualize a Callable with the above context:
            Callable<Object[]> contextualCallable = contextSvc.contextualCallable(() -> {
                // The Callable records the context
                Object lookupResult;
                try {
                    lookupResult = InitialContext.doLookup("java:app/concurrent/dd/ejb/LPScheduledExecutor");
                    throw new AssertionError("Application context was not cleared. Looked up: " + lookupResult);
                } catch (NamingException x) {
                    // expected
                }
                return new Object[] {
                                      ListContext.asString(),
                                      Thread.currentThread().getPriority(),
                                      Timestamp.get(),
                                      ZipCode.get(),
                };
            });

            // Alter some of the context on the current thread
            ZipCode.set(55901);
            ListContext.newList();
            ListContext.add(1);
            Thread.currentThread().setPriority(6);
            TimeUnit.MILLISECONDS.sleep(100);
            Long ts1 = Timestamp.get();

            // Run with the captured context:
            Object[] results;
            try {
                results = contextualCallable.call();
            } catch (RuntimeException x) {
                throw x;
            } catch (Exception x) {
                throw new CompletionException(x);
            }

            // List context was configured to be propagated
            assertEquals("[4, 8]", results[0]);

            // Priority context was configured to be propagated
            assertEquals(Integer.valueOf(4), results[1]);

            // Remaining context (Timestamp) was configured to be left unchanged
            assertEquals(ts1, results[2]);

            // Zip code context was configured to be left unchanged
            assertEquals(Integer.valueOf(55901), results[3]);

            // Verify that context is restored on the current thread:
            assertEquals("[1]", ListContext.asString());
            assertEquals(6, Thread.currentThread().getPriority());
            assertEquals(ts1, Timestamp.get());
            assertEquals(55901, ZipCode.get());

            // Run the task on another thread
            Future<Object[]> future = unmanagedThreads.submit(contextualCallable);
            results = future.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);

            // List context was configured to be propagated
            assertEquals("[4, 8]", results[0]);

            // Priority context was configured to be propagated
            assertEquals(Integer.valueOf(4), results[1]);

            // Remaining context (Timestamp) was configured to be left unchanged
            assertEquals(null, results[2]);

            // Zip code context was configured to be left unchanged
            assertEquals(Integer.valueOf(0), results[3]);
        } finally {
            // Remove fake context
            Timestamp.clear();
            ZipCode.clear();
            ListContext.clear();
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        }
    }

    /**
     * Use a ManagedExecutorService that is defined via managed-executor in an EJB deployment descriptor.
     */
    @Test
    public void testEJBDDManagedExecutorDefinition() throws Exception {
        BiFunction<CountDownLatch, CountDownLatch, Object> task = (twoStarted, threeStarted) -> {
            twoStarted.countDown();
            threeStarted.countDown();
            try {
                assertTrue(threeStarted.await(TIMEOUT_NS, TimeUnit.NANOSECONDS));
                // requires application context
                return InitialContext.doLookup("java:comp/concurrent/dd/ejb/Executor");
            } catch (InterruptedException | NamingException x) {
                throw new CompletionException(x);
            }
        };

        Executor bean = InitialContext.doLookup("java:global/ConcurrencyTestApp/ConcurrencyTestEJB/ExecutorBean!java.util.concurrent.Executor");
        assertNotNull(bean);
        bean.execute(() -> {
            try {
                ManagedExecutorService executor = InitialContext.doLookup("java:comp/concurrent/dd/ejb/Executor");

                CompletableFuture<CountDownLatch> twoStartedFuture = executor.completedFuture(new CountDownLatch(2));
                CompletableFuture<CountDownLatch> threeStartedFuture = executor.completedFuture(new CountDownLatch(3));

                CompletableFuture<?> stage3 = twoStartedFuture.thenCombineAsync(threeStartedFuture, task);
                CompletableFuture<?> stage4 = twoStartedFuture.thenCombineAsync(threeStartedFuture, task);
                CompletableFuture<?> stage5 = twoStartedFuture.thenCombineAsync(threeStartedFuture, task);

                // 2 tasks must run concurrently per <max-async>2</max-async>
                assertTrue(twoStartedFuture.join().await(TIMEOUT_NS, TimeUnit.NANOSECONDS));

                // 3 tasks must not run concurrently
                assertFalse(threeStartedFuture.join().await(1, TimeUnit.SECONDS));

                // running inline is not considered async
                CompletableFuture<?> stage6 = twoStartedFuture.thenCombine(threeStartedFuture, task);
                assertNotNull(stage6.join());

                assertNotNull(stage5.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
                assertNotNull(stage4.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
                assertNotNull(stage3.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            } catch (ExecutionException | InterruptedException | NamingException | TimeoutException x) {
                throw new EJBException(x);
            }
        });
    }

    /**
     * Use a ManagedScheduledExecutorService that is defined via managed-scheduled-executor in an EJB deployment descriptor.
     */
    @Test
    public void testEJBDDManagedScheduledExecutorDefinition() throws Exception {
        BiFunction<CountDownLatch, CountDownLatch, int[]> task = (threeStarted, fourStarted) -> {
            threeStarted.countDown();
            fourStarted.countDown();

            try {
                assertTrue(fourStarted.await(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            } catch (InterruptedException x) {
                throw new CompletionException(x);
            }

            // Application context must be cleared, per java:global/concurrent/dd/ejb/LPContextService,
            // which is configured as the context-service-ref for the managed-scheduled-executor,
            try {
                Object lookedUp = InitialContext.doLookup("java:app/concurrent/dd/ejb/LPScheduledExecutor");
                throw new AssertionError("Application context should be cleared. Instead looked up " + lookedUp);
            } catch (NamingException x) {
                // pass
            }

            return new int[] {
                               Thread.currentThread().getPriority(), // must be propagated
                               ZipCode.get() // must be left unchanged
            };
        };

        Executor bean = InitialContext.doLookup("java:global/ConcurrencyTestApp/ConcurrencyTestEJB/ExecutorBean!java.util.concurrent.Executor");
        assertNotNull(bean);
        bean.execute(() -> {
            try {
                ManagedExecutorService executor = InitialContext.doLookup("java:app/concurrent/dd/ejb/LPScheduledExecutor");

                CompletableFuture<CountDownLatch> threeStartedFuture = executor.completedFuture(new CountDownLatch(3));
                CompletableFuture<CountDownLatch> fourStartedFuture = executor.completedFuture(new CountDownLatch(4));

                Thread.currentThread().setPriority(4);
                ZipCode.set(55904);
                CompletableFuture<int[]> stage3 = threeStartedFuture.thenCombineAsync(fourStartedFuture, task);
                CompletableFuture<int[]> stage4 = threeStartedFuture.thenCombineAsync(fourStartedFuture, task);

                Thread.currentThread().setPriority(6);
                ZipCode.set(55906);
                CompletableFuture<int[]> stage5 = threeStartedFuture.thenCombineAsync(fourStartedFuture, task);
                CompletableFuture<int[]> stage6 = threeStartedFuture.thenCombineAsync(fourStartedFuture, task);

                Thread.currentThread().setPriority(2);
                ZipCode.set(55902);

                // 3 tasks must run concurrently per <max-async>3</max-async>
                assertTrue(threeStartedFuture.join().await(TIMEOUT_NS, TimeUnit.NANOSECONDS));

                // 4 tasks must not run concurrently
                assertFalse(fourStartedFuture.join().await(1, TimeUnit.SECONDS));

                // running inline is not considered async
                CompletableFuture<int[]> stage7 = threeStartedFuture.thenCombine(fourStartedFuture, task);

                int[] results;
                assertNotNull(results = stage7.join());
                assertEquals(2, results[0]); // Priority context is propagated
                assertEquals(55902, results[1]); // ZipCode context is left unchanged

                assertNotNull(results = stage6.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
                assertEquals(6, results[0]); // Priority context is propagated
                assertEquals(0, results[1]); // ZipCode context is left unchanged

                assertNotNull(results = stage5.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
                assertEquals(6, results[0]); // Priority context is propagated
                assertEquals(0, results[1]); // ZipCode context is left unchanged

                assertNotNull(results = stage4.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
                assertEquals(4, results[0]); // Priority context is propagated
                assertEquals(0, results[1]); // ZipCode context is left unchanged

                assertNotNull(results = stage3.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
                assertEquals(4, results[0]); // Priority context is propagated
                assertEquals(0, results[1]); // ZipCode context is left unchanged
            } catch (ExecutionException | InterruptedException | NamingException | TimeoutException x) {
                throw new EJBException(x);
            } finally {
                Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
                ZipCode.clear();
            }
        });
    }

    /**
     * Use a ManagedThreadFactory that is defined by managed-thread-factory in an EJB deployment descriptor.
     */
    @Test
    public void testEJBDDManagedThreadFactoryDefinition() throws Exception {
        Executor bean = InitialContext.doLookup("java:global/ConcurrencyTestApp/ConcurrencyTestEJB/ExecutorBean!java.util.concurrent.Executor");
        assertNotNull(bean);
        bean.execute(() -> {
            try {
                try {
                    Timestamp.set();
                    Thread.currentThread().setPriority(6);

                    ManagedThreadFactory threadFactory = InitialContext.doLookup("java:module/concurrent/dd/ejb/ZLThreadFactory");

                    LinkedBlockingQueue<Object> results = new LinkedBlockingQueue<Object>();

                    threadFactory.newThread(() -> {
                        results.add(Thread.currentThread().getPriority());

                        Long timestamp = Timestamp.get();
                        results.add(timestamp == null ? "null" : timestamp);

                        try {
                            results.add(InitialContext.doLookup("java:module/concurrent/dd/ejb/ZLThreadFactory"));
                        } catch (Throwable x) {
                            results.add(x);
                        }
                    }).start();

                    // Verify that priority from the managed-thread-factory is used,
                    Object priority = results.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS);
                    assertEquals(Integer.valueOf(7), priority);

                    // Verify that custom thread context type TimestampContext is cleared
                    Object timestamp = results.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS);
                    assertEquals("null", timestamp);

                    // Verify that Application component context is propagated,
                    Object resultOfLookup = results.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS);
                    if (resultOfLookup instanceof Throwable)
                        throw new CompletionException((Throwable) resultOfLookup);
                    assertNotNull(resultOfLookup);
                    assertTrue(resultOfLookup.toString(), resultOfLookup instanceof ManagedThreadFactory);
                } finally {
                    Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
                    ListContext.clear();
                }

            } catch (InterruptedException | NamingException x) {
                throw new EJBException(x);
            }
        });
    }

    /**
     * Verify that a ManagedExecutorService propagates context to dependent stages that are
     * created from a failedFuture().
     */
    @Test
    public void testFailedFuture() throws Exception {
        Throwable failure = new IllegalStateException("Intentional failure to test error paths");
        CompletableFuture<Integer> failed = executor5.failedFuture(failure);
        CompletableFuture<Integer> handled;
        try {
            ZipCode.set(55904);
            handled = failed.handleAsync((result, x) -> {
                if (result == null && x == failure)
                    return ZipCode.get();
                else
                    throw new CompletionException(x);
            });
        } finally {
            ZipCode.clear();
        }
        assertEquals(Integer.valueOf(55904), handled.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
    }

    /**
     * Verify that forced completion of a copied stage neither impacts the original, nor other copies.
     */
    @Test
    public void testForcedCompletionOfCopies() throws Exception {
        CompletableFuture<String> original = new CompletableFuture<String>();
        CompletableFuture<String> copy1 = executor5.copy(original);
        CompletableFuture<String> copy2 = executor5.copy(original);
        CompletableFuture<String> copy3 = executor5.copy(original);
        CompletableFuture<String> copy4 = executor5.copy(original);
        CompletableFuture<String> copy5 = executor5.copy(original);
        CompletableFuture<String> copy6 = executor5.copy(original);
        CompletableFuture<String> copy7 = executor5.copy(original);

        copy7.completeOnTimeout("7 is done", 60, TimeUnit.MILLISECONDS);
        assertTrue(copy6.cancel(true));
        assertTrue(copy5.complete("5 is done"));
        copy4.obtrudeValue("4 is done");
        copy3.completeExceptionally(new IllegalArgumentException("3 is done"));
        copy2.obtrudeException(new ArrayIndexOutOfBoundsException("2 is done"));

        assertFalse(copy1.isDone());
        assertFalse(original.isDone());

        assertEquals("7 is done", copy7.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));

        assertTrue(original.complete("original is done"));
        assertEquals("original is done", copy1.join());
        assertTrue(copy2.isCompletedExceptionally());
        assertTrue(copy3.isCompletedExceptionally());
        assertEquals("4 is done", copy4.join());
        assertEquals("5 is done", copy5.join());
        assertTrue(copy6.isCancelled());
    }

    /**
     * Verify that it is possible to obtain the nested ContextService of a ManagedExecutorService
     * that is configured in server.xml, and that when withContextCapture is invoked on this ContextService,
     * the resulting CompletableFuture is backed by the ManagedExecutorService, subject to its concurrency
     * constraints, and runs tasks under the context propagation settings of its nested ContextService.
     */
    @Test
    public void testGetContextService1WithContextCapture() throws Exception {
        ContextService contextSvc = executor1.getContextService();

        CompletableFuture<String> stage1 = new CompletableFuture<String>();

        CompletableFuture<String> stage1copy = contextSvc.withContextCapture(stage1);

        // block the managed executor's only thread
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch blocking = new CountDownLatch(1);
        try {
            CompletableFuture<Object> stage2a = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    blocking.countDown();
                    if (blocker.await(TIMEOUT_NS, TimeUnit.NANOSECONDS))
                        return InitialContext.doLookup(jndiName);
                    else
                        return "timed out";
                } catch (InterruptedException | NamingException x) {
                    throw new CompletionException(x);
                }
            });
            stage1.complete("java:comp/env/concurrent/executor3Ref");
            assertTrue(blocking.await(TIMEOUT_NS, TimeUnit.NANOSECONDS));

            // fill the managed executor's only queue slot
            CompletableFuture<Object> stage2b = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    return InitialContext.doLookup(jndiName);
                } catch (NamingException x) {
                    throw new CompletionException(x);
                }
            });

            // attempt to exceed the managed executor's maximum queue size
            CompletableFuture<String> stage2c = stage1copy.thenApplyAsync(s -> s);
            try {
                String result = stage2c.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);
                fail("Should not be able to queue another completion stage " + stage2c + ", with result: " + result);
            } catch (ExecutionException x) {
                if (x.getCause() instanceof RejectedExecutionException)
                    ; // expected
                else
                    throw x;
            }

            blocker.countDown();

            Object result;
            assertNotNull(result = stage2a.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2b.join());
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);
        } finally {
            blocker.countDown();
        }
    }

    /**
     * Per the spec JavaDoc, ContextService.getExecutionProperties can be used on a
     * contextual proxy obtained by createContextualProxy, but otherwise raises
     * IllegalArgumentException.
     */
    @Test
    public void testGetExecutionProperties() throws Exception {
        ContextService contextSvc = InitialContext.doLookup("java:app/concurrent/appContextSvc");
        Function<Integer, Integer> triple = i -> i * 3;
        Map<String, String> execProps = Collections.singletonMap(ManagedTask.IDENTITY_NAME, "testGetExecutionProperties");

        @SuppressWarnings("unchecked")
        Function<Integer, Integer> proxyFn = contextSvc.createContextualProxy(triple, execProps, Function.class);
        assertEquals(execProps, contextSvc.getExecutionProperties(proxyFn));

        Function<Integer, Integer> contextFn = contextSvc.contextualFunction(triple);
        try {
            Map<String, String> unexpected = contextSvc.getExecutionProperties(contextFn);
            fail("getExecutionProperties must raise IllegalArgumentException when the proxy is " +
                 "not created by createContextualProxy. Result: " + unexpected);
        } catch (IllegalArgumentException x) {
            // expected
        }

        Executor contextExecutor = contextSvc.currentContextExecutor();
        try {
            Map<String, String> unexpected = contextSvc.getExecutionProperties(contextExecutor);
            fail("getExecutionProperties must raise IllegalArgumentException when the proxy is " +
                 "not created by createContextualProxy. Result: " + unexpected);
        } catch (IllegalArgumentException x) {
            // expected
        }
    }

    /**
     * Verify that a ManagedExecutorService that is injected from a ManagedExecutorDefinition
     * abides by the configured maxAsync and the configured thread context propagation/clearing
     * that makes it possible to access third-party context from async completion stage actions.
     */
    @Test
    public void testManagedExecutorDefinitionAnno() throws Throwable {
        ManagedExecutorService executor5 = InitialContext.doLookup("java:module/concurrent/executor5");

        CompletableFuture<Exchanger<String>> stage0 = executor5.completedFuture(new Exchanger<String>());

        CompletableFuture<Object[]> stage1a, stage1b, stage1c;

        // Async completion stage action will attempt an exchange (which shouldn't be possible
        // due to maxAsync of 1) and record the thread context under which it runs:
        Function<Exchanger<String>, Object[]> fn = exchanger -> {
            Object[] results = new Object[6];
            try {
                results[0] = exchanger.exchange("maxAsync=1 was not enforced", 1, TimeUnit.SECONDS);
            } catch (InterruptedException | TimeoutException x) {
                results[0] = x;
            }
            results[1] = Timestamp.get(); // should be cleared
            results[2] = ZipCode.get(); // should be propagated
            results[3] = ListContext.asString(); // should be propagated
            results[4] = Thread.currentThread().getPriority(); // should be cleared
            try {
                // Application context should not be applied to pooled thread, causing the lookup to fail
                results[5] = InitialContext.doLookup("java:module/concurrent/executor5");
            } catch (NamingException x) {
                results[5] = x;
            }
            return results;
        };

        // Put some fake context onto the thread:
        Timestamp.set();
        ZipCode.set(55902);
        ListContext.newList();
        ListContext.add(33);
        ListContext.add(56);
        ListContext.add(65);
        Thread.currentThread().setPriority(4);
        try {
            // request async completion stages with above context,
            stage1a = stage0.thenApplyAsync(fn);
            stage1b = stage0.thenApplyAsync(fn);
            // alter context slightly and request another async completion stage,
            ZipCode.set(55904);
            stage1c = stage0.thenApplyAsync(fn);
        } finally {
            // Remove fake context
            Timestamp.clear();
            ZipCode.clear();
            ListContext.clear();
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        }

        Object[] results = stage1a.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);

        if (!(results[0] instanceof TimeoutException)) // must time out due to enforcement of maxAsync=1
            if (results[0] instanceof Throwable)
                throw new AssertionError().initCause((Throwable) results[0]);
            else
                throw new AssertionError(results[0]);
        assertNull(results[1]); // must be cleared
        assertEquals(Integer.valueOf(55902), results[2]); // must be propagated
        assertEquals("[33, 56, 65]", results[3]); // must be propagated
        assertEquals(Integer.valueOf(Thread.NORM_PRIORITY), results[4]); // must be cleared
        if (!(results[5] instanceof NamingException)) // must be unchanged (not present on async thread)
            if (results[5] instanceof Throwable)
                throw new AssertionError().initCause((Throwable) results[5]);
            else
                throw new AssertionError(results[5]);

        results = stage1b.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);

        if (!(results[0] instanceof TimeoutException)) // must time out due to enforcement of maxAsync=1
            if (results[0] instanceof Throwable)
                throw new AssertionError().initCause((Throwable) results[0]);
            else
                throw new AssertionError(results[0]);
        assertNull(results[1]); // must be cleared
        assertEquals(Integer.valueOf(55902), results[2]); // must be propagated
        assertEquals("[33, 56, 65]", results[3]); // must be propagated
        assertEquals(Integer.valueOf(Thread.NORM_PRIORITY), results[4]); // must be cleared
        if (!(results[5] instanceof NamingException)) // must be unchanged (not present on async thread)
            if (results[5] instanceof Throwable)
                throw new AssertionError().initCause((Throwable) results[5]);
            else
                throw new AssertionError(results[5]);

        results = stage1c.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);

        if (!(results[0] instanceof TimeoutException)) // must time out due to enforcement of maxAsync=1
            if (results[0] instanceof Throwable)
                throw new AssertionError().initCause((Throwable) results[0]);
            else
                throw new AssertionError(results[0]);
        assertNull(results[1]); // must be cleared
        assertEquals(Integer.valueOf(55904), results[2]); // must be propagated
        assertEquals("[33, 56, 65]", results[3]); // must be propagated
        assertEquals(Integer.valueOf(Thread.NORM_PRIORITY), results[4]); // must be cleared
        if (!(results[5] instanceof NamingException)) // must be unchanged (not present on async thread)
            if (results[5] instanceof Throwable)
                throw new AssertionError().initCause((Throwable) results[5]);
            else
                throw new AssertionError(results[5]);
    }

    /**
     * Verify that a ManagedExecutorService that is defined in application.xml
     * abides by the configured maxAsync and the configured thread context propagation/clearing
     * that makes it possible to access third-party context from async completion stage actions.
     */
    @Test
    public void testManagedExecutorDefinitionAppDD() throws Throwable {
        ManagedExecutorService executor = InitialContext.doLookup("java:app/concurrent/dd/ZPExecutor");

        CountDownLatch blocker = new CountDownLatch(1);
        final TransferQueue<CountDownLatch> queue = new LinkedTransferQueue<CountDownLatch>();

        Future<Object[]> future1, future2, future3, future4;

        // This async task polls the transfer queue for a latch to block on.
        // This allows the caller to use up the maxAsync (which is 2) and then attempt additional
        // transfers to test whether additional async requests can run in parallel.
        Callable<Object[]> task = () -> {
            Object[] results = new Object[6];
            results[0] = queue.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS).await(TIMEOUT_NS, TimeUnit.NANOSECONDS);
            results[1] = Timestamp.get(); // should be cleared
            results[2] = ZipCode.get(); // should be propagated
            results[3] = ListContext.asString(); // should be cleared
            results[4] = Thread.currentThread().getPriority(); // should be propagated
            try {
                results[5] = InitialContext.doLookup("java:app/concurrent/dd/ZPExecutor");
            } catch (NamingException x) {
                // expected, due to unchanged Application context on executor thread
                results[5] = x;
            }
            return results;
        };

        // Put some fake context onto the thread:
        Timestamp.set();
        ZipCode.set(55901);
        ListContext.newList();
        ListContext.add(25);
        Thread.currentThread().setPriority(6);
        try {
            // submit async task with the above context,
            future1 = executor.submit(task);
            // alter context slightly and submit more tasks,
            ZipCode.set(55902);
            future2 = executor.submit(task);
            future3 = executor.submit(task);
            future4 = executor.submit(task);
        } finally {
            // Remove fake context
            Timestamp.clear();
            ZipCode.clear();
            ListContext.clear();
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        }

        // With maxAsync=2, there should be 2 async completion stage actions running to accept transfers:
        assertTrue(queue.tryTransfer(blocker, TIMEOUT_NS, TimeUnit.NANOSECONDS));
        assertTrue(queue.tryTransfer(blocker, TIMEOUT_NS, TimeUnit.NANOSECONDS));

        // Additional transfers should not be possible
        assertFalse(queue.tryTransfer(blocker, 1, TimeUnit.SECONDS));

        // Allow completion stage actions to finish:
        blocker.countDown();

        // The remaining completion stage actions can start now:
        assertTrue(queue.tryTransfer(blocker, TIMEOUT_NS, TimeUnit.NANOSECONDS));
        assertTrue(queue.tryTransfer(blocker, TIMEOUT_NS, TimeUnit.NANOSECONDS));

        Object[] results = future1.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);

        assertEquals(Boolean.TRUE, results[0]);
        assertNull(results[1]); // Timestamp context must be cleared
        assertEquals(Integer.valueOf(55901), results[2]); // must be propagated
        assertEquals("[]", results[3]); // List context must be cleared
        assertEquals(Integer.valueOf(6), results[4]); // must be propagated
        if (!(results[5] instanceof NamingException)) // must be unchanged (not present on async thread)
            if (results[5] instanceof Throwable)
                throw new AssertionError().initCause((Throwable) results[5]);
            else
                throw new AssertionError(results[5]);

        results = future2.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);

        assertEquals(Boolean.TRUE, results[0]);
        assertNull(results[1]); // Timestamp context must be cleared
        assertEquals(Integer.valueOf(55902), results[2]); // must be propagated
        assertEquals("[]", results[3]); // List context must be cleared
        assertEquals(Integer.valueOf(6), results[4]); // must be propagated
        if (!(results[5] instanceof NamingException)) // must be unchanged (not present on async thread)
            if (results[5] instanceof Throwable)
                throw new AssertionError().initCause((Throwable) results[5]);
            else
                throw new AssertionError(results[5]);

        results = future3.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);

        assertEquals(Boolean.TRUE, results[0]);
        assertNull(results[1]); // Timestamp context must be cleared
        assertEquals(Integer.valueOf(55902), results[2]); // must be propagated
        assertEquals("[]", results[3]); // List context must be cleared
        assertEquals(Integer.valueOf(6), results[4]); // must be propagated
        if (!(results[5] instanceof NamingException)) // must be unchanged (not present on async thread)
            if (results[5] instanceof Throwable)
                throw new AssertionError().initCause((Throwable) results[5]);

        results = future4.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);

        assertEquals(Boolean.TRUE, results[0]);
        assertNull(results[1]); // Timestamp context must be cleared
        assertEquals(Integer.valueOf(55902), results[2]); // must be propagated
        assertEquals("[]", results[3]); // List context must be cleared
        assertEquals(Integer.valueOf(6), results[4]); // must be propagated
        if (!(results[5] instanceof NamingException)) // must be unchanged (not present on async thread)
            if (results[5] instanceof Throwable)
                throw new AssertionError().initCause((Throwable) results[5]);
    }

    /**
     * Verify that a ManagedScheduledExecutorService that is injected from a ManagedScheduledExecutorDefinition
     * abides by the configured maxAsync and the configured thread context propagation that
     * makes it possible to look up resource references in the application component's namespace.
     */
    @Test
    public void testManagedScheduledExecutorDefinitionAnno() throws Exception {
        assertNotNull(executor6);

        CountDownLatch blocker = new CountDownLatch(1);
        TransferQueue<CountDownLatch> queue = new LinkedTransferQueue<CountDownLatch>();
        CompletableFuture<TransferQueue<CountDownLatch>> stage0 = executor6.completedFuture(queue);

        CompletableFuture<Object> stage1a, stage1b, stage1c, stage1d;

        // This async completion stage action polls the transfer queue for a latch to block on.
        // This allows the caller to use up the maxAsync (which is 2) and then attempt additional
        // transfers to test whether additional async requests can run in parallel.
        Function<TransferQueue<CountDownLatch>, Object> fn = q -> {
            try {
                if (q.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS).await(TIMEOUT_NS, TimeUnit.NANOSECONDS))
                    return InitialContext.doLookup("java:comp/concurrent/executor6"); // requires Application context
                else
                    return false;
            } catch (InterruptedException | NamingException x) {
                throw new CompletionException(x);
            }
        };

        stage1a = stage0.thenApplyAsync(fn);
        stage1b = stage0.thenApplyAsync(fn);
        stage1c = stage0.thenApplyAsync(fn);
        stage1d = stage0.thenApplyAsync(fn);

        // With maxAsync=2, there should be 2 async completion stage actions running to accept transfers:
        assertTrue(queue.tryTransfer(blocker, TIMEOUT_NS, TimeUnit.NANOSECONDS));
        assertTrue(queue.tryTransfer(blocker, TIMEOUT_NS, TimeUnit.NANOSECONDS));

        // Additional transfers should not be possible
        assertFalse(queue.tryTransfer(blocker, 1, TimeUnit.SECONDS));

        // Allow completion stage actions to finish:
        blocker.countDown();

        // The remaining completion stage actions can start now:
        assertTrue(queue.tryTransfer(blocker, TIMEOUT_NS, TimeUnit.NANOSECONDS));
        assertTrue(queue.tryTransfer(blocker, TIMEOUT_NS, TimeUnit.NANOSECONDS));

        Object result;
        assertNotNull(result = stage1a.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
        assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService); // successful lookup on completion stage thread

        assertNotNull(result = stage1b.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
        assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService); // successful lookup on completion stage thread

        assertNotNull(result = stage1c.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
        assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService); // successful lookup on completion stage thread

        assertNotNull(result = stage1d.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
        assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService); // successful lookup on completion stage thread
    }

    /**
     * Verify that a ManagedScheduledExecutorService that is defined in application.xml
     * abides by the configured maxAsync and the configured thread context propagation that
     * makes it possible to look up resource references in the application component's namespace.
     */
    @Test
    public void testManagedScheduledExecutorDefinitionAppDD() throws Throwable {
        ManagedExecutorService executor = InitialContext.doLookup("java:global/concurrent/dd/ScheduledExecutor");

        final Exchanger<String> exchanger = new Exchanger<String>();

        Future<Object[]> future1, future2, future3;

        // Async task that attempts an exchange (which shouldn't be possible
        // due to maxAsync of 1) and records the thread context under which it runs:
        Callable<Object[]> task = () -> {
            Object[] results = new Object[6];
            try {
                results[0] = exchanger.exchange("maxAsync=1 was not enforced", 1, TimeUnit.SECONDS);
            } catch (InterruptedException | TimeoutException x) {
                results[0] = x;
            }
            results[1] = Timestamp.get();
            results[2] = ZipCode.get();
            results[3] = ListContext.asString();
            results[4] = Thread.currentThread().getPriority();
            results[5] = InitialContext.doLookup("java:app/concurrent/appContextSvc"); // Application context is propagated
            return results;
        };

        // Put some fake context onto the thread:
        Timestamp.set();
        ZipCode.set(55901);
        ListContext.newList();
        ListContext.add(20);
        Thread.currentThread().setPriority(7);
        try {
            future1 = executor.submit(task);
            future2 = executor.submit(task);
            future3 = executor.submit(task);
        } finally {
            // Remove fake context
            Timestamp.clear();
            ZipCode.clear();
            ListContext.clear();
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        }

        Object[] results = future1.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);

        if (!(results[0] instanceof TimeoutException)) // must time out due to enforcement of maxAsync=1
            if (results[0] instanceof Throwable)
                throw new AssertionError().initCause((Throwable) results[0]);
            else
                throw new AssertionError(results[0]);
        // TODO results[1] to results[4] : does third-party context propagate to the default managed executor?
        if (results[5] instanceof Throwable)
            throw new AssertionError().initCause((Throwable) results[5]);
        else
            assertNotNull(results[5]);

        results = future2.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);

        if (!(results[0] instanceof TimeoutException)) // must time out due to enforcement of maxAsync=1
            if (results[0] instanceof Throwable)
                throw new AssertionError().initCause((Throwable) results[0]);
            else
                throw new AssertionError(results[0]);
        // TODO results[1] to results[4] : does third-party context propagate to the default managed executor?
        if (results[5] instanceof Throwable)
            throw new AssertionError().initCause((Throwable) results[5]);
        else
            assertNotNull(results[5]);

        results = future3.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);

        if (!(results[0] instanceof TimeoutException)) // must time out due to enforcement of maxAsync=1
            if (results[0] instanceof Throwable)
                throw new AssertionError().initCause((Throwable) results[0]);
            else
                throw new AssertionError(results[0]);
        // TODO results[1] to results[4] : does third-party context propagate to the default managed executor?
        if (results[5] instanceof Throwable)
            throw new AssertionError().initCause((Throwable) results[5]);
        else
            assertNotNull(results[5]);
    }

    /**
     * Verify that a ManagedThreadFactory that is injected from a ManagedThreadFactoryDefinition
     * creates threads that run with the configured priority and with the configured thread context
     * that makes it possible to look up resource references in the application component's namespace.
     */
    @Test
    public void testManagedThreadFactoryDefinitionAnno() throws Exception {
        assertNotNull(lowPriorityThreads);

        int priority = Thread.currentThread().getPriority();

        ForkJoinPool pool = new ForkJoinPool(2, lowPriorityThreads, null, false);
        try {
            ForkJoinTask<Long> task = pool.submit(new Factorial(5)
                            .assertAvailable("java:comp/env/concurrent/executor3Ref")
                            .assertPriority(3));

            assertEquals(Long.valueOf(120), task.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
        } finally {
            pool.shutdown();
        }

        assertEquals(priority, Thread.currentThread().getPriority());

        Thread managedThread = lowPriorityThreads.newThread(() -> {
        });
        assertEquals(3, managedThread.getPriority());
        assertTrue(managedThread.getClass().getName(), managedThread instanceof ManageableThread);
    }

    /**
     * Verify that a ManagedThreadFactory that is defined in application.xml
     * creates threads that run with the configured priority and with the configured thread context
     * that makes it possible to look up resource references in the application component's namespace.
     */
    @Test
    public void testManagedThreadFactoryDefinitionAppDD() throws Throwable {
        ManagedThreadFactory threadFactory = InitialContext.doLookup("java:app/concurrent/dd/ThreadFactory");

        final LinkedBlockingQueue<Object> results = new LinkedBlockingQueue<Object>();
        threadFactory.newThread(() -> {
            results.add(Thread.currentThread().getName());
            results.add(Thread.currentThread().getPriority());
            try {
                results.add(InitialContext.doLookup("java:app/concurrent/dd/ThreadFactory"));
            } catch (Throwable x) {
                results.add(x);
            }
        }).start();

        Object result;
        assertNotNull(result = results.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS));
        assertTrue(result.toString(), !Thread.currentThread().getName().equals(result));

        assertNotNull(result = results.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS));
        assertEquals(Integer.valueOf(4), result);

        assertNotNull(result = results.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS));
        if (result instanceof Throwable)
            throw new AssertionError().initCause((Throwable) result);
    }

    /**
     * Verify that it is possible to use nested ContextService without ever having obtained the
     * managed executor that it is nested under, and that is possible to use the withContextCapture
     * methods which create completion stages that are backed by that managed executor.
     * Verify that the completion stages run on the managed executor, subject to its concurrency
     * constraints, and runs tasks under the context propagation settings of its nested ContextService.
     */
    @Test
    public void testNestedContextService2WithContextCapture() throws Exception {
        CompletableFuture<String> stage1 = new CompletableFuture<String>();

        CompletableFuture<String> stage1copy = contextSvc2.withContextCapture(stage1);

        // block the managed executor's 2 threads
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch blocking = new CountDownLatch(2);
        try {
            CompletableFuture<Object> stage2a = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    blocking.countDown();
                    if (blocker.await(TIMEOUT_NS, TimeUnit.NANOSECONDS))
                        return InitialContext.doLookup(jndiName);
                    else
                        return "timed out";
                } catch (InterruptedException | NamingException x) {
                    throw new CompletionException(x);
                }
            });
            CompletableFuture<Object> stage2b = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    blocking.countDown();
                    if (blocker.await(TIMEOUT_NS, TimeUnit.NANOSECONDS))
                        return InitialContext.doLookup(jndiName);
                    else
                        return "timed out";
                } catch (InterruptedException | NamingException x) {
                    throw new CompletionException(x);
                }
            });
            stage1.complete("java:comp/env/concurrent/executor3Ref");
            assertTrue(blocking.await(TIMEOUT_NS, TimeUnit.NANOSECONDS));

            // fill the managed executor's 2 queue slots
            CompletableFuture<Object> stage2c = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    return InitialContext.doLookup(jndiName);
                } catch (NamingException x) {
                    throw new CompletionException(x);
                }
            });
            CompletableFuture<Object> stage2d = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    return InitialContext.doLookup(jndiName);
                } catch (NamingException x) {
                    throw new CompletionException(x);
                }
            });

            // attempt to exceed the managed executor's maximum queue size
            CompletableFuture<String> stage2e = stage1copy.thenApplyAsync(s -> s);
            try {
                String result = stage2e.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);
                fail("Should not be able to queue another completion stage " + stage2e + ", with result: " + result);
            } catch (ExecutionException x) {
                if (x.getCause() instanceof RejectedExecutionException)
                    ; // expected
                else
                    throw x;
            }

            blocker.countDown();

            Object result;
            assertNotNull(result = stage2a.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2b.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2c.join());
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2d.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);
        } finally {
            blocker.countDown();
        }
    }

    /**
     * Schedule a one-shot timer with a ZonedTrigger that implements only getZoneId and the
     * getNextRunTime method that accepts a ZonedDateTime. Record the LastExecution and ensure
     * that the methods which specify a ZoneId are working and return times that are consistent
     * with what the ZonedTrigger asks for.
     */
    @Test
    public void testOneShotTimerWithZonedTrigger() throws Exception {
        final AtomicReference<LastExecution> lastExecRef = new AtomicReference<LastExecution>();
        final AtomicReference<ZonedDateTime> scheduledAtRef = new AtomicReference<ZonedDateTime>();
        final ZoneId USCentral = ZoneId.of("America/Chicago");
        final ZoneId USMountain = ZoneId.of("America/Denver");
        final ZoneId NewZealand = ZoneId.of("Pacific/Auckland");
        final long TOLERANCE_NS = TimeUnit.MILLISECONDS.toNanos(500);

        ZonedDateTime beforeScheduled = ZonedDateTime.now(USCentral);
        ScheduledFuture<Integer> future = executor4.schedule(() -> 400, new ZonedTrigger() {
            @Override
            public ZonedDateTime getNextRunTime(LastExecution lastExecution, ZonedDateTime scheduledAt) {
                if (lastExecution == null)
                    return scheduledAt.plusSeconds(4);
                lastExecRef.set(lastExecution);
                scheduledAtRef.set(scheduledAt);
                return null;
            }

            @Override
            public ZoneId getZoneId() {
                return USCentral;
            }
        });
        try {
            ZonedDateTime afterScheduled = ZonedDateTime.now(USCentral);

            assertEquals(Integer.valueOf(400), future.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(future.isDone());
            assertFalse(future.isCancelled());

            // Is the scheduledAt time within the range of when we actually scheduled it?
            ZonedDateTime scheduledAt = scheduledAtRef.get();
            assertEquals(USCentral, scheduledAt.getZone()); // must supply scheduledAt time in same zone
            assertTrue(beforeScheduled + " must be less or equal to " + scheduledAt,
                       beforeScheduled.minusNanos(TOLERANCE_NS).isBefore(scheduledAt));
            assertTrue(afterScheduled + " must be greater or equal to " + scheduledAt,
                       afterScheduled.plusNanos(TOLERANCE_NS).isAfter(scheduledAt));

            // Does the target start time of the last execution match what the trigger asked for?
            LastExecution lastExec = lastExecRef.get();
            ZonedDateTime targetStartAt = lastExec.getScheduledStart(USCentral);
            assertEquals(USCentral, targetStartAt.getZone());
            ZonedDateTime targetStartAtExpected = scheduledAt.plusSeconds(4);
            assertTrue(targetStartAt + " must be equal to " + targetStartAtExpected,
                       targetStartAt.isAfter(targetStartAtExpected.minusNanos(TOLERANCE_NS)) &&
                                                                                     targetStartAt.isBefore(targetStartAtExpected.plusNanos(TOLERANCE_NS)));

            // Is the actual start time after (or equal to) the expected?
            ZonedDateTime startAt = lastExec.getRunStart(USMountain);
            assertEquals(USMountain, startAt.getZone());
            assertTrue(startAt + " must be greater or equal to " + targetStartAt,
                       startAt.isAfter(targetStartAt.minusNanos(TOLERANCE_NS)));

            // Is the actual end time after (or equal to) the actual start time?
            ZonedDateTime endAt = lastExec.getRunEnd(NewZealand);
            assertEquals(NewZealand, endAt.getZone());
            assertTrue(endAt + " must be greater or equal to " + startAt,
                       endAt.isAfter(startAt.minusNanos(TOLERANCE_NS)));
        } finally {
            if (!future.isDone())
                future.cancel(true);
        }
    }

    /**
     * Verify that a parallel stream can run on a ForkJoinPool that uses a ManagedThreadFactory
     * to create its ForkJoinWorkerThreads, and that those threads run with the application
     * component context of the the application that looked up or injected the ManagedThreadFactory.
     * Verify this by attempting a resource reference lookup from the parallel stream operations.
     */
    @Test
    public void testParallelStreamRunsOnManagedThreadFactory() throws Exception {
        String curThreadName = Thread.currentThread().getName();
        LinkedBlockingQueue<Object> results = new LinkedBlockingQueue<Object>();

        ForkJoinPool pool = new ForkJoinPool(3, forkJoinThreadFactory, null, false);
        try {
            pool.submit(() -> {
                Arrays.asList(1, 2, 3).parallelStream().forEach(i -> {
                    try {
                        // Perform a resource reference lookup to demonstrate that the
                        // application component's context is established on the ForkJoinWorkerThread,
                        Object lookedUp = InitialContext.doLookup("java:module/env/concurrent/threadFactoryRef");
                        results.add(Thread.currentThread().getName() + " (" + i + ") " + lookedUp);
                    } catch (NamingException x) {
                        results.add(x);
                    }
                });
            });

            Object result;
            assertNotNull(result = results.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            if (result instanceof Exception) {
                throw new AssertionError("Failure on parallel stream thread", (Exception) result);
            } else {
                String s = (String) result;
                assertTrue("Current: " + curThreadName + " vs " + s, !s.startsWith(curThreadName));
                assertTrue(s, s.contains(" (1) ") || s.contains(" (2) ") || s.contains(" (3) "));
            }

            assertNotNull(result = results.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            if (result instanceof Exception) {
                throw new AssertionError("Failure on parallel stream thread", (Exception) result);
            } else {
                String s = (String) result;
                assertTrue("Current: " + curThreadName + " vs " + s, !s.startsWith(curThreadName));
                assertTrue(s, s.contains(" (1) ") || s.contains(" (2) ") || s.contains(" (3) "));
            }

            assertNotNull(result = results.poll(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            if (result instanceof Exception) {
                throw new AssertionError("Failure on parallel stream thread", (Exception) result);
            } else {
                String s = (String) result;
                assertTrue("Current: " + curThreadName + " vs " + s, !s.startsWith(curThreadName));
                assertTrue(s, s.contains(" (1) ") || s.contains(" (2) ") || s.contains(" (3) "));
            }
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Verify that it is possible to obtain a ContextService that is referenced by
     * multiple managed executors, and that is possible to use the withContextCapture methods
     * which create completion stages that are backed by the respective managed executor.
     * Verify that the completion stages run on the managed executor, subject to its concurrency
     * constraints, and runs tasks under the context propagation settings of the ContextService.
     * Part 1 - This test covers usage of the managedScheduledExecutorService concurrent/executor3.
     */
    @Test
    public void testReferencedContextServiceWithContextCapture3() throws Exception {
        CompletableFuture<String> stage1 = new CompletableFuture<String>();

        ContextService contextSvc3 = executor3.getContextService();

        CompletableFuture<String> stage1copy = contextSvc3.withContextCapture(stage1);

        // block the managed executor's 3 threads
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch blocking = new CountDownLatch(3);
        try {
            CompletableFuture<Object> stage2a = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    blocking.countDown();
                    if (blocker.await(TIMEOUT_NS, TimeUnit.NANOSECONDS))
                        return InitialContext.doLookup(jndiName);
                    else
                        return "timed out";
                } catch (InterruptedException | NamingException x) {
                    throw new CompletionException(x);
                }
            });
            CompletableFuture<Object> stage2b = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    blocking.countDown();
                    if (blocker.await(TIMEOUT_NS, TimeUnit.NANOSECONDS))
                        return InitialContext.doLookup(jndiName);
                    else
                        return "timed out";
                } catch (InterruptedException | NamingException x) {
                    throw new CompletionException(x);
                }
            });
            CompletableFuture<Object> stage2c = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    blocking.countDown();
                    if (blocker.await(TIMEOUT_NS, TimeUnit.NANOSECONDS))
                        return InitialContext.doLookup(jndiName);
                    else
                        return "timed out";
                } catch (InterruptedException | NamingException x) {
                    throw new CompletionException(x);
                }
            });
            stage1.complete("java:comp/env/concurrent/executor3Ref");
            assertTrue(blocking.await(TIMEOUT_NS, TimeUnit.NANOSECONDS));

            // fill the managed executor's 3 queue slots
            CompletableFuture<Object> stage2d = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    return InitialContext.doLookup(jndiName);
                } catch (NamingException x) {
                    throw new CompletionException(x);
                }
            });
            CompletableFuture<Object> stage2e = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    return InitialContext.doLookup(jndiName);
                } catch (NamingException x) {
                    throw new CompletionException(x);
                }
            });
            CompletableFuture<Object> stage2f = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    return InitialContext.doLookup(jndiName);
                } catch (NamingException x) {
                    throw new CompletionException(x);
                }
            });

            // attempt to exceed the managed executor's maximum queue size
            CompletableFuture<String> stage2g = stage1copy.thenApplyAsync(s -> s);
            try {
                String result = stage2g.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);
                fail("Should not be able to queue another completion stage " + stage2g + ", with result: " + result);
            } catch (ExecutionException x) {
                if (x.getCause() instanceof RejectedExecutionException)
                    ; // expected
                else
                    throw x;
            }

            blocker.countDown();

            Object result;
            assertNotNull(result = stage2a.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2b.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2c.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2d.join());
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2e.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2f.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);
        } finally {
            blocker.countDown();
        }
    }

    /**
     * Verify that it is possible to obtain a ContextService that is referenced by
     * multiple managed executors, and that is possible to use the withContextCapture methods
     * which create completion stages that are backed by the respective managed executor.
     * Verify that the completion stages run on the managed executor, subject to its concurrency
     * constraints, and runs tasks under the context propagation settings of the ContextService.
     * Part 2 - This test covers usage of the managedScheduledExecutorService concurrent/executor4.
     */
    @Test
    public void testReferencedContextServiceWithContextCapture4() throws Exception {
        CompletableFuture<String> stage1 = new CompletableFuture<String>();

        ContextService contextSvc4 = executor4.getContextService();

        CompletableFuture<String> stage1copy = contextSvc4.withContextCapture(stage1);

        // block the managed executor's 4 threads
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch blocking = new CountDownLatch(4);
        try {
            CompletableFuture<Object> stage2a = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    blocking.countDown();
                    if (blocker.await(TIMEOUT_NS, TimeUnit.NANOSECONDS))
                        return InitialContext.doLookup(jndiName);
                    else
                        return "timed out";
                } catch (InterruptedException | NamingException x) {
                    throw new CompletionException(x);
                }
            });
            CompletableFuture<Object> stage2b = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    blocking.countDown();
                    if (blocker.await(TIMEOUT_NS, TimeUnit.NANOSECONDS))
                        return InitialContext.doLookup(jndiName);
                    else
                        return "timed out";
                } catch (InterruptedException | NamingException x) {
                    throw new CompletionException(x);
                }
            });
            CompletableFuture<Object> stage2c = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    blocking.countDown();
                    if (blocker.await(TIMEOUT_NS, TimeUnit.NANOSECONDS))
                        return InitialContext.doLookup(jndiName);
                    else
                        return "timed out";
                } catch (InterruptedException | NamingException x) {
                    throw new CompletionException(x);
                }
            });
            CompletableFuture<Object> stage2d = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    blocking.countDown();
                    if (blocker.await(TIMEOUT_NS, TimeUnit.NANOSECONDS))
                        return InitialContext.doLookup(jndiName);
                    else
                        return "timed out";
                } catch (InterruptedException | NamingException x) {
                    throw new CompletionException(x);
                }
            });

            stage1.complete("java:comp/env/concurrent/executor3Ref");
            assertTrue(blocking.await(TIMEOUT_NS, TimeUnit.NANOSECONDS));

            // fill the managed executor's 4 queue slots
            CompletableFuture<Object> stage2e = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    return InitialContext.doLookup(jndiName);
                } catch (NamingException x) {
                    throw new CompletionException(x);
                }
            });
            CompletableFuture<Object> stage2f = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    return InitialContext.doLookup(jndiName);
                } catch (NamingException x) {
                    throw new CompletionException(x);
                }
            });
            CompletableFuture<Object> stage2g = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    return InitialContext.doLookup(jndiName);
                } catch (NamingException x) {
                    throw new CompletionException(x);
                }
            });
            CompletableFuture<Object> stage2h = stage1copy.thenApplyAsync(jndiName -> {
                try {
                    return InitialContext.doLookup(jndiName);
                } catch (NamingException x) {
                    throw new CompletionException(x);
                }
            });

            // attempt to exceed the managed executor's maximum queue size
            CompletableFuture<String> stage2i = stage1copy.thenApplyAsync(s -> s);
            try {
                String result = stage2i.get(TIMEOUT_NS, TimeUnit.NANOSECONDS);
                fail("Should not be able to queue another completion stage " + stage2i + ", with result: " + result);
            } catch (ExecutionException x) {
                if (x.getCause() instanceof RejectedExecutionException)
                    ; // expected
                else
                    throw x;
            }

            blocker.countDown();

            Object result;
            assertNotNull(result = stage2a.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2b.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2c.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2d.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2e.get(TIMEOUT_NS, TimeUnit.NANOSECONDS));
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2f.join());
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2g.get());
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);

            assertNotNull(result = stage2h.join());
            assertTrue(result.toString(), result instanceof ManagedScheduledExecutorService);
        } finally {
            blocker.countDown();
        }
    }

    /**
     * Verify that the ManagedExecutorService runAsync runs the completion stage action
     * with context captured per the java:comp/DefaultContextService when the
     * ManagedExecutorDefinition does not specify any value for context.
     */
    @Test
    public void testRunAsyncWithDefaultContextPropagation() throws Exception {
        ManagedExecutorService executor = InitialContext.doLookup("java:global/concurrent/executor7");

        ZipCode.set(55906);
        try {
            CompletableFuture<Void> future = executor.runAsync(() -> {
                // third-party context is not propagated
                assertEquals(0, ZipCode.get());
                try {
                    // must have access to application component namespace, per Application context
                    assertNotNull(InitialContext.doLookup("java:comp/env/concurrent/executor3Ref"));
                } catch (NamingException x) {
                    throw new CompletionException(x);
                }
            });
            // cause any assertion errors from above to be raised,
            assertNull(future.join());
        } finally {
            ZipCode.clear();
        }
    }
}
