package org.jenkinsci.gradle.plugins.jpi

class JarManifestIntegrationSpec extends AbstractManifestIntegrationSpec {

    @Override
    String taskToRun() {
        'jar'
    }

    @Override
    String generatedFileName() {
        "${projectName}-${projectVersion}.jar"
    }
}
