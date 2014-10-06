/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.domain.management.security;

import java.security.KeyStore;

import org.jboss.as.controller.services.path.PathEntry;
import org.jboss.as.controller.services.path.PathManager;
import org.jboss.as.controller.services.path.PathManager.Callback;
import org.jboss.as.controller.services.path.PathManager.Event;
import org.jboss.as.controller.services.path.PathManager.PathEventContext;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;

/**
 * An extension to {@link AbstractKeyManagerService} so that a KeyManager[] can be provided based on a JKS file based key store.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
class FileKeyManagerService extends AbstractKeyManagerService {

    private final InjectedValue<PathManager> pathManager = new InjectedValue<PathManager>();

    private volatile String provider;
    private volatile String path;
    private volatile String relativeTo;
    private volatile char[] keystorePassword;
    private volatile char[] keyPassword;
    private volatile String alias;

    private volatile FileKeystore keyStore;

    FileKeyManagerService(final String provider, final String path, final String relativeTo, final char[] keystorePassword,
                          final char[] keyPassword, final String alias) {
        super(keystorePassword, keyPassword);

        this.provider = provider;
        this.path = path;
        this.relativeTo = relativeTo;
        this.keystorePassword = keystorePassword;
        this.keyPassword = keyPassword;
        this.alias = alias;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getRelativeTo() {
        return relativeTo;
    }

    public void setRelativeTo(final String relativeTo) {
        this.relativeTo = relativeTo;
    }

    public char[] getKeystorePassword() {
        return keystorePassword;
    }

    public void setKeystorePassword(char[] keystorePassword) {
        this.keystorePassword = keystorePassword;
    }

    public char[] getKeyPassword() {
        return keyPassword;
    }

    public void setKeyPassword(char[] keyPassword) {
        this.keyPassword = keyPassword;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    /*
     * Service Lifecycle Methods
     */

    @Override
    public void start(StartContext context) throws StartException {
        // Do our initialisation first as the parent implementation will
        // expect that to be complete.
        String file = path;
        if (relativeTo != null) {
            PathManager pm = pathManager.getValue();

            file = pm.resolveRelativePathEntry(file, relativeTo);
            pm.registerCallback(relativeTo, new Callback() {

                @Override
                public void pathModelEvent(PathEventContext eventContext, String name) {
                    if (eventContext.isResourceServiceRestartAllowed() == false) {
                        eventContext.reloadRequired();
                    }
                }

                @Override
                public void pathEvent(Event event, PathEntry pathEntry) {
                    // Service dependencies should trigger a stop and start.
                }
            }, Event.REMOVED, Event.UPDATED);
        }

        keyStore = FileKeystore.newKeyStore(provider, file, keystorePassword, keyPassword, alias);
        keyStore.load();

        super.start(context);
    }

    @Override
    public void stop(StopContext context) {
        super.stop(context);
        keyStore = null;
    }

    @Override
    protected KeyStore loadKeyStore() {
        return keyStore.getKeyStore();
    }

    public Injector<PathManager> getPathManagerInjector() {
        return pathManager;
    }

}
