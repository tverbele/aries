/**
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
package org.apache.aries.blueprint.container;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.aries.blueprint.BlueprintConstants;
import org.apache.aries.blueprint.ParserService;
import org.apache.aries.blueprint.annotation.service.BlueprintAnnotationScanner;
import org.apache.aries.blueprint.namespace.NamespaceHandlerRegistryImpl;
import org.apache.aries.blueprint.utils.HeaderParser;
import org.apache.aries.blueprint.utils.HeaderParser.PathElement;
import org.apache.aries.proxy.ProxyManager;
import org.apache.aries.util.SingleServiceTracker;
import org.apache.aries.util.SingleServiceTracker.SingleServiceListener;
import org.apache.aries.util.tracker.RecursiveBundleTracker;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.service.blueprint.container.BlueprintContainer;
import org.osgi.service.blueprint.container.BlueprintEvent;
import org.osgi.util.tracker.BundleTrackerCustomizer;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the blueprint extender that listens to blueprint bundles.  
 *
 * @version $Rev$, $Date$
 */
public class BlueprintExtender implements BundleActivator, SynchronousBundleListener {

	/** The QuiesceParticipant implementation class name */
	private static final String QUIESCE_PARTICIPANT_CLASS = "org.apache.aries.quiesce.participant.QuiesceParticipant";
    private static final Logger LOGGER = LoggerFactory.getLogger(BlueprintExtender.class);

    private BundleContext context;
    private ScheduledExecutorService executors;
    private Map<Bundle, BlueprintContainerImpl> containers;
    private BlueprintEventDispatcher eventDispatcher;
    private NamespaceHandlerRegistry handlers;
    private RecursiveBundleTracker bt;
    private ServiceRegistration parserServiceReg;
    private ServiceRegistration quiesceParticipantReg;
    private static SingleServiceTracker<ProxyManager> proxyManager;
    
    public void start(BundleContext ctx) {
        LOGGER.debug("Starting blueprint extender...");

        this.context = ctx;
        handlers = new NamespaceHandlerRegistryImpl(ctx);
        executors = Executors.newScheduledThreadPool(3, new BlueprintThreadFactory("Blueprint Extender"));
        eventDispatcher = new BlueprintEventDispatcher(ctx, executors);
        containers = new HashMap<Bundle, BlueprintContainerImpl>();

        int stateMask = Bundle.INSTALLED | Bundle.RESOLVED | Bundle.STARTING | Bundle.ACTIVE
        | Bundle.STOPPING;
        bt = new RecursiveBundleTracker(ctx, stateMask, new BlueprintBundleTrackerCustomizer());
        
        proxyManager = new SingleServiceTracker<ProxyManager>(ctx, ProxyManager.class, new SingleServiceListener() {
          public void serviceFound() {
            LOGGER.debug("Found ProxyManager service, starting to process blueprint bundles");
            bt.open();
          }
          public void serviceLost() {
            // TODO we should probably close here, not sure.
          }
          public void serviceReplaced() {
          }
        });
        proxyManager.open();
        
        // Create and publish a ParserService
        parserServiceReg = ctx.registerService(ParserService.class.getName(), 
            new ParserServiceImpl (handlers), 
            new Hashtable<Object, Object>()); 

        try{
            ctx.getBundle().loadClass(QUIESCE_PARTICIPANT_CLASS);
            //Class was loaded, register

            quiesceParticipantReg = ctx.registerService(QUIESCE_PARTICIPANT_CLASS, 
              new BlueprintQuiesceParticipant(ctx, this), 
              new Hashtable<Object, Object>()); 
        } 
        catch (ClassNotFoundException e) 
        {
            LOGGER.info("No quiesce support is available, so blueprint components will not participate in quiesce operations");
        }
        
        LOGGER.debug("Blueprint extender started");
    }

    /**
     * this method checks the initial bundle that are installed/active before
     * bundle tracker is opened.
     *
     * @param b the bundle to check
     */
    private void checkInitialBundle(Bundle b) {
        // If the bundle is active, check it
        if (b.getState() == Bundle.ACTIVE) {
            checkBundle(b);
            // Also check bundles in the starting state with a lazy activation
            // policy
        } else if (b.getState() == Bundle.STARTING) {
            String activationPolicyHeader = (String) b.getHeaders().get(
                    Constants.BUNDLE_ACTIVATIONPOLICY);
            if (activationPolicyHeader != null
                    && activationPolicyHeader
                            .startsWith(Constants.ACTIVATION_LAZY)) {
                checkBundle(b);
            }
        }
        
    }

    public void stop(BundleContext context) {
        LOGGER.debug("Stopping blueprint extender...");
        if (bt != null) {
        	bt.close();
        }
        
        parserServiceReg.unregister();
        
        if (quiesceParticipantReg != null) 
          	quiesceParticipantReg.unregister();

        // Orderly shutdown of containers
        while (!containers.isEmpty()) {
            for (Bundle bundle : getBundlesToDestroy()) {
                destroyContext(bundle);
            }
        }
        this.eventDispatcher.destroy();
        this.handlers.destroy();
        executors.shutdown();
        LOGGER.debug("Blueprint extender stopped");
    }
    
