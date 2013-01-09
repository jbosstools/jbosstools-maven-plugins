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

}
