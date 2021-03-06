/*
 * Copyright 2009-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.gradle.plugins.jpi

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.plugins.WarPlugin
import org.gradle.api.plugins.GroovyPlugin
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.War
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

import static org.gradle.api.logging.LogLevel.INFO
import static org.gradle.api.plugins.JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME
import static org.gradle.api.plugins.WarPlugin.PROVIDED_COMPILE_CONFIGURATION_NAME
import static org.gradle.api.plugins.WarPlugin.PROVIDED_RUNTIME_CONFIGURATION_NAME
import static org.gradle.api.tasks.SourceSet.MAIN_SOURCE_SET_NAME
import static org.gradle.api.tasks.SourceSet.TEST_SOURCE_SET_NAME
import static org.jenkinsci.gradle.plugins.jpi.JpiManifest.attributesToMap

/**
 * Loads HPI related tasks into the current project.
 *
 * @author Hans Dockter
 * @author Kohsuke Kawaguchi
 * @author Andrew Bayer
 */
class JpiPlugin implements Plugin<Project> {
    /**
     * Represents the dependency to the Jenkins core.
     */
    public static final String CORE_DEPENDENCY_CONFIGURATION_NAME = 'jenkinsCore'

    /**
     * Represents the dependency to the Jenkins war. Test scope.
     */
    public static final String WAR_DEPENDENCY_CONFIGURATION_NAME = 'jenkinsWar'

    /**
     * Represents the dependencies on other Jenkins plugins.
     */
    public static final String PLUGINS_DEPENDENCY_CONFIGURATION_NAME = 'jenkinsPlugins'

    /**
     * Represents the dependencies on other Jenkins plugins.
     *
     * Using a separate configuration until we see GRADLE-1749.
     */
    public static final String OPTIONAL_PLUGINS_DEPENDENCY_CONFIGURATION_NAME = 'optionalJenkinsPlugins'

    /**
     * Represents the Jenkins plugin test dependencies.
     */
    public static final String JENKINS_TEST_DEPENDENCY_CONFIGURATION_NAME = 'jenkinsTest'

    /**
     * Represents the extra dependencies on other Jenkins plugins for the server task.
     */
    public static final String JENKINS_SERVER_DEPENDENCY_CONFIGURATION_NAME = 'jenkinsServer'

    public static final String JPI_TASK_NAME = 'jpi'
    public static final String SOURCES_JAR_TASK_NAME = 'sourcesJar'
    public static final String JAVADOC_JAR_TASK_NAME = 'javadocJar'
    public static final String LICENSE_TASK_NAME = 'generateLicenseInfo'

    void apply(final Project gradleProject) {
        gradleProject.plugins.apply(JavaPlugin)
        gradleProject.plugins.apply(WarPlugin)
        gradleProject.plugins.apply(GroovyPlugin)

        def ext = new JpiExtension(gradleProject)
        gradleProject.extensions.jenkinsPlugin = ext

        def server = gradleProject.tasks.create(ServerTask.TASK_NAME, ServerTask)
        server.description = 'Run Jenkins in place with the plugin being developed'
        server.group = BasePlugin.BUILD_GROUP // TODO
        server.dependsOn(ext.mainSourceTree().runtimeClasspath)

        // set build directory for Jenkins test harness, JENKINS-26331
        Test test = gradleProject.tasks.test as Test
        test.systemProperty('buildDirectory', gradleProject.buildDir.absolutePath)

        configureLocalizer(gradleProject)
        configureLicenseInfo(gradleProject)
        configureInjectedTest(gradleProject)

        gradleProject.task(SOURCES_JAR_TASK_NAME, type: Jar, dependsOn: 'classes') {
            classifier = 'sources'
            from gradleProject.sourceSets.main.allSource
        }
        gradleProject.task(JAVADOC_JAR_TASK_NAME, type: Jar, dependsOn: 'javadoc') {
            classifier = 'javadoc'
            from gradleProject.javadoc.destinationDir
        }

        if (!gradleProject.logger.isEnabled(INFO)) {
            gradleProject.tasks.withType(JavaCompile) {
                options.compilerArgs << '-Asezpoz.quiet=true'
            }
            gradleProject.tasks.withType(GroovyCompile) {
                options.compilerArgs << '-Asezpoz.quiet=true'
            }
        }

        gradleProject.tasks.withType(GroovyCompile) {
            groovyOptions.javaAnnotationProcessing = true
        }

        configureRepositories(gradleProject)
        configureConfigurations(gradleProject)
        configureManifest(gradleProject)
        configureJpi(gradleProject)
        configureTestDependencies(gradleProject)
        configurePublishing(gradleProject)
        configureTestHpl(gradleProject)
    }