    /**
     * @return the proxy manager. This will return null if the blueprint is not yet managing bundles.
     */
    public static ProxyManager getProxyManager()
    {
      return proxyManager.getService();
    }

    private List<Bundle> getBundlesToDestroy() {
        List<Bundle> bundlesToDestroy = new ArrayList<Bundle>();
        for (Bundle bundle : containers.keySet()) {
            ServiceReference[] references = bundle.getRegisteredServices();
            int usage = 0;
            if (references != null) {
                for (ServiceReference reference : references) {
                    usage += getServiceUsage(reference);
                }
            }
            LOGGER.debug("Usage for bundle {} is {}", bundle, usage);
            if (usage == 0) {
                bundlesToDestroy.add(bundle);
            }
        }
        if (!bundlesToDestroy.isEmpty()) {
            Collections.sort(bundlesToDestroy, new Comparator<Bundle>() {
                public int compare(Bundle b1, Bundle b2) {
                    return (int) (b2.getLastModified() - b1.getLastModified());
                }
            });
            LOGGER.debug("Selected bundles {} for destroy (no services in use)", bundlesToDestroy);
        } else {
            ServiceReference ref = null;
            for (Bundle bundle : containers.keySet()) {
                ServiceReference[] references = bundle.getRegisteredServices();
                for (ServiceReference reference : references) {
                    if (getServiceUsage(reference) == 0) {
                        continue;
                    }
                    if (ref == null || reference.compareTo(ref) < 0) {
                        LOGGER.debug("Currently selecting bundle {} for destroy (with reference {})", bundle, reference);
                        ref = reference;
                    }
                }
            }
            bundlesToDestroy.add(ref.getBundle());
            LOGGER.debug("Selected bundle {} for destroy (lowest ranking service)", bundlesToDestroy);
        }
        return bundlesToDestroy;
    }

    private static int getServiceUsage(ServiceReference ref) {
        Bundle[] usingBundles = ref.getUsingBundles();
        return (usingBundles != null) ? usingBundles.length : 0;        
    }
    
    public void bundleChanged(BundleEvent event) {
        Bundle bundle = event.getBundle();
        if (event.getType() == BundleEvent.LAZY_ACTIVATION) {
            checkBundle(bundle);
        } else if (event.getType() == BundleEvent.STARTED) {
            BlueprintContainerImpl blueprintContainer = containers.get(bundle);
            if (blueprintContainer == null) {
                checkBundle(bundle);
            }
        } else if (event.getType() == BundleEvent.STOPPING) {
            destroyContext(bundle);
        }
    }

    private void destroyContext(Bundle bundle) {
        BlueprintContainerImpl blueprintContainer = containers.remove(bundle);
        if (blueprintContainer != null) {
            LOGGER.debug("Destroying BlueprintContainer for bundle {}", bundle.getSymbolicName());
            blueprintContainer.destroy();
        }
        eventDispatcher.removeBlueprintBundle(bundle);
    }
    
