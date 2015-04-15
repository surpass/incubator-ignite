/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.multijvm;

import org.apache.ignite.internal.util.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.testframework.junits.common.*;

import java.io.*;
import java.util.*;

/**
 * Multi JVM tests. 
 */
public class MultiJvmTest extends GridCommonAbstractTest {
    /** Proces name to process map. */
    private final Map<String, GridJavaProcess> nodes = new HashMap<>();

    @Override protected void afterTestsStopped() throws Exception {
        for (GridJavaProcess process : nodes.values())
            process.kill();

        nodes.clear();

        super.afterTestsStopped();
    }

    protected GridJavaProcess runIgniteProcess(final String nodeName, String cfg) throws Exception {
        GridJavaProcess ps = GridJavaProcess.exec(
            IgniteNodeRunner.class,
            cfg, // Params.
            log,
            // Optional closure to be called each time wrapped process prints line to system.out or system.err.
            new IgniteInClosure<String>() {
                @Override public void apply(String s) {
                    log.info("[" + nodeName + "] " + s);
                }
            },
            null,
            Collections.<String>emptyList(), // JVM Args.
            System.getProperty("surefire.test.class.path")
        );
        
        nodes.put(nodeName, ps);
        
        return ps;
    }

    protected void executeTask(String nodeName, Class<? extends IgniteNodeRunner.Task> taskCls,
        Object... args) throws Exception {
        GridJavaProcess proc = nodes.get(nodeName);

        OutputStream os = proc.getProcess().getOutputStream();

        String argsAsStr = "";

        for (Object arg : args)
            argsAsStr += arg.toString();

        OutputStreamWriter writer = new OutputStreamWriter(os);
        
        writer.write(IgniteNodeRunner.EXECUTE_TASK + taskCls.getName() + IgniteNodeRunner.TASK_ARGS + argsAsStr + '\n');
        
        writer.flush();

        // Wait for finish.
        Thread.sleep(3_000);
    }

}