    private static Properties loadDotJenkinsOrg() {
        Properties props = new Properties()
        def dot = new File(new File(System.getProperty('user.home')), '.jenkins-ci.org')
        if (!dot.exists()) {
            throw new GradleException(
                    "Trying to deploy to Jenkins community repository but there's no credential file ${dot}." +
                            ' See https://wiki.jenkins-ci.org/display/JENKINS/Dot+Jenkins+Ci+Dot+Org'
            )
        }
        dot.withInputStream { i -> props.load(i) }
        props
    }

    private static configureManifest(Project project) {
        JavaPluginConvention javaPluginConvention = project.convention.getPlugin(JavaPluginConvention)
        War war = project.tasks[WarPlugin.WAR_TASK_NAME] as War
        Jar jar = project.tasks.getByName(JavaPlugin.JAR_TASK_NAME) as Jar

        Task configureManifest = project.task('configureManifest') {
            doLast {
                Map<String, ?> attributes = attributesToMap(new JpiManifest(project).mainAttributes)
                war.manifest.attributes(attributes)
                war.inputs.property('manifest', attributes)
                jar.manifest.attributes(attributes)
                jar.inputs.property('manifest', attributes)
            }

            dependsOn(javaPluginConvention.sourceSets.getByName(MAIN_SOURCE_SET_NAME).output)
        }

        war.dependsOn(configureManifest)
        jar.dependsOn(configureManifest)
    }

    private static configureJpi(Project project) {
        JpiExtension jpiExtension = project.extensions.getByType(JpiExtension)

        War war = project.tasks[WarPlugin.WAR_TASK_NAME] as War
        war.description = 'Generates the JPI package'

        project.afterEvaluate {
            war.archiveName = "${jpiExtension.shortName}.${jpiExtension.fileExtension}"
            war.extension = jpiExtension.fileExtension
        }

        Task jpi = project.tasks.create(JPI_TASK_NAME)
        jpi.dependsOn(war)
        jpi.description = 'Generates the JPI package'
        jpi.group = BasePlugin.BUILD_GROUP
    }

    private static configureTestDependencies(Project project) {
        JavaPluginConvention javaConvention = project.convention.getPlugin(JavaPluginConvention)
        Configuration plugins = project.configurations.create('pluginResources')
        plugins.visible = false

        project.afterEvaluate {
            [
                    PLUGINS_DEPENDENCY_CONFIGURATION_NAME,
                    OPTIONAL_PLUGINS_DEPENDENCY_CONFIGURATION_NAME,
                    JENKINS_SERVER_DEPENDENCY_CONFIGURATION_NAME,
                    JENKINS_TEST_DEPENDENCY_CONFIGURATION_NAME,
            ].each {
                project.configurations.getByName(it).dependencies.each {
                    project.dependencies.add(plugins.name, "${it.group}:${it.name}:${it.version}")
                }
            }
        }

        TestDependenciesTask task = project.tasks.create(TestDependenciesTask.TASK_NAME, TestDependenciesTask)
        task.configuration = plugins

        project.tasks.getByName(javaConvention.sourceSets.test.processResourcesTaskName).dependsOn(task)
    }

    private static configureLocalizer(Project project) {
        JavaPluginConvention javaConvention = project.convention.getPlugin(JavaPluginConvention)
        JpiExtension jpiExtension = project.extensions.getByType(JpiExtension)

        LocalizerTask localizer = project.tasks.create(LocalizerTask.TASK_NAME, LocalizerTask)
        localizer.description = 'Generates the Java source for the localizer.'
        localizer.group = BasePlugin.BUILD_GROUP
        localizer.sourceDirs = javaConvention.sourceSets.main.resources.srcDirs
        localizer.conventionMapping.map('destinationDir') {
            jpiExtension.localizerOutputDir
        }
        javaConvention.sourceSets.main.java.srcDir { localizer.destinationDir }
        project.tasks[javaConvention.sourceSets.main.compileJavaTaskName].dependsOn(localizer)
    }

