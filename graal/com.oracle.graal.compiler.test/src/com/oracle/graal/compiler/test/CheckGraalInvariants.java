/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.compiler.test;

import com.oracle.jvmci.code.BailoutException;
import com.oracle.jvmci.code.Register;
import com.oracle.jvmci.meta.ResolvedJavaMethod;
import com.oracle.jvmci.meta.JavaType;
import com.oracle.jvmci.meta.Value;
import com.oracle.jvmci.meta.JavaMethod;
import com.oracle.jvmci.meta.LocationIdentity;
import com.oracle.jvmci.meta.LIRKind;
import com.oracle.jvmci.meta.ExcludeFromIdentityComparisonVerification;
import com.oracle.jvmci.meta.MetaAccessProvider;
import com.oracle.jvmci.meta.JavaField;
import static com.oracle.jvmci.debug.DelegatingDebugConfig.Feature.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

import org.junit.*;

import com.oracle.jvmci.code.Register.RegisterCategory;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.CompilerThreadFactory.DebugConfigAccess;
import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graphbuilderconf.*;
import com.oracle.graal.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import com.oracle.graal.java.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.VerifyPhase.VerificationError;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.phases.util.*;
import com.oracle.graal.phases.verify.*;
import com.oracle.graal.printer.*;
import com.oracle.graal.runtime.*;
import com.oracle.graal.test.*;
import com.oracle.jvmci.debug.*;

/**
 * Checks that all classes in graal*.jar from the boot classpath comply with global invariants such
 * as using {@link Object#equals(Object)} to compare certain types instead of identity comparisons.
 */
public class CheckGraalInvariants extends GraalTest {

    @Test
    public void test() {
        RuntimeProvider rt = Graal.getRequiredCapability(RuntimeProvider.class);
        Providers providers = rt.getHostBackend().getProviders();
        MetaAccessProvider metaAccess = providers.getMetaAccess();

        PhaseSuite<HighTierContext> graphBuilderSuite = new PhaseSuite<>();
        GraphBuilderConfiguration config = GraphBuilderConfiguration.getEagerDefault(new Plugins(new InvocationPlugins(metaAccess)));
        graphBuilderSuite.appendPhase(new GraphBuilderPhase(config));
        HighTierContext context = new HighTierContext(providers, graphBuilderSuite, OptimisticOptimizations.NONE);

        Assume.assumeTrue(VerifyPhase.class.desiredAssertionStatus());

        String bootclasspath = System.getProperty("sun.boot.class.path");
        Assert.assertNotNull("Cannot find value of boot class path", bootclasspath);

        bootclasspath.split(File.pathSeparator);

        final List<String> classNames = new ArrayList<>();
        for (String path : bootclasspath.split(File.pathSeparator)) {
            File file = new File(path);
            String fileName = file.getName();
            if (fileName.startsWith("graal") && fileName.endsWith(".jar")) {
                try {
                    final ZipFile zipFile = new ZipFile(file);
                    for (final Enumeration<? extends ZipEntry> entry = zipFile.entries(); entry.hasMoreElements();) {
                        final ZipEntry zipEntry = entry.nextElement();
                        String name = zipEntry.getName();
                        if (name.endsWith(".class")) {
                            String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                            classNames.add(className);
                        }
                    }
                } catch (IOException ex) {
                    Assert.fail(ex.toString());
                }
            }
        }
        Assert.assertFalse("Could not find graal jars on boot class path: " + bootclasspath, classNames.isEmpty());

        // Allows a subset of methods to be checked through use of a system property
        String property = System.getProperty(CheckGraalInvariants.class.getName() + ".filters");
        String[] filters = property == null ? null : property.split(",");

        CompilerThreadFactory factory = new CompilerThreadFactory("CheckInvariantsThread", new DebugConfigAccess() {
            public GraalDebugConfig getDebugConfig() {
                return DebugEnvironment.initialize(System.out);
            }
        });
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(availableProcessors, availableProcessors, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), factory);

        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        for (String className : classNames) {
            try {
                Class<?> c = Class.forName(className, false, CheckGraalInvariants.class.getClassLoader());
                executor.execute(() -> {
                    try {
                        checkClass(c, metaAccess);
                    } catch (Throwable e) {
                        errors.add(String.format("Error while checking %s:%n%s", className, printStackTraceToString(e)));
                    }
                });

                for (Method m : c.getDeclaredMethods()) {
                    if (Modifier.isNative(m.getModifiers()) || Modifier.isAbstract(m.getModifiers())) {
                        // ignore
                    } else {
                        String methodName = className + "." + m.getName();
                        boolean verifyEquals = !m.isAnnotationPresent(ExcludeFromIdentityComparisonVerification.class);
                        if (matches(filters, methodName)) {
                            executor.execute(() -> {
                                ResolvedJavaMethod method = metaAccess.lookupJavaMethod(m);
                                StructuredGraph graph = new StructuredGraph(method, AllowAssumptions.NO);
                                try (DebugConfigScope s = Debug.setConfig(new DelegatingDebugConfig().disable(INTERCEPT)); Debug.Scope ds = Debug.scope("CheckingGraph", graph, method)) {
                                    graphBuilderSuite.apply(graph, context);
                                    // update phi stamps
                                    graph.getNodes().filter(PhiNode.class).forEach(PhiNode::inferStamp);
                                    checkGraph(context, graph, verifyEquals);
                                } catch (VerificationError e) {
                                    errors.add(e.getMessage());
                                } catch (LinkageError e) {
                                    // suppress linkages errors resulting from eager resolution
                                } catch (BailoutException e) {
                                    // Graal bail outs on certain patterns in Java bytecode (e.g.,
                                    // unbalanced monitors introduced by jacoco).
                                } catch (Throwable e) {
                                    errors.add(String.format("Error while checking %s:%n%s", methodName, printStackTraceToString(e)));
                                }
                            });
                        }
                    }
                }

            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e1) {
            throw new RuntimeException(e1);
        }

        if (!errors.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            String nl = String.format("%n");
            for (String e : errors) {
                if (msg.length() != 0) {
                    msg.append(nl);
                }
                msg.append(e);
            }
            Assert.fail(msg.toString());
        }
    }