    private void checkBundle(Bundle bundle) {
        LOGGER.debug("Scanning bundle {} for blueprint application", bundle.getSymbolicName());
        try {
            List<Object> pathList = new ArrayList<Object>();
            String blueprintHeader = (String) bundle.getHeaders().get(BlueprintConstants.BUNDLE_BLUEPRINT_HEADER);
            String blueprintHeaderAnnotation = (String) bundle.getHeaders().get(BlueprintConstants.BUNDLE_BLUEPRINT_ANNOTATION_HEADER);
            if (blueprintHeader == null) {
                blueprintHeader = "OSGI-INF/blueprint/";
            } 
            List<PathElement> paths = HeaderParser.parseHeader(blueprintHeader);
            for (PathElement path : paths) {
                String name = path.getName();
                if (name.endsWith("/")) {
                    addEntries(bundle, name, "*.xml", pathList);
                } else {
                    String baseName;
                    String filePattern;
                    int pos = name.lastIndexOf('/');
                    if (pos < 0) {
                        baseName = "/";
                        filePattern = name;
                    } else {
                        baseName = name.substring(0, pos + 1);
                        filePattern = name.substring(pos + 1);
                    }
                    if (hasWildcards(filePattern)) {
                        addEntries(bundle, baseName, filePattern, pathList);
                    } else {
                        addEntry(bundle, name, pathList);
                    }                    
                }
            }
            
            if (pathList.isEmpty() && blueprintHeaderAnnotation != null && blueprintHeaderAnnotation.trim().equalsIgnoreCase("true")) {
                LOGGER.debug("Scanning bundle {} for blueprint annotations", bundle.getSymbolicName());
                ServiceReference sr = this.context.getServiceReference("org.apache.aries.blueprint.annotation.service.BlueprintAnnotationScanner");
                           
                if (sr != null) {
                    BlueprintAnnotationScanner bas = (BlueprintAnnotationScanner)this.context.getService(sr);
                    // try to generate the blueprint definition XML
                    URL url = bas.createBlueprintModel(bundle);
                        
                    if (url != null) {
                        pathList.add(url);
                    }
                    
                    this.context.ungetService(sr);
                }
             
            }
            
            if (!pathList.isEmpty()) {
                LOGGER.debug("Found blueprint application in bundle {} with paths: {}", bundle.getSymbolicName(), pathList);
                // Check compatibility
                // TODO: For lazy bundles, the class is either loaded from an imported package or not found, so it should
                // not trigger the activation.  If it does, we need to use something else like package admin or
                // ServiceReference, or just not do this check, which could be quite harmful.
                boolean compatible = isCompatible(bundle);
                if (compatible) {
                    final BlueprintContainerImpl blueprintContainer = new BlueprintContainerImpl(bundle.getBundleContext(), context.getBundle(), eventDispatcher, handlers, executors, pathList);
                    containers.put(bundle, blueprintContainer);
                    String val = context.getProperty("org.apache.aries.blueprint.synchronous");
                    if (Boolean.parseBoolean(val)) {
                        LOGGER.debug("Starting creation of blueprint bundle {} synchronously", bundle.getSymbolicName());
                        blueprintContainer.run();
                    } else {
                        LOGGER.debug("Scheduling creation of blueprint bundle {} asynchronously", bundle.getSymbolicName());
                        blueprintContainer.schedule();
                    }
                } else {
                    LOGGER.info("Bundle {} is not compatible with this blueprint extender", bundle.getSymbolicName());
                }

            } else {
                LOGGER.debug("No blueprint application found in bundle {}", bundle.getSymbolicName());   
            }
        } catch (Throwable t) {
            eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.FAILURE, bundle, context.getBundle(), t));
        }
    }

    private boolean isCompatible(Bundle bundle) {
        // Check compatibility
        boolean compatible;
        if (bundle.getState() == Bundle.ACTIVE) {
            try {
                Class<?> clazz = bundle.getBundleContext().getBundle().loadClass(BlueprintContainer.class.getName());
                compatible = (clazz == BlueprintContainer.class);
            } catch (ClassNotFoundException e) {
                compatible = true;
            }
        } else {
            // for lazy bundle, we can't load the class, so just assume it's ok
            compatible = true;
        }
        return compatible;
    }
    
    private boolean hasWildcards(String path) {
        return path.indexOf("*") >= 0; 
    }
    
    private String getFilePart(URL url) {
        String path = url.getPath();
        int index = path.lastIndexOf('/');
        return path.substring(index + 1);
    }
    
    private String cachePath(Bundle bundle, String filePath)
    {
      return Integer.toHexString(bundle.hashCode()) + "/" + filePath;
    }    
    
    private URL getOverrideURLForCachePath(String privatePath){
        URL override = null;
        File privateDataVersion = context.getDataFile(privatePath);
        if (privateDataVersion != null
                && privateDataVersion.exists()) {
            try {
                override = privateDataVersion.toURI().toURL();
            } catch (MalformedURLException e) {
                LOGGER.error("Unexpected URL Conversion Issue", e);
            }
        }
        return override;
    }
    
    private URL getOverrideURL(Bundle bundle, String path){
        String cachePath = cachePath(bundle, path);
        return getOverrideURLForCachePath(cachePath);
    }
    
    private URL getOverrideURL(Bundle bundle, URL path, String basePath){
        String cachePath = cachePath(bundle, basePath + getFilePart(path));
        return getOverrideURLForCachePath(cachePath);
    }    
    
    private void addEntry(Bundle bundle, String path, List<Object> pathList) {
        URL override = getOverrideURL(bundle, path);
        if(override == null) {
            pathList.add(path);
        } else {
            pathList.add(override);
        }
    }
    
    private void addEntries(Bundle bundle, String path, String filePattern, List<Object> pathList) {
        Enumeration<?> e = bundle.findEntries(path, filePattern, false);
        while (e != null && e.hasMoreElements()) {
            URL u = (URL) e.nextElement();
            URL override = getOverrideURL(bundle, u, path);
            if(override == null) {
                pathList.add(u);
            } else {
                pathList.add(override);
            }
        }
    }
    
    // blueprint bundle tracker calls bundleChanged to minimize changes.
    private class BlueprintBundleTrackerCustomizer implements
            BundleTrackerCustomizer {

        public BlueprintBundleTrackerCustomizer() {
        }

        public Object addingBundle(Bundle b, BundleEvent event) {
            if (event == null) {
                // existing bundles first added to the tracker with no event change
                checkInitialBundle(b);
            } else {
                bundleChanged(event);
            }

            return b;
        }

        public void modifiedBundle(Bundle b, BundleEvent event, Object arg2) {
            if (event == null) {
                // cannot think of why we would be interested in a modified bundle with no bundle event
                return;
            }

            bundleChanged(event);

        }

        // don't think we would be interested in removedBundle, as that is
        // called when bundle is removed from the tracker
        public void removedBundle(Bundle b, BundleEvent event, Object arg2) {
        }
    }
    
    protected BlueprintContainerImpl getBlueprintContainerImpl(Bundle bundle)
    {
    	return containers.get(bundle);
    }
    
}