    private static configureLicenseInfo(Project project) {
        JavaPluginConvention javaConvention = project.convention.getPlugin(JavaPluginConvention)

        LicenseTask task = project.tasks.create(LICENSE_TASK_NAME, LicenseTask)
        task.description = 'Generates license information.'
        task.group = BasePlugin.BUILD_GROUP
        task.outputDirectory = new File(project.buildDir, 'licenses')
        task.configurations = [
                project.configurations[javaConvention.sourceSets.main.compileConfigurationName],
                project.configurations[javaConvention.sourceSets.main.runtimeConfigurationName],
        ]
        task.providedConfigurations = [
                project.configurations[PROVIDED_COMPILE_CONFIGURATION_NAME],
                project.configurations[PROVIDED_RUNTIME_CONFIGURATION_NAME],
        ]

        War war = project.tasks[WarPlugin.WAR_TASK_NAME] as War
        war.webInf.from(task.outputDirectory)
        war.dependsOn(task)
    }

    private static configureInjectedTest(Project project) {
        JpiExtension jpiExtension = project.extensions.getByType(JpiExtension)
        JavaPluginConvention javaConvention = project.convention.getPlugin(JavaPluginConvention)
        SourceSet testSourceSet = javaConvention.sourceSets.getByName(TEST_SOURCE_SET_NAME)

        File root = new File(project.buildDir, 'inject-tests')
        testSourceSet.java.srcDirs += root

        TestInsertionTask testInsertionTask = project.tasks.create(TestInsertionTask.TASK_NAME, TestInsertionTask)
        testInsertionTask.onlyIf {
            !jpiExtension.disabledTestInjection
        }

        project.tasks.compileTestJava.dependsOn(testInsertionTask)

        project.afterEvaluate {
            testInsertionTask.testSuite = new File(root, "${jpiExtension.injectedTestName}.java")
        }
    }

    private static configureRepositories(Project project) {
        project.afterEvaluate {
            if (project.extensions.getByType(JpiExtension).configureRepositories) {
                project.repositories {
                    mavenCentral()
                    mavenLocal()
                    maven {
                        name 'jenkins'
                        url('https://repo.jenkins-ci.org/public/')
                    }
                }
            }
        }
    }

    private static configureConfigurations(Project project) {
        Configuration core = project.configurations.create(CORE_DEPENDENCY_CONFIGURATION_NAME)
        core.visible = false
        core.description = 'Jenkins core that your plugin is built against'

        Configuration plugins = project.configurations.create(PLUGINS_DEPENDENCY_CONFIGURATION_NAME)
        plugins.visible = false
        plugins.description = 'Jenkins plugins which your plugin is built against'

        Configuration optionalPlugins = project.configurations.create(OPTIONAL_PLUGINS_DEPENDENCY_CONFIGURATION_NAME)
        optionalPlugins.visible = false
        optionalPlugins.description = 'Optional Jenkins plugins dependencies which your plugin is built against'

        Configuration serverPlugins = project.configurations.create(JENKINS_SERVER_DEPENDENCY_CONFIGURATION_NAME)
        serverPlugins.visible = false
        serverPlugins.description = 'Jenkins plugins which will be installed by the server task'

        Configuration test = project.configurations.create(JENKINS_TEST_DEPENDENCY_CONFIGURATION_NAME)
                .exclude(group: 'org.jenkins-ci.modules', module: 'ssh-cli-auth')
                .exclude(group: 'org.jenkins-ci.modules', module: 'sshd')
        test.visible = false
        test.description = 'Jenkins plugin test dependencies.'

        project.configurations.getByName(PROVIDED_COMPILE_CONFIGURATION_NAME).extendsFrom(core)
        project.configurations.getByName(PROVIDED_COMPILE_CONFIGURATION_NAME).extendsFrom(plugins)
        project.configurations.getByName(PROVIDED_COMPILE_CONFIGURATION_NAME).extendsFrom(optionalPlugins)
        project.configurations.getByName(TEST_IMPLEMENTATION_CONFIGURATION_NAME).extendsFrom(test)

        Configuration warDependencies = project.configurations.create(WAR_DEPENDENCY_CONFIGURATION_NAME)
        warDependencies.visible = false
        warDependencies.description = 'Jenkins war that corresponds to the Jenkins core'

        resolvePluginDependencies(project)
    }

