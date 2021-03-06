/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.jmx.whiteboard.integration.helper2;

import org.apache.aries.jmx.whiteboard.integration.helper.TestClass;


/**
 * The <code>TestClass2</code> is a simple class which will be registered as a
 * Simple MBean implementing the {@link TestClassMBean} interface.
 */
public class TestClass2 extends TestClass {

    public TestClass2() {
        this(null);
    }

    public TestClass2(final String name) {
        super(name);
    }

}