    /**
     * @param metaAccess
     */
    private static void checkClass(Class<?> c, MetaAccessProvider metaAccess) {
        if (Node.class.isAssignableFrom(c)) {
            if (c.getAnnotation(NodeInfo.class) == null) {
                throw new AssertionError(String.format("Node subclass %s requires %s annotation", c.getName(), NodeClass.class.getSimpleName()));
            }
        }
    }

    /**
     * Checks the invariants for a single graph.
     */
    private static void checkGraph(HighTierContext context, StructuredGraph graph, boolean verifyEquals) {
        if (verifyEquals) {
            new VerifyUsageWithEquals(Value.class).apply(graph, context);
            new VerifyUsageWithEquals(Register.class).apply(graph, context);
            new VerifyUsageWithEquals(RegisterCategory.class).apply(graph, context);
            new VerifyUsageWithEquals(JavaType.class).apply(graph, context);
            new VerifyUsageWithEquals(JavaMethod.class).apply(graph, context);
            new VerifyUsageWithEquals(JavaField.class).apply(graph, context);
            new VerifyUsageWithEquals(LocationIdentity.class).apply(graph, context);
            new VerifyUsageWithEquals(LIRKind.class).apply(graph, context);
            new VerifyUsageWithEquals(ArithmeticOpTable.class).apply(graph, context);
            new VerifyUsageWithEquals(ArithmeticOpTable.Op.class).apply(graph, context);
        }
        new VerifyDebugUsage().apply(graph, context);
    }

    private static boolean matches(String[] filters, String s) {
        if (filters == null || filters.length == 0) {
            return true;
        }
        for (String filter : filters) {
            if (s.contains(filter)) {
                return true;
            }
        }
        return false;
    }

    private static String printStackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}