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
 * "AS IS" BASIS, WITHOUT WARRANTIESOR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.jpa.supplier;

import javax.persistence.EntityManager;

/**
 * Provides a thread safe way to use an EntityManager. - The first call must be to preCall(). This will create
 * an EM for the current thread. - The EM can then be retrieved by calling get() and user normally - At the
 * end postCall() has to be called to close the EntityManager If this is used on nested methods then the EM is
 * only created / closed on the outermost calls to preCall() / postCall()
 */
public interface EmSupplier {

    /**
     * Is called before first access to get() in a method
     * @deprecated Use a Coordination instead
     */
    @Deprecated
    void preCall();
    
    EntityManager get();

    /**
     * Is called after last access to get() in a method
     * @deprecated Use a Coordination instead
     */
    @Deprecated
    void postCall();
}
