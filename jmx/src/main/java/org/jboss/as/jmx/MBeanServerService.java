/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
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

package org.jboss.as.jmx;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.MBeanServerDelegate;

import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.access.management.JmxAuthorizer;
import org.jboss.as.controller.audit.ManagedAuditLogger;
import org.jboss.as.jmx.model.ConfiguredDomains;
import org.jboss.as.jmx.model.ManagementModelIntegration;
import org.jboss.as.jmx.model.ModelControllerMBeanServerPlugin;
import org.jboss.as.server.Services;
import org.jboss.as.server.jmx.MBeanServerPlugin;
import org.jboss.as.server.jmx.PluggableMBeanServer;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceListener;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;

/**
 * Basic service managing and wrapping an MBeanServer instance. Note: Just using the platform mbean server for now.
 *
 * @author John Bailey
 * @author Kabir Khan
 */
public class MBeanServerService implements Service<PluggableMBeanServer> {
    public static final ServiceName SERVICE_NAME = ServiceName.JBOSS.append("mbean", "server");

    private static final ServiceName DOMAIN_CONTROLLER_NAME = ServiceName.JBOSS.append("host", "controller", "model", "controller");

    private final String resolvedDomainName;
    private final String expressionsDomainName;
    private final boolean legacyWithProperPropertyFormat;
    private final boolean coreMBeanSensitivity;
    private final JmxAuthorizer authorizer;
    private final ManagedAuditLogger auditLoggerInfo;
    private final InjectedValue<ModelController> modelControllerValue = new InjectedValue<ModelController>();
    private final InjectedValue<ManagementModelIntegration.ManagementModelProvider> managementModelProviderValue = new InjectedValue<ManagementModelIntegration.ManagementModelProvider>();
    private final ProcessType processType;
    private final boolean isMasterHc;
    private PluggableMBeanServer mBeanServer;
    private MBeanServerPlugin showModelPlugin;

    private MBeanServerService(final String resolvedDomainName, final String expressionsDomainName, final boolean legacyWithProperPropertyFormat,
                               final boolean coreMBeanSensitivity,
            final ManagedAuditLogger auditLoggerInfo, final JmxAuthorizer authorizer, final ProcessType processType, final boolean isMasterHc) {
        this.resolvedDomainName = resolvedDomainName;
        this.expressionsDomainName = expressionsDomainName;
        this.legacyWithProperPropertyFormat = legacyWithProperPropertyFormat;
        this.coreMBeanSensitivity = coreMBeanSensitivity;
        this.auditLoggerInfo = auditLoggerInfo;
        this.authorizer = authorizer;
        this.processType = processType;
        this.isMasterHc = isMasterHc;
    }

    @SafeVarargs
    public static ServiceController<?> addService(final ServiceTarget batchBuilder, final String resolvedDomainName, final String expressionsDomainName, final boolean legacyWithProperPropertyFormat,
                                                  final boolean coreMBeanSensitivity,
                                                  final ManagedAuditLogger auditLoggerInfo, final JmxAuthorizer authorizer,
                                                  final ProcessType processType, final boolean isMasterHc,
                                                  final ServiceListener<? super PluggableMBeanServer>... listeners) {
        final MBeanServerService service = new MBeanServerService(resolvedDomainName, expressionsDomainName, legacyWithProperPropertyFormat,
                coreMBeanSensitivity, auditLoggerInfo, authorizer, processType, isMasterHc);
        final ServiceName modelControllerName = processType == ProcessType.HOST_CONTROLLER ?
                DOMAIN_CONTROLLER_NAME : Services.JBOSS_SERVER_CONTROLLER;
        return batchBuilder.addService(MBeanServerService.SERVICE_NAME, service)
            .addListener(listeners)
            .setInitialMode(ServiceController.Mode.ACTIVE)
            .addDependency(modelControllerName, ModelController.class, service.modelControllerValue)
            .addDependency(ManagementModelIntegration.SERVICE_NAME, ManagementModelIntegration.ManagementModelProvider.class, service.managementModelProviderValue)
            .install();
    }

    /** {@inheritDoc} */
    public synchronized void start(final StartContext context) throws StartException {
        //If the platform MBeanServer was set up to be the PluggableMBeanServer, use that otherwise create a new one and delegate
        MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
        PluggableMBeanServerImpl pluggable = platform instanceof PluggableMBeanServerImpl ? (PluggableMBeanServerImpl)platform : new PluggableMBeanServerImpl(platform, null);
        MBeanServerDelegate delegate = platform instanceof PluggableMBeanServerImpl ? ((PluggableMBeanServerImpl)platform).getMBeanServerDelegate() : null;
        pluggable.setAuditLogger(auditLoggerInfo);
        pluggable.setAuthorizer(authorizer);
        authorizer.setNonFacadeMBeansSensitive(coreMBeanSensitivity);
        if (resolvedDomainName != null || expressionsDomainName != null) {
            //TODO make these configurable
            ConfiguredDomains configuredDomains = new ConfiguredDomains(resolvedDomainName, expressionsDomainName);
            showModelPlugin = new ModelControllerMBeanServerPlugin(configuredDomains, modelControllerValue.getValue(),
                    delegate, legacyWithProperPropertyFormat, processType, managementModelProviderValue.getValue(), isMasterHc);
            pluggable.addPlugin(showModelPlugin);
        }
        mBeanServer = pluggable;
    }

    /** {@inheritDoc} */
    public synchronized void stop(final StopContext context) {
        mBeanServer.removePlugin(showModelPlugin);
        mBeanServer = null;
    }

    /** {@inheritDoc} */
    public synchronized PluggableMBeanServer getValue() throws IllegalStateException {
        return mBeanServer;
    }
}
