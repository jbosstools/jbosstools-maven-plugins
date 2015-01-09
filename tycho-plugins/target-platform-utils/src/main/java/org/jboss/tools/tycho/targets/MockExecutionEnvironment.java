/**
 * Copyright (c) 2013, 2014 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributor:
 *     Mickael Istria (Red Hat, Inc.) - initial API and implementation
 */
package org.jboss.tools.tycho.targets;

import java.util.List;

import org.eclipse.tycho.core.ee.shared.ExecutionEnvironment;
import org.eclipse.tycho.core.ee.shared.ExecutionEnvironmentConfiguration;
import org.eclipse.tycho.core.ee.shared.SystemCapability;

public class MockExecutionEnvironment implements
		ExecutionEnvironmentConfiguration {

	public void overrideProfileConfiguration(String profileName,
			String configurationOrigin) {
	}

	public void setProfileConfiguration(String profileName,
			String configurationOrigin) {
	}

	public String getProfileName() {
		return "Mock";
	}

	public boolean isCustomProfile() {
		return false;
	}

	public void setFullSpecificationForCustomProfile(List<SystemCapability> systemCapabilities) {
	}

	public ExecutionEnvironment getFullSpecification() {
		return null;
	}

	public boolean isIgnoredByResolver() {
		return true;
	}

}
