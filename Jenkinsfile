@Library('jenkins-shared-library') _

/**
* Job Parameters:
*	skipDeploy - whether to deploy build artifacts in case of successful maven build or not (should be false by default)
*	skipTests - whether to skip tests or not
**/

try {

	def currentVersion
	def revision
	def branch
	def mavenPhase = params.skipDeploy ? "verify" : "deploy"

	slack.notifyBuild()

	node('build-jdk17-isolated') {

		stage('Checkout repository') {

			scmVars = checkout scm

			pom = readMavenPom file: 'pom.xml'
			currentVersion = pom.version

			revision = sh(returnStdout: true, script: "git rev-parse --short HEAD").trim()
			branch = scmVars.GIT_BRANCH.replaceAll("origin/", "")
			
			println("Current version: " + currentVersion)
			println("Revision: " + revision)
			println("Branch: " + branch)

		}

		stage('Build') {

			withMaven(jdk: 'OpenJDK_17', mavenSettingsConfig: custom_maven_settings, publisherStrategy: 'EXPLICIT', traceability: true) {
				sh "./mvnw clean ${mavenPhase} -Dmaven.test.skip=${skipTests} -Dmaven.install.skip=true"
			}

		}

	}

} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
	currentBuild.result = "ABORTED"
	throw e
} catch (e) {
	currentBuild.result = "FAILURE"
	throw e
} finally {
	slack.notifyBuild(currentBuild.result)
}