    private static configurePublishing(Project project) {
        JpiExtension jpiExtension = project.extensions.getByType(JpiExtension)

        Task jar = project.tasks.getByName(JavaPlugin.JAR_TASK_NAME)
        Task sourcesJar = project.tasks.getByName(SOURCES_JAR_TASK_NAME)
        Task javadocJar = project.tasks.getByName(JAVADOC_JAR_TASK_NAME)

        // delay configuration until all settings are available (groupId, shortName, ...)
        project.afterEvaluate {
            if (jpiExtension.configurePublishing) {
                project.plugins.apply(MavenPublishPlugin)
                PublishingExtension publishingExtension = project.extensions.getByType(PublishingExtension)
                publishingExtension.publications {
                    mavenJpi(MavenPublication) {
                        artifactId jpiExtension.shortName

                        from(project.components.web)

                        artifact jar
                        artifact sourcesJar
                        artifact javadocJar

                        new JpiPomCustomizer(project).customizePom(pom)
                    }
                }
                publishingExtension.repositories {
                    maven {
                        name 'jenkins'
                        if (project.version.toString().endsWith('-SNAPSHOT')) {
                            url jpiExtension.snapshotRepoUrl
                        } else {
                            url jpiExtension.repoUrl
                        }
                    }
                }
            }
        }

        // load credentials only when publishing
        project.gradle.taskGraph.whenReady { TaskExecutionGraph taskGraph ->
            if (jpiExtension.configurePublishing && taskGraph.hasTask(project.tasks.publish)) {
                def credentials = loadDotJenkinsOrg()
                PublishingExtension publishingExtension = project.extensions.getByType(PublishingExtension)
                publishingExtension.repositories.getByName('jenkins').credentials {
                    username credentials.userName
                    password credentials.password
                }
            }
        }
    }

    private static configureTestHpl(Project project) {
        JavaPluginConvention javaConvention = project.convention.getPlugin(JavaPluginConvention)
        SourceSet testSourceSet = javaConvention.sourceSets.getByName(TEST_SOURCE_SET_NAME)

        // generate test hpl manifest for the current plugin, to be used during unit test
        GenerateTestHpl generateTestHpl = project.tasks.create('generate-test-hpl', GenerateTestHpl)
        generateTestHpl.hplDir.set(project.layout.buildDirectory.dir('generated-resources/test'))
        testSourceSet.output.dir(generateTestHpl.hplDir)
    }

    private static void resolvePluginDependencies(Project project) {
        resolvePluginDependencies(
                project,
                PLUGINS_DEPENDENCY_CONFIGURATION_NAME,
                PROVIDED_COMPILE_CONFIGURATION_NAME
        )
        resolvePluginDependencies(
                project,
                OPTIONAL_PLUGINS_DEPENDENCY_CONFIGURATION_NAME,
                PROVIDED_COMPILE_CONFIGURATION_NAME
        )
        resolvePluginDependencies(
                project,
                JENKINS_TEST_DEPENDENCY_CONFIGURATION_NAME,
                TEST_IMPLEMENTATION_CONFIGURATION_NAME
        )
    }

    private static void resolvePluginDependencies(Project project, String from, String to) {
        ConfigurationContainer configurations = project.configurations
        Configuration fromConfiguration = configurations.getByName(from)
        Configuration toConfiguration = configurations.getByName(to)

        toConfiguration.withDependencies { deps ->
            fromConfiguration.resolvedConfiguration.resolvedArtifacts
                    .findAll { it.type == 'hpi' || it.type == 'jpi' }
                    .collect { toDependency(project, it, from) }
                    .each { deps.add(it) }
        }
    }

    private static Dependency toDependency(Project project, ResolvedArtifact it, String from) {
        project.dependencies.create("${it.moduleVersion.id}@jar") { Dependency d ->
            d.because "JpiPlugin added jar for compilation support (plugin present on $from)"
        }
    }
